package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.AccessRequest;
import com.melonhub.rbac.entity.AccessReview;
import com.melonhub.rbac.entity.AccessReviewItem;
import com.melonhub.rbac.entity.Application;
import com.melonhub.rbac.entity.Department;
import com.melonhub.rbac.entity.Role;
import com.melonhub.rbac.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Access requests and periodic access reviews. Writes reach the database before a method returns. */
@Repository
@Transactional
public class GovernanceDao {
    private final EntityManager em;
    public GovernanceDao(EntityManager em) { this.em = em; }

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

    // ---- access requests ----
    /**
     * Records the names next to the ids so the request stays readable after the user, role or department is
     * deleted. pending_role_id is unique per user while pending, so a concurrent duplicate fails on insert.
     */
    public long createRequest(long user, long dept, long app, long role, String reason, Integer durationDays, String requestedBy) {
        User person = em.find(User.class, user);
        Role granted = em.find(Role.class, role);
        Department department = em.find(Department.class, dept);
        var request = new AccessRequest().setUser(person).setDepartment(department).setApplication(em.getReference(Application.class, app))
                .setRole(granted).setPendingRoleId(role).setReason(reason).setDurationDays(durationDays).setStatus("PENDING")
                .setRequestedBy(requestedBy).setDecisionComment("").setUserName(person.getName()).setExternalId(person.getExternalId())
                .setRoleCode(granted.getCode()).setRoleName(granted.getName()).setDepartmentName(department.getName());
        em.persist(request);
        em.flush();
        return request.getId();
    }
    public boolean pendingExists(long user, long role) {
        return count("select count(r) from AccessRequest r where r.user.id = :user and r.role.id = :role and r.status = 'PENDING'", "user", user, "role", role) > 0;
    }
    /** Withdraws pending requests of a user or role that is about to be deleted. */
    public int cancelPending(String column, long id, String comment) {
        String path = switch (column) {
            case "user_id" -> "r.user.id";
            case "role_id" -> "r.role.id";
            default -> throw new IllegalArgumentException();
        };
        return update("update AccessRequest r set r.status = 'CANCELLED', r.pendingRoleId = null, r.decidedBy = 'system', r.decidedAt = current_timestamp, "
                + "r.decisionComment = :comment where " + path + " = :id and r.status = 'PENDING'", "comment", comment, "id", id);
    }
    private static final String REQUEST_SELECT = """
            select r.id as id, u.id as userId, coalesce(u.name, r.userName) as userName, coalesce(u.externalId, r.externalId) as externalId,
                   d.id as departmentId, coalesce(d.name, r.departmentName) as departmentName, a.id as applicationId, a.name as applicationName,
                   ro.id as roleId, coalesce(ro.name, r.roleName) as roleName, coalesce(ro.code, r.roleCode) as roleCode, ro.enabled as roleEnabled,
                   r.reason as reason, r.durationDays as durationDays, r.status as status, r.requestedBy as requestedBy, r.decidedBy as decidedBy,
                   r.decidedAt as decidedAt, r.decisionComment as decisionComment, r.grantExpiresAt as grantExpiresAt, r.createdAt as createdAt""";
    private static final String REQUEST_FROM = "from AccessRequest r left join r.user u left join r.department d join r.application a left join r.role ro";
    public Optional<Map<String,Object>> request(long id) {
        return Optional.ofNullable(Jpql.tuple(em.createQuery(REQUEST_SELECT + " " + REQUEST_FROM + " where r.id = :id", Tuple.class)
                .setParameter("id", id))).map(Jpql::row);
    }
    public RbacDao.Page requests(Long app, Visibility visible, String status, String keyword, int page, int size) {
        var where = new Jpql.Where();
        if (app != null) where.and("r.application.id = ?", app);
        if (visible.applications() != null) where.in("r.application.id", visible.applications());
        if (visible.departments() != null) where.in("r.department.id", visible.departments());
        if (status != null && !status.isBlank()) where.and("r.status = ?", status.strip());
        if (keyword != null && !keyword.isBlank()) {
            String pattern = Jpql.like(keyword.strip());
            where.and("(coalesce(u.name, r.userName) like ? escape '!' or coalesce(u.externalId, r.externalId) like ? escape '!' "
                    + "or coalesce(ro.name, r.roleName) like ? escape '!' or coalesce(ro.code, r.roleCode) like ? escape '!')", pattern, pattern, pattern, pattern);
        }
        return Jpql.page(em, REQUEST_SELECT, REQUEST_FROM, where, "r.id desc", page, size);
    }
    public List<Map<String,Object>> requestsOfUser(long user, long app) {
        return Jpql.rows(em.createQuery(REQUEST_SELECT + " " + REQUEST_FROM + " where r.user.id = :user and r.application.id = :app order by r.id desc", Tuple.class)
                .setParameter("user", user).setParameter("app", app).setMaxResults(50).getResultList());
    }
    /** Only a PENDING request can be decided; the loser of a race gets 0 rows and the caller reports a conflict. */
    public int decide(long id, String status, String decidedBy, String comment, Timestamp grantExpires) {
        return update("""
                update AccessRequest r set r.status = :status, r.pendingRoleId = null, r.decidedBy = :by, r.decidedAt = current_timestamp,
                       r.decisionComment = :comment, r.grantExpiresAt = :expires
                where r.id = :id and r.status = 'PENDING'""",
                "status", status, "by", decidedBy, "comment", comment, "expires", Jpql.instant(grantExpires), "id", id);
    }
    public int cancelOwn(long id, long user) {
        return update("""
                update AccessRequest r set r.status = 'CANCELLED', r.pendingRoleId = null, r.decidedAt = current_timestamp
                where r.id = :id and r.user.id = :user and r.status = 'PENDING'""", "id", id, "user", user);
    }
    public long pendingCount(Visibility visible) {
        var where = new Jpql.Where().and("r.status = 'PENDING'");
        if (visible.applications() != null) where.in("r.application.id", visible.applications());
        if (visible.departments() != null) where.in("r.department.id", visible.departments());
        return where.bind(em.createQuery("select count(r) from AccessRequest r" + where.text, Long.class)).getSingleResult();
    }

    // ---- access reviews ----
    public long createReview(String name, long app, Long dept, Timestamp due, String createdBy) {
        Department department = dept == null ? null : em.find(Department.class, dept);
        var review = new AccessReview().setName(name).setApplication(em.getReference(Application.class, app)).setDepartment(department)
                .setStatus("OPEN").setDueAt(Jpql.instant(due)).setCreatedBy(createdBy).setSummary("")
                .setDepartmentName(department == null ? "" : department.getName());
        em.persist(review);
        em.flush();
        return review.getId();
    }
    /** A review scoped to a department that was deleted later still shows that department's name. */
    private static final String REVIEW_SELECT = """
            select v.id as id, v.name as name, a.id as applicationId, a.name as applicationName, d.id as departmentId,
                   coalesce(d.name, nullif(v.departmentName, '')) as departmentName, v.status as status, v.dueAt as dueAt, v.createdBy as createdBy,
                   v.createdAt as createdAt, v.closedBy as closedBy, v.closedAt as closedAt""";
    private static final String REVIEW_FROM = "from AccessReview v join v.application a left join v.department d";
    /** Item counts by decision, added to each review row. */
    private List<Map<String,Object>> withStats(List<Map<String,Object>> reviews) {
        if (reviews.isEmpty()) return reviews;
        var stats = new HashMap<Long,Tuple>();
        for (var tuple : em.createQuery("""
                select i.review.id as review, count(i) as items, sum(case when i.decision = 'PENDING' then 1 else 0 end) as pending,
                       sum(case when i.decision = 'KEEP' then 1 else 0 end) as kept, sum(case when i.decision = 'REVOKE' then 1 else 0 end) as revoked,
                       sum(case when i.skipped = true then 1 else 0 end) as skipped
                from AccessReviewItem i where i.review.id in :ids group by i.review.id""", Tuple.class)
                .setParameter("ids", reviews.stream().map(r -> (Long) r.get("id")).toList()).getResultList())
            stats.put(tuple.get("review", Long.class), tuple);
        for (var review : reviews) {
            var stat = stats.get((Long) review.get("id"));
            for (String key : List.of("items", "pending", "kept", "revoked", "skipped"))
                review.put(key, stat == null ? 0L : ((Number) stat.get(key)).longValue());
        }
        return reviews;
    }
    public Optional<Map<String,Object>> review(long id) {
        var tuple = Jpql.tuple(em.createQuery(REVIEW_SELECT + ", v.summary as summary " + REVIEW_FROM + " where v.id = :id", Tuple.class).setParameter("id", id));
        return tuple == null ? Optional.empty() : Optional.of(withStats(new java.util.ArrayList<>(List.of(Jpql.row(tuple)))).get(0));
    }
    /** A review over every department of an application (no department) is only visible to unlimited operators. */
    public List<Map<String,Object>> reviews(Long app, Visibility visible, String status) {
        var where = new Jpql.Where();
        if (app != null) where.and("a.id = ?", app);
        if (visible.applications() != null) where.in("a.id", visible.applications());
        if (visible.departments() != null) where.in("v.department.id", visible.departments());
        if (status != null && !status.isBlank()) where.and("v.status = ?", status.strip());
        return withStats(Jpql.rows(where.bind(em.createQuery(REVIEW_SELECT + " " + REVIEW_FROM + where.text + " order by v.id desc", Tuple.class))
                .setMaxResults(200).getResultList()));
    }
    /**
     * Snapshots current non-expired assignments of the application (one department when scoped) as review items,
     * each with the grant it was taken from and the names needed to read it after deletions.
     */
    public int insertSnapshot(long review, long app, Long dept) {
        var where = new Jpql.Where().and("ur.applicationId = ?", app).and("(ur.expiresAt is null or ur.expiresAt > current_timestamp)");
        if (dept != null) where.and("ur.departmentId = ?", dept);
        var assignments = where.bind(em.createQuery("""
                select u.id, r.id, ur.departmentId, ur.grantId, u.name, u.externalId, r.code, r.name, d.name
                from UserRole ur join ur.user u join ur.role r join ur.department d""" + where.text + " order by ur.departmentId, u.id, r.id", Tuple.class))
                .getResultList();
        var owner = em.getReference(AccessReview.class, review);
        int count = 0;
        for (var t : assignments) {
            em.persist(new AccessReviewItem().setReview(owner).setUser(em.getReference(User.class, t.get(0, Long.class)))
                    .setRole(em.getReference(Role.class, t.get(1, Long.class))).setDepartmentId(t.get(2, Long.class)).setGrantId(t.get(3, String.class))
                    .setUserName(t.get(4, String.class)).setExternalId(t.get(5, String.class)).setRoleCode(t.get(6, String.class))
                    .setRoleName(t.get(7, String.class)).setDepartmentName(t.get(8, String.class)).setDecision("PENDING").setComment(""));
            if (++count % 500 == 0) em.flush();
        }
        em.flush();
        return count;
    }
    public RbacDao.Page reviewItems(long review, String decision, int page, int size) {
        var where = new Jpql.Where().and("i.review.id = ?", review);
        if (decision != null && !decision.isBlank()) where.and("i.decision = ?", decision.strip());
        return Jpql.page(em, """
                select i.id as id, u.id as userId, coalesce(u.name, i.userName) as userName, coalesce(u.externalId, i.externalId) as externalId,
                       r.id as roleId, coalesce(r.name, i.roleName) as roleName, coalesce(r.code, i.roleCode) as roleCode, i.departmentId as departmentId,
                       coalesce(d.name, i.departmentName) as departmentName, i.decision as decision, i.comment as comment, i.decidedBy as decidedBy,
                       i.decidedAt as decidedAt, i.applied as applied, i.skipped as skipped""",
                "from AccessReviewItem i left join i.user u left join i.role r left join i.department d", where, "i.id desc", page, size);
    }
    public Optional<Map<String,Object>> reviewItem(long review, long item) {
        return Optional.ofNullable(Jpql.tuple(em.createQuery("""
                select i.id as id, i.review.id as reviewId, i.user.id as userId, i.role.id as roleId, i.departmentId as departmentId, i.decision as decision
                from AccessReviewItem i where i.id = :item and i.review.id = :review""", Tuple.class)
                .setParameter("item", item).setParameter("review", review))).map(Jpql::row);
    }
    /** Serializes decisions with closing: both take this row lock before reading the review status. */
    public Optional<String> lockReview(long review) {
        AccessReview found = em.find(AccessReview.class, review);
        if (found == null) return Optional.empty();
        em.refresh(found, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(found.getStatus());
    }
    /** Decisions stay editable until closing applies them. */
    public int decideItem(long item, long review, String decision, String by, String comment) {
        return update("""
                update AccessReviewItem i set i.decision = :decision, i.decidedBy = :by, i.decidedAt = current_timestamp, i.comment = :comment
                where i.id = :item and i.review.id = :review and i.applied = false""",
                "decision", decision, "by", by, "comment", comment, "item", item, "review", review);
    }
    public List<Map<String,Object>> revocations(long review) {
        return Jpql.rows(em.createQuery("""
                select i.id as id, i.user.id as userId, i.role.id as roleId, i.departmentId as departmentId, i.grantId as grantId
                from AccessReviewItem i where i.review.id = :review and i.decision = 'REVOKE' and i.applied = false order by i.id""", Tuple.class)
                .setParameter("review", review).getResultList());
    }
    /**
     * Removes exactly the snapshotted grant: a role that was removed and granted again, or renewed, after the
     * snapshot is a newer decision and stays. Items from before grant ids existed match the role alone.
     */
    public int revokeGrant(long user, long role, String grantId) {
        if (grantId == null) return update("delete from UserRole ur where ur.user.id = :user and ur.role.id = :role", "user", user, "role", role);
        return update("delete from UserRole ur where ur.user.id = :user and ur.role.id = :role and ur.grantId = :grant",
                "user", user, "role", role, "grant", grantId);
    }
    public void markApplied(long item, boolean skipped) {
        update("update AccessReviewItem i set i.applied = true, i.skipped = :skipped where i.id = :item", "skipped", skipped, "item", item);
    }
    public int closeReview(long review, String by, String summary) {
        return update("""
                update AccessReview v set v.status = 'CLOSED', v.closedBy = :by, v.closedAt = current_timestamp, v.summary = :summary
                where v.id = :review and v.status = 'OPEN'""", "by", by, "summary", summary, "review", review);
    }
    public long openReviewCount(Visibility visible) {
        var where = new Jpql.Where().and("v.status = 'OPEN'");
        if (visible.applications() != null) where.in("v.application.id", visible.applications());
        if (visible.departments() != null) where.in("v.department.id", visible.departments());
        return where.bind(em.createQuery("select count(v) from AccessReview v" + where.text, Long.class)).getSingleResult();
    }
}
