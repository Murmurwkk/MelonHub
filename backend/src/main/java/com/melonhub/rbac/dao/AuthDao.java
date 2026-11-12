package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.AdminAccount;
import com.melonhub.rbac.entity.AdminRole;
import com.melonhub.rbac.entity.AdminSession;
import com.melonhub.rbac.repository.AdminAccountRepository;
import com.melonhub.rbac.repository.AdminSessionRepository;
import com.melonhub.rbac.repository.ApplicationKeyRepository;
import com.melonhub.rbac.repository.ApplicationRepository;
import com.melonhub.rbac.repository.DepartmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Console accounts, sessions and API key lookup. Every method runs in a transaction (joining the caller's), and
 * every write reaches the database before the method returns, so callers and tests that still read with JDBC see
 * it. Times are taken from this server's clock.
 */
@Repository
@Transactional
public class AuthDao {
    public record AccountRow(String username, String displayName, String role, boolean enabled, String passwordHash, boolean locked,
                             String mfaSecret, long mfaLastStep, long credentialVersion, boolean allApplications,
                             String kind, boolean root, boolean allDepartments) {}
    public record SessionRow(String username, String displayName, String role, boolean allApplications, String kind, boolean root,
                             boolean allDepartments, boolean stale) {}
    /** One account's place in the administrator tree: its parent (null for a root), kind, role and scopes. */
    public record AccountNode(String username, String parent, String kind, String role, boolean enabled, boolean allDepartments,
                              Set<Long> departments, boolean allApplications, Set<Long> applications) {}
    public record KeyRow(long keyId, long applicationId, boolean valid, Instant expiresAt) {}

    private static final int PURGE_BATCH = 1000;

    private final AdminAccountRepository accounts;
    private final AdminSessionRepository sessions;
    private final ApplicationRepository applications;
    private final ApplicationKeyRepository keys;
    private final DepartmentRepository departments;
    private final EntityManager em;

    public AuthDao(AdminAccountRepository accounts, AdminSessionRepository sessions, ApplicationRepository applications,
                   ApplicationKeyRepository keys, DepartmentRepository departments, EntityManager em) {
        this.accounts = accounts; this.sessions = sessions; this.applications = applications; this.keys = keys;
        this.departments = departments; this.em = em;
    }

    /** TIMESTAMP columns hold whole seconds and MySQL rounds fractions, so stored times are truncated first. */
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    /** The account row read again under the lock: find() alone may return the copy this persistence context already holds. */
    private AdminAccount locked(String username, LockModeType mode) {
        AdminAccount account = em.find(AdminAccount.class, username);
        if (account == null) return null;
        try { em.refresh(account, mode); } catch (EntityNotFoundException deleted) { return null; }
        return account;
    }

    public boolean accountExists(String username) { return accounts.existsById(username); }
    public void createAccount(String username, String displayName, String role, String hash, boolean enabled, String parent, String kind,
                              boolean allDepartments, boolean allApplications) {
        // persist, not save(): with an assigned key save() merges into an existing account instead of failing as a duplicate.
        em.persist(new AdminAccount(username, displayName, em.getReference(AdminRole.class, role), hash, enabled,
                parent == null ? null : em.getReference(AdminAccount.class, parent), kind, allDepartments, allApplications, now()));
        em.flush();
    }
    /** Restores the bootstrap account as an enabled super administrator at a root of the tree, over everything. */
    public void resetBootstrap(String username, String hash, String superAdminRole) {
        accounts.resetBootstrap(username, hash, em.getReference(AdminRole.class, superAdminRole), now());
    }
    public Optional<AccountRow> account(String username) {
        Instant now = Instant.now();
        return accounts.credentials(username).map(a -> new AccountRow(a.getUsername(), a.getDisplayName(), a.getRole(), a.isEnabled(),
                a.getPasswordHash(), a.getLockedUntil() != null && a.getLockedUntil().isAfter(now), a.getMfaSecret(), a.getMfaLastStep(),
                a.getCredentialVersion(), a.isAllApplications(), a.getKind(), a.isRoot(), a.isAllDepartments()));
    }
    /** Counts a failed password and returns whether the account is now locked. */
    public boolean recordFailure(String username, int maxFailures, int lockMinutes) {
        AdminAccount account = locked(username, LockModeType.PESSIMISTIC_WRITE);
        if (account == null) return false;
        Instant now = now();
        if (account.getFailedAttempts() + 1 >= maxFailures) {
            account.setLockedUntil(now.plus(lockMinutes, ChronoUnit.MINUTES));
            account.setFailedAttempts(0);
        } else {
            account.setFailedAttempts(account.getFailedAttempts() + 1);
        }
        em.flush();
        return account.getLockedUntil() != null && account.getLockedUntil().isAfter(now);
    }
    public void recordSuccess(String username) { accounts.recordSuccess(username, now()); }

    /**
     * Creates the session only while the account still has the credentials the login checked: a password reset,
     * role change, disable or two-factor change committed meanwhile makes this create nothing. The account row is
     * read under a shared lock, so such a change either commits first and is seen here or waits for this login.
     * The password hash the login verified is compared too, because an account deleted and created again under
     * the same name starts over at credential version 0; its new hash has a fresh salt and never equals the old one.
     */
    public int createSession(String hash, AccountRow account, Instant expires, String sourceIp) {
        AdminAccount current = locked(account.username(), LockModeType.PESSIMISTIC_READ);
        if (current == null || !current.isEnabled() || current.getCredentialVersion() != account.credentialVersion()
                || !current.getPasswordHash().equals(account.passwordHash()) || !Objects.equals(current.getMfaSecret(), account.mfaSecret())) return 0;
        em.persist(new AdminSession(hash, current, expires, now(), sourceIp, current.getCredentialVersion()));
        em.flush();
        return 1;
    }
    public Optional<SessionRow> session(String hash, int idleMinutes) {
        Instant now = Instant.now();
        return sessions.active(hash, now, now.minus(idleMinutes, ChronoUnit.MINUTES)).map(s ->
                new SessionRow(s.getUsername(), s.getDisplayName(), s.getRole(), s.isAllApplications(), s.getKind(), s.isRoot(),
                        s.isAllDepartments(), s.getLastSeenAt().isBefore(now.minusSeconds(60))));
    }
    public void touchSession(String hash) { sessions.touch(hash, now()); }
    public List<Long> accountApplications(String username) { return accounts.applicationIds(username); }
    public List<Long> accountDepartments(String username) { return accounts.departmentIds(username); }
    public void logout(String hash) { sessions.deleteByHashes(List.of(hash)); }
    /** Ends every session, including one a concurrent login is about to create with the old credentials. */
    public void endSessions(String username) {
        accounts.bumpCredentialVersion(username);
        sessions.deleteForAccount(username);
    }
    /** After a password change the caller's own session moves to the new credential version; all others end. */
    public void keepOnlySession(String username, String keepHash) {
        accounts.credentials(username).ifPresent(account -> sessions.moveToVersion(keepHash, username, account.getCredentialVersion()));
        sessions.deleteOthers(username, keepHash);
    }
    public int purgeSessions(int idleMinutes, int limit) {
        Instant now = Instant.now();
        Instant idleSince = now.minus(idleMinutes, ChronoUnit.MINUTES);
        int removed = 0;
        while (removed < limit) {
            var batch = sessions.ended(now, idleSince, Limit.of(Math.min(PURGE_BATCH, limit - removed)));
            if (batch.isEmpty()) break;
            removed += sessions.deleteByHashes(batch);
            if (batch.size() < PURGE_BATCH) break;
        }
        return removed;
    }

    public List<Map<String,Object>> accounts() {
        Instant now = Instant.now();
        var sessionCounts = new HashMap<String,Long>();
        for (var count : sessions.activeCounts(now)) sessionCounts.put(count.getUsername(), count.getSessions());
        var rows = new ArrayList<Map<String,Object>>();
        for (AdminAccount account : accounts.listed()) {
            var row = new LinkedHashMap<String,Object>();
            row.put("username", account.getUsername());
            row.put("displayName", account.getDisplayName());
            row.put("role", account.getRole().getCode());
            row.put("enabled", account.isEnabled());
            row.put("failedAttempts", account.getFailedAttempts());
            // 0/1 rather than a boolean, as the SQL expressions this replaced returned them; the console reads both as flags.
            row.put("locked", account.getLockedUntil() != null && account.getLockedUntil().isAfter(now) ? 1L : 0L);
            row.put("lockedUntil", timestamp(account.getLockedUntil()));
            row.put("lastLoginAt", timestamp(account.getLastLoginAt()));
            row.put("passwordChangedAt", timestamp(account.getPasswordChangedAt()));
            row.put("createdAt", timestamp(account.getCreatedAt()));
            row.put("activeSessions", sessionCounts.getOrDefault(account.getUsername(), 0L));
            row.put("mfaEnabled", account.getMfaSecret() != null ? 1L : 0L);
            row.put("allApplications", account.isAllApplications());
            row.put("parentUsername", account.getParent() == null ? null : account.getParent().getUsername());
            row.put("kind", account.getKind());
            row.put("allDepartments", account.isAllDepartments());
            rows.add(row);
        }
        return rows;
    }
    public List<Map<String,Object>> accountApplicationPairs() {
        return accounts.applicationPairs().stream()
                .map(pair -> Map.<String,Object>of("username", pair.getUsername(), "application_id", pair.getApplicationId())).toList();
    }
    public List<Map<String,Object>> accountDepartmentPairs() {
        return accounts.departmentPairs().stream()
                .map(pair -> Map.<String,Object>of("username", pair.getUsername(), "department_id", pair.getDepartmentId())).toList();
    }
    public int updateAccount(String username, String displayName, String role, boolean enabled, String kind, boolean allDepartments,
                             boolean allApplications) {
        return accounts.update(username, displayName, em.getReference(AdminRole.class, role), enabled, kind, allDepartments, allApplications);
    }
    public void moveUnder(String username, String parent) { accounts.moveUnder(username, em.getReference(AdminAccount.class, parent)); }
    public void replaceAccountDepartments(String username, Collection<Long> departmentIds) {
        AdminAccount account = em.find(AdminAccount.class, username);
        if (account == null) return;
        account.getDepartmentIds().clear();
        account.getDepartmentIds().addAll(departmentIds);
        em.flush();
    }
    public void replaceAccountApplications(String username, Collection<Long> applicationIds) {
        AdminAccount account = em.find(AdminAccount.class, username);
        if (account == null) return;
        account.getApplicationIds().clear();
        account.getApplicationIds().addAll(applicationIds);
        em.flush();
    }
    public int updatePassword(String username, String hash) { return accounts.updatePassword(username, hash, now()); }
    /** Changes the password only if it is still the one the caller proved, so two concurrent changes cannot both win. */
    public int replacePassword(String username, String expectedHash, String hash) { return accounts.replacePassword(username, expectedHash, hash, now()); }
    public int unlock(String username) { return accounts.unlock(username); }
    public int deleteAccount(String username) { return accounts.delete(username); }
    public String pendingMfaSecret(String username) {
        return accounts.pendingMfaSecret(username).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
    public void setMfaPending(String username, String secret) { accounts.setMfaPending(username, secret); }
    public int confirmMfa(String username, String pendingSecret, long step) { return accounts.confirmMfa(username, pendingSecret, step); }
    public int disableMfa(String username) { return accounts.disableMfa(username); }
    public int consumeMfaStep(String username, String secret, long step) { return accounts.consumeMfaStep(username, secret, step); }
    public int disableMfaWithStep(String username, String secret, long step) { return accounts.disableMfaWithStep(username, secret, step); }

    /** Locks every account row; changes to the administrator tree take this before reading it. */
    public void lockAccounts() { accounts.lockAll(); }
    /** The whole administrator tree as it is now in this transaction. */
    public List<AccountNode> nodes() {
        var departments = new HashMap<String,Set<Long>>();
        for (var pair : accounts.departmentPairs()) departments.computeIfAbsent(pair.getUsername(), k -> new TreeSet<>()).add(pair.getDepartmentId());
        var applications = new HashMap<String,Set<Long>>();
        for (var pair : accounts.applicationPairs()) applications.computeIfAbsent(pair.getUsername(), k -> new TreeSet<>()).add(pair.getApplicationId());
        return accounts.nodes().stream().map(n -> new AccountNode(n.getUsername(), n.getParent(), n.getKind(), n.getRole(), n.isEnabled(),
                n.isAllDepartments(), departments.getOrDefault(n.getUsername(), Set.of()), n.isAllApplications(),
                applications.getOrDefault(n.getUsername(), Set.of()))).toList();
    }
    public long existingApplications(Collection<Long> ids) { return ids.isEmpty() ? 0 : applications.countByIdIn(ids); }
    public long existingDepartments(Collection<Long> ids) { return ids.isEmpty() ? 0 : departments.countByIdIn(ids); }

    public Optional<KeyRow> applicationKey(String hash) {
        Instant now = Instant.now();
        return keys.byHash(hash).map(key -> new KeyRow(key.getKeyId(), key.getApplicationId(),
                key.isApplicationEnabled() && key.getRevokedAt() == null && (key.getExpiresAt() == null || key.getExpiresAt().isAfter(now)),
                key.getExpiresAt()));
    }
    public void markKeysUsed(Collection<Long> ids) { if (!ids.isEmpty()) keys.markUsed(ids, now()); }

}
