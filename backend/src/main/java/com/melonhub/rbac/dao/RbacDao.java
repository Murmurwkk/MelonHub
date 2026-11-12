package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.Application;
import com.melonhub.rbac.entity.ApplicationKey;
import com.melonhub.rbac.entity.AuditEvent;
import com.melonhub.rbac.entity.Department;
import com.melonhub.rbac.entity.Membership;
import com.melonhub.rbac.entity.MembershipId;
import com.melonhub.rbac.entity.Permission;
import com.melonhub.rbac.entity.Role;
import com.melonhub.rbac.entity.RolePermission;
import com.melonhub.rbac.entity.RolePermissionId;
import com.melonhub.rbac.entity.User;
import com.melonhub.rbac.entity.UserRole;
import com.melonhub.rbac.entity.UserRoleId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Departments, applications and keys, users and memberships, roles, permissions and assignments, audit records and
 * the console reports. Every method runs in a transaction (joining the caller's) and writes reach the database before
 * it returns. Locking reads take the latest committed rows, as the SELECT ... FOR UPDATE/FOR SHARE they replace did:
 * the persistence context is cleared first so no entity read earlier in the transaction hides a newer row.
 */
@Repository
@Transactional
public class RbacDao {
    public record Page(List<Map<String,Object>> items, long total) {}
    /** visible limits the rows to the operator's applications and departments; records without a department need both unlimited. */
    public record AuditFilter(Long departmentId, Long applicationId, Visibility visible, String actor,
                              String action, String keyword, Timestamp from, Timestamp to) {}

    /** An assignment that still counts. */
    private static final String CURRENT = "(ur.expiresAt is null or ur.expiresAt > current_timestamp)";
    private final EntityManager em;
    public RbacDao(EntityManager em) { this.em = em; }

    // ---- helpers ----
    private int update(String jpql, Object... namedValues) {
        em.flush();
        var query = em.createQuery(jpql);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        int changed = query.executeUpdate();
        em.clear();
        return changed;
    }
    private long count(String jpql, Object... namedValues) {
        var query = em.createQuery(jpql, Long.class);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        return query.getSingleResult();
    }
    private <T> TypedQuery<T> query(String jpql, Class<T> type, Object... namedValues) {
        var query = em.createQuery(jpql, type);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        return query;
    }
    private List<Map<String,Object>> rows(String jpql, Object... namedValues) { return Jpql.rows(query(jpql, Tuple.class, namedValues).getResultList()); }
    /** A locking read of the latest committed rows (see the class comment). */
    private <T> List<T> lockedRead(TypedQuery<T> query, LockModeType mode) {
        em.flush();
        em.clear();
        return query.setLockMode(mode).getResultList();
    }
    private <T> T persisted(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }
    /** Adds a count per id, read with one grouped query, to each row under the given key. */
    private void attachCounts(List<Map<String,Object>> rows, String key, String groupedJpql) {
        if (rows.isEmpty()) return;
        var counts = new HashMap<Long,Long>();
        for (var tuple : query(groupedJpql, Tuple.class, "ids", rows.stream().map(r -> (Long) r.get("id")).toList()).getResultList())
            counts.put(tuple.get(0, Long.class), tuple.get(1, Long.class));
        for (var row : rows) row.put(key, counts.getOrDefault((Long) row.get("id"), 0L));
    }
    /** A LIKE pattern for the search methods; they match it with escape '!'. */
    public static String like(String keyword) { return Jpql.like(keyword); }
    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String scopedEntity(String table) {
        return switch (table) {
            case "roles" -> "Role";
            case "permissions" -> "Permission";
            default -> throw new IllegalArgumentException();
        };
    }

    public boolean healthy() { return Integer.valueOf(1).equals(query("select 1 from AuditSeal s where s.id = 1", Integer.class).getSingleResult()); }
    public void audit(String actor, String action, String target, Long department, Long app, String detail, String sourceIp) {
        persisted(new AuditEvent().setActor(truncate(actor, 100)).setAction(action).setTarget(truncate(target, 200)).setDepartmentId(department)
                .setApplicationId(app).setDetail(truncate(detail, 1000)).setSourceIp(truncate(sourceIp, 64)));
    }
    public boolean departmentExists(long id) { return count("select count(d) from Department d where d.id = :id", "id", id) > 0; }
    public boolean applicationExists(long id) { return count("select count(a) from Application a where a.id = :id", "id", id) > 0; }
    /** Name and code of a department or application, read before deletion so the audit record stays readable. */
    public Optional<String[]> label(String table, long id) {
        String entity = switch (table) {
            case "departments" -> "Department";
            case "applications" -> "Application";
            default -> throw new IllegalArgumentException();
        };
        return query("select x.name, x.code from " + entity + " x where x.id = :id", Tuple.class, "id", id).getResultStream().findFirst()
                .map(t -> new String[] { t.get(0, String.class), t.get(1, String.class) });
    }
    /** Members, roles, permissions and administrators limited to it that keep a department from being deleted. */
    public long[] departmentUsage(long id) {
        return new long[] {
            count("select count(m) from Membership m where m.department.id = :id", "id", id),
            count("select count(r) from Role r where r.department.id = :id", "id", id),
            count("select count(p) from Permission p where p.department.id = :id", "id", id),
            count("select count(a) from AdminAccount a join a.departmentIds d where d = :id", "id", id) };
    }
    /** Roles and permissions, across all departments, that keep an application from being deleted. */
    public long[] applicationUsage(long id) {
        return new long[] {
            count("select count(r) from Role r where r.application.id = :id", "id", id),
            count("select count(p) from Permission p where p.application.id = :id", "id", id) };
    }
    /** Serializes key creation and rotation of one application, so the active key limit holds under concurrency. */
    public boolean lockApplication(long id) {
        return !lockedRead(query("select a from Application a where a.id = :id", Application.class, "id", id), LockModeType.PESSIMISTIC_WRITE).isEmpty();
    }
    public boolean applicationEnabled(long id) { return count("select count(a) from Application a where a.id = :id and a.enabled = true", "id", id) > 0; }
    public boolean scopedExists(String table, long id, long dept, long app) {
        return count("select count(x) from " + scopedEntity(table) + " x where x.id = :id and x.department.id = :dept and x.application.id = :app",
                "id", id, "dept", dept, "app", app) > 0;
    }
    public long scopedCount(String table, Collection<Long> ids, long dept, long app) {
        if (ids.isEmpty()) return 0;
        return count("select count(x) from " + scopedEntity(table) + " x where x.department.id = :dept and x.application.id = :app and x.id in :ids",
                "dept", dept, "app", app, "ids", ids);
    }

    // ---- departments ----
    public List<Map<String,Object>> departments(Collection<Long> visible) {
        var where = new Jpql.Where();
        if (visible != null) where.in("d.id", visible);
        var rows = Jpql.rows(where.bind(em.createQuery("select d.id as id, d.name as name, d.code as code, d.createdAt as createdAt from Department d"
                + where.text + " order by d.id", Tuple.class)).getResultList());
        attachCounts(rows, "memberCount", "select m.department.id, count(m) from Membership m where m.department.id in :ids group by m.department.id");
        attachCounts(rows, "roleCount", "select r.department.id, count(r) from Role r where r.department.id in :ids group by r.department.id");
        return rows;
    }
    public long createDepartment(String name, String code) { return persisted(new Department().setName(name).setCode(code)).getId(); }
    public int updateDepartment(long id, String name, String code) {
        return update("update Department d set d.name = :name, d.code = :code where d.id = :id", "name", name, "code", code, "id", id);
    }
    public int deleteDepartment(long id) { return update("delete from Department d where d.id = :id", "id", id); }

    // ---- applications and keys ----
    public List<Map<String,Object>> applications(Collection<Long> visible) {
        var where = new Jpql.Where();
        if (visible != null) where.in("a.id", visible);
        var rows = Jpql.rows(where.bind(em.createQuery("""
                select a.id as id, a.name as name, a.code as code, a.description as description, a.owner as owner, a.enabled as enabled,
                       a.rateLimit as rateLimit, a.createdAt as createdAt from Application a""" + where.text + " order by a.id", Tuple.class)).getResultList());
        attachCounts(rows, "roleCount", "select r.application.id, count(r) from Role r where r.application.id in :ids group by r.application.id");
        attachCounts(rows, "permissionCount", "select p.application.id, count(p) from Permission p where p.application.id in :ids group by p.application.id");
        attachCounts(rows, "activeKeyCount", "select k.application.id, count(k) from ApplicationKey k where k.application.id in :ids and k.revokedAt is null "
                + "and (k.expiresAt is null or k.expiresAt > current_timestamp) group by k.application.id");
        return rows;
    }
    public long createApplication(String name, String code, String description, String owner) {
        return persisted(new Application().setName(name).setCode(code).setDescription(description).setOwner(owner).setEnabled(true)).getId();
    }
    public int updateApplication(long id, String name, String code, String description, String owner) {
        return update("update Application a set a.name = :name, a.code = :code, a.description = :description, a.owner = :owner where a.id = :id",
                "name", name, "code", code, "description", description, "owner", owner, "id", id);
    }
    public int applicationStatus(long id, boolean enabled) { return update("update Application a set a.enabled = :enabled where a.id = :id", "enabled", enabled, "id", id); }
    /** Keys, webhooks, administrator grants and their rows go with it (ON DELETE CASCADE). */
    public int deleteApplication(long id) { return update("delete from Application a where a.id = :id", "id", id); }

    public List<Map<String,Object>> keys(long app) {
        return rows("""
                select k.id as id, k.name as name, k.keyPrefix as prefix, k.createdBy as createdBy, k.createdAt as createdAt, k.expiresAt as expiresAt,
                       k.lastUsedAt as lastUsedAt, k.revokedAt as revokedAt,
                       case when k.revokedAt is not null then 'REVOKED' when k.expiresAt is not null and k.expiresAt <= current_timestamp then 'EXPIRED'
                            else 'ACTIVE' end as status
                from ApplicationKey k where k.application.id = :app order by k.id desc""", "app", app);
    }
    private static final String ACTIVE_KEYS = "from ApplicationKey k where k.application.id = :app and k.revokedAt is null "
            + "and (k.expiresAt is null or k.expiresAt > current_timestamp)";
    public long activeKeyCount(long app) { return count("select count(k) " + ACTIVE_KEYS, "app", app); }
    /** Locking read: sees keys committed by a transaction that held the application lock before this one. */
    public long lockedActiveKeyCount(long app) {
        return lockedRead(query("select k " + ACTIVE_KEYS, ApplicationKey.class, "app", app), LockModeType.PESSIMISTIC_READ).size();
    }
    public long createKey(long app, String name, String prefix, String hash, String createdBy, Timestamp expires) {
        return persisted(new ApplicationKey().setApplication(em.getReference(Application.class, app)).setName(name).setKeyPrefix(prefix)
                .setKeyHash(hash).setCreatedBy(truncate(createdBy, 100)).setExpiresAt(Jpql.instant(expires))).getId();
    }
    public Optional<String> keyName(long app, long key) {
        return query("select k.name from ApplicationKey k where k.id = :key and k.application.id = :app", String.class, "key", key, "app", app)
                .getResultStream().findFirst();
    }
    public int revokeKey(long app, long key) {
        return update("update ApplicationKey k set k.revokedAt = current_timestamp where k.id = :key and k.application.id = :app and k.revokedAt is null",
                "key", key, "app", app);
    }
    public int revokeAllKeys(long app) {
        return update("update ApplicationKey k set k.revokedAt = current_timestamp where k.application.id = :app and k.revokedAt is null", "app", app);
    }

    // ---- users and memberships ----
    private static final String USER_COLUMNS = "u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled, "
            + "ad.id as activeDepartmentId, u.contextVersion as contextVersion, u.createdAt as createdAt";
    /** Users with a membership in one of the departments. */
    private static void members(Jpql.Where where, Collection<Long> departments) {
        if (departments.isEmpty()) where.and("1=0");
        else where.and("exists (select 1 from Membership mv where mv.user.id = u.id and mv.department.id in ?)", departments);
    }
    /** visibleDepartments limits the list to members of those departments; null lists every user. */
    public Page users(String keyword, Long department, Boolean enabled, Collection<Long> visibleDepartments, int page, int size) {
        var where = new Jpql.Where();
        if (visibleDepartments != null) members(where, visibleDepartments);
        if (enabled != null) where.and("u.enabled = ?", enabled);
        if (department != null) where.and("exists (select 1 from Membership m where m.user.id = u.id and m.department.id = ?)", department);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = like(keyword.strip());
            where.and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')", pattern, pattern, pattern);
        }
        return Jpql.page(em, "select " + USER_COLUMNS, "from User u left join u.activeDepartment ad", where, "u.id", page, size);
    }
    public List<Map<String,Object>> memberships(Collection<Long> users) {
        if (users.isEmpty()) return List.of();
        return rows("select m.user.id as user_id, m.department.id as department_id from Membership m where m.user.id in :users order by m.department.id",
                "users", users);
    }
    public List<Map<String,Object>> departmentUsers(long dept) {
        return rows("""
                select u.id as id, u.name as name, u.externalId as externalId, u.enabled as enabled
                from Membership m join m.user u where m.department.id = :dept order by u.id""", "dept", dept);
    }
    public Optional<Map<String,Object>> user(long id) {
        return Optional.ofNullable(Jpql.tuple(query("select " + USER_COLUMNS + " from User u left join u.activeDepartment ad where u.id = :id", Tuple.class, "id", id)))
                .map(Jpql::row);
    }
    public long createUser(String name, String external, String email) {
        return persisted(new User().setName(name).setExternalId(external).setEmail(email).setEnabled(true)).getId();
    }
    public Optional<String> lockUser(long id) {
        return lockedRead(query("select u from User u where u.id = :id", User.class, "id", id), LockModeType.PESSIMISTIC_WRITE).stream()
                .findFirst().map(User::getExternalId);
    }
    public List<Long> lockUsers(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return lockedRead(query("select u from User u where u.id in :ids order by u.id", User.class, "ids", ids), LockModeType.PESSIMISTIC_WRITE)
                .stream().map(User::getId).toList();
    }
    /** Locks the user that currently has this external id, reading past the transaction snapshot. */
    public Optional<Long> lockUserByExternal(String external) {
        return lockedRead(query("select u from User u where u.externalId = :external", User.class, "external", external), LockModeType.PESSIMISTIC_WRITE)
                .stream().findFirst().map(User::getId);
    }
    /** Name and email only; the external id is left as it is now, not as it was when the caller looked it up. */
    public void updateProfile(long id, String name, String email) {
        update("update User u set u.name = :name, u.email = :email where u.id = :id", "name", name, "email", email, "id", id);
    }
    public void updateUser(long id, String name, String external, String email, boolean invalidate) {
        update("update User u set u.name = :name, u.externalId = :external, u.email = :email, u.contextVersion = u.contextVersion + :step where u.id = :id",
                "name", name, "external", external, "email", email, "step", invalidate ? 1L : 0L, "id", id);
    }
    public int userStatus(long id, boolean enabled) {
        return update("update User u set u.enabled = :enabled, u.contextVersion = u.contextVersion + 1 where u.id = :id", "enabled", enabled, "id", id);
    }
    public void invalidateUser(long id) { update("update User u set u.contextVersion = u.contextVersion + 1 where u.id = :id", "id", id); }
    /** Memberships, assignments and contexts go with the user (ON DELETE CASCADE); governance history keeps the names. */
    public int deleteUser(long id) { return update("delete from User u where u.id = :id", "id", id); }
    public List<Long> userDepartments(long user) {
        return query("select m.department.id from Membership m where m.user.id = :user", Long.class, "user", user).getResultList();
    }
    /**
     * Locking reads return the latest committed rows rather than the transaction snapshot, which may predate the
     * row lock the caller just waited for. Whole-set replacements compute their difference from these.
     */
    public List<Long> lockedUserDepartments(long user) {
        return lockedRead(query("select m from Membership m where m.user.id = :user", Membership.class, "user", user), LockModeType.PESSIMISTIC_WRITE)
                .stream().map(m -> m.getId().getDepartmentId()).toList();
    }
    public boolean lockedMember(long user, long dept) {
        return !lockedRead(query("select m from Membership m where m.user.id = :user and m.department.id = :dept", Membership.class, "user", user, "dept", dept),
                LockModeType.PESSIMISTIC_READ).isEmpty();
    }
    public long lockedMembers(Collection<Long> users, long dept) {
        if (users.isEmpty()) return 0;
        return lockedRead(query("select m from Membership m where m.department.id = :dept and m.user.id in :users", Membership.class, "dept", dept, "users", users),
                LockModeType.PESSIMISTIC_READ).size();
    }
    public void removeMembership(long user, long dept) {
        update("delete from Membership m where m.user.id = :user and m.department.id = :dept", "user", user, "dept", dept);
    }
    /** Adds the membership unless it exists; callers hold the user row, so no concurrent insert races this one. */
    public void addMembership(long user, long dept) {
        var id = new MembershipId(user, dept);
        if (em.find(Membership.class, id) != null) return;
        persisted(new Membership().setId(id).setUser(em.getReference(User.class, user)).setDepartment(em.getReference(Department.class, dept)));
    }
    public void clearInactiveDepartment(long user) {
        update("""
                update User u set u.activeDepartment = null
                where u.id = :user and u.activeDepartment.id not in (select m.department.id from Membership m where m.user.id = :user)""", "user", user);
    }
    public boolean member(long user, long dept) {
        return count("select count(m) from Membership m where m.user.id = :user and m.department.id = :dept", "user", user, "dept", dept) > 0;
    }
    public long members(Collection<Long> users, long dept) {
        if (users.isEmpty()) return 0;
        return count("select count(m) from Membership m where m.department.id = :dept and m.user.id in :users", "dept", dept, "users", users);
    }
    public List<Map<String,Object>> accessRoles(long user) {
        return rows("""
                select ur.departmentId as department_id, d.name as department_name, ur.applicationId as application_id, a.name as application_name,
                       a.enabled as application_enabled, r.id as id, r.name as name, r.code as code, r.enabled as enabled, ur.expiresAt as expires_at,
                       ur.grantedBy as granted_by
                from UserRole ur join ur.role r join ur.department d join ur.application a
                where ur.user.id = :user order by ur.departmentId, ur.applicationId, r.id""", "user", user);
    }
    public List<Map<String,Object>> accessPermissions(long user) {
        return rows("""
                select distinct ur.departmentId as department_id, ur.applicationId as application_id, p.code as code
                from UserRole ur join ur.role r, RolePermission rp join rp.permission p
                where rp.role = r and r.enabled = true and\s""" + CURRENT + " and ur.user.id = :user order by p.code", "user", user);
    }

    // ---- roles and permissions of one domain ----
    public List<Map<String,Object>> roles(long dept, long app) {
        var rows = rows("""
                select r.id as id, r.name as name, r.code as code, r.description as description, r.enabled as enabled, r.createdAt as createdAt
                from Role r where r.department.id = :dept and r.application.id = :app order by r.id""", "dept", dept, "app", app);
        attachCounts(rows, "memberCount", "select ur.role.id, count(ur) from UserRole ur where ur.role.id in :ids group by ur.role.id");
        return rows;
    }
    public List<Map<String,Object>> rolePermissions(long dept, long app) {
        return rows("""
                select rp.role.id as role_id, rp.permission.id as permission_id from RolePermission rp
                where rp.departmentId = :dept and rp.applicationId = :app order by rp.permission.id""", "dept", dept, "app", app);
    }
    public List<Map<String,Object>> permissions(long dept, long app) {
        var rows = rows("""
                select p.id as id, p.name as name, p.code as code, p.resource as resource, p.action as action, p.createdAt as createdAt
                from Permission p where p.department.id = :dept and p.application.id = :app order by p.id""", "dept", dept, "app", app);
        attachCounts(rows, "roleCount", "select rp.permission.id, count(rp) from RolePermission rp where rp.permission.id in :ids group by rp.permission.id");
        return rows;
    }
    public long createRole(long dept, long app, String name, String code, String description) { return createRole(dept, app, name, code, description, true); }
    public long createRole(long dept, long app, String name, String code, String description, boolean enabled) {
        return persisted(new Role().setDepartment(em.getReference(Department.class, dept)).setApplication(em.getReference(Application.class, app))
                .setName(name).setCode(code).setDescription(description).setEnabled(enabled)).getId();
    }
    public void updateRole(long id, String name, String code, String description) {
        update("update Role r set r.name = :name, r.code = :code, r.description = :description where r.id = :id",
                "name", name, "code", code, "description", description, "id", id);
    }
    public int roleStatus(long id, boolean enabled) { return update("update Role r set r.enabled = :enabled where r.id = :id", "enabled", enabled, "id", id); }
    /** Its assignments, grants and context activations go with it (ON DELETE CASCADE). */
    public int deleteRole(long id) { return update("delete from Role r where r.id = :id", "id", id); }
    public long createPermission(long dept, long app, String name, String code, String resource, String action) {
        return persisted(new Permission().setDepartment(em.getReference(Department.class, dept)).setApplication(em.getReference(Application.class, app))
                .setName(name).setCode(code).setResource(resource).setAction(action)).getId();
    }
    public void updatePermission(long id, String name, String code, String resource, String action) {
        update("update Permission p set p.name = :name, p.code = :code, p.resource = :resource, p.action = :action where p.id = :id",
                "name", name, "code", code, "resource", resource, "action", action, "id", id);
    }
    public int deletePermission(long id) { return update("delete from Permission p where p.id = :id", "id", id); }
    public List<Long> rolePermissionIds(long role) {
        return query("select rp.permission.id from RolePermission rp where rp.role.id = :role", Long.class, "role", role).getResultList();
    }
    /** Serializes whole-set edits of one role's permissions or members. */
    public boolean lockRole(long role) {
        return !lockedRead(query("select r from Role r where r.id = :role", Role.class, "role", role), LockModeType.PESSIMISTIC_WRITE).isEmpty();
    }
    public List<Long> lockedRolePermissionIds(long role) {
        return lockedRead(query("select rp from RolePermission rp where rp.role.id = :role", RolePermission.class, "role", role), LockModeType.PESSIMISTIC_READ)
                .stream().map(rp -> rp.getId().getPermissionId()).toList();
    }
    public List<String> codes(String table, Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return query("select x.code from " + scopedEntity(table) + " x where x.id in :ids order by x.code", String.class, "ids", ids).getResultList();
    }
    public void replaceRolePermissions(long role, long dept, long app, Collection<Long> permissions) {
        update("delete from RolePermission rp where rp.role.id = :role", "role", role);
        var owner = em.getReference(Role.class, role);
        for (long permission : permissions)
            em.persist(new RolePermission().setId(new RolePermissionId(role, permission)).setRole(owner)
                    .setPermission(em.getReference(Permission.class, permission)).setDepartmentId(dept).setApplicationId(app));
        em.flush();
    }

    // ---- assignments ----
    private static final String DOMAIN_ASSIGNMENTS = "from UserRole ur where ur.user.id = :user and ur.departmentId = :dept and ur.applicationId = :app";
    public List<Long> assignedRoles(long user, long dept, long app) {
        return query("select ur.role.id " + DOMAIN_ASSIGNMENTS + " order by ur.role.id", Long.class, "user", user, "dept", dept, "app", app).getResultList();
    }
    /** The user's current assignments in one domain (role id to expiry, null when permanent), without locking. */
    public Map<Long,Timestamp> assignmentExpiries(long user, long dept, long app) {
        var result = new LinkedHashMap<Long,Timestamp>();
        for (var t : query("select ur.role.id, ur.expiresAt " + DOMAIN_ASSIGNMENTS + " order by ur.role.id", Tuple.class, "user", user, "dept", dept, "app", app)
                .getResultList()) result.put(t.get(0, Long.class), t.get(1) == null ? null : Timestamp.from(t.get(1, Instant.class)));
        return result;
    }
    /** The user's current assignments in one domain (role id to expiry, null when permanent), locked for replacement. */
    public Map<Long,Timestamp> lockedAssignments(long user, long dept, long app) {
        var result = new LinkedHashMap<Long,Timestamp>();
        for (UserRole assignment : lockedRead(query("select ur " + DOMAIN_ASSIGNMENTS + " order by ur.role.id", UserRole.class, "user", user, "dept", dept, "app", app),
                LockModeType.PESSIMISTIC_WRITE))
            result.put(assignment.getId().getRoleId(), assignment.getExpiresAt() == null ? null : Timestamp.from(assignment.getExpiresAt()));
        return result;
    }
    public void removeUserRoles(long user, Collection<Long> roles) {
        if (roles.isEmpty()) return;
        update("delete from UserRole ur where ur.user.id = :user and ur.role.id in :roles", "user", user, "roles", roles);
    }
    public List<Map<String,Object>> roleMembers(long role) {
        return rows("""
                select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled
                from UserRole ur join ur.user u where ur.role.id = :role order by u.id""", "role", role);
    }
    public List<Long> roleMemberIds(long role) {
        return query("select ur.user.id from UserRole ur where ur.role.id = :role order by ur.user.id", Long.class, "role", role).getResultList();
    }
    public List<Long> lockedRoleMemberIds(long role) {
        return lockedRead(query("select ur from UserRole ur where ur.role.id = :role order by ur.user.id", UserRole.class, "role", role),
                LockModeType.PESSIMISTIC_WRITE).stream().map(ur -> ur.getId().getUserId()).toList();
    }
    public void removeRoleMembers(long role, Collection<Long> users) {
        if (users.isEmpty()) return;
        update("delete from UserRole ur where ur.role.id = :role and ur.user.id in :users", "role", role, "users", users);
    }
    public void addRoleMembers(long role, long dept, long app, Collection<Long> users, String actor) {
        Instant now = Jpql.now();
        for (long user : users) em.persist(assignment(user, role, dept, app, null, actor, now));
        em.flush();
    }
    private UserRole assignment(long user, long role, long dept, long app, Instant expires, String grantedBy, Instant now) {
        return new UserRole().setId(new UserRoleId(user, role)).setUser(em.getReference(User.class, user)).setRole(em.getReference(Role.class, role))
                .setDepartmentId(dept).setApplicationId(app).setExpiresAt(expires).setGrantedBy(truncate(grantedBy, 100)).setGrantedAt(now)
                .setGrantId(UUID.randomUUID().toString());
    }
    public Page departmentMembers(long dept, String keyword, int page, int size) {
        var where = new Jpql.Where().and("m.department.id = ?", dept);
        if (keyword != null && !keyword.isBlank()) {
            String pattern = like(keyword.strip());
            where.and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')", pattern, pattern, pattern);
        }
        return Jpql.page(em, "select u.id as id, u.name as name, u.externalId as externalId, u.email as email, u.enabled as enabled",
                "from Membership m join m.user u", where, "u.id", page, size);
    }
    public List<Map<String,Object>> domainRolePairs(long dept, long app, Collection<Long> users) {
        if (users.isEmpty()) return List.of();
        return rows("""
                select ur.user.id as user_id, ur.role.id as role_id, ur.expiresAt as expires_at from UserRole ur
                where ur.departmentId = :dept and ur.applicationId = :app and ur.user.id in :users order by ur.role.id""",
                "dept", dept, "app", app, "users", users);
    }
    public List<Map<String,Object>> userRolesInDomain(long user, long dept, long app) {
        return rows("""
                select r.id as id, r.name as name, r.code as code, r.enabled as enabled, ur.expiresAt as expiresAt
                from UserRole ur join ur.role r where ur.user.id = :user and r.department.id = :dept and r.application.id = :app order by r.id""",
                "user", user, "dept", dept, "app", app);
    }
    public List<Map<String,Object>> grants(Collection<Long> roles) {
        if (roles.isEmpty()) return List.of();
        return rows("""
                select p.id as id, p.code as code, p.name as name, p.resource as resource, p.action as action, rp.role.id as role_id
                from RolePermission rp join rp.permission p where rp.role.id in :roles order by p.code, rp.role.id""", "roles", roles);
    }

    // ---- audit ----
    public Page events(AuditFilter filter, int page, int size) {
        var where = new Jpql.Where();
        if (filter.departmentId() != null) where.and("e.departmentId = ?", filter.departmentId());
        if (filter.applicationId() != null) where.and("e.applicationId = ?", filter.applicationId());
        if (filter.visible().applications() != null) where.in("e.applicationId", filter.visible().applications());
        if (filter.visible().departments() != null) where.in("e.departmentId", filter.visible().departments());
        if (filter.actor() != null && !filter.actor().isBlank()) where.and("e.actor = ?", filter.actor().strip());
        if (filter.action() != null && !filter.action().isBlank()) where.and("e.action = ?", filter.action().strip());
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String pattern = like(filter.keyword().strip());
            where.and("(e.target like ? escape '!' or e.detail like ? escape '!' or e.actor like ? escape '!')", pattern, pattern, pattern);
        }
        if (filter.from() != null) where.and("e.createdAt >= ?", filter.from().toInstant());
        if (filter.to() != null) where.and("e.createdAt < ?", filter.to().toInstant());
        return Jpql.page(em, """
                select e.id as id, e.actor as actor, e.action as action, e.target as target, e.detail as detail, e.sourceIp as sourceIp,
                       e.createdAt as createdAt, e.departmentId as departmentId, d.name as departmentName, e.applicationId as applicationId,
                       a.name as applicationName""", "from AuditEvent e left join e.department d left join e.application a", where, "e.id desc", page, size);
    }

    // ---- governance support ----
    public Optional<Long> userIdByExternal(String external) {
        return query("select u.id from User u where u.externalId = :external", Long.class, "external", external).getResultStream().findFirst();
    }
    public List<Map<String,Object>> departmentIdsByCodes(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return rows("select d.id as id, d.code as code from Department d where d.code in :codes", "codes", codes);
    }
    public Optional<Long> scopedIdByCode(String table, long dept, long app, String code) {
        return query("select x.id from " + scopedEntity(table) + " x where x.department.id = :dept and x.application.id = :app and x.code = :code",
                Long.class, "dept", dept, "app", app, "code", code).getResultStream().findFirst();
    }
    /** Creates or replaces one assignment; granting a held role again sets its expiry and makes it a new grant. Callers hold the user row. */
    public void grantRole(long user, long dept, long app, long role, Timestamp expires, String grantedBy) {
        Instant now = Jpql.now();
        UserRole existing = em.find(UserRole.class, new UserRoleId(user, role));
        if (existing == null) {
            persisted(assignment(user, role, dept, app, Jpql.instant(expires), grantedBy, now));
            return;
        }
        existing.setExpiresAt(Jpql.instant(expires)).setGrantedBy(truncate(grantedBy, 100)).setGrantedAt(now).setGrantId(UUID.randomUUID().toString());
        em.flush();
    }
    public int applicationRateLimit(long app, int fallback) {
        return query("select a.rateLimit from Application a where a.id = :app", Integer.class, "app", app).getResultStream()
                .filter(Objects::nonNull).findFirst().orElse(fallback);
    }
    public int setRateLimit(long app, Integer perSecond) {
        return update("update Application a set a.rateLimit = :limit where a.id = :app", "limit", perSecond, "app", app);
    }
    public Optional<Map<String,Object>> applicationRow(long app) {
        return Optional.ofNullable(Jpql.tuple(query("select a.id as id, a.name as name, a.code as code, a.enabled as enabled from Application a where a.id = :app",
                Tuple.class, "app", app))).map(Jpql::row);
    }
    public List<Long> manifestDepartmentIds(long app) {
        var ids = new TreeSet<Long>(query("select distinct p.department.id from Permission p where p.application.id = :app", Long.class, "app", app).getResultList());
        ids.addAll(query("select distinct r.department.id from Role r where r.application.id = :app", Long.class, "app", app).getResultList());
        return new ArrayList<>(ids);
    }

    /** Current holders of one permission code: an enabled user, an enabled role and a non-expired assignment in the permission's department. */
    private static final String HOLDERS = """
            from UserRole ur join ur.user u join ur.role r join ur.department d, RolePermission rp join rp.permission p
            where rp.role = r and u.enabled = true and r.enabled = true and p.application.id = :app and p.code = :code
              and r.department.id = p.department.id and\s""" + CURRENT;
    private TypedQuery<Tuple> holders(String select, Long dept, Collection<Long> visibleDepartments, long app, String code, String order) {
        var text = new StringBuilder(select).append(' ').append(HOLDERS);
        if (dept != null) text.append(" and ur.departmentId = :dept");
        if (visibleDepartments != null) text.append(visibleDepartments.isEmpty() ? " and 1=0" : " and ur.departmentId in :visible");
        var query = query(text + order, Tuple.class, "app", app, "code", code);
        if (dept != null) query.setParameter("dept", dept);
        if (visibleDepartments != null && !visibleDepartments.isEmpty()) query.setParameter("visible", visibleDepartments);
        return query;
    }
    /** Number of distinct (user, department) holders of one permission code. */
    public long permissionHolderCount(long app, String code, Long dept, Collection<Long> visibleDepartments) {
        return holders("select distinct ur.departmentId, u.id", dept, visibleDepartments, app, code, "").getResultList().size();
    }
    /**
     * Everyone currently holding one permission code through an enabled role and a non-expired assignment: the
     * first {@code limit} (user, department) holders, each with every role that grants the code.
     */
    public List<Map<String,Object>> permissionHolders(long app, String code, Long dept, Collection<Long> visibleDepartments, int limit) {
        var first = holders("select distinct ur.departmentId, u.id", dept, visibleDepartments, app, code, " order by ur.departmentId, u.id")
                .setMaxResults(limit).getResultList();
        if (first.isEmpty()) return List.of();
        var pairs = new LinkedHashSet<String>();
        var users = new LinkedHashSet<Long>();
        for (var t : first) {
            pairs.add(t.get(0) + ":" + t.get(1));
            users.add(t.get(1, Long.class));
        }
        var query = holders("""
                select u.id as userId, u.name as userName, u.externalId as externalId, ur.departmentId as departmentId, d.name as departmentName,
                       r.id as roleId, r.name as roleName, r.code as roleCode, ur.expiresAt as expiresAt""",
                dept, visibleDepartments, app, code, " and u.id in :users order by ur.departmentId, u.id, r.id");
        return Jpql.rows(query.setParameter("users", users).getResultList()).stream()
                .filter(row -> pairs.contains(row.get("departmentId") + ":" + row.get("userId"))).toList();
    }
    public List<Map<String,Object>> expiringAssignments(Timestamp deadline, Visibility visible, Long app) {
        var where = new Jpql.Where().and("ur.expiresAt is not null and ur.expiresAt <= ?", deadline.toInstant());
        if (visible.applications() != null) where.in("ur.applicationId", visible.applications());
        if (visible.departments() != null) where.in("ur.departmentId", visible.departments());
        if (app != null) where.and("ur.applicationId = ?", app);
        return Jpql.rows(where.bind(em.createQuery("""
                select u.id as userId, u.name as userName, u.externalId as externalId, r.id as roleId, r.name as roleName, r.code as roleCode,
                       ur.departmentId as departmentId, d.name as departmentName, ur.applicationId as applicationId, a.name as applicationName,
                       ur.expiresAt as expiresAt, ur.grantedBy as grantedBy, ur.grantedAt as grantedAt
                from UserRole ur join ur.user u join ur.role r join ur.department d join ur.application a""" + where.text + " order by ur.expiresAt", Tuple.class))
                .setMaxResults(500).getResultList());
    }
    public long expiringCount(Timestamp deadline) {
        return count("select count(ur) from UserRole ur where ur.expiresAt is not null and ur.expiresAt <= :deadline", "deadline", deadline.toInstant());
    }
    /** Lapsed assignments, oldest first, with what the expiry notice needs. */
    public List<Map<String,Object>> lapsedAssignments(int limit) {
        return Jpql.rows(query("""
                select u.id as userId, u.externalId as externalId, r.id as roleId, r.code as roleCode, ur.departmentId as departmentId,
                       ur.applicationId as applicationId
                from UserRole ur join ur.user u join ur.role r where ur.expiresAt <= current_timestamp order by ur.expiresAt, u.id, r.id""", Tuple.class)
                .setMaxResults(limit).getResultList());
    }
    /** Deletes the assignment only while it is still lapsed, so a concurrent re-grant or another instance wins cleanly. */
    public int deleteLapsedAssignment(long user, long role) {
        return update("delete from UserRole ur where ur.user.id = :user and ur.role.id = :role and ur.expiresAt <= current_timestamp", "user", user, "role", role);
    }

    // ---- console search and status ----
    public List<Map<String,Object>> searchUsers(String pattern, Collection<Long> visibleDepartments) {
        var where = new Jpql.Where().and("(u.name like ? escape '!' or u.externalId like ? escape '!' or u.email like ? escape '!')", pattern, pattern, pattern);
        if (visibleDepartments != null) members(where, visibleDepartments);
        return Jpql.rows(where.bind(em.createQuery("select u.id as id, u.name as name, u.externalId as externalId, u.enabled as enabled from User u"
                + where.text + " order by u.id", Tuple.class)).setMaxResults(5).getResultList());
    }
    public List<Map<String,Object>> searchDepartments(String pattern, Collection<Long> visibleDepartments) {
        var where = new Jpql.Where().and("(d.name like ? escape '!' or d.code like ? escape '!')", pattern, pattern);
        if (visibleDepartments != null) where.in("d.id", visibleDepartments);
        return Jpql.rows(where.bind(em.createQuery("select d.id as id, d.name as name, d.code as code from Department d" + where.text + " order by d.id", Tuple.class))
                .setMaxResults(5).getResultList());
    }
    public List<Map<String,Object>> searchApplications(String pattern, Collection<Long> visible) {
        var where = new Jpql.Where().and("(a.name like ? escape '!' or a.code like ? escape '!')", pattern, pattern);
        if (visible != null) where.in("a.id", visible);
        return Jpql.rows(where.bind(em.createQuery("select a.id as id, a.name as name, a.code as code, a.enabled as enabled from Application a"
                + where.text + " order by a.id", Tuple.class)).setMaxResults(5).getResultList());
    }
    public List<Map<String,Object>> searchRoles(String pattern, Visibility visible) {
        var where = new Jpql.Where().and("(r.name like ? escape '!' or r.code like ? escape '!')", pattern, pattern);
        if (visible.applications() != null) where.in("a.id", visible.applications());
        if (visible.departments() != null) where.in("d.id", visible.departments());
        return Jpql.rows(where.bind(em.createQuery("""
                select r.id as id, r.name as name, r.code as code, d.id as departmentId, d.name as departmentName, a.id as applicationId,
                       a.name as applicationName from Role r join r.department d join r.application a""" + where.text + " order by r.id", Tuple.class))
                .setMaxResults(5).getResultList());
    }
    public Map<String,Object> consoleCounts() {
        var result = new LinkedHashMap<String,Object>();
        result.put("users", count("select count(u) from User u"));
        result.put("enabledUsers", count("select count(u) from User u where u.enabled = true"));
        result.put("departments", count("select count(d) from Department d"));
        result.put("applications", count("select count(a) from Application a"));
        result.put("enabledApplications", count("select count(a) from Application a where a.enabled = true"));
        result.put("roles", count("select count(r) from Role r"));
        result.put("permissions", count("select count(p) from Permission p"));
        result.put("assignments", count("select count(ur) from UserRole ur"));
        result.put("activeContexts", count("select count(c) from PermissionContext c where c.expiresAt > current_timestamp"));
        return result;
    }
    public Map<String,Object> overview(long dept, long app) {
        var result = new LinkedHashMap<String,Object>();
        result.put("applications", count("select count(a) from Application a"));
        result.put("enabledApplications", count("select count(a) from Application a where a.enabled = true"));
        result.put("departments", count("select count(d) from Department d"));
        result.put("users", count("select count(u) from User u"));
        result.put("enabledUsers", count("select count(u) from User u where u.enabled = true"));
        result.put("departmentMembers", count("select count(m) from Membership m where m.department.id = :dept", "dept", dept));
        result.put("roles", count("select count(r) from Role r where r.department.id = :dept and r.application.id = :app", "dept", dept, "app", app));
        result.put("permissions", count("select count(p) from Permission p where p.department.id = :dept and p.application.id = :app", "dept", dept, "app", app));
        result.put("members", count("select count(distinct ur.user.id) from UserRole ur where ur.departmentId = :dept and ur.applicationId = :app",
                "dept", dept, "app", app));
        result.put("activeKeys", activeKeyCount(app));
        result.put("activeContexts", count("""
                select count(c) from PermissionContext c join c.user u join c.application a
                where c.departmentId = :dept and c.applicationId = :app and c.contextVersion = u.contextVersion and c.departmentId = u.activeDepartment.id
                  and c.expiresAt > current_timestamp and u.enabled = true and a.enabled = true""", "dept", dept, "app", app));
        result.put("auditLast24h", count("select count(e) from AuditEvent e where e.createdAt > :since", "since", Instant.now().minus(24, ChronoUnit.HOURS)));
        return result;
    }
}
