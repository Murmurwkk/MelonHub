package com.melonhub.rbac.service;

import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.ContextDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.ClientAddress;
import com.melonhub.rbac.security.Credentials;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RbacService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ACTIVE_KEYS = 10;
    private static final int MEMBER_ATTEMPTS = 3;

    private final RbacDao dao;
    private final ContextDao contexts;
    private final PermissionCache cache;
    private final WebhookService webhooks;
    private final GovernanceDao governance;
    private final TransactionTemplate memberAttempt;

    public RbacService(RbacDao dao, ContextDao contexts, PermissionCache cache, WebhookService webhooks, GovernanceDao governance,
                       PlatformTransactionManager transactionManager) {
        this.dao = dao; this.contexts = contexts; this.cache = cache; this.webhooks = webhooks; this.governance = governance;
        // The console calls this without a transaction, so each attempt is a transaction of its own and rolling it
        // back releases its locks. JPA has no savepoints: inside a caller's transaction (the integration tests) the
        // attempts join it, and an attempt that has to retry marks that transaction for rollback.
        this.memberAttempt = new TransactionTemplate(transactionManager);
        this.memberAttempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public Map<String,Object> health() {
        boolean database;
        try { database = dao.healthy(); } catch (RuntimeException e) { database = false; }
        var result = new LinkedHashMap<String,Object>();
        result.put("status", database ? "UP" : "DOWN");
        result.put("database", database ? "UP" : "DOWN");
        result.put("cache", cache.health());
        result.put("model", "RBAC0");
        return result;
    }

    public void audit(String actor, String action, String target, Long department, Long app) { audit(actor, action, target, department, app, ""); }
    public void audit(String actor, String action, String target, Long department, Long app, String detail) {
        dao.audit(actor, action, target, department, app, detail, ClientAddress.current());
    }

    public void domain(long dept, long app) {
        if (!dao.departmentExists(dept)) throw ApiException.missing("部门不存在");
        if (!dao.applicationExists(app)) throw ApiException.missing("软件不存在");
    }
    public void scoped(String table, long id, long dept, long app) {
        if (!dao.scopedExists(table, id, dept, app)) throw ApiException.forbidden("关联记录不属于当前隔离域");
    }

    public static Map<String,Object> page(RbacDao.Page page, int number, int size) {
        var result = new LinkedHashMap<String,Object>();
        result.put("items", page.items());
        result.put("total", page.total());
        result.put("page", number);
        result.put("size", size);
        return result;
    }

    // ---- departments ----
    /** visible limits the list to those departments; null lists them all. */
    public List<Map<String,Object>> departments(Collection<Long> visible) { return dao.departments(visible); }
    @Transactional public long department(Requests.Department body, Long id, String actor) {
        if (id == null) {
            id = dao.createDepartment(body.name(), body.code());
            audit(actor, "DEPARTMENT_CREATED", body.name(), id, null, "编码 " + body.code());
        } else {
            if (dao.updateDepartment(id, body.name(), body.code()) == 0) throw ApiException.missing("部门不存在");
            audit(actor, "DEPARTMENT_UPDATED", body.name(), id, null, "编码 " + body.code());
        }
        return id;
    }
    @Transactional public void deleteDepartment(long id, String actor) {
        String[] label = dao.label("departments", id).orElseThrow(() -> ApiException.missing("部门不存在"));
        long[] used = dao.departmentUsage(id);
        String left = remaining(used[0], "名成员", used[1], "个角色", used[2], "个权限", used[3], "名限定于该部门的管理员");
        if (!left.isEmpty()) throw ApiException.conflict("部门下还有 " + left + "，请先移除后再删除");
        // Former members' contexts outlive the membership until expiry and would block the delete; none of them can validate any more.
        contexts.clearDepartment(id);
        dao.deleteDepartment(id);
        audit(actor, "DEPARTMENT_DELETED", label[0], null, null, "编码 " + label[1]);
    }
    private static String remaining(Object... countUnitPairs) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < countUnitPairs.length; i += 2) {
            long n = (Long) countUnitPairs[i];
            if (n > 0) parts.add(n + " " + countUnitPairs[i + 1]);
        }
        return String.join("、", parts);
    }

    // ---- applications and keys ----
    public List<Map<String,Object>> applications(Collection<Long> visible) { return dao.applications(visible); }
    @Transactional public Map<String,Object> application(Requests.Application body, String actor) {
        long id = dao.createApplication(body.name(), body.code(), text(body.description()), text(body.owner()));
        var key = newKey(id, "默认密钥", null, actor);
        audit(actor, "APPLICATION_CREATED", body.name(), null, id, "编码 " + body.code() + "，已生成默认密钥 " + key.get("prefix"));
        var result = new LinkedHashMap<String,Object>();
        result.put("id", id);
        result.put("apiKey", key.get("apiKey"));
        result.put("keyId", key.get("id"));
        return result;
    }
    @Transactional public long updateApplication(long id, Requests.Application body, String actor) {
        if (dao.updateApplication(id, body.name(), body.code(), text(body.description()), text(body.owner())) == 0) throw ApiException.missing("软件不存在");
        audit(actor, "APPLICATION_UPDATED", body.name(), null, id, "编码 " + body.code());
        return id;
    }
    @Transactional public void applicationStatus(long id, boolean enabled, String actor) {
        if (dao.applicationStatus(id, enabled) == 0) throw ApiException.missing("软件不存在");
        if (!enabled) contexts.clearApplication(id);
        cache.touchApplication(id);
        webhooks.emit(id, enabled ? "application.enabled" : "application.disabled", Map.of());
        audit(actor, enabled ? "APPLICATION_ENABLED" : "APPLICATION_DISABLED", "application:" + id, null, id, enabled ? "" : "已清除该软件全部权限上下文");
    }
    @Transactional public void deleteApplication(long id, String actor) {
        String[] label = dao.label("applications", id).orElseThrow(() -> ApiException.missing("软件不存在"));
        long[] used = dao.applicationUsage(id);
        String left = remaining(used[0], "个角色", used[1], "个权限");
        if (!left.isEmpty()) throw ApiException.conflict("软件下还有 " + left + "，请先删除，或改为停用");
        contexts.clearApplication(id);
        cache.touchApplication(id);
        dao.deleteApplication(id);
        audit(actor, "APPLICATION_DELETED", label[0], null, id, "编码 " + label[1]);
    }
    public List<Map<String,Object>> keys(long app) {
        if (!dao.applicationExists(app)) throw ApiException.missing("软件不存在");
        return dao.keys(app);
    }
    @Transactional public Map<String,Object> createKey(long app, Requests.ApiKey body, String actor) {
        if (!dao.lockApplication(app)) throw ApiException.missing("软件不存在");
        if (dao.lockedActiveKeyCount(app) >= MAX_ACTIVE_KEYS) throw ApiException.conflict("每个软件最多保留 " + MAX_ACTIVE_KEYS + " 个有效密钥，请先吊销不用的密钥");
        var key = newKey(app, body.name().strip(), body.expiresInDays(), actor);
        audit(actor, "API_KEY_CREATED", body.name(), null, app,
                key.get("prefix") + (body.expiresInDays() == null ? "，永不过期" : "，" + body.expiresInDays() + " 天后过期"));
        return key;
    }
    @Transactional public void revokeKey(long app, long key, String actor) {
        String name = dao.keyName(app, key).orElseThrow(() -> ApiException.missing("密钥不存在"));
        if (dao.revokeKey(app, key) == 0) throw ApiException.conflict("密钥已被吊销");
        cache.touchKeys(app);
        webhooks.emit(app, "apikey.revoked", Map.of("keyId", key, "name", name));
        audit(actor, "API_KEY_REVOKED", name, null, app, "key:" + key);
    }
    /** Emergency rotation: every existing key stops working at once. Prefer create + revoke for planned rotation. */
    @Transactional public String rotateKey(long app, String actor) {
        if (!dao.lockApplication(app)) throw ApiException.missing("软件不存在");
        int revoked = dao.revokeAllKeys(app);
        var key = newKey(app, "轮换密钥 " + LocalDate.now(ZONE), null, actor);
        cache.touchKeys(app);
        webhooks.emit(app, "apikey.revoked", Map.of("rotated", true, "revoked", revoked));
        audit(actor, "API_KEY_ROTATED", "application:" + app, null, app, "吊销 " + revoked + " 个旧密钥，新密钥 " + key.get("prefix"));
        return (String) key.get("apiKey");
    }
    private Map<String,Object> newKey(long app, String name, Integer days, String actor) {
        String key = Credentials.apiKey();
        Instant expires = days == null ? null : Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
        long id = dao.createKey(app, name, Credentials.prefix(key), Credentials.hash(key), actor, expires == null ? null : Timestamp.from(expires));
        var result = new LinkedHashMap<String,Object>();
        result.put("id", id);
        result.put("name", name);
        result.put("prefix", Credentials.prefix(key));
        result.put("apiKey", key);
        result.put("expiresAt", expires);
        return result;
    }

    // ---- users ----
    @Transactional(readOnly=true) public Map<String,Object> users(String keyword, Long department, Boolean enabled, Collection<Long> visibleDepartments,
                                                                 int page, int size) {
        var result = dao.users(keyword, department, enabled, visibleDepartments, page, size);
        attach(result.items(), dao.memberships(ids(result.items())), "user_id", "department_id", "departmentIds");
        return page(result, page, size);
    }
    public List<Map<String,Object>> departmentUsers(long dept) {
        if (!dao.departmentExists(dept)) throw ApiException.missing("部门不存在");
        return dao.departmentUsers(dept);
    }
    @Transactional public long user(Requests.User body, Long id, String actor) { return user(body, id, actor, null); }
    /**
     * Creates or changes a user. An operator limited to some departments (scope) only places new users in them;
     * a user who also belongs to other departments keeps their profile and those memberships, so the operator can
     * only add or remove memberships within its own departments.
     */
    @Transactional public long user(Requests.User body, Long id, String actor, Collection<Long> scope) {
        var departments = new LinkedHashSet<>(body.departmentIds());
        if (scope != null && id == null && (departments.isEmpty() || !scope.containsAll(departments)))
            throw ApiException.forbidden("只能在自己管理的部门中创建用户");
        for (long dept : departments) if (!dao.departmentExists(dept)) throw ApiException.missing("部门不存在");
        String email = text(body.email());
        String label = body.name() + " (" + body.externalId() + ")";
        if (id == null) {
            id = dao.createUser(body.name(), body.externalId(), email);
            for (long dept : departments) dao.addMembership(id, dept);
            audit(actor, "USER_CREATED", label, null, null, "所属部门 " + departments.size() + " 个");
            return id;
        }
        String previous = dao.lockUser(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (scope != null) {
            var current = dao.lockedUserDepartments(id);
            var outside = current.stream().filter(d -> !scope.contains(d)).collect(Collectors.toSet());
            var wantedOutside = departments.stream().filter(d -> !scope.contains(d)).collect(Collectors.toSet());
            if (!outside.equals(wantedOutside)) throw ApiException.forbidden("不能调整自己管理范围以外的部门成员关系");
            var user = dao.user(id).orElseThrow();
            boolean profileChanged = !previous.equals(body.externalId()) || !body.name().equals(user.get("name")) || !text(body.email()).equals(user.get("email"));
            if (!outside.isEmpty() && profileChanged) throw ApiException.forbidden("该用户还属于你管理范围以外的部门，只能调整所管部门内的成员关系");
        }
        // Business systems identify users by externalId, so changing it must end existing contexts.
        boolean identityChanged = !previous.equals(body.externalId());
        dao.updateUser(id, body.name(), body.externalId(), email, identityChanged);
        var removed = dao.lockedUserDepartments(id).stream().filter(d -> !departments.contains(d)).toList();
        for (long dept : removed) dao.removeMembership(id, dept);
        for (long dept : departments) dao.addMembership(id, dept);
        dao.clearInactiveDepartment(id);
        cache.touchUser(id);
        webhooks.emitAll("user.updated", Map.of("userId", id, "externalId", body.externalId()));
        var detail = new ArrayList<String>();
        if (identityChanged) detail.add("外部标识由 " + previous + " 变更，原有上下文已失效");
        if (!removed.isEmpty()) detail.add("移出部门 " + removed.size() + " 个，相关角色已解除");
        audit(actor, "USER_UPDATED", label, null, null, String.join("；", detail));
        return id;
    }
    @Transactional public void userStatus(long id, boolean enabled, String actor) { userStatus(id, enabled, actor, null); }
    @Transactional public void userStatus(long id, boolean enabled, String actor, Collection<Long> scope) {
        String external = dao.lockUser(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        dao.userStatus(id, enabled);
        cache.touchUser(id);
        webhooks.emitAll(enabled ? "user.enabled" : "user.disabled", Map.of("userId", id, "externalId", external));
        audit(actor, enabled ? "USER_ENABLED" : "USER_DISABLED", external, null, null, enabled ? "" : "原有上下文已失效");
    }
    @Transactional public void deleteUser(long id, String actor, Collection<Long> scope) {
        String external = dao.lockUser(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        int cancelled = governance.cancelPending("user_id", id, "用户已删除");
        dao.deleteUser(id);
        cache.touchUser(id);
        webhooks.emitAll("user.deleted", Map.of("userId", id, "externalId", external));
        audit(actor, "USER_DELETED", external, null, null, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
    }
    /** A user an operator limited to some departments (scope) may disable, delete or sign out belongs only to those departments. */
    private void requireOwned(long user, Collection<Long> scope) {
        if (scope == null) return;
        var departments = dao.lockedUserDepartments(user);
        if (departments.isEmpty() || !scope.containsAll(departments)) throw ApiException.forbidden("只能操作完全属于你所管部门的用户");
    }
    public Map<String,Object> userAccess(long id, Visibility visible) {
        var user = dao.user(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        var memberOf = dao.userDepartments(id);
        if (visible.departments() != null && memberOf.stream().noneMatch(visible::department)) throw ApiException.forbidden("该用户不在你管理的部门中");
        user.put("departmentIds", memberOf);
        var domains = new LinkedHashMap<String,Map<String,Object>>();
        for (var row : dao.accessRoles(id)) {
            long app = number(row.get("application_id"));
            if (!visible.application(app) || !visible.department(number(row.get("department_id")))) continue;
            var domain = domains.computeIfAbsent(row.get("department_id") + ":" + app, k -> {
                var d = new LinkedHashMap<String,Object>();
                d.put("departmentId", number(row.get("department_id")));
                d.put("departmentName", row.get("department_name"));
                d.put("applicationId", app);
                d.put("applicationName", row.get("application_name"));
                d.put("applicationEnabled", row.get("application_enabled"));
                d.put("roles", new ArrayList<Map<String,Object>>());
                d.put("permissions", new ArrayList<String>());
                return d;
            });
            var role = new LinkedHashMap<String,Object>();
            role.put("id", number(row.get("id")));
            role.put("name", row.get("name"));
            role.put("code", row.get("code"));
            role.put("enabled", row.get("enabled"));
            role.put("expiresAt", row.get("expires_at"));
            role.put("grantedBy", row.get("granted_by"));
            rows(domain.get("roles")).add(role);
        }
        for (var row : dao.accessPermissions(id)) {
            var domain = domains.get(row.get("department_id") + ":" + number(row.get("application_id")));
            if (domain != null) strings(domain.get("permissions")).add((String) row.get("code"));
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("user", user);
        result.put("domains", new ArrayList<>(domains.values()));
        result.put("contexts", contexts.activeContexts(id).stream()
                .filter(c -> visible.application(number(c.get("applicationId"))) && visible.department(number(c.get("departmentId")))).toList());
        return result;
    }
    @Transactional public int revokeUserContexts(long id, String actor, Collection<Long> scope) {
        String external = dao.lockUser(id).orElseThrow(() -> ApiException.missing("用户不存在"));
        requireOwned(id, scope);
        dao.invalidateUser(id);
        int revoked = contexts.revokeUser(id);
        cache.touchUser(id);
        webhooks.emitAll("user.contexts.revoked", Map.of("userId", id, "externalId", external));
        audit(actor, "USER_CONTEXTS_REVOKED", external, null, null, "强制下线 " + revoked + " 个上下文");
        return revoked;
    }

    // ---- roles and permissions in one isolation domain ----
    public List<Map<String,Object>> roles(long dept, long app) {
        domain(dept, app);
        var roles = dao.roles(dept, app);
        attach(roles, dao.rolePermissions(dept, app), "role_id", "permission_id", "permissionIds");
        return roles;
    }
    @Transactional public long role(Requests.Role body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        String description = text(body.description());
        if (id == null) {
            id = dao.createRole(dept, app, body.name(), body.code(), description);
            audit(actor, "ROLE_CREATED", body.name() + " (" + body.code() + ")", dept, app);
        } else {
            scoped("roles", id, dept, app);
            dao.updateRole(id, body.name(), body.code(), description);
            cache.touchDomain(dept, app);
            audit(actor, "ROLE_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
        }
        return id;
    }
    @Transactional public void roleStatus(long id, long dept, long app, boolean enabled, String actor) {
        scoped("roles", id, dept, app);
        dao.roleStatus(id, enabled);
        cache.touchDomain(dept, app);
        webhooks.emit(app, enabled ? "role.enabled" : "role.disabled", Map.of("roleId", id, "departmentId", dept));
        audit(actor, enabled ? "ROLE_ENABLED" : "ROLE_DISABLED", code("roles", id), dept, app);
    }
    @Transactional public void deleteRole(long id, long dept, long app, String actor) {
        scoped("roles", id, dept, app);
        String code = code("roles", id);
        int cancelled = governance.cancelPending("role_id", id, "角色已删除");
        dao.deleteRole(id);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "role.deleted", Map.of("roleId", id, "departmentId", dept));
        audit(actor, "ROLE_DELETED", code, dept, app, cancelled == 0 ? "" : "撤回待审批申请 " + cancelled + " 个");
    }
    public List<Map<String,Object>> permissions(long dept, long app) { domain(dept, app); return dao.permissions(dept, app); }
    @Transactional public long permission(Requests.Permission body, Long id, long dept, long app, String actor) {
        domain(dept, app);
        if (id == null) {
            id = dao.createPermission(dept, app, body.name(), body.code(), body.resource(), body.action());
            audit(actor, "PERMISSION_CREATED", body.name() + " (" + body.code() + ")", dept, app);
        } else {
            scoped("permissions", id, dept, app);
            dao.updatePermission(id, body.name(), body.code(), body.resource(), body.action());
            cache.touchDomain(dept, app);
            webhooks.emit(app, "permission.updated", Map.of("permissionId", id, "departmentId", dept));
            audit(actor, "PERMISSION_UPDATED", body.name() + " (" + body.code() + ")", dept, app);
        }
        return id;
    }
    @Transactional public void deletePermission(long id, long dept, long app, String actor) {
        scoped("permissions", id, dept, app);
        String code = code("permissions", id);
        dao.deletePermission(id);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "permission.deleted", Map.of("permissionId", id, "departmentId", dept));
        audit(actor, "PERMISSION_DELETED", code, dept, app);
    }
    @Transactional public void rolePermissions(long role, long dept, long app, List<Long> permissions, String actor) {
        scoped("roles", role, dept, app);
        var wanted = new LinkedHashSet<>(permissions);
        if (dao.scopedCount("permissions", wanted, dept, app) != wanted.size()) throw ApiException.forbidden("关联记录不属于当前隔离域");
        if (!dao.lockRole(role)) throw ApiException.missing("角色不存在");
        var before = new HashSet<>(dao.lockedRolePermissionIds(role));
        dao.replaceRolePermissions(role, dept, app, wanted);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "role.permissions.changed", Map.of("roleId", role, "departmentId", dept));
        audit(actor, "ROLE_PERMISSIONS_UPDATED", code("roles", role), dept, app, diff("permissions", before, wanted));
    }
    public List<Map<String,Object>> roleMembers(long role, long dept, long app) {
        scoped("roles", role, dept, app);
        return dao.roleMembers(role);
    }
    /**
     * Replaces the members of one role. Locks are taken users first, then the role, then its assignments: the
     * same order as every per-user assignment change, which locks the user before that user's assignments. The
     * member set is read again with a locking read so the difference is taken against the latest committed
     * state. Someone who joined the role after the first, unlocked read only shows up in that locking read, and
     * locking that user then would reverse the order and could deadlock with a change to that user; instead the
     * attempt rolls back, releasing every lock, and the next attempt locks that user up front.
     */
    public void roleMembers(long role, long dept, long app, List<Long> users, String actor) {
        var late = new TreeSet<Long>();
        for (int attempt = 1; ; attempt++) {
            try {
                memberAttempt.executeWithoutResult(status -> replaceRoleMembers(role, dept, app, users, late, actor));
                return;
            } catch (LateMembers e) {
                if (attempt >= MEMBER_ATTEMPTS) throw ApiException.busy("SERVICE_BUSY", "角色成员正在被同时修改，请稍后重试");
                late.addAll(e.users);
            }
        }
    }
    /** Signals members that were not locked in this attempt; thrown inside it so the attempt rolls back. */
    private static final class LateMembers extends RuntimeException {
        final Set<Long> users;
        LateMembers(Set<Long> users) { super(null, null, false, false); this.users = users; }
    }
    private void replaceRoleMembers(long role, long dept, long app, List<Long> users, Set<Long> known, String actor) {
        scoped("roles", role, dept, app);
        var wanted = new LinkedHashSet<>(users);
        var affected = new TreeSet<Long>(wanted);
        affected.addAll(known);
        affected.addAll(dao.roleMemberIds(role));
        if (!dao.lockUsers(affected).containsAll(wanted)) throw ApiException.missing("用户不存在");
        if (!dao.lockRole(role)) throw ApiException.missing("角色不存在");
        var before = new LinkedHashSet<>(dao.lockedRoleMemberIds(role));
        var late = new TreeSet<Long>(before);
        late.removeAll(affected);
        if (!late.isEmpty()) throw new LateMembers(late);
        if (dao.lockedMembers(wanted, dept) != wanted.size()) throw ApiException.forbidden("用户不属于当前部门");
        var added = wanted.stream().filter(u -> !before.contains(u)).toList();
        var removed = before.stream().filter(u -> !wanted.contains(u)).toList();
        dao.removeRoleMembers(role, removed);
        dao.addRoleMembers(role, dept, app, added, actor);
        var changed = new ArrayList<>(added);
        changed.addAll(removed);
        contexts.revokeDomain(changed, dept, app);
        cache.touchDomain(dept, app);
        webhooks.emit(app, "user.roles.changed", Map.of("roleId", role, "departmentId", dept, "added", added.size(), "removed", removed.size()));
        String roleCode = code("roles", role);
        for (long user : added) audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "新增角色 " + roleCode);
        for (long user : removed) audit(actor, "ROLE_MEMBERS_UPDATED", "user:" + user, dept, app, "移除角色 " + roleCode);
    }
    public Map<String,Object> assignments(long user, long dept, long app) {
        domain(dept, app);
        var current = dao.assignmentExpiries(user, dept, app);
        var timed = new LinkedHashMap<Long,Object>();
        current.forEach((role, at) -> { if (at != null) timed.put(role, at); });
        return Map.of("roleIds", List.copyOf(current.keySet()), "roleExpiries", timed);
    }
    public Map<String,Object> domainAssignments(long dept, long app, String keyword, int page, int size) {
        domain(dept, app);
        var result = dao.departmentMembers(dept, keyword, page, size);
        var pairs = dao.domainRolePairs(dept, app, ids(result.items()));
        attach(result.items(), pairs, "user_id", "role_id", "roleIds");
        var expiries = new HashMap<Long,Map<Long,Object>>();
        for (var pair : pairs) {
            if (pair.get("expires_at") != null)
                expiries.computeIfAbsent(number(pair.get("user_id")), k -> new LinkedHashMap<>()).put(number(pair.get("role_id")), pair.get("expires_at"));
        }
        for (var item : result.items()) item.put("roleExpiries", expiries.getOrDefault(number(item.get("id")), Map.of()));
        return page(result, page, size);
    }
    /** Adds selected roles to department members, keeping every existing active grant and its expiry. */
    @Transactional public Map<String,Object> addUserRoles(long dept, long app, Requests.BatchUserRoles body, String actor) {
        domain(dept, app);
        var users = new TreeSet<>(body.userIds());
        var roles = new TreeSet<>(body.roleIds());
        if (dao.lockUsers(users).size() != users.size()) throw ApiException.missing("用户不存在");
        if (dao.lockedMembers(users, dept) != users.size()) throw ApiException.forbidden("用户不属于当前部门");
        for (long role : roles) {
            if (!dao.lockRole(role)) throw ApiException.missing("角色不存在");
            scoped("roles", role, dept, app);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Timestamp expires = body.expiresInDays() == null ? null : Timestamp.from(now.plus(body.expiresInDays(), ChronoUnit.DAYS));
        int changedUsers = 0, grants = 0;
        for (long user : users) {
            var current = dao.lockedAssignments(user, dept, app);
            var added = new ArrayList<Long>();
            for (long role : roles) {
                Timestamp held = current.get(role);
                if (current.containsKey(role) && (held == null || held.toInstant().isAfter(now))) continue;
                dao.grantRole(user, dept, app, role, expires, actor);
                added.add(role);
            }
            if (added.isEmpty()) continue;
            String external = (String) dao.user(user).orElseThrow().get("externalId");
            contexts.revokeDomain(user, dept, app);
            cache.touchUser(user);
            webhooks.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
            audit(actor, "USER_ROLES_UPDATED", external, dept, app, "批量追加 " + String.join(", ", dao.codes("roles", added)));
            changedUsers++;
            grants += added.size();
        }
        return Map.of("users", users.size(), "changedUsers", changedUsers, "grantsAdded", grants);
    }

    /** Replaces a user's roles in one domain and ends that user's contexts there; other domains are untouched. */
    @Transactional public void userRoles(long user, long dept, long app, List<Long> roles, String actor) {
        userRoles(user, dept, app, roles, null, null, actor);
    }
    @Transactional public void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays, String actor) {
        userRoles(user, dept, app, roles, expiresInDays, null, actor);
    }
    /**
     * Sets the complete role set of a user in one domain. An entry in expiries sets that role's expiry (null for
     * permanent); otherwise expiresInDays, when given, applies to the role. A role with neither keeps the expiry
     * and grant it already has, and a newly added one is permanent. Removed roles are revoked.
     */
    @Transactional public void userRoles(long user, long dept, long app, List<Long> roles, Integer expiresInDays,
                                         List<Requests.RoleExpiry> expiries, String actor) {
        domain(dept, app);
        String external = dao.lockUser(user).orElseThrow(() -> ApiException.missing("用户不存在"));
        if (!dao.lockedMember(user, dept)) throw ApiException.forbidden("用户不属于当前部门");
        var wanted = new LinkedHashSet<>(roles);
        if (dao.scopedCount("roles", wanted, dept, app) != wanted.size()) throw ApiException.forbidden("关联记录不属于当前隔离域");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var explicit = new HashMap<Long,Instant>();
        for (var entry : expiries == null ? List.<Requests.RoleExpiry>of() : expiries) {
            if (!wanted.contains(entry.roleId())) throw ApiException.bad("只能为本次分配的角色设置到期时间");
            explicit.put(entry.roleId(), expiry(entry.expiresAt(), now));
        }
        Instant uniform = expiresInDays == null ? null : now.plus(expiresInDays, ChronoUnit.DAYS);
        var current = dao.lockedAssignments(user, dept, app);
        var removed = current.keySet().stream().filter(role -> !wanted.contains(role)).toList();
        dao.removeUserRoles(user, removed);
        int expiryChanges = 0;
        for (long role : wanted) {
            boolean held = current.containsKey(role);
            Instant target;
            if (explicit.containsKey(role)) target = explicit.get(role);
            else if (uniform != null) target = uniform;
            else if (held) continue;
            else target = null;
            Timestamp was = current.get(role);
            if (held && Objects.equals(was == null ? null : was.toInstant(), target)) continue;
            dao.grantRole(user, dept, app, role, target == null ? null : Timestamp.from(target), actor);
            if (held) expiryChanges++;
        }
        contexts.revokeDomain(user, dept, app);
        cache.touchUser(user);
        webhooks.emit(app, "user.roles.changed", Map.of("userId", user, "externalId", external, "departmentId", dept));
        String detail = diff("roles", current.keySet(), wanted);
        if (expiryChanges > 0) detail += "；调整到期时间 " + expiryChanges + " 个";
        if (uniform != null) detail += "，" + expiresInDays + " 天后到期";
        audit(actor, "USER_ROLES_UPDATED", external, dept, app, detail);
    }
    private static Instant expiry(OffsetDateTime value, Instant now) {
        if (value == null) return null;
        Instant at = value.toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (!at.isAfter(now)) throw ApiException.bad("到期时间必须晚于当前时间");
        if (at.isAfter(now.plus(3650, ChronoUnit.DAYS))) throw ApiException.bad("到期时间不能晚于 3650 天后");
        return at;
    }
    /** Per-application request budget; null restores the platform default and 0 disables limiting. */
    @Transactional public void applicationRateLimit(long id, Integer perSecond, String actor) {
        if (dao.setRateLimit(id, perSecond) == 0) throw ApiException.missing("软件不存在");
        audit(actor, "APPLICATION_RATE_LIMIT_UPDATED", "application:" + id, null, id,
                perSecond == null ? "恢复平台默认" : perSecond == 0 ? "不限制" : perSecond + " 次/秒");
    }

    @Transactional public Map<String,Object> copyDomain(Requests.DomainCopy body, String actor) {
        long app = body.applicationId(), source = body.sourceDepartmentId(), target = body.targetDepartmentId();
        if (source == target) throw ApiException.bad("源部门与目标部门不能相同");
        domain(source, app);
        domain(target, app);
        var existingPermissions = new HashMap<String,Long>();
        for (var p : dao.permissions(target, app)) existingPermissions.put((String) p.get("code"), number(p.get("id")));
        var permissionMap = new HashMap<Long,Long>();
        int permissionsCreated = 0;
        for (var p : dao.permissions(source, app)) {
            String code = (String) p.get("code");
            Long mapped = existingPermissions.get(code);
            if (mapped == null) {
                mapped = dao.createPermission(target, app, (String) p.get("name"), code, (String) p.get("resource"), (String) p.get("action"));
                permissionsCreated++;
            }
            permissionMap.put(number(p.get("id")), mapped);
        }
        var existingRoles = new HashSet<String>();
        for (var r : dao.roles(target, app)) existingRoles.add((String) r.get("code"));
        var grants = new HashMap<Long,List<Long>>();
        for (var pair : dao.rolePermissions(source, app)) {
            grants.computeIfAbsent(number(pair.get("role_id")), k -> new ArrayList<>()).add(permissionMap.get(number(pair.get("permission_id"))));
        }
        int rolesCreated = 0, rolesSkipped = 0;
        for (var r : dao.roles(source, app)) {
            if (existingRoles.contains((String) r.get("code"))) { rolesSkipped++; continue; }
            long created = dao.createRole(target, app, (String) r.get("name"), (String) r.get("code"), text((String) r.get("description")), Boolean.TRUE.equals(r.get("enabled")));
            dao.replaceRolePermissions(created, target, app, new LinkedHashSet<>(grants.getOrDefault(number(r.get("id")), List.of())));
            rolesCreated++;
        }
        int permissionsSkipped = permissionMap.size() - permissionsCreated;
        audit(actor, "DOMAIN_COPIED", "department:" + source + " -> department:" + target, target, app,
                "新建权限 " + permissionsCreated + " 个、角色 " + rolesCreated + " 个；同编码跳过权限 " + permissionsSkipped + " 个、角色 " + rolesSkipped + " 个");
        var result = new LinkedHashMap<String,Object>();
        result.put("permissionsCreated", permissionsCreated);
        result.put("permissionsSkipped", permissionsSkipped);
        result.put("rolesCreated", rolesCreated);
        result.put("rolesSkipped", rolesSkipped);
        return result;
    }

    /**
     * Explains what a context for this user would contain, without issuing it or switching departments. issues
     * would make context creation fail; notices only explain why a role does not count.
     */
    public Map<String,Object> simulate(Requests.Simulation body) {
        long dept = body.departmentId(), app = body.applicationId();
        domain(dept, app);
        var user = dao.user(body.userId()).orElseThrow(() -> ApiException.missing("用户不存在"));
        var issues = new ArrayList<String>();
        var notices = new ArrayList<String>();
        if (!Boolean.TRUE.equals(user.get("enabled"))) issues.add("用户已停用");
        boolean applicationEnabled = dao.applicationEnabled(app);
        if (!applicationEnabled) issues.add("软件已停用");
        boolean member = dao.member(body.userId(), dept);
        if (!member) issues.add("用户不属于该部门");
        var assigned = dao.userRolesInDomain(body.userId(), dept, app);
        var usable = new LinkedHashSet<Long>();
        for (var role : assigned) {
            boolean expired = role.get("expiresAt") != null && ((Timestamp) role.get("expiresAt")).toInstant().isBefore(Instant.now());
            role.put("expired", expired);
            if (Boolean.TRUE.equals(role.get("enabled")) && !expired) usable.add(number(role.get("id")));
            else if (expired) notices.add("角色 " + role.get("code") + " 的授权已到期，不计入权限");
            else notices.add("角色 " + role.get("code") + " 已停用，不计入权限");
        }
        var active = new LinkedHashSet<Long>();
        if (body.activeRoleIds() == null) active.addAll(usable);
        else {
            for (long role : body.activeRoleIds()) {
                if (usable.contains(role)) active.add(role);
                else issues.add("角色 " + role + " 不是该用户在此隔离域内已分配且启用的角色，业务系统创建上下文会被拒绝");
            }
        }
        for (var role : assigned) role.put("active", active.contains(number(role.get("id"))));
        var permissions = new LinkedHashMap<String,Map<String,Object>>();
        for (var grant : dao.grants(active)) {
            var permission = permissions.computeIfAbsent((String) grant.get("code"), code -> {
                var p = new LinkedHashMap<String,Object>();
                p.put("id", number(grant.get("id")));
                p.put("code", code);
                p.put("name", grant.get("name"));
                p.put("resource", grant.get("resource"));
                p.put("action", grant.get("action"));
                p.put("grantedBy", new ArrayList<Long>());
                return p;
            });
            longs(permission.get("grantedBy")).add(number(grant.get("role_id")));
        }
        boolean eligible = issues.isEmpty();
        Object currentDepartment = user.get("activeDepartmentId");
        var result = new LinkedHashMap<String,Object>();
        result.put("eligible", eligible);
        result.put("issues", issues);
        result.put("notices", notices);
        result.put("user", user);
        result.put("member", member);
        result.put("applicationEnabled", applicationEnabled);
        result.put("switchesDepartment", currentDepartment == null || number(currentDepartment) != dept);
        result.put("roles", assigned);
        result.put("permissions", eligible ? new ArrayList<>(permissions.values()) : List.of());
        if (body.permissions() != null && !body.permissions().isEmpty()) {
            var decisions = new LinkedHashMap<String,Boolean>();
            for (String code : body.permissions()) decisions.put(code, eligible && permissions.containsKey(code));
            result.put("decisions", decisions);
            result.put("allowed", decisions.values().stream().allMatch(Boolean::booleanValue));
        }
        return result;
    }

    // ---- audit and overview ----
    @Transactional(readOnly=true) public Map<String,Object> events(Long dept, Long app, Visibility visible, String actor, String action, String keyword,
                                     LocalDate from, LocalDate to, int page, int size) {
        Timestamp start = from == null ? null : Timestamp.from(from.atStartOfDay(ZONE).toInstant());
        Timestamp end = to == null ? null : Timestamp.from(to.plusDays(1).atStartOfDay(ZONE).toInstant());
        var filter = new RbacDao.AuditFilter(dept, app, visible, actor, action, keyword, start, end);
        return page(dao.events(filter, page, size), page, size);
    }
    public Map<String,Object> overview(long dept, long app) { domain(dept, app); return dao.overview(dept, app); }

    // ---- helpers ----
    private String code(String table, long id) { return dao.codes(table, List.of(id)).stream().findFirst().orElse(table + ":" + id); }
    private String diff(String table, Set<Long> before, Set<Long> after) {
        var added = after.stream().filter(id -> !before.contains(id)).toList();
        var removed = before.stream().filter(id -> !after.contains(id)).toList();
        if (added.isEmpty() && removed.isEmpty()) return "未变化";
        var parts = new ArrayList<String>();
        if (!added.isEmpty()) parts.add("新增 " + String.join(", ", dao.codes(table, added)));
        if (!removed.isEmpty()) parts.add("移除 " + String.join(", ", dao.codes(table, removed)));
        return String.join("；", parts);
    }
    private static void attach(List<Map<String,Object>> items, List<Map<String,Object>> pairs, String ownerColumn, String valueColumn, String field) {
        var grouped = new HashMap<Long,List<Long>>();
        for (var pair : pairs) grouped.computeIfAbsent(number(pair.get(ownerColumn)), k -> new ArrayList<>()).add(number(pair.get(valueColumn)));
        for (var item : items) item.put(field, grouped.getOrDefault(number(item.get("id")), List.of()));
    }
    private static List<Long> ids(List<Map<String,Object>> items) { return items.stream().map(item -> number(item.get("id"))).toList(); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String text(String value) { return Objects.requireNonNullElse(value, "").strip(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>) value; }
    @SuppressWarnings("unchecked") private static List<String> strings(Object value) { return (List<String>) value; }
    @SuppressWarnings("unchecked") private static List<Long> longs(Object value) { return (List<Long>) value; }
}
