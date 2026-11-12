package com.melonhub.rbac.dao;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Access requests and periodic access reviews. */
@Repository
public class GovernanceDao {
    private final JdbcTemplate db;
    public GovernanceDao(JdbcTemplate db) { this.db = db; }

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
    }
    private RbacDao.Page page(String select, String from, Where where, String order, int page, int size) {
        long total = db.queryForObject("SELECT COUNT(*) " + from + where.sql, Long.class, where.args.toArray());
        if (total == 0) return new RbacDao.Page(new ArrayList<>(), 0);
        var args = new ArrayList<>(where.args);
        args.add(size);
        args.add((long) (page - 1) * size);
        return new RbacDao.Page(db.queryForList(select + " " + from + where.sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray()), total);
    }

    // ---- access requests ----
    /**
     * Records the names next to the ids so the request stays readable after the user, role or department is
     * deleted. pending_role_id is unique per user while pending, so a concurrent duplicate fails on insert.
     */
    public long createRequest(long user, long dept, long app, long role, String reason, Integer durationDays, String requestedBy) {
        return insert("INSERT INTO access_requests(user_id,department_id,application_id,role_id,pending_role_id,reason,duration_days,requested_by,"
                + "user_name,external_id,role_code,role_name,department_name) "
                + "SELECT u.id,d.id,?,ro.id,ro.id,?,?,?,u.name,u.external_id,ro.code,ro.name,d.name FROM users u JOIN roles ro ON ro.id=? "
                + "JOIN departments d ON d.id=? WHERE u.id=?",
                app, reason, durationDays, requestedBy, role, dept, user);
    }
    public boolean pendingExists(long user, long role) {
        return count("SELECT COUNT(*) FROM access_requests WHERE user_id=? AND role_id=? AND status='PENDING'", user, role) > 0;
    }
    /** Withdraws pending requests of a user or role that is about to be deleted. */
    public int cancelPending(String column, long id, String comment) {
        if (!List.of("user_id", "role_id").contains(column)) throw new IllegalArgumentException();
        return db.update("UPDATE access_requests SET status='CANCELLED',pending_role_id=NULL,decided_by='system',decided_at=CURRENT_TIMESTAMP,"
                + "decision_comment=? WHERE " + column + "=? AND status='PENDING'", comment, id);
    }
    private static final String REQUEST_SELECT = "SELECT r.id,r.user_id AS userId,COALESCE(u.name,r.user_name) AS userName,"
            + "COALESCE(u.external_id,r.external_id) AS externalId,"
            + "r.department_id AS departmentId,COALESCE(d.name,r.department_name) AS departmentName,r.application_id AS applicationId,a.name AS applicationName,"
            + "r.role_id AS roleId,COALESCE(ro.name,r.role_name) AS roleName,COALESCE(ro.code,r.role_code) AS roleCode,ro.enabled AS roleEnabled,"
            + "r.reason,r.duration_days AS durationDays,r.status,r.requested_by AS requestedBy,r.decided_by AS decidedBy,"
            + "r.decided_at AS decidedAt,r.decision_comment AS decisionComment,r.grant_expires_at AS grantExpiresAt,r.created_at AS createdAt ";
    private static final String REQUEST_FROM = "FROM access_requests r LEFT JOIN users u ON u.id=r.user_id LEFT JOIN departments d ON d.id=r.department_id "
            + "JOIN applications a ON a.id=r.application_id LEFT JOIN roles ro ON ro.id=r.role_id";
    public Optional<Map<String,Object>> request(long id) {
        return db.queryForList(REQUEST_SELECT + REQUEST_FROM + " WHERE r.id=?", id).stream().findFirst();
    }
    public RbacDao.Page requests(Long app, Visibility visible, String status, String keyword, int page, int size) {
        var where = new Where();
        if (app != null) where.and("r.application_id=?", app);
        if (visible.applications() != null) where.in("r.application_id", visible.applications());
        if (visible.departments() != null) where.in("r.department_id", visible.departments());
        if (status != null && !status.isBlank()) where.and("r.status=?", status.strip());
        if (keyword != null && !keyword.isBlank()) {
            String pattern = RbacDao.like(keyword.strip());
            where.and("(COALESCE(u.name,r.user_name) LIKE ? OR COALESCE(u.external_id,r.external_id) LIKE ? OR COALESCE(ro.name,r.role_name) LIKE ? "
                    + "OR COALESCE(ro.code,r.role_code) LIKE ?)", pattern, pattern, pattern, pattern);
        }
        return page(REQUEST_SELECT, REQUEST_FROM, where, "r.id DESC", page, size);
    }
    public List<Map<String,Object>> requestsOfUser(long user, long app) {
        return db.queryForList(REQUEST_SELECT + REQUEST_FROM + " WHERE r.user_id=? AND r.application_id=? ORDER BY r.id DESC LIMIT 50", user, app);
    }
    /** Only a PENDING request can be decided; the loser of a race gets 0 rows and the caller reports a conflict. */
    public int decide(long id, String status, String decidedBy, String comment, Timestamp grantExpires) {
        return db.update("UPDATE access_requests SET status=?,pending_role_id=NULL,decided_by=?,decided_at=CURRENT_TIMESTAMP,decision_comment=?,grant_expires_at=? "
                + "WHERE id=? AND status='PENDING'", status, decidedBy, comment, grantExpires, id);
    }
    public int cancelOwn(long id, long user) {
        return db.update("UPDATE access_requests SET status='CANCELLED',pending_role_id=NULL,decided_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND user_id=? AND status='PENDING'", id, user);
    }
    public long pendingCount(Visibility visible) {
        var where = new Where().and("status='PENDING'");
        if (visible.applications() != null) where.in("application_id", visible.applications());
        if (visible.departments() != null) where.in("department_id", visible.departments());
        return count("SELECT COUNT(*) FROM access_requests" + where.sql, where.args.toArray());
    }

    // ---- access reviews ----
    public long createReview(String name, long app, Long dept, Timestamp due, String createdBy) {
        return insert("INSERT INTO access_reviews(name,application_id,department_id,due_at,created_by,department_name) "
                + "VALUES (?,?,?,?,?,COALESCE((SELECT name FROM departments WHERE id=?),''))", name, app, dept, due, createdBy, dept);
    }
    private static final String REVIEW_STATS = "(SELECT COUNT(*) FROM access_review_items i WHERE i.review_id=v.id) AS items,"
            + "(SELECT COUNT(*) FROM access_review_items i WHERE i.review_id=v.id AND i.decision='PENDING') AS pending,"
            + "(SELECT COUNT(*) FROM access_review_items i WHERE i.review_id=v.id AND i.decision='KEEP') AS kept,"
            + "(SELECT COUNT(*) FROM access_review_items i WHERE i.review_id=v.id AND i.decision='REVOKE') AS revoked,"
            + "(SELECT COUNT(*) FROM access_review_items i WHERE i.review_id=v.id AND i.skipped=TRUE) AS skipped";
    /** A review scoped to a department that was deleted later still shows that department's name. */
    private static final String REVIEW_DEPARTMENT = "COALESCE(d.name,NULLIF(v.department_name,'')) AS departmentName";
    public Optional<Map<String,Object>> review(long id) {
        return db.queryForList("SELECT v.id,v.name,v.application_id AS applicationId,a.name AS applicationName,v.department_id AS departmentId,"
                + REVIEW_DEPARTMENT + ",v.status,v.due_at AS dueAt,v.created_by AS createdBy,v.created_at AS createdAt,"
                + "v.closed_by AS closedBy,v.closed_at AS closedAt,v.summary," + REVIEW_STATS + " "
                + "FROM access_reviews v JOIN applications a ON a.id=v.application_id LEFT JOIN departments d ON d.id=v.department_id WHERE v.id=?", id)
                .stream().findFirst();
    }
    /** A review over every department of an application (no department) is only visible to unlimited operators. */
    public List<Map<String,Object>> reviews(Long app, Visibility visible, String status) {
        var where = new Where();
        if (app != null) where.and("v.application_id=?", app);
        if (visible.applications() != null) where.in("v.application_id", visible.applications());
        if (visible.departments() != null) where.in("v.department_id", visible.departments());
        if (status != null && !status.isBlank()) where.and("v.status=?", status.strip());
        return db.queryForList("SELECT v.id,v.name,v.application_id AS applicationId,a.name AS applicationName,v.department_id AS departmentId,"
                + REVIEW_DEPARTMENT + ",v.status,v.due_at AS dueAt,v.created_by AS createdBy,v.created_at AS createdAt,"
                + "v.closed_by AS closedBy,v.closed_at AS closedAt," + REVIEW_STATS + " "
                + "FROM access_reviews v JOIN applications a ON a.id=v.application_id LEFT JOIN departments d ON d.id=v.department_id"
                + where.sql + " ORDER BY v.id DESC LIMIT 200", where.args.toArray());
    }
    /**
     * Snapshots current non-expired assignments of the application (one department when scoped) as review items,
     * each with the grant it was taken from and the names needed to read it after deletions.
     */
    public int insertSnapshot(long review, long app, Long dept) {
        return db.update("INSERT INTO access_review_items(review_id,user_id,role_id,department_id,grant_id,user_name,external_id,role_code,role_name,department_name) "
                + "SELECT ?,ur.user_id,ur.role_id,ur.department_id,ur.grant_id,u.name,u.external_id,r.code,r.name,d.name FROM user_roles ur "
                + "JOIN users u ON u.id=ur.user_id JOIN roles r ON r.id=ur.role_id JOIN departments d ON d.id=ur.department_id "
                + "WHERE ur.application_id=? AND (? IS NULL OR ur.department_id=?) AND (ur.expires_at IS NULL OR ur.expires_at>CURRENT_TIMESTAMP)",
                review, app, dept, dept);
    }
    public RbacDao.Page reviewItems(long review, String decision, int page, int size) {
        var where = new Where().and("i.review_id=?", review);
        if (decision != null && !decision.isBlank()) where.and("i.decision=?", decision.strip());
        return page("SELECT i.id,i.user_id AS userId,COALESCE(u.name,i.user_name) AS userName,COALESCE(u.external_id,i.external_id) AS externalId,"
                + "i.role_id AS roleId,COALESCE(r.name,i.role_name) AS roleName,COALESCE(r.code,i.role_code) AS roleCode,"
                + "i.department_id AS departmentId,COALESCE(d.name,i.department_name) AS departmentName,i.decision,i.comment,"
                + "i.decided_by AS decidedBy,i.decided_at AS decidedAt,i.applied,i.skipped",
                "FROM access_review_items i LEFT JOIN users u ON u.id=i.user_id LEFT JOIN roles r ON r.id=i.role_id LEFT JOIN departments d ON d.id=i.department_id",
                where, "i.id DESC", page, size);
    }
    public Optional<Map<String,Object>> reviewItem(long review, long item) {
        return db.queryForList("SELECT id,review_id AS reviewId,user_id AS userId,role_id AS roleId,department_id AS departmentId,decision "
                + "FROM access_review_items WHERE id=? AND review_id=?", item, review).stream().findFirst();
    }
    /** Serializes decisions with closing: both take this row lock before reading the review status. */
    public Optional<String> lockReview(long review) {
        return db.queryForList("SELECT status FROM access_reviews WHERE id=? FOR UPDATE", String.class, review).stream().findFirst();
    }
    /** Decisions stay editable until closing applies them. */
    public int decideItem(long item, long review, String decision, String by, String comment) {
        return db.update("UPDATE access_review_items SET decision=?,decided_by=?,decided_at=CURRENT_TIMESTAMP,comment=? "
                + "WHERE id=? AND review_id=? AND applied=FALSE", decision, by, comment, item, review);
    }
    public List<Map<String,Object>> revocations(long review) {
        return db.queryForList("SELECT i.id,i.user_id AS userId,i.role_id AS roleId,i.department_id AS departmentId,i.grant_id AS grantId "
                + "FROM access_review_items i WHERE i.review_id=? AND i.decision='REVOKE' AND i.applied=FALSE ORDER BY i.id", review);
    }
    /**
     * Removes exactly the snapshotted grant: a role that was removed and granted again, or renewed, after the
     * snapshot is a newer decision and stays. Items from before grant ids existed match the role alone.
     */
    public int revokeGrant(long user, long role, String grantId) {
        if (grantId == null) return db.update("DELETE FROM user_roles WHERE user_id=? AND role_id=?", user, role);
        return db.update("DELETE FROM user_roles WHERE user_id=? AND role_id=? AND grant_id=?", user, role, grantId);
    }
    public void markApplied(long item, boolean skipped) { db.update("UPDATE access_review_items SET applied=TRUE,skipped=? WHERE id=?", skipped, item); }
    public int closeReview(long review, String by, String summary) {
        return db.update("UPDATE access_reviews SET status='CLOSED',closed_by=?,closed_at=CURRENT_TIMESTAMP,summary=? WHERE id=? AND status='OPEN'",
                by, summary, review);
    }
    public long openReviewCount(Visibility visible) {
        var where = new Where().and("status='OPEN'");
        if (visible.applications() != null) where.in("application_id", visible.applications());
        if (visible.departments() != null) where.in("department_id", visible.departments());
        return count("SELECT COUNT(*) FROM access_reviews" + where.sql, where.args.toArray());
    }
}
