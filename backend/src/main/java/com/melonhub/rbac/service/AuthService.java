package com.melonhub.rbac.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.AdminRoleDao;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminFunction;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.ApplicationCaller;
import com.melonhub.rbac.security.ClientAddress;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.security.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements ApplicationRunner {
    /** BCrypt only reads the first 72 bytes of a password; longer ones would be silently cut. */
    public static final int PASSWORD_MAX_BYTES = 72;
    private record KeyMeta(String a, long k) {}
    private record KeyState(boolean valid, Long exp) {}

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final AuthDao dao;
    private final AdminRoleDao roles;
    private final RbacService rbac;
    private final PermissionCache cache;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String bootstrapUser;
    private final String bootstrapPassword;
    private final boolean bootstrapReset;
    private final int maxFailures;
    private final int lockMinutes;
    private final int sessionSeconds;
    private final int idleMinutes;
    private final String dummyHash;
    private final Set<Long> usedKeys = ConcurrentHashMap.newKeySet();

    public AuthService(AuthDao dao, AdminRoleDao roles, RbacService rbac, PermissionCache cache, ObjectMapper json,
                       @Value("${melonhub.admin-username}") String bootstrapUser,
                       @Value("${melonhub.admin-password}") String bootstrapPassword,
                       @Value("${melonhub.admin-password-reset:false}") boolean bootstrapReset,
                       @Value("${melonhub.admin-security.max-failures:5}") int maxFailures,
                       @Value("${melonhub.admin-security.lock-minutes:15}") int lockMinutes,
                       @Value("${melonhub.admin-security.session-hours:8}") int sessionHours,
                       @Value("${melonhub.admin-security.idle-minutes:30}") int idleMinutes) {
        this.dao = dao; this.roles = roles; this.rbac = rbac; this.cache = cache; this.json = json;
        this.bootstrapUser = bootstrapUser; this.bootstrapPassword = bootstrapPassword; this.bootstrapReset = bootstrapReset;
        this.maxFailures = maxFailures; this.lockMinutes = lockMinutes; this.sessionSeconds = sessionHours * 3600; this.idleMinutes = idleMinutes;
        // Unknown usernames still pay for one BCrypt comparison so response time does not reveal which accounts exist.
        this.dummyHash = encoder.encode(Credentials.token());
    }

    /** Creates the bootstrap super administrator once; later password changes happen in the console. */
    @Override public void run(ApplicationArguments args) {
        if (bootstrapPassword == null || bootstrapPassword.length() < 12) throw new IllegalStateException("ADMIN_PASSWORD must contain at least 12 characters");
        if (bytes(bootstrapPassword) > PASSWORD_MAX_BYTES) throw new IllegalStateException("ADMIN_PASSWORD must not exceed 72 bytes in UTF-8");
        if (!bootstrapUser.matches(Requests.USERNAME)) throw new IllegalStateException("ADMIN_USERNAME must be 3-100 letters, digits or _.@-");
        if (!dao.accountExists(bootstrapUser)) {
            dao.createAccount(bootstrapUser, "平台管理员", AdminPrincipal.SUPER_ADMIN, encode(bootstrapPassword), true, null, AdminPrincipal.MANAGER, true, true);
            log.info("Bootstrap super administrator account created");
        } else if (bootstrapReset) {
            dao.resetBootstrap(bootstrapUser, encode(bootstrapPassword), AdminPrincipal.SUPER_ADMIN);
            dao.endSessions(bootstrapUser);
            log.warn("Bootstrap administrator restored from ADMIN_PASSWORD; set ADMIN_PASSWORD_RESET=false again");
        }
        dao.purgeSessions(idleMinutes, 100_000);
    }

    public Map<String,Object> login(Requests.Login input) {
        String username = input.username();
        var found = dao.account(username);
        if (found.isEmpty()) {
            matches(input.password(), dummyHash);
            rbac.audit(username, "LOGIN_FAILED", username, null, null, "账号不存在");
            throw badCredentials();
        }
        var account = found.get();
        if (account.locked()) {
            rbac.audit(username, "LOGIN_BLOCKED", username, null, null, "账号处于锁定期");
            throw locked();
        }
        if (!matches(input.password(), account.passwordHash())) {
            boolean nowLocked = dao.recordFailure(username, maxFailures, lockMinutes);
            rbac.audit(username, nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", username, null, null,
                    nowLocked ? "连续 " + maxFailures + " 次密码错误，锁定 " + lockMinutes + " 分钟" : "密码错误");
            throw nowLocked ? locked() : badCredentials();
        }
        if (!account.enabled()) {
            rbac.audit(username, "LOGIN_FAILED", username, null, null, "账号已停用");
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用，请联系超级管理员");
        }
        if (account.mfaSecret() != null) {
            String code = input.mfaCode();
            if (code == null || code.isBlank())
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "该账号已启用两步验证，请输入认证器动态口令");
            Long step = Totp.verify(account.mfaSecret(), code.strip());
            // A step counter stops replay of a code inside its 90 s acceptance window; the conditional update
            // decides between concurrent logins that present the same code.
            if (step == null || step <= account.mfaLastStep() || dao.consumeMfaStep(username, account.mfaSecret(), step) == 0) {
                boolean nowLocked = dao.recordFailure(username, maxFailures, lockMinutes);
                rbac.audit(username, nowLocked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", username, null, null,
                        nowLocked ? "连续失败锁定" : "动态口令不正确或已使用");
                throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_INVALID", "动态口令不正确或已使用");
            }
        }
        String token = Credentials.token();
        if (dao.createSession(Credentials.hash(token), account, Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(sessionSeconds), ClientAddress.current()) == 0) {
            rbac.audit(username, "LOGIN_FAILED", username, null, null, "登录过程中账号凭据已变更");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号凭据已变更，请重新登录");
        }
        dao.recordSuccess(username);
        rbac.audit(username, "LOGIN", username, null, null, "");
        var result = new LinkedHashMap<String,Object>();
        result.put("token", token);
        result.put("expiresIn", sessionSeconds);
        result.put("idleTimeout", idleMinutes * 60);
        result.putAll(view(principal(account.username(), account.displayName(), account.role(), account.kind(), account.root(),
                account.allDepartments(), account.allApplications())));
        result.put("mfaEnabled", account.mfaSecret() != null);
        return result;
    }

    public AdminPrincipal admin(String authorization) {
        String hash = sessionHash(authorization);
        var session = dao.session(hash, idleMinutes).orElseThrow(ApiException::unauthorized);
        if (session.stale()) dao.touchSession(hash);
        return principal(session.username(), session.displayName(), session.role(), session.kind(), session.root(), session.allDepartments(),
                session.allApplications());
    }

    public String sessionHash(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw ApiException.unauthorized();
        String hash = Credentials.hashOrNull(authorization.substring(7));
        if (hash == null) throw ApiException.unauthorized();
        return hash;
    }

    public void logout(AdminPrincipal principal, String authorization) {
        dao.logout(sessionHash(authorization));
        rbac.audit(principal.actor(), "LOGOUT", principal.username(), null, null, "");
    }

    @Transactional public void changePassword(AdminPrincipal principal, String authorization, Requests.PasswordChange input) {
        var account = dao.account(principal.username()).orElseThrow(ApiException::unauthorized);
        if (!matches(input.currentPassword(), account.passwordHash())) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CREDENTIALS", "当前密码不正确");
        if (input.currentPassword().equals(input.newPassword())) throw ApiException.bad("新密码不能与当前密码相同");
        if (dao.replacePassword(principal.username(), account.passwordHash(), encode(input.newPassword())) == 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CREDENTIALS", "密码已被更改，请重新登录后再试");
        dao.keepOnlySession(principal.username(), sessionHash(authorization));
        rbac.audit(principal.actor(), "PASSWORD_CHANGED", principal.username(), null, null, "其他会话已下线");
    }

    // ---- two-factor authentication (TOTP) ----
    /** Step 1: generates a pending secret. Nothing is active until confirmMfa proves possession. */
    public Map<String,Object> setupMfa(AdminPrincipal principal) {
        var account = dao.account(principal.username()).orElseThrow(ApiException::unauthorized);
        if (account.mfaSecret() != null) throw ApiException.conflict("已启用两步验证，请先停用再重新绑定");
        String secret = Totp.newSecret();
        dao.setMfaPending(principal.username(), secret);
        String label = "MelonHub:" + principal.username();
        var result = new LinkedHashMap<String,Object>();
        result.put("secret", secret);
        result.put("uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=MelonHub&digits=6&period=30");
        return result;
    }

    /** Step 2: the first valid code activates the secret; that code is also marked as used. */
    @Transactional public void confirmMfa(AdminPrincipal principal, String code) {
        var pending = dao.pendingMfaSecret(principal.username());
        if (pending == null) throw ApiException.bad("请先生成绑定密钥");
        Long step = Totp.verify(pending, code);
        if (step == null) throw new ApiException(HttpStatus.BAD_REQUEST, "MFA_INVALID", "动态口令不正确，请确认设备时间准确");
        if (dao.confirmMfa(principal.username(), pending, step) == 0) throw ApiException.conflict("绑定密钥已更换，请使用最新的二维码重新绑定");
        dao.endSessions(principal.username());
        rbac.audit(principal.actor(), "MFA_ENABLED", principal.username(), null, null, "");
    }

    @Transactional public void disableMfa(AdminPrincipal principal, Requests.MfaDisable input) {
        var account = dao.account(principal.username()).orElseThrow(ApiException::unauthorized);
        if (account.mfaSecret() == null) throw ApiException.bad("未启用两步验证");
        if (!matches(input.password(), account.passwordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CREDENTIALS", "当前密码不正确");
        Long step = Totp.verify(account.mfaSecret(), input.code());
        if (step == null || step <= account.mfaLastStep() || dao.disableMfaWithStep(principal.username(), account.mfaSecret(), step) == 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "MFA_INVALID", "动态口令不正确或已使用");
        dao.endSessions(principal.username());
        rbac.audit(principal.actor(), "MFA_DISABLED", principal.username(), null, null, "");
    }

    /** Super administrators clear a lost device; the account then signs in with password only until re-enrolled. */
    @Transactional public void resetMfa(String username, AdminPrincipal actor) {
        requireManaged(actor, username);
        if (dao.disableMfa(username) == 0) throw ApiException.missing("管理员账号不存在");
        dao.endSessions(username);
        rbac.audit(actor.actor(), "MFA_RESET", username, null, null, "两步验证已重置");
    }

    public Map<String,Object> me(AdminPrincipal principal) {
        var view = new LinkedHashMap<>(view(principal));
        view.put("mfaEnabled", dao.account(principal.username()).map(account -> account.mfaSecret() != null).orElse(false));
        return view;
    }
    public ApplicationCaller application(String key) {
        String hash = Credentials.hashOrNull(key);
        if (hash == null) throw ApiException.unauthorized();
        var lookup = cache.applicationKey(hash);
        if (lookup.status() == PermissionCache.Status.HIT) {
            KeyMeta meta = read(lookup.meta(), KeyMeta.class);
            KeyState state = read(lookup.payload(), KeyState.class);
            if (meta == null || state == null) throw ApiException.unauthorized();
            if (!state.valid() || state.exp() != null && state.exp() <= Instant.now().getEpochSecond()) throw ApiException.unauthorized();
            return used(Long.parseLong(meta.a()), meta.k());
        }
        var row = dao.applicationKey(hash).orElseThrow(ApiException::unauthorized);
        if (lookup.status() == PermissionCache.Status.NONE) cache.putApplicationKey(hash, write(new KeyMeta(String.valueOf(row.applicationId()), row.keyId())));
        if (lookup.status() == PermissionCache.Status.MISS) {
            Long exp = row.expiresAt() == null ? null : row.expiresAt().getEpochSecond();
            cache.store(lookup.storeKey(), write(new KeyState(row.valid(), exp)), PermissionCache.ENTRY_TTL_SECONDS);
        }
        if (!row.valid()) throw ApiException.unauthorized();
        return used(row.applicationId(), row.keyId());
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void flushKeyUsage() {
        if (usedKeys.isEmpty()) return;
        var ids = new ArrayList<>(usedKeys);
        usedKeys.removeAll(ids);
        try { dao.markKeysUsed(ids); }
        catch (DataAccessException e) { log.warn("Could not record API key usage: {}", e.getClass().getSimpleName()); }
    }

    public int purgeSessions() { return dao.purgeSessions(idleMinutes, 5000); }

    /** The operator for an account as a new session would see it. */
    public AdminPrincipal principal(String username) {
        var account = dao.account(username).orElseThrow(() -> ApiException.missing("管理员账号不存在"));
        return principal(account.username(), account.displayName(), account.role(), account.kind(), account.root(), account.allDepartments(),
                account.allApplications());
    }

    /** The accounts the operator manages, and its own; manageable tells which ones it may change. */
    public List<Map<String,Object>> accounts(AdminPrincipal actor) {
        var tree = tree();
        var applications = new HashMap<String,List<Long>>();
        for (var pair : dao.accountApplicationPairs())
            applications.computeIfAbsent((String) pair.get("username"), k -> new ArrayList<>()).add(((Number) pair.get("application_id")).longValue());
        var departments = new HashMap<String,List<Long>>();
        for (var pair : dao.accountDepartmentPairs())
            departments.computeIfAbsent((String) pair.get("username"), k -> new ArrayList<>()).add(((Number) pair.get("department_id")).longValue());
        var result = new ArrayList<Map<String,Object>>();
        for (var account : dao.accounts()) {
            String username = (String) account.get("username");
            boolean manageable = tree.manages(actor.username(), username);
            if (!manageable && !username.equals(actor.username())) continue;
            account.put("applicationIds", applications.getOrDefault(username, List.of()));
            account.put("departmentIds", departments.getOrDefault(username, List.of()));
            account.put("manageable", manageable);
            result.add(account);
        }
        return result;
    }

    /**
     * Creates an account below the operator, or below an account the operator manages. The new account must stay
     * within its parent (role functions, departments, applications); see AdminTree.
     */
    @Transactional public void createAccount(Requests.Account input, AdminPrincipal actor) {
        dao.lockAccounts();
        requireRole(input.role());
        var nodes = dao.nodes();
        var roleFunctions = roles.functionsByRole();
        var before = new AdminTree(nodes, roleFunctions);
        String parent = input.parentUsername() == null ? actor.username() : input.parentUsername();
        if (before.node(parent) == null) throw ApiException.bad("上级管理员不存在");
        if (!parent.equals(actor.username()) && !before.manages(actor.username(), parent))
            throw ApiException.forbidden("只能在自己或自己管理的管理员下创建账号");
        String kind = input.kind() == null ? AdminPrincipal.MANAGER : input.kind();
        boolean allDepartments = everything(input.allDepartments(), input.departmentIds());
        var departments = allDepartments ? List.<Long>of() : departments(input.departmentIds());
        boolean allApplications = everything(input.allApplications(), input.applicationIds());
        var applications = allApplications ? List.<Long>of() : applications(input.applicationIds());
        boolean enabled = !Boolean.FALSE.equals(input.enabled());
        requireConsistent(with(nodes, new AuthDao.AccountNode(input.username(), parent, kind, input.role(), enabled, allDepartments,
                Set.copyOf(departments), allApplications, Set.copyOf(applications))), roleFunctions);
        String displayName = blankTo(input.displayName(), input.username());
        dao.createAccount(input.username(), displayName, input.role(), encode(input.password()), enabled, parent, kind, allDepartments, allApplications);
        dao.replaceAccountApplications(input.username(), applications);
        dao.replaceAccountDepartments(input.username(), departments);
        rbac.audit(actor.actor(), "ACCOUNT_CREATED", input.username(), null, null,
                "角色 " + input.role() + "，上级 " + parent + kindDetail(kind) + scopeDetail(allDepartments, departments, allApplications, applications));
    }

    /**
     * Changes an account the operator manages. Operators may only rename themselves: their own role, kind, scope and
     * parent are decided above them. Omitted scopes and parent stay as they are.
     */
    @Transactional public void updateAccount(String username, Requests.AccountUpdate input, AdminPrincipal actor) {
        dao.lockAccounts();
        var nodes = dao.nodes();
        var roleFunctions = roles.functionsByRole();
        var before = new AdminTree(nodes, roleFunctions);
        var current = before.node(username);
        if (current == null) throw ApiException.missing("管理员账号不存在");
        boolean self = username.equals(actor.username());
        if (!self && !before.manages(actor.username(), username)) throw ApiException.forbidden("只能管理自己下级的管理员账号");
        requireRole(input.role());
        String kind = input.kind() == null ? current.kind() : input.kind();
        boolean keepDepartments = input.allDepartments() == null && input.departmentIds() == null;
        boolean allDepartments = keepDepartments ? current.allDepartments() : everything(input.allDepartments(), input.departmentIds());
        var departments = allDepartments ? List.<Long>of() : keepDepartments ? List.copyOf(current.departments()) : departments(input.departmentIds());
        boolean keepApplications = input.allApplications() == null && input.applicationIds() == null;
        boolean allApplications = keepApplications ? current.allApplications() : everything(input.allApplications(), input.applicationIds());
        var applications = allApplications ? List.<Long>of() : keepApplications ? List.copyOf(current.applications()) : applications(input.applicationIds());
        String parent = input.parentUsername() == null ? current.parent() : input.parentUsername();

        boolean roleChanged = !current.role().equals(input.role());
        boolean disabling = current.enabled() && !input.enabled();
        boolean kindChanged = !current.kind().equals(kind);
        boolean parentChanged = !Objects.equals(current.parent(), parent);
        boolean scopeChanged = current.allDepartments() != allDepartments || !current.departments().equals(Set.copyOf(departments))
                || current.allApplications() != allApplications || !current.applications().equals(Set.copyOf(applications));
        if (self && (roleChanged || disabling || kindChanged || parentChanged || scopeChanged))
            throw ApiException.bad("不能修改自己的角色、类型、管理范围或上级，也不能停用自己的账号");
        if ((roleChanged || disabling) && current.enabled() && AdminPrincipal.SUPER_ADMIN.equals(current.role()) && enabledSuperAdmins(before) <= 1)
            throw ApiException.conflict("至少需要保留一个启用的超级管理员");
        if (disabling && !before.children(username).isEmpty())
            throw ApiException.conflict("该管理员还有 " + before.children(username).size() + " 个下级管理员，请先移交或删除下级后再停用");
        if (parentChanged) {
            if (parent == null || before.node(parent) == null) throw ApiException.bad("上级管理员不存在");
            if (!parent.equals(actor.username()) && !before.manages(actor.username(), parent))
                throw ApiException.forbidden("只能移到自己或自己管理的管理员之下");
        }
        requireConsistent(with(nodes, new AuthDao.AccountNode(username, parent, kind, input.role(), input.enabled(), allDepartments,
                Set.copyOf(departments), allApplications, Set.copyOf(applications))), roleFunctions);
        dao.updateAccount(username, blankTo(input.displayName(), username), input.role(), input.enabled(), kind, allDepartments, allApplications);
        dao.replaceAccountApplications(username, applications);
        dao.replaceAccountDepartments(username, departments);
        if (parentChanged) dao.moveUnder(username, parent);
        if (roleChanged || disabling || kindChanged) dao.endSessions(username);
        rbac.audit(actor.actor(), "ACCOUNT_UPDATED", username, null, null,
                "角色 " + input.role() + (input.enabled() ? "，启用" : "，停用") + (parentChanged ? "，上级改为 " + parent : "") + kindDetail(kind)
                        + scopeDetail(allDepartments, departments, allApplications, applications));
    }

    @Transactional public void resetPassword(String username, Requests.PasswordReset input, AdminPrincipal actor) {
        requireManaged(actor, username);
        if (dao.updatePassword(username, encode(input.password())) == 0) throw ApiException.missing("管理员账号不存在");
        dao.endSessions(username);
        rbac.audit(actor.actor(), "ACCOUNT_PASSWORD_RESET", username, null, null, "该账号全部会话已下线");
    }

    @Transactional public void unlock(String username, AdminPrincipal actor) {
        requireManaged(actor, username);
        if (dao.unlock(username) == 0) throw ApiException.missing("管理员账号不存在");
        rbac.audit(actor.actor(), "ACCOUNT_UNLOCKED", username, null, null, "");
    }

    @Transactional public void deleteAccount(String username, AdminPrincipal actor) {
        if (username.equals(actor.username())) throw ApiException.bad("不能删除当前登录的账号");
        dao.lockAccounts();
        var tree = requireManaged(actor, username);
        if (!tree.children(username).isEmpty())
            throw ApiException.conflict("该管理员还有 " + tree.children(username).size() + " 个下级管理员，请先移交或删除下级");
        var account = tree.node(username);
        if (account.enabled() && AdminPrincipal.SUPER_ADMIN.equals(account.role()) && enabledSuperAdmins(tree) <= 1)
            throw ApiException.conflict("至少需要保留一个启用的超级管理员");
        if (dao.deleteAccount(username) == 0) throw ApiException.missing("管理员账号不存在");
        rbac.audit(actor.actor(), "ACCOUNT_DELETED", username, null, null, "");
    }

    /** Takes the administrator tree lock; role changes call this before changing what accounts may do. */
    public void lockTree() { dao.lockAccounts(); }

    /** Refuses a role change, before it is written, when an account using the role would no longer fit the tree. */
    public void requireConsistentWithRole(String role, Set<String> functions) {
        var roleFunctions = new HashMap<>(roles.functionsByRole());
        roleFunctions.put(role, functions);
        requireConsistent(dao.nodes(), roleFunctions);
    }
    /** Refuses a change, before anything is written, when the tree it would produce breaks a rule. */
    private static void requireConsistent(List<AuthDao.AccountNode> nodes, Map<String,Set<String>> roleFunctions) {
        var problems = new AdminTree(nodes, roleFunctions).violations();
        if (!problems.isEmpty())
            throw ApiException.conflict("管理员层级不满足要求：" + String.join("；", problems.subList(0, Math.min(3, problems.size()))));
    }
    /** The accounts with one of them added or replaced. */
    private static List<AuthDao.AccountNode> with(List<AuthDao.AccountNode> nodes, AuthDao.AccountNode changed) {
        var result = new ArrayList<AuthDao.AccountNode>();
        for (var node : nodes) if (!node.username().equals(changed.username())) result.add(node);
        result.add(changed);
        return result;
    }

    private AdminTree tree() { return new AdminTree(dao.nodes(), roles.functionsByRole()); }
    private AdminTree requireManaged(AdminPrincipal actor, String username) {
        var tree = tree();
        if (tree.node(username) == null) throw ApiException.missing("管理员账号不存在");
        if (!tree.manages(actor.username(), username)) throw ApiException.forbidden("只能管理自己下级的管理员账号");
        return tree;
    }
    private static long enabledSuperAdmins(AdminTree tree) {
        return tree.accounts().stream().filter(a -> a.enabled() && AdminPrincipal.SUPER_ADMIN.equals(a.role())).count();
    }

    public static Map<String,Object> view(AdminPrincipal principal) {
        var view = new LinkedHashMap<String,Object>();
        view.put("username", principal.username());
        view.put("displayName", principal.displayName());
        view.put("role", principal.role());
        view.put("roleName", principal.roleName());
        view.put("functions", principal.functions().stream().map(AdminFunction::code).sorted().toList());
        view.put("root", principal.root());
        view.put("kind", principal.kind());
        view.put("allDepartments", principal.allDepartments());
        view.put("departmentIds", principal.departmentIds().stream().sorted().toList());
        view.put("allApplications", principal.allApplications());
        view.put("applicationIds", principal.applicationIds().stream().sorted().toList());
        return view;
    }

    /** The operator with the functions its role grants now; a role change takes effect on the next request. */
    private AdminPrincipal principal(String username, String displayName, String role, String kind, boolean root, boolean allDepartments,
                                     boolean allApplications) {
        var granted = roles.role(role).orElseThrow(ApiException::unauthorized);
        var functions = EnumSet.noneOf(AdminFunction.class);
        granted.functions().stream().map(AdminFunction::of).filter(Objects::nonNull).forEach(functions::add);
        Set<Long> departments = allDepartments ? Set.of() : Set.copyOf(dao.accountDepartments(username));
        Set<Long> applications = allApplications ? Set.of() : Set.copyOf(dao.accountApplications(username));
        return new AdminPrincipal(username, blankTo(displayName, username), role, granted.name(), Collections.unmodifiableSet(functions),
                root, kind, allDepartments, departments, allApplications, applications);
    }

    private void requireRole(String role) {
        if (!roles.exists(role)) throw ApiException.bad("管理员角色不存在");
    }
    /** Whether a scope covers everything: as requested, or else when no list is given. */
    private static boolean everything(Boolean requested, List<Long> listed) {
        return requested != null ? requested : listed == null;
    }
    private List<Long> applications(List<Long> requested) {
        if (requested == null) return List.of();
        var ids = new ArrayList<>(new LinkedHashSet<>(requested));
        if (dao.existingApplications(ids) != ids.size()) throw ApiException.missing("授权的软件不存在");
        return ids;
    }

    private List<Long> departments(List<Long> requested) {
        if (requested == null) return List.of();
        var ids = new ArrayList<>(new LinkedHashSet<>(requested));
        if (dao.existingDepartments(ids) != ids.size()) throw ApiException.missing("指定的部门不存在");
        return ids;
    }
    private static String kindDetail(String kind) { return AdminPrincipal.DEPARTMENT.equals(kind) ? "，部门管理员" : ""; }
    private static String scopeDetail(boolean allDepartments, List<Long> departments, boolean allApplications, List<Long> applications) {
        return (allDepartments ? "" : "，管理部门 " + departments.size() + " 个") + (allApplications ? "" : "，授权软件 " + applications.size() + " 个");
    }

    private ApplicationCaller used(long application, long key) {
        usedKeys.add(key);
        return new ApplicationCaller(application, key);
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException | RuntimeException e) { return null; }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private String encode(String password) {
        if (bytes(password) > PASSWORD_MAX_BYTES) throw ApiException.bad("密码不能超过 72 字节（UTF-8 编码，约 24 个汉字）");
        return encoder.encode(password);
    }
    /** Passwords BCrypt cannot represent never match; the dummy comparison keeps the response time the same. */
    private boolean matches(String password, String hash) {
        if (password == null || bytes(password) > PASSWORD_MAX_BYTES) {
            encoder.matches("", dummyHash);
            return false;
        }
        return encoder.matches(password, hash);
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String blankTo(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.strip(); }
    private static ApiException badCredentials() { return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误"); }
    private ApiException locked() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "登录失败次数过多，账号已锁定 " + lockMinutes + " 分钟，可联系超级管理员解锁");
    }
}
