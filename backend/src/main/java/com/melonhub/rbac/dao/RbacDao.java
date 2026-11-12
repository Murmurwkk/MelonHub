package com.melonhub.rbac.dao;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class RbacDao {
    public record Page(List<Map<String,Object>> items, long total) {}
    /** visible limits the rows to the operator's applications and departments; records without a department need both unlimited. */
    public record AuditFilter(Long departmentId, Long applicationId, Visibility visible, String actor,
                              String action, String keyword, Timestamp from, Timestamp to) {}

    private final JdbcTemplate db;
    public RbacDao(JdbcTemplate db) { this.db = db; }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }

    private static final class Where {
        final StringBuilder sql = new StringBuilder(" WHERE 1=1");
        final List<Object> args = new ArrayList<>();
        Where and(String clause, Object... values) { sql.append(" AND ").append(clause); args.addAll(Arrays.asList(values)); return this; }
        Where in(String column, Collection<Long> values) {
            if (values.isEmpty()) return and("1=0");
            return and(column + " IN (" + AuthDao.placeholders(values.size()) + ")", values.toArray());
        }
        /** Users with a membership in one of the departments. */
        Where members(String userColumn, Collection<Long> departments) {
            if (departments.isEmpty()) return and("1=0");
            return and("EXISTS(SELECT 1 FROM memberships mv WHERE mv.user_id=" + userColumn + " AND mv.department_id IN ("
                    + AuthDao.placeholders(departments.size()) + "))", departments.toArray());
        }
    }
    private Page page(String select, String from, Where where, String order, int page, int size) {
        long total = db.queryForObject("SELECT COUNT(*) " + from + where.sql, Long.class, where.args.toArray());
        if (total == 0) return new Page(new ArrayList<>(), 0);
        var args = new ArrayList<>(where.args);
        args.add(size);
        args.add((long) (page - 1) * size);
        return new Page(db.queryForList(select + " " + from + where.sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray()), total);
    }
    public static String like(String keyword) {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private static Object[] join(Object[] head, Collection<Long> tail) {
        Object[] all = Arrays.copyOf(head, head.length + tail.size());
        int i = head.length;
        for (Long value : tail) all[i++] = value;
        return all;
    }

    public boolean healthy() { return Integer.valueOf(1).equals(db.queryForObject("SELECT 1", Integer.class)); }
    public void audit(String actor, String action, String target, Long department, Long app, String detail, String sourceIp) {
        db.update("INSERT INTO audit_events(actor,action,target,department_id,application_id,detail,source_ip) VALUES (?,?,?,?,?,?,?)",
                truncate(actor, 100), action, truncate(target, 200), department, app, truncate(detail, 1000), truncate(sourceIp, 64));
    }
    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    public boolean departmentExists(long id) { return count("SELECT COUNT(*) FROM departments WHERE id=?", id) > 0; }
    public boolean applicationExists(long id) { return count("SELECT COUNT(*) FROM applications WHERE id=?", id) > 0; }
    /** Name and code of a department or application, read before deletion so the audit record stays readable. */
    public Optional<String[]> label(String table, long id) {
        if (!Set.of("departments", "applications").contains(table)) throw new IllegalArgumentException();
        return db.query("SELECT name,code FROM " + table + " WHERE id=?", (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, id).stream().findFirst();
    }
    /** Members, roles, permissions and administrators limited to it that keep a department from being deleted. */
    public long[] departmentUsage(long id) {
        return new long[] {
            count("SELECT COUNT(*) FROM memberships WHERE department_id=?", id),
            count("SELECT COUNT(*) FROM roles WHERE department_id=?", id),
            count("SELECT COUNT(*) FROM permissions WHERE department_id=?", id),
            count("SELECT COUNT(*) FROM admin_account_departments WHERE department_id=?", id) };
    }
    /** Roles and permissions, across all departments, that keep an application from being deleted. */
    public long[] applicationUsage(long id) {
        return new long[] {
            count("SELECT COUNT(*) FROM roles WHERE application_id=?", id),
            count("SELECT COUNT(*) FROM permissions WHERE application_id=?", id) };
    }
    /** Serializes key creation and rotation of one application, so the active key limit holds under concurrency. */
    public boolean lockApplication(long id) {
        return !db.queryForList("SELECT id FROM applications WHERE id=? FOR UPDATE", Long.class, id).isEmpty();
    }
    public boolean applicationEnabled(long id) { return count("SELECT COUNT(*) FROM applications WHERE id=? AND enabled=TRUE", id) > 0; }
    public boolean scopedExists(String table, long id, long dept, long app) {
        if (!Set.of("roles", "permissions").contains(table)) throw new IllegalArgumentException();
        return count("SELECT COUNT(*) FROM " + table + " WHERE id=? AND department_id=? AND application_id=?", id, dept, app) > 0;
    }
    public long scopedCount(String table, Collection<Long> ids, long dept, long app) {
        if (!Set.of("roles", "permissions").contains(table)) throw new IllegalArgumentException();
        if (ids.isEmpty()) return 0;
        return count("SELECT COUNT(*) FROM " + table + " WHERE department_id=? AND application_id=? AND id IN (" + AuthDao.placeholders(ids.size()) + ")",
                join(new Object[]{dept, app}, ids));
    }

    public List<Map<String,Object>> departments(Collection<Long> visible) {
        var where = new Where();
        if (visible != null) where.in("d.id", visible);
        return db.queryForList("SELECT d.id,d.name,d.code,d.created_at AS createdAt,(SELECT COUNT(*) FROM memberships m WHERE m.department_id=d.id) AS memberCount,"
                + "(SELECT COUNT(*) FROM roles r WHERE r.department_id=d.id) AS roleCount FROM departments d" + where.sql + " ORDER BY d.id", where.args.toArray());
    }
    public long createDepartment(String name, String code) { return insert("INSERT INTO departments(name,code) VALUES (?,?)", name, code); }
    public int updateDepartment(long id, String name, String code) { return db.update("UPDATE departments SET name=?,code=? WHERE id=?", name, code, id); }
    public int deleteDepartment(long id) { return db.update("DELETE FROM departments WHERE id=?", id); }

    public List<Map<String,Object>> applications(Collection<Long> visible) {
        var where = new Where();
        if (visible != null) where.in("a.id", visible);
        return db.queryForList("SELECT a.id,a.name,a.code,a.description,a.owner,a.enabled,a.rate_limit AS rateLimit,a.created_at AS createdAt,"
                + "(SELECT COUNT(*) FROM roles r WHERE r.application_id=a.id) AS roleCount,"
                + "(SELECT COUNT(*) FROM permissions p WHERE p.application_id=a.id) AS permissionCount,"
                + "(SELECT COUNT(*) FROM application_keys k WHERE k.application_id=a.id AND k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at>CURRENT_TIMESTAMP)) AS activeKeyCount "
                + "FROM applications a" + where.sql + " ORDER BY a.id", where.args.toArray());
    }
    public long createApplication(String name, String code, String description, String owner) {
        return insert("INSERT INTO applications(name,code,description,owner) VALUES (?,?,?,?)", name, code, description, owner);
    }
    public int updateApplication(long id, String name, String code, String description, String owner) {
        return db.update("UPDATE applications SET name=?,code=?,description=?,owner=? WHERE id=?", name, code, description, owner, id);
    }
    public int applicationStatus(long id, boolean enabled) { return db.update("UPDATE applications SET enabled=? WHERE id=?", enabled, id); }
    public int deleteApplication(long id) { return db.update("DELETE FROM applications WHERE id=?", id); }

    public List<Map<String,Object>> keys(long app) {
        return db.queryForList("SELECT id,name,key_prefix AS prefix,created_by AS createdBy,created_at AS createdAt,expires_at AS expiresAt,"
                + "last_used_at AS lastUsedAt,revoked_at AS revokedAt,CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED' "
                + "WHEN expires_at IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'ACTIVE' END AS status "
                + "FROM application_keys WHERE application_id=? ORDER BY id DESC", app);
    }
    public long activeKeyCount(long app) {
        return count("SELECT COUNT(*) FROM application_keys WHERE application_id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)", app);
    }
    /** Locking read: sees keys committed by a transaction that held the application lock before this one. */
    public long lockedActiveKeyCount(long app) {
        return count("SELECT COUNT(*) FROM application_keys WHERE application_id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP) FOR SHARE", app);
    }
    public long createKey(long app, String name, String prefix, String hash, String createdBy, Timestamp expires) {
        return insert("INSERT INTO application_keys(application_id,name,key_prefix,key_hash,created_by,expires_at) VALUES (?,?,?,?,?,?)",
                app, name, prefix, hash, truncate(createdBy, 100), expires);
    }
    public Optional<String> keyName(long app, long key) {
        return db.queryForList("SELECT name FROM application_keys WHERE id=? AND application_id=?", String.class, key, app).stream().findFirst();
    }
    public int revokeKey(long app, long key) {
        return db.update("UPDATE application_keys SET revoked_at=CURRENT_TIMESTAMP WHERE id=? AND application_id=? AND revoked_at IS NULL", key, app);
    }
    public int revokeAllKeys(long app) { return db.update("UPDATE application_keys SET revoked_at=CURRENT_TIMESTAMP WHERE application_id=? AND revoked_at IS NULL", app); }

    /** visibleDepartments limits the list to members of those departments; null lists every user. */
    public Page users(String keyword, Long department, Boolean enabled, Collection<Long> visibleDepartments, int page, int size) {
        var where = new Where();
        if (visibleDepartments != null) where.members("u.id", visibleDepartments);
        if (enabled != null) where.and("u.enabled=?", enabled);
        if (department != null) where.and("EXISTS(SELECT 1 FROM memberships m WHERE m.user_id=u.id AND m.department_id=?)", department);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = like(keyword.strip());
            where.and("(u.name LIKE ? OR u.external_id LIKE ? OR u.email LIKE ?)", pattern, pattern, pattern);
        }
        return page("SELECT u.id,u.name,u.external_id AS externalId,u.email,u.enabled,u.active_department_id AS activeDepartmentId,"
                + "u.context_version AS contextVersion,u.created_at AS createdAt", "FROM users u", where, "u.id", page, size);
    }
    public List<Map<String,Object>> memberships(Collection<Long> users) {
        if (users.isEmpty()) return List.of();
        return db.queryForList("SELECT user_id,department_id FROM memberships WHERE user_id IN (" + AuthDao.placeholders(users.size()) + ") ORDER BY department_id", users.toArray());
    }
    public List<Map<String,Object>> departmentUsers(long dept) {
        return db.queryForList("SELECT u.id,u.name,u.external_id AS externalId,u.enabled FROM users u JOIN memberships m ON m.user_id=u.id WHERE m.department_id=? ORDER BY u.id", dept);
    }
    public Optional<Map<String,Object>> user(long id) {
        return db.queryForList("SELECT id,name,external_id AS externalId,email,enabled,active_department_id AS activeDepartmentId,context_version AS contextVersion,"
                + "created_at AS createdAt FROM users WHERE id=?", id).stream().findFirst();
    }
    public long createUser(String name, String external, String email) { return insert("INSERT INTO users(name,external_id,email) VALUES (?,?,?)", name, external, email); }
    public Optional<String> lockUser(long id) { return db.queryForList("SELECT external_id FROM users WHERE id=? FOR UPDATE", String.class, id).stream().findFirst(); }
    public List<Long> lockUsers(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return db.queryForList("SELECT id FROM users WHERE id IN (" + AuthDao.placeholders(ids.size()) + ") ORDER BY id FOR UPDATE", Long.class, ids.toArray());
    }
    /** Locks the user that currently has this external id, reading past the transaction snapshot. */
    public Optional<Long> lockUserByExternal(String external) {
        return db.queryForList("SELECT id FROM users WHERE external_id=? FOR UPDATE", Long.class, external).stream().findFirst();
    }
    /** Name and email only; the external id is left as it is now, not as it was when the caller looked it up. */
    public void updateProfile(long id, String name, String email) { db.update("UPDATE users SET name=?,email=? WHERE id=?", name, email, id); }
    public void updateUser(long id, String name, String external, String email, boolean invalidate) {
        db.update("UPDATE users SET name=?,external_id=?,email=?,context_version=context_version+? WHERE id=?", name, external, email, invalidate ? 1 : 0, id);
    }
    public int userStatus(long id, boolean enabled) { return db.update("UPDATE users SET enabled=?,context_version=context_version+1 WHERE id=?", enabled, id); }
    public void invalidateUser(long id) { db.update("UPDATE users SET context_version=context_version+1 WHERE id=?", id); }
    public int deleteUser(long id) { return db.update("DELETE FROM users WHERE id=?", id); }
    public List<Long> userDepartments(long user) { return db.queryForList("SELECT department_id FROM memberships WHERE user_id=?", Long.class, user); }
    /**
     * Locking reads return the latest committed rows rather than the transaction snapshot, which may predate the
     * row lock the caller just waited for. Whole-set replacements compute their difference from these.
     */
    public List<Long> lockedUserDepartments(long user) {
        return db.queryForList("SELECT department_id FROM memberships WHERE user_id=? FOR UPDATE", Long.class, user);
    }
    public boolean lockedMember(long user, long dept) {
        return count("SELECT COUNT(*) FROM memberships WHERE user_id=? AND department_id=? FOR SHARE", user, dept) > 0;
    }
    public long lockedMembers(Collection<Long> users, long dept) {
        if (users.isEmpty()) return 0;
        return count("SELECT COUNT(*) FROM memberships WHERE department_id=? AND user_id IN (" + AuthDao.placeholders(users.size()) + ") FOR SHARE",
                join(new Object[]{dept}, users));
    }
    public void removeMembership(long user, long dept) { db.update("DELETE FROM memberships WHERE user_id=? AND department_id=?", user, dept); }
    public void addMembership(long user, long dept) { db.update("INSERT IGNORE INTO memberships(user_id,department_id) VALUES (?,?)", user, dept); }
    public void clearInactiveDepartment(long user) {
        db.update("UPDATE users SET active_department_id=NULL WHERE id=? AND active_department_id NOT IN (SELECT department_id FROM memberships WHERE user_id=?)", user, user);
    }
    public boolean member(long user, long dept) { return count("SELECT COUNT(*) FROM memberships WHERE user_id=? AND department_id=?", user, dept) > 0; }
    public long members(Collection<Long> users, long dept) {
        if (users.isEmpty()) return 0;
        return count("SELECT COUNT(*) FROM memberships WHERE department_id=? AND user_id IN (" + AuthDao.placeholders(users.size()) + ")", join(new Object[]{dept}, users));
    }
    public List<Map<String,Object>> accessRoles(long user) {
        return db.queryForList("SELECT ur.department_id,d.name AS department_name,ur.application_id,a.name AS application_name,a.enabled AS application_enabled,"
                + "r.id,r.name,r.code,r.enabled,ur.expires_at AS expires_at,ur.granted_by AS granted_by FROM user_roles ur JOIN roles r ON r.id=ur.role_id JOIN departments d ON d.id=ur.department_id "
                + "JOIN applications a ON a.id=ur.application_id WHERE ur.user_id=? ORDER BY ur.department_id,ur.application_id,r.id", user);
    }
    public List<Map<String,Object>> accessPermissions(long user) {
        return db.queryForList("SELECT DISTINCT ur.department_id,ur.application_id,p.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id AND r.enabled=TRUE "
                + "AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP) "
                + "JOIN role_permissions rp ON rp.role_id=r.id JOIN permissions p ON p.id=rp.permission_id WHERE ur.user_id=? ORDER BY p.code", user);
    }

    public List<Map<String,Object>> roles(long dept, long app) {
        return db.queryForList("SELECT r.id,r.name,r.code,r.description,r.enabled,r.created_at AS createdAt,"
                + "(SELECT COUNT(*) FROM user_roles ur WHERE ur.role_id=r.id) AS memberCount FROM roles r WHERE r.department_id=? AND r.application_id=? ORDER BY r.id", dept, app);
    }
    public List<Map<String,Object>> rolePermissions(long dept, long app) {
        return db.queryForList("SELECT role_id,permission_id FROM role_permissions WHERE department_id=? AND application_id=? ORDER BY permission_id", dept, app);
    }
    public List<Map<String,Object>> permissions(long dept, long app) {
        return db.queryForList("SELECT p.id,p.name,p.code,p.resource,p.action,p.created_at AS createdAt,"
                + "(SELECT COUNT(*) FROM role_permissions rp WHERE rp.permission_id=p.id) AS roleCount FROM permissions p WHERE p.department_id=? AND p.application_id=? ORDER BY p.id", dept, app);
    }
    public long createRole(long dept, long app, String name, String code, String description) {
        return insert("INSERT INTO roles(department_id,application_id,name,code,description) VALUES (?,?,?,?,?)", dept, app, name, code, description);
    }
    public long createRole(long dept, long app, String name, String code, String description, boolean enabled) {
        return insert("INSERT INTO roles(department_id,application_id,name,code,description,enabled) VALUES (?,?,?,?,?,?)", dept, app, name, code, description, enabled);
    }
    public void updateRole(long id, String name, String code, String description) { db.update("UPDATE roles SET name=?,code=?,description=? WHERE id=?", name, code, description, id); }
    public int roleStatus(long id, boolean enabled) { return db.update("UPDATE roles SET enabled=? WHERE id=?", enabled, id); }
    public int deleteRole(long id) { return db.update("DELETE FROM roles WHERE id=?", id); }
    public long createPermission(long dept, long app, String name, String code, String resource, String action) {
        return insert("INSERT INTO permissions(department_id,application_id,name,code,resource,action) VALUES (?,?,?,?,?,?)", dept, app, name, code, resource, action);
    }
    public void updatePermission(long id, String name, String code, String resource, String action) {
        db.update("UPDATE permissions SET name=?,code=?,resource=?,action=? WHERE id=?", name, code, resource, action, id);
    }
    public int deletePermission(long id) { return db.update("DELETE FROM permissions WHERE id=?", id); }
    public List<Long> rolePermissionIds(long role) { return db.queryForList("SELECT permission_id FROM role_permissions WHERE role_id=?", Long.class, role); }
    /** Serializes whole-set edits of one role's permissions or members. */
    public boolean lockRole(long role) { return !db.queryForList("SELECT id FROM roles WHERE id=? FOR UPDATE", Long.class, role).isEmpty(); }
    public List<Long> lockedRolePermissionIds(long role) {
        return db.queryForList("SELECT permission_id FROM role_permissions WHERE role_id=? FOR SHARE", Long.class, role);
    }
    public List<String> codes(String table, Collection<Long> ids) {
        if (!Set.of("roles", "permissions").contains(table)) throw new IllegalArgumentException();
        if (ids.isEmpty()) return List.of();
        return db.queryForList("SELECT code FROM " + table + " WHERE id IN (" + AuthDao.placeholders(ids.size()) + ") ORDER BY code", String.class, ids.toArray());
    }
    public void replaceRolePermissions(long role, long dept, long app, Collection<Long> permissions) {
        db.update("DELETE FROM role_permissions WHERE role_id=?", role);
        db.batchUpdate("INSERT INTO role_permissions(role_id,permission_id,department_id,application_id) VALUES (?,?,?,?)",
                permissions.stream().map(p -> new Object[]{role, p, dept, app}).toList());
    }
    public List<Long> assignedRoles(long user, long dept, long app) {
        return db.queryForList("SELECT role_id FROM user_roles WHERE user_id=? AND department_id=? AND application_id=? ORDER BY role_id", Long.class, user, dept, app);
    }
    /** The user's current assignments in one domain (role id to expiry, null when permanent), without locking. */
    public Map<Long,Timestamp> assignmentExpiries(long user, long dept, long app) {
        var result = new LinkedHashMap<Long,Timestamp>();
        db.query("SELECT role_id,expires_at FROM user_roles WHERE user_id=? AND department_id=? AND application_id=? ORDER BY role_id",
                rs -> { result.put(rs.getLong(1), rs.getTimestamp(2)); }, user, dept, app);
        return result;
    }
    /** The user's current assignments in one domain (role id to expiry, null when permanent), locked for replacement. */
    public Map<Long,Timestamp> lockedAssignments(long user, long dept, long app) {
        var result = new LinkedHashMap<Long,Timestamp>();
        db.query("SELECT role_id,expires_at FROM user_roles WHERE user_id=? AND department_id=? AND application_id=? ORDER BY role_id FOR UPDATE",
                rs -> { result.put(rs.getLong(1), rs.getTimestamp(2)); }, user, dept, app);
        return result;
    }
    public void removeUserRoles(long user, Collection<Long> roles) {
        if (roles.isEmpty()) return;
        db.update("DELETE FROM user_roles WHERE user_id=? AND role_id IN (" + AuthDao.placeholders(roles.size()) + ")", join(new Object[]{user}, roles));
    }
    public List<Map<String,Object>> roleMembers(long role) {
        return db.queryForList("SELECT u.id,u.name,u.external_id AS externalId,u.email,u.enabled FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE ur.role_id=? ORDER BY u.id", role);
    }
    public List<Long> roleMemberIds(long role) { return db.queryForList("SELECT user_id FROM user_roles WHERE role_id=? ORDER BY user_id", Long.class, role); }
    public List<Long> lockedRoleMemberIds(long role) {
        return db.queryForList("SELECT user_id FROM user_roles WHERE role_id=? ORDER BY user_id FOR UPDATE", Long.class, role);
    }
    public void removeRoleMembers(long role, Collection<Long> users) {
        if (users.isEmpty()) return;
        db.update("DELETE FROM user_roles WHERE role_id=? AND user_id IN (" + AuthDao.placeholders(users.size()) + ")", join(new Object[]{role}, users));
    }
    public void addRoleMembers(long role, long dept, long app, Collection<Long> users, String actor) {
        db.batchUpdate("INSERT INTO user_roles(user_id,role_id,department_id,application_id,granted_by,grant_id) VALUES (?,?,?,?,?,UUID())",
                users.stream().map(u -> new Object[]{u, role, dept, app, truncate(actor, 100)}).toList());
    }
    public Page departmentMembers(long dept, String keyword, int page, int size) {
        var where = new Where().and("m.department_id=?", dept);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = like(keyword.strip());
            where.and("(u.name LIKE ? OR u.external_id LIKE ? OR u.email LIKE ?)", pattern, pattern, pattern);
        }
        return page("SELECT u.id,u.name,u.external_id AS externalId,u.email,u.enabled", "FROM users u JOIN memberships m ON m.user_id=u.id", where, "u.id", page, size);
    }
    public List<Map<String,Object>> domainRolePairs(long dept, long app, Collection<Long> users) {
        if (users.isEmpty()) return List.of();
        return db.queryForList("SELECT user_id,role_id,expires_at FROM user_roles WHERE department_id=? AND application_id=? AND user_id IN (" + AuthDao.placeholders(users.size()) + ") ORDER BY role_id",
                join(new Object[]{dept, app}, users));
    }

    public List<Map<String,Object>> userRolesInDomain(long user, long dept, long app) {
        return db.queryForList("SELECT r.id,r.name,r.code,r.enabled,ur.expires_at AS expiresAt FROM roles r JOIN user_roles ur ON ur.role_id=r.id "
                + "WHERE ur.user_id=? AND r.department_id=? AND r.application_id=? ORDER BY r.id", user, dept, app);
    }
    public List<Map<String,Object>> grants(Collection<Long> roles) {
        if (roles.isEmpty()) return List.of();
        return db.queryForList("SELECT p.id,p.code,p.name,p.resource,p.action,rp.role_id FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id "
                + "WHERE rp.role_id IN (" + AuthDao.placeholders(roles.size()) + ") ORDER BY p.code,rp.role_id", roles.toArray());
    }

    public Page events(AuditFilter filter, int page, int size) {
        var where = new Where();
        if (filter.departmentId() != null) where.and("e.department_id=?", filter.departmentId());
        if (filter.applicationId() != null) where.and("e.application_id=?", filter.applicationId());
        if (filter.visible().applications() != null) where.in("e.application_id", filter.visible().applications());
        if (filter.visible().departments() != null) where.in("e.department_id", filter.visible().departments());
        if (filter.actor() != null && !filter.actor().isBlank()) where.and("e.actor=?", filter.actor().strip());
        if (filter.action() != null && !filter.action().isBlank()) where.and("e.action=?", filter.action().strip());
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String pattern = like(filter.keyword().strip());
            where.and("(e.target LIKE ? OR e.detail LIKE ? OR e.actor LIKE ?)", pattern, pattern, pattern);
        }
        if (filter.from() != null) where.and("e.created_at>=?", filter.from());
        if (filter.to() != null) where.and("e.created_at<?", filter.to());
        return page("SELECT e.id,e.actor,e.action,e.target,e.detail,e.source_ip AS sourceIp,e.created_at AS createdAt,e.department_id AS departmentId,"
                + "d.name AS departmentName,e.application_id AS applicationId,a.name AS applicationName",
                "FROM audit_events e LEFT JOIN departments d ON d.id=e.department_id LEFT JOIN applications a ON a.id=e.application_id", where, "e.id DESC", page, size);
    }

    // ---- governance support (V3) ----
    public Optional<Long> userIdByExternal(String external) {
        return db.queryForList("SELECT id FROM users WHERE external_id=?", Long.class, external).stream().findFirst();
    }
    public List<Map<String,Object>> departmentIdsByCodes(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return db.queryForList("SELECT id,code FROM departments WHERE code IN (" + AuthDao.placeholders(codes.size()) + ")", codes.toArray());
    }
    public Optional<Long> scopedIdByCode(String table, long dept, long app, String code) {
        if (!Set.of("roles", "permissions").contains(table)) throw new IllegalArgumentException();
        return db.queryForList("SELECT id FROM " + table + " WHERE department_id=? AND application_id=? AND code=?", Long.class, dept, app, code).stream().findFirst();
    }
    /** Creates or replaces one assignment; granting a held role again sets its expiry and makes it a new grant. */
    public void grantRole(long user, long dept, long app, long role, Timestamp expires, String grantedBy) {
        db.update("INSERT INTO user_roles(user_id,role_id,department_id,application_id,expires_at,granted_by,grant_id) VALUES (?,?,?,?,?,?,UUID()) "
                + "ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at),granted_by=VALUES(granted_by),granted_at=CURRENT_TIMESTAMP,grant_id=UUID()",
                user, role, dept, app, expires, truncate(grantedBy, 100));
    }
    public int applicationRateLimit(long app, int fallback) {
        var rows = db.queryForList("SELECT rate_limit FROM applications WHERE id=?", Integer.class, app);
        return rows.isEmpty() || rows.get(0) == null ? fallback : rows.get(0);
    }
    public int setRateLimit(long app, Integer perSecond) { return db.update("UPDATE applications SET rate_limit=? WHERE id=?", perSecond, app); }
    public Optional<Map<String,Object>> applicationRow(long app) {
        return db.queryForList("SELECT id,name,code,enabled FROM applications WHERE id=?", app).stream().findFirst();
    }
    public List<Long> manifestDepartmentIds(long app) {
        return db.queryForList("SELECT department_id FROM (SELECT department_id FROM permissions WHERE application_id=? "
                + "UNION SELECT department_id FROM roles WHERE application_id=?) t ORDER BY department_id", Long.class, app, app);
    }
    private static final String HOLDERS = "FROM user_roles ur JOIN users u ON u.id=ur.user_id AND u.enabled=TRUE "
            + "JOIN roles r ON r.id=ur.role_id AND r.enabled=TRUE "
            + "JOIN role_permissions rp ON rp.role_id=r.id JOIN permissions p ON p.id=rp.permission_id "
            + "JOIN departments d ON d.id=ur.department_id "
            + "WHERE p.application_id=? AND p.code=? AND r.department_id=p.department_id AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP)";
    /** Limits holders to one department and to the departments the operator may see (null: all). */
    private static String holderFilter(Long dept, Collection<Long> visibleDepartments, List<Object> args) {
        var filter = new StringBuilder();
        if (dept != null) { filter.append(" AND ur.department_id=?"); args.add(dept); }
        if (visibleDepartments != null) {
            if (visibleDepartments.isEmpty()) return filter.append(" AND 1=0").toString();
            filter.append(" AND ur.department_id IN (").append(AuthDao.placeholders(visibleDepartments.size())).append(")");
            args.addAll(visibleDepartments);
        }
        return filter.toString();
    }
    /** Number of distinct (user, department) holders of one permission code. */
    public long permissionHolderCount(long app, String code, Long dept, Collection<Long> visibleDepartments) {
        var args = new ArrayList<Object>(List.of(app, code));
        String filter = holderFilter(dept, visibleDepartments, args);
        return db.queryForObject("SELECT COUNT(*) FROM (SELECT DISTINCT ur.user_id,ur.department_id " + HOLDERS + filter + ") holders", Long.class, args.toArray());
    }
    /**
     * Everyone currently holding one permission code through an enabled role and a non-expired assignment: the
     * first {@code limit} (user, department) holders, each with every role that grants the code.
     */
    public List<Map<String,Object>> permissionHolders(long app, String code, Long dept, Collection<Long> visibleDepartments, int limit) {
        var args = new ArrayList<Object>(List.of(app, code));
        String filter = holderFilter(dept, visibleDepartments, args);
        var keys = new ArrayList<Object>(args);
        keys.add(limit);
        var holders = db.queryForList("SELECT DISTINCT ur.department_id,ur.user_id " + HOLDERS + filter + " ORDER BY ur.department_id,ur.user_id LIMIT ?", keys.toArray());
        if (holders.isEmpty()) return List.of();
        var pairs = new StringBuilder();
        for (var holder : holders) {
            pairs.append(pairs.isEmpty() ? "" : ",").append("(?,?)");
            args.add(holder.get("user_id"));
            args.add(holder.get("department_id"));
        }
        return db.queryForList("SELECT ur.user_id AS userId,u.name AS userName,u.external_id AS externalId,"
                + "ur.department_id AS departmentId,d.name AS departmentName,r.id AS roleId,r.name AS roleName,r.code AS roleCode,ur.expires_at AS expiresAt "
                + HOLDERS + filter + " AND (ur.user_id,ur.department_id) IN (" + pairs + ") ORDER BY ur.department_id,u.id,r.id", args.toArray());
    }
    public List<Map<String,Object>> expiringAssignments(Timestamp deadline, Visibility visible, Long app) {
        var where = new Where().and("ur.expires_at IS NOT NULL AND ur.expires_at<=?", deadline);
        if (visible.applications() != null) where.in("ur.application_id", visible.applications());
        if (visible.departments() != null) where.in("ur.department_id", visible.departments());
        if (app != null) where.and("ur.application_id=?", app);
        return db.queryForList("SELECT ur.user_id AS userId,u.name AS userName,u.external_id AS externalId,ur.role_id AS roleId,r.name AS roleName,r.code AS roleCode,"
                + "ur.department_id AS departmentId,d.name AS departmentName,ur.application_id AS applicationId,a.name AS applicationName,"
                + "ur.expires_at AS expiresAt,ur.granted_by AS grantedBy,ur.granted_at AS grantedAt "
                + "FROM user_roles ur JOIN users u ON u.id=ur.user_id JOIN roles r ON r.id=ur.role_id "
                + "JOIN departments d ON d.id=ur.department_id JOIN applications a ON a.id=ur.application_id"
                + where.sql + " ORDER BY ur.expires_at LIMIT 500", where.args.toArray());
    }
    public long expiringCount(Timestamp deadline) {
        return count("SELECT COUNT(*) FROM user_roles WHERE expires_at IS NOT NULL AND expires_at<=?", deadline);
    }
    /** Lapsed assignments, oldest first, with what the expiry notice needs. */
    public List<Map<String,Object>> lapsedAssignments(int limit) {
        return db.queryForList("SELECT ur.user_id AS userId,u.external_id AS externalId,ur.role_id AS roleId,r.code AS roleCode,"
                + "ur.department_id AS departmentId,ur.application_id AS applicationId FROM user_roles ur "
                + "JOIN users u ON u.id=ur.user_id JOIN roles r ON r.id=ur.role_id "
                + "WHERE ur.expires_at<=CURRENT_TIMESTAMP ORDER BY ur.expires_at,ur.user_id,ur.role_id LIMIT ?", limit);
    }
    /** Deletes the assignment only while it is still lapsed, so a concurrent re-grant or another instance wins cleanly. */
    public int deleteLapsedAssignment(long user, long role) {
        return db.update("DELETE FROM user_roles WHERE user_id=? AND role_id=? AND expires_at<=CURRENT_TIMESTAMP", user, role);
    }

    // ---- console search and status (V3) ----
    public List<Map<String,Object>> searchUsers(String pattern, Collection<Long> visibleDepartments) {
        var where = new Where().and("(u.name LIKE ? OR u.external_id LIKE ? OR u.email LIKE ?)", pattern, pattern, pattern);
        if (visibleDepartments != null) where.members("u.id", visibleDepartments);
        return db.queryForList("SELECT u.id,u.name,u.external_id AS externalId,u.enabled FROM users u" + where.sql + " ORDER BY u.id LIMIT 5", where.args.toArray());
    }
    public List<Map<String,Object>> searchDepartments(String pattern, Collection<Long> visibleDepartments) {
        var where = new Where().and("(name LIKE ? OR code LIKE ?)", pattern, pattern);
        if (visibleDepartments != null) where.in("id", visibleDepartments);
        return db.queryForList("SELECT id,name,code FROM departments" + where.sql + " ORDER BY id LIMIT 5", where.args.toArray());
    }
    public List<Map<String,Object>> searchApplications(String pattern, Collection<Long> visible) {
        var sql = new StringBuilder("SELECT id,name,code,enabled FROM applications WHERE (name LIKE ? OR code LIKE ?)");
        var args = new ArrayList<Object>(List.of(pattern, pattern));
        if (visible != null) {
            if (visible.isEmpty()) return List.of();
            sql.append(" AND id IN (" + AuthDao.placeholders(visible.size()) + ")");
            args.addAll(visible);
        }
        sql.append(" ORDER BY id LIMIT 5");
        return db.queryForList(sql.toString(), args.toArray());
    }
    public List<Map<String,Object>> searchRoles(String pattern, Visibility visible) {
        var where = new Where().and("(r.name LIKE ? OR r.code LIKE ?)", pattern, pattern);
        if (visible.applications() != null) where.in("r.application_id", visible.applications());
        if (visible.departments() != null) where.in("r.department_id", visible.departments());
        return db.queryForList("SELECT r.id,r.name,r.code,r.department_id AS departmentId,d.name AS departmentName,r.application_id AS applicationId,a.name AS applicationName "
                + "FROM roles r JOIN departments d ON d.id=r.department_id JOIN applications a ON a.id=r.application_id" + where.sql + " ORDER BY r.id LIMIT 5",
                where.args.toArray());
    }
    public Map<String,Object> consoleCounts() {
        return db.queryForMap("SELECT (SELECT COUNT(*) FROM users) AS users,(SELECT COUNT(*) FROM users WHERE enabled=TRUE) AS enabledUsers,"
                + "(SELECT COUNT(*) FROM departments) AS departments,(SELECT COUNT(*) FROM applications) AS applications,"
                + "(SELECT COUNT(*) FROM applications WHERE enabled=TRUE) AS enabledApplications,(SELECT COUNT(*) FROM roles) AS roles,"
                + "(SELECT COUNT(*) FROM permissions) AS permissions,(SELECT COUNT(*) FROM user_roles) AS assignments,"
                + "(SELECT COUNT(*) FROM permission_contexts WHERE expires_at>CURRENT_TIMESTAMP) AS activeContexts");
    }
    public Map<String,Object> overview(long dept, long app) {
        var result = new LinkedHashMap<String,Object>();
        result.put("applications", count("SELECT COUNT(*) FROM applications"));
        result.put("enabledApplications", count("SELECT COUNT(*) FROM applications WHERE enabled=TRUE"));
        result.put("departments", count("SELECT COUNT(*) FROM departments"));
        result.put("users", count("SELECT COUNT(*) FROM users"));
        result.put("enabledUsers", count("SELECT COUNT(*) FROM users WHERE enabled=TRUE"));
        result.put("departmentMembers", count("SELECT COUNT(*) FROM memberships WHERE department_id=?", dept));
        result.put("roles", count("SELECT COUNT(*) FROM roles WHERE department_id=? AND application_id=?", dept, app));
        result.put("permissions", count("SELECT COUNT(*) FROM permissions WHERE department_id=? AND application_id=?", dept, app));
        result.put("members", count("SELECT COUNT(DISTINCT user_id) FROM user_roles WHERE department_id=? AND application_id=?", dept, app));
        result.put("activeKeys", activeKeyCount(app));
        result.put("activeContexts", count("SELECT COUNT(*) FROM permission_contexts c JOIN users u ON u.id=c.user_id JOIN applications a ON a.id=c.application_id "
                + "WHERE c.department_id=? AND c.application_id=? AND c.context_version=u.context_version AND c.department_id=u.active_department_id "
                + "AND c.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE AND a.enabled=TRUE", dept, app));
        result.put("auditLast24h", count("SELECT COUNT(*) FROM audit_events WHERE created_at>TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP)"));
        return result;
    }
}
