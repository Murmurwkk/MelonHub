package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.Application;
import com.melonhub.rbac.entity.Webhook;
import com.melonhub.rbac.entity.WebhookDelivery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhooks and their delivery queue. Every method runs in a transaction (joining the caller's) and writes reach the
 * database before it returns. Claims and results are conditional updates on the attempt count, so concurrent workers
 * and instances never overwrite each other. Lease and retry times come from this server's clock.
 */
@Repository
@Transactional
public class WebhookDao {
    private static final String SENDING_LEASE_ERROR = "Delivery lease expired after final attempt";
    private final EntityManager em;
    public WebhookDao(EntityManager em) { this.em = em; }

    /** A bulk JPQL statement: pending changes are written first, and entities it may have changed are dropped after. */
    private int update(String jpql, Object... namedValues) {
        em.flush();
        var query = em.createQuery(jpql);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        int changed = query.executeUpdate();
        em.clear();
        return changed;
    }

    public long create(long app, String name, String url, String secret, String events, boolean enabled, String createdBy) {
        var webhook = new Webhook().setApplication(em.getReference(Application.class, app)).setName(name).setUrl(url).setSecret(secret)
                .setEvents(events).setEnabled(enabled).setCreatedBy(createdBy);
        em.persist(webhook);
        em.flush();
        return webhook.getId();
    }
    public List<Map<String,Object>> webhooks(long app) {
        var rows = Jpql.rows(em.createQuery("""
                select w.id as id, w.name as name, w.url as url, w.events as events, w.enabled as enabled, w.createdBy as createdBy,
                       w.createdAt as createdAt
                from Webhook w where w.application.id = :app order by w.id""", Tuple.class).setParameter("app", app).getResultList());
        var stats = new HashMap<Long,Tuple>();
        for (var tuple : em.createQuery("""
                select d.webhook.id as webhook, sum(case when d.status = 'PENDING' then 1 else 0 end) as pending,
                       sum(case when d.status = 'FAILED' then 1 else 0 end) as failed,
                       max(case when d.status = 'DELIVERED' then d.deliveredAt end) as lastDeliveredAt
                from WebhookDelivery d where d.webhook.application.id = :app group by d.webhook.id""", Tuple.class)
                .setParameter("app", app).getResultList()) stats.put(tuple.get("webhook", Long.class), tuple);
        for (var row : rows) {
            var stat = stats.get((Long) row.get("id"));
            row.put("pending", stat == null ? 0L : ((Number) stat.get("pending")).longValue());
            row.put("failed", stat == null ? 0L : ((Number) stat.get("failed")).longValue());
            row.put("lastDeliveredAt", stat == null || stat.get("lastDeliveredAt") == null ? null : java.sql.Timestamp.from((Instant) stat.get("lastDeliveredAt")));
        }
        return rows;
    }
    public Optional<Map<String,Object>> webhook(long id) {
        return Optional.ofNullable(Jpql.tuple(em.createQuery("""
                select w.id as id, w.application.id as applicationId, w.name as name, w.url as url, w.events as events, w.enabled as enabled,
                       w.createdBy as createdBy, w.createdAt as createdAt
                from Webhook w where w.id = :id""", Tuple.class).setParameter("id", id))).map(Jpql::row);
    }
    public int update(long id, String name, String url, String events, boolean enabled) {
        return update("update Webhook w set w.name = :name, w.url = :url, w.events = :events, w.enabled = :enabled where w.id = :id",
                "name", name, "url", url, "events", events, "enabled", enabled, "id", id);
    }
    public int updateSecret(long id, String secret) { return update("update Webhook w set w.secret = :secret where w.id = :id", "secret", secret, "id", id); }
    /** Its deliveries go with it (ON DELETE CASCADE). */
    public int delete(long id) { return update("delete from Webhook w where w.id = :id", "id", id); }

    /** One delivery per matching enabled webhook, written in the caller's transaction (transactional outbox). */
    public int emit(long app, String type, String payload) {
        return enqueue(em.createQuery("select w from Webhook w where w.application.id = :app and w.enabled = true order by w.id", Webhook.class)
                .setParameter("app", app).getResultList(), type, payload);
    }
    /** Platform-wide events (e.g. user disabled) fan out to every matching enabled webhook. */
    public int emitAll(String type, String payload) {
        return enqueue(em.createQuery("select w from Webhook w where w.enabled = true order by w.id", Webhook.class).getResultList(), type, payload);
    }
    private int enqueue(List<Webhook> webhooks, String type, String payload) {
        int queued = 0;
        Instant now = Jpql.now();
        for (Webhook webhook : webhooks) {
            var events = Arrays.asList(webhook.getEvents().split(","));
            if (!events.contains("*") && !events.contains(type)) continue;
            em.persist(delivery(webhook, type, payload, now));
            queued++;
        }
        em.flush();
        return queued;
    }
    private static WebhookDelivery delivery(Webhook webhook, String type, String payload, Instant now) {
        return new WebhookDelivery().setWebhook(webhook).setEventId(UUID.randomUUID().toString()).setEventType(type).setPayload(payload)
                .setStatus("PENDING").setAttempts(0).setNextAttemptAt(now).setLastError("");
    }
    public void enqueueTest(long webhook, String payload) {
        em.persist(delivery(em.getReference(Webhook.class, webhook), "webhook.test", payload, Jpql.now()));
        em.flush();
    }

    /** Deliveries of a disabled webhook stay queued until it is enabled again. */
    public List<Map<String,Object>> dueDeliveries(int limit) {
        return Jpql.rows(em.createQuery("""
                select d.id as id, d.eventId as eventId, d.eventType as eventType, d.payload as payload, d.attempts as attempts,
                       w.url as url, w.secret as secret
                from WebhookDelivery d join d.webhook w
                where d.status = 'PENDING' and d.nextAttemptAt <= current_timestamp and w.enabled = true order by d.id""", Tuple.class)
                .setMaxResults(limit).getResultList());
    }
    /**
     * Claims the delivery exactly as it was read: still due and with the same attempt count. Another instance that
     * already sent it and scheduled a retry changed both, so a stale read cannot send early or reuse the count.
     * next_attempt_at doubles as a lease: a crashed worker leaves SENDING rows that the requeue takes back.
     * The new attempt count identifies this claim when the result is written.
     */
    public int claim(long id, int attempts) {
        return update("""
                update WebhookDelivery d set d.status = 'SENDING', d.attempts = d.attempts + 1, d.nextAttemptAt = :lease
                where d.id = :id and d.status = 'PENDING' and d.attempts = :attempts and d.nextAttemptAt <= current_timestamp
                  and exists (select 1 from Webhook w where w.id = d.webhook.id and w.enabled = true)""",
                "lease", Jpql.now().plus(5, ChronoUnit.MINUTES), "id", id, "attempts", attempts);
    }
    /** Hands a claimed delivery back without counting an attempt, e.g. when it could not be scheduled. */
    public void release(long id, int attempt) {
        update("""
                update WebhookDelivery d set d.status = 'PENDING', d.attempts = d.attempts - 1, d.nextAttemptAt = current_timestamp
                where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""", "id", id, "attempt", attempt);
    }
    /** An expired final claim is terminal too: a crashed worker must not bypass the attempt limit. */
    public int requeueStale(int maxAttempts) {
        return update("""
                update WebhookDelivery d set d.status = case when d.attempts >= :max then 'FAILED' else 'PENDING' end,
                       d.lastError = case when d.attempts >= :max then :error else d.lastError end
                where d.status = 'SENDING' and d.nextAttemptAt <= current_timestamp""", "max", maxAttempts, "error", SENDING_LEASE_ERROR);
    }
    // Results are written only by the claim that is still current: a worker whose lease expired and whose delivery
    // was claimed again finds a different attempt count and leaves the newer claim alone.
    public int delivered(long id, int attempt, int status) {
        return update("""
                update WebhookDelivery d set d.status = 'DELIVERED', d.deliveredAt = current_timestamp, d.lastStatus = :status, d.lastError = ''
                where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""", "status", status, "id", id, "attempt", attempt);
    }
    public int retry(long id, int attempt, int delayMinutes, Integer status, String error) {
        return update("""
                update WebhookDelivery d set d.status = 'PENDING', d.nextAttemptAt = :next, d.lastStatus = :status, d.lastError = :error
                where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""",
                "next", Jpql.now().plus(delayMinutes, ChronoUnit.MINUTES), "status", status, "error", truncate(error), "id", id, "attempt", attempt);
    }
    public int failed(long id, int attempt, Integer status, String error) {
        return update("""
                update WebhookDelivery d set d.status = 'FAILED', d.lastStatus = :status, d.lastError = :error
                where d.id = :id and d.status = 'SENDING' and d.attempts = :attempt""",
                "status", status, "error", truncate(error), "id", id, "attempt", attempt);
    }
    public RbacDao.Page deliveries(long webhook, int page, int size) {
        return Jpql.page(em, """
                select d.id as id, d.eventId as eventId, d.eventType as eventType, d.status as status, d.attempts as attempts,
                       d.nextAttemptAt as nextAttemptAt, d.lastStatus as lastStatus, d.lastError as lastError, d.createdAt as createdAt,
                       d.deliveredAt as deliveredAt""", "from WebhookDelivery d", new Jpql.Where().and("d.webhook.id = ?", webhook), "d.id desc", page, size);
    }
    /** Finished deliveries older than the retention period, at most limit of them. */
    public int purge(int days, int limit) {
        var ids = em.createQuery("""
                select d.id from WebhookDelivery d where d.createdAt < :cutoff and d.status in ('DELIVERED', 'FAILED') order by d.id""", Long.class)
                .setParameter("cutoff", Jpql.now().minus(days, ChronoUnit.DAYS)).setMaxResults(limit).getResultList();
        return ids.isEmpty() ? 0 : update("delete from WebhookDelivery d where d.id in :ids", "ids", ids);
    }
    public long backlog() {
        return em.createQuery("select count(d) from WebhookDelivery d where d.status = 'PENDING'", Long.class).getSingleResult();
    }
    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
