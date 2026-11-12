package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.AdminAccount;
import com.melonhub.rbac.entity.AdminRole;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Console accounts. Reads that decide something select scalars, so they always see the database row rather than an
 * entity this persistence context loaded earlier. Updates keep the conditions that make concurrent requests safe
 * (password still the one proven, code step still unused) and report how many rows changed.
 */
public interface AdminAccountRepository extends JpaRepository<AdminAccount, String> {
    interface Credentials {
        String getUsername();
        String getDisplayName();
        String getRole();
        boolean isEnabled();
        String getPasswordHash();
        Instant getLockedUntil();
        String getMfaSecret();
        long getMfaLastStep();
        long getCredentialVersion();
        boolean isAllApplications();
        String getKind();
        boolean isRoot();
        boolean isAllDepartments();
    }
    interface AccountApplication {
        String getUsername();
        Long getApplicationId();
    }
    interface AccountDepartment {
        String getUsername();
        Long getDepartmentId();
    }
    /** One account's place in the administrator tree. */
    interface Node {
        String getUsername();
        String getParent();
        String getKind();
        String getRole();
        boolean isEnabled();
        boolean isAllDepartments();
        boolean isAllApplications();
    }

    @Query("""
            select a.username as username, a.displayName as displayName, a.role.code as role, a.enabled as enabled,
                   a.passwordHash as passwordHash, a.lockedUntil as lockedUntil, a.mfaSecret as mfaSecret,
                   a.mfaLastStep as mfaLastStep, a.credentialVersion as credentialVersion, a.allApplications as allApplications,
                   a.kind as kind, case when a.parent is null then true else false end as root, a.allDepartments as allDepartments
            from AdminAccount a where a.username = :username""")
    Optional<Credentials> credentials(String username);

    @Query("select a from AdminAccount a order by a.createdAt, a.username")
    List<AdminAccount> listed();

    @Query("select a.username as username, app as applicationId from AdminAccount a join a.applicationIds app")
    List<AccountApplication> applicationPairs();

    @Query("select app from AdminAccount a join a.applicationIds app where a.username = :username order by app")
    List<Long> applicationIds(String username);

    @Query("select a.username as username, d as departmentId from AdminAccount a join a.departmentIds d")
    List<AccountDepartment> departmentPairs();

    @Query("select d from AdminAccount a join a.departmentIds d where a.username = :username order by d")
    List<Long> departmentIds(String username);

    @Query("""
            select a.username as username, p.username as parent, a.kind as kind, a.role.code as role, a.enabled as enabled,
                   a.allDepartments as allDepartments, a.allApplications as allApplications
            from AdminAccount a left join a.parent p""")
    List<Node> nodes();

    @Query("select a.mfaPendingSecret from AdminAccount a where a.username = :username")
    List<String> pendingMfaSecret(String username);

    /**
     * Serializes changes to the administrator tree (accounts, their scopes and the roles they use): each one takes
     * every account row before reading the tree, so two changes cannot each pass the checks and together break them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminAccount a order by a.username")
    List<AdminAccount> lockAll();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.passwordHash = :hash, a.role = :role, a.allApplications = true, a.allDepartments = true, a.kind = 'MANAGER',
                   a.parent = null, a.enabled = true, a.failedAttempts = 0,
                   a.lockedUntil = null, a.passwordChangedAt = :now, a.credentialVersion = a.credentialVersion + 1
            where a.username = :username""")
    int resetBootstrap(String username, String hash, AdminRole role, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.failedAttempts = 0, a.lockedUntil = null, a.lastLoginAt = :now where a.username = :username")
    int recordSuccess(String username, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.credentialVersion = a.credentialVersion + 1 where a.username = :username")
    int bumpCredentialVersion(String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.displayName = :displayName, a.role = :role, a.enabled = :enabled, a.kind = :kind,
                   a.allDepartments = :allDepartments, a.allApplications = :allApplications
            where a.username = :username""")
    int update(String username, String displayName, AdminRole role, boolean enabled, String kind, boolean allDepartments, boolean allApplications);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.parent = :parent where a.username = :username")
    int moveUnder(String username, AdminAccount parent);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.passwordHash = :hash, a.passwordChangedAt = :now, a.failedAttempts = 0, a.lockedUntil = null,
                   a.credentialVersion = a.credentialVersion + 1
            where a.username = :username""")
    int updatePassword(String username, String hash, Instant now);

    /** Changes the password only if it is still the one the caller proved, so two concurrent changes cannot both win. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.passwordHash = :hash, a.passwordChangedAt = :now, a.failedAttempts = 0, a.lockedUntil = null,
                   a.credentialVersion = a.credentialVersion + 1
            where a.username = :username and a.passwordHash = :expectedHash""")
    int replacePassword(String username, String expectedHash, String hash, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.failedAttempts = 0, a.lockedUntil = null where a.username = :username")
    int unlock(String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminAccount a where a.username = :username")
    int delete(String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaPendingSecret = :secret where a.username = :username")
    int setMfaPending(String username, String secret);

    /** Activates exactly the pending secret the code was checked against; a newer setup in between makes this a no-op. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.mfaSecret = :pendingSecret, a.mfaPendingSecret = null, a.mfaLastStep = :step
            where a.username = :username and a.mfaPendingSecret = :pendingSecret""")
    int confirmMfa(String username, String pendingSecret, long step);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaSecret = null, a.mfaPendingSecret = null, a.mfaLastStep = 0 where a.username = :username")
    int disableMfa(String username);

    /**
     * Uses a code step at most once: the row only changes while the step is newer than the last used one, so of two
     * concurrent requests with the same code exactly one gets 1 row back.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminAccount a set a.mfaLastStep = :step where a.username = :username and a.mfaSecret = :secret and a.mfaLastStep < :step")
    int consumeMfaStep(String username, String secret, long step);

    /** Disabling spends the code the same way, so it cannot race a login that uses the same code. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AdminAccount a set a.mfaSecret = null, a.mfaPendingSecret = null, a.mfaLastStep = 0
            where a.username = :username and a.mfaSecret = :secret and a.mfaLastStep < :step""")
    int disableMfaWithStep(String username, String secret, long step);
}
