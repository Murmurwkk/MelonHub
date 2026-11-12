package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.ContextRole;
import com.melonhub.rbac.entity.ContextRoleId;
import com.melonhub.rbac.entity.Department;
import com.melonhub.rbac.entity.PermissionContext;
import com.melonhub.rbac.entity.Role;
import com.melonhub.rbac.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permission contexts. Issuing locks the user row; validation (state, effective roles and permissions) never locks
 * and is meant to run in one read-only transaction so every query reads the same snapshot. Each validation query is
 * a single JPQL statement, as the SQL it replaced was.
 */
@Repository
@Transactional
public class ContextDao {
    public record ContextRow(long user, long department, long application, long version, Instant expiresAt) {}
    /** A stored context and whether its user, application, membership and version still allow it. */
    public record ContextState(ContextRow context, boolean valid) {}

    /** Roles that count: enabled, and assigned to the user without having expired. */
    private static final String HELD = "ur.user.id = :user and (ur.expiresAt is null or ur.expiresAt > current_timestamp) and r.enabled = true";
    private final EntityManager em;
    public ContextDao(EntityManager em) { this.em = em; }

    private int update(String jpql, Object... namedValues) {
        em.flush();
        var query = em.createQuery(jpql);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        int changed = query.executeUpdate();
        em.clear();
        return changed;
    }

    /** Locks the user that currently has this external id and reads its row again under the lock. */
    public List<Map<String,Object>> lockUserByExternalId(String external) {
        var users = em.createQuery("select u from User u where u.externalId = :external", User.class)
                .setParameter("external", external).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        return users.stream().map(user -> {
            em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            var row = new LinkedHashMap<String,Object>();
            row.put("id", user.getId());
            row.put("enabled", user.isEnabled());
            row.put("active_department_id", user.getActiveDepartment() == null ? null : user.getActiveDepartment().getId());
            row.put("context_version", user.getContextVersion());
            return (Map<String,Object>) row;
        }).toList();
    }
    public boolean applicationEnabled(long app) {
        return em.createQuery("select count(a) from Application a where a.id = :app and a.enabled = true", Long.class).setParameter("app", app).getSingleResult() > 0;
    }
    public boolean member(long user, long dept) {
        return em.createQuery("select count(m) from Membership m where m.user.id = :user and m.department.id = :dept", Long.class)
                .setParameter("user", user).setParameter("dept", dept).getSingleResult() > 0;
    }
    public List<Long> assignedRoles(long user, long dept, long app) {
        return em.createQuery("select r.id from UserRole ur join ur.role r where " + HELD
                        + " and r.department.id = :dept and r.application.id = :app order by r.id", Long.class)
                .setParameter("user", user).setParameter("dept", dept).setParameter("app", app).getResultList();
    }
    public void switchDepartment(long user, long dept, long version) {
        update("update User u set u.activeDepartment = :dept, u.contextVersion = :version where u.id = :user",
                "dept", em.getReference(Department.class, dept), "version", version, "user", user);
    }
    /** Context roles go with their context (ON DELETE CASCADE). */
    public void clearStaleContexts(long user, long version) {
        update("delete from PermissionContext c where c.user.id = :user and (c.expiresAt < current_timestamp or c.contextVersion <> :version)",
                "user", user, "version", version);
    }
    public void create(String hash, long user, long dept, long app, long version, Instant expires) {
        em.persist(new PermissionContext().setId(hash).setUser(em.getReference(User.class, user)).setDepartmentId(dept).setApplicationId(app)
                .setContextVersion(version).setExpiresAt(expires));
        em.flush();
    }
    public void activateRoles(String hash, Collection<Long> roles) {
        var context = em.getReference(PermissionContext.class, hash);
        for (long role : roles)
            em.persist(new ContextRole().setId(new ContextRoleId(hash, role)).setContext(context).setRole(em.getReference(Role.class, role)));
        em.flush();
    }

    /** Lock-free validation; callers run it inside one transaction so every query reads the same snapshot. */
    public Optional<ContextState> state(String hash) {
        return em.createQuery("""
                select c.user.id, c.departmentId, c.applicationId, c.contextVersion, c.expiresAt,
                       case when u.enabled = true and a.enabled = true and u.contextVersion = c.contextVersion
                                 and u.activeDepartment.id = c.departmentId
                                 and exists (select 1 from Membership m where m.user.id = u.id and m.department.id = c.departmentId)
                            then true else false end
                from PermissionContext c join c.user u join c.application a where c.id = :hash""", Tuple.class)
                .setParameter("hash", hash).getResultStream().findFirst()
                .map(t -> new ContextState(new ContextRow(t.get(0, Long.class), t.get(1, Long.class), t.get(2, Long.class), t.get(3, Long.class),
                        t.get(4, Instant.class)), Boolean.TRUE.equals(t.get(5))));
    }
    public List<Map<String,Object>> effectiveRoles(long user, String hash, long dept, long app) {
        return Jpql.rows(em.createQuery("""
                select r.id as id, r.name as name, r.code as code, ur.expiresAt as expires_at
                from ContextRole cr join cr.role r, UserRole ur
                where ur.role = r and cr.context.id = :hash and r.department.id = :dept and r.application.id = :app and\s""" + HELD + " order by r.id", Tuple.class)
                .setParameter("user", user).setParameter("hash", hash).setParameter("dept", dept).setParameter("app", app).getResultList());
    }
    public List<String> effectivePermissions(long user, String hash, long dept, long app) {
        return em.createQuery("""
                select distinct p.code from RolePermission rp join rp.permission p join rp.role r, ContextRole cr, UserRole ur
                where cr.role = r and ur.role = r and cr.context.id = :hash and p.department.id = :dept and p.application.id = :app and\s"""
                        + HELD + " order by p.code", String.class)
                .setParameter("user", user).setParameter("hash", hash).setParameter("dept", dept).setParameter("app", app).getResultList();
    }
    public int revoke(String hash, long app) {
        return update("delete from PermissionContext c where c.id = :hash and c.applicationId = :app", "hash", hash, "app", app);
    }
    public int revokeDomain(long user, long dept, long app) {
        return update("delete from PermissionContext c where c.user.id = :user and c.departmentId = :dept and c.applicationId = :app",
                "user", user, "dept", dept, "app", app);
    }
    public int revokeDomain(Collection<Long> users, long dept, long app) {
        if (users.isEmpty()) return 0;
        return update("delete from PermissionContext c where c.departmentId = :dept and c.applicationId = :app and c.user.id in :users",
                "dept", dept, "app", app, "users", users);
    }
    public int revokeUser(long user) { return update("delete from PermissionContext c where c.user.id = :user", "user", user); }
    public int clearApplication(long app) { return update("delete from PermissionContext c where c.applicationId = :app", "app", app); }
    public int clearDepartment(long dept) { return update("delete from PermissionContext c where c.departmentId = :dept", "dept", dept); }
    public List<Map<String,Object>> activeContexts(long user) {
        var rows = Jpql.rows(em.createQuery("""
                select c.id as id, c.departmentId as departmentId, d.name as departmentName, c.applicationId as applicationId,
                       a.name as applicationName, c.createdAt as createdAt, c.expiresAt as expiresAt,
                       case when u.enabled = true and a.enabled = true and u.contextVersion = c.contextVersion
                                 and u.activeDepartment.id = c.departmentId then 1L else 0L end as valid
                from PermissionContext c join c.user u join c.department d join c.application a
                where c.user.id = :user and c.expiresAt > current_timestamp order by c.createdAt desc""", Tuple.class)
                .setParameter("user", user).setMaxResults(200).getResultList());
        if (rows.isEmpty()) return rows;
        var counts = new HashMap<String,Long>();
        for (var tuple : em.createQuery("select cr.context.id, count(cr) from ContextRole cr where cr.context.id in :ids group by cr.context.id", Tuple.class)
                .setParameter("ids", rows.stream().map(r -> (String) r.get("id")).toList()).getResultList())
            counts.put(tuple.get(0, String.class), tuple.get(1, Long.class));
        for (var row : rows) {
            // The token hash is internal; the console only sees which domain and when.
            String id = (String) row.remove("id");
            var ordered = new LinkedHashMap<String,Object>();
            row.forEach((key, value) -> { if (!key.equals("valid")) ordered.put(key, value); });
            ordered.put("activeRoleCount", counts.getOrDefault(id, 0L));
            ordered.put("valid", row.get("valid"));
            row.clear();
            row.putAll(ordered);
        }
        return rows;
    }
    /** Expired contexts, at most limit of them. */
    public int purgeExpired(int limit) {
        var ids = em.createQuery("select c.id from PermissionContext c where c.expiresAt < current_timestamp", String.class)
                .setMaxResults(limit).getResultList();
        return ids.isEmpty() ? 0 : update("delete from PermissionContext c where c.id in :ids", "ids", ids);
    }
}
