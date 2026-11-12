package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.AuditSeal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tamper-evident audit chain. Rows are written without a hash first; a scheduled sealer locks the seal row, gives
 * every unsealed row the next sequence number in id order and fills prev_hash/chain_hash, so a row committed late
 * is appended instead of skipped. Verification walks the chain by sequence number. Rows handed to the hash are
 * maps with the column names as keys and ts as the epoch seconds of created_at, exactly as the chain was defined.
 */
@Repository
@Transactional
public class AuditDao {
    public static final String GENESIS = "0".repeat(64);
    public record SealRow(long lastId, long lastSeq, String lastHash, long anchorSeq, String anchorHash, long v2FromSeq) {}
    public record Link(long seq, String hash) {}

    private static final String COLUMNS = "e.id as id, e.actor as actor, e.action as action, e.target as target, e.departmentId as department_id, "
            + "e.applicationId as application_id, e.detail as detail, e.sourceIp as source_ip, e.createdAt as created_at";
    private final EntityManager em;
    public AuditDao(EntityManager em) { this.em = em; }

    private static SealRow sealRow(AuditSeal seal) {
        return new SealRow(seal.getLastId(), seal.getLastSeq(), seal.getLastHash(), seal.getAnchorSeq(), seal.getAnchorHash(), seal.getV2FromSeq());
    }
    /** The seal row, read again under the lock: it may already sit in this persistence context with older values. */
    public SealRow lockSeal() {
        AuditSeal seal = em.find(AuditSeal.class, 1);
        em.refresh(seal, LockModeType.PESSIMISTIC_WRITE);
        return sealRow(seal);
    }
    public SealRow seal() {
        AuditSeal seal = em.find(AuditSeal.class, 1);
        em.refresh(seal);
        return sealRow(seal);
    }

    /** A chain row: the hashed columns, ts in epoch seconds, plus any extra aliases of the query. */
    private static Map<String,Object> chainRow(Tuple tuple) {
        var row = new LinkedHashMap<String,Object>();
        for (var element : tuple.getElements()) {
            Object value = tuple.get(element);
            if ("created_at".equals(element.getAlias())) row.put("ts", ((Instant) value).getEpochSecond());
            else row.put(element.getAlias(), value);
        }
        return row;
    }
    public List<Map<String,Object>> unsealed(int limit) {
        return em.createQuery("select " + COLUMNS + " from AuditEvent e where e.sealSeq is null order by e.id", Tuple.class)
                .setMaxResults(limit).getResultStream().map(AuditDao::chainRow).toList();
    }
    private int update(String jpql, Object... namedValues) {
        em.flush();
        var query = em.createQuery(jpql);
        for (int i = 0; i < namedValues.length; i += 2) query.setParameter((String) namedValues[i], namedValues[i + 1]);
        int changed = query.executeUpdate();
        em.clear();
        return changed;
    }
    public int sealEvent(long id, long seq, String prev, String hash) {
        return update("update AuditEvent e set e.sealSeq = :seq, e.prevHash = :prev, e.chainHash = :hash where e.id = :id and e.sealSeq is null",
                "seq", seq, "prev", prev, "hash", hash, "id", id);
    }
    public void updateSeal(long lastId, long lastSeq, String lastHash, long sealed) {
        update("""
                update AuditSeal s set s.lastId = :lastId, s.lastSeq = :lastSeq, s.lastHash = :lastHash, s.sealedCount = s.sealedCount + :sealed,
                       s.updatedAt = current_timestamp where s.id = 1""", "lastId", lastId, "lastSeq", lastSeq, "lastHash", lastHash, "sealed", sealed);
    }

    /** Sealed rows in chain order, never beyond the chain end read together with them. */
    public List<Map<String,Object>> sealedFrom(long fromSeq, long toSeq, int limit) {
        return em.createQuery("select " + COLUMNS + ", e.sealSeq as seal_seq, e.prevHash as prev_hash, e.chainHash as chain_hash "
                        + "from AuditEvent e where e.sealSeq >= :from and e.sealSeq <= :to order by e.sealSeq", Tuple.class)
                .setParameter("from", fromSeq).setParameter("to", toSeq).setMaxResults(limit).getResultStream().map(AuditDao::chainRow).toList();
    }
    /** The link right before a position; empty when every earlier row was removed. */
    public Optional<Link> linkBefore(long seq) {
        return em.createQuery("select e.sealSeq, e.chainHash from AuditEvent e where e.sealSeq < :seq order by e.sealSeq desc", Tuple.class)
                .setParameter("seq", seq).setMaxResults(1).getResultStream().findFirst().map(t -> new Link(t.get(0, Long.class), t.get(1, String.class)));
    }

    // ---- retention ----
    /** First position, in chain order, of a row that is still inside the retention period. */
    public Optional<Long> firstSeqSince(int days) {
        return em.createQuery("select e.sealSeq from AuditEvent e where e.sealSeq is not null and e.createdAt >= :since order by e.sealSeq", Long.class)
                .setParameter("since", Jpql.now().minus(days, ChronoUnit.DAYS)).setMaxResults(1).getResultStream().findFirst();
    }
    /** The last link of the oldest batch below a position: the chain is only ever cut as a prefix. */
    public Optional<Link> prefixEnd(long belowSeq, int limit) {
        var batch = em.createQuery("select e.sealSeq from AuditEvent e where e.sealSeq < :below order by e.sealSeq", Long.class)
                .setParameter("below", belowSeq).setMaxResults(limit).getResultList();
        if (batch.isEmpty()) return Optional.empty();
        long last = batch.get(batch.size() - 1);
        return em.createQuery("select e.chainHash from AuditEvent e where e.sealSeq = :seq", String.class)
                .setParameter("seq", last).getResultStream().findFirst().map(hash -> new Link(last, hash));
    }
    public int deleteThrough(long seq) { return update("delete from AuditEvent e where e.sealSeq <= :seq", "seq", seq); }
    public void updateAnchor(Link anchor) {
        update("update AuditSeal s set s.anchorSeq = :seq, s.anchorHash = :hash where s.id = 1", "seq", anchor.seq(), "hash", anchor.hash());
    }

    public Map<String,Object> sealState() {
        var state = Jpql.row(em.createQuery("""
                select s.lastId as lastId, s.lastSeq as lastSeq, s.lastHash as lastHash, s.sealedCount as sealedCount, s.anchorSeq as anchorSeq,
                       s.updatedAt as updatedAt from AuditSeal s where s.id = 1""", Tuple.class).getSingleResult());
        state.put("unsealed", em.createQuery("select count(e) from AuditEvent e where e.sealSeq is null", Long.class).getSingleResult());
        state.put("total", em.createQuery("select count(e) from AuditEvent e", Long.class).getSingleResult());
        return state;
    }

    /**
     * Original canonical form, kept for rows sealed before V5. Fields are joined with "|", which text fields may
     * contain, so it is only used to verify those rows.
     */
    public static String canonicalV1(Map<String,Object> row) {
        return row.get("id") + "|" + row.get("actor") + "|" + row.get("action") + "|" + row.get("target") + "|"
                + value(row.get("department_id")) + "|" + value(row.get("application_id")) + "|" + row.get("detail") + "|"
                + row.get("source_ip") + "|" + row.get("ts");
    }
    /**
     * Unambiguous canonical form: every field is written as its length, ":" and the value ("-" for NULL), and the
     * chain position comes first, so no change of field boundaries or order yields the same text. Timestamps use
     * epoch seconds to avoid format drift.
     */
    public static String canonicalV2(long seq, Map<String,Object> row) {
        var text = new StringBuilder("v2");
        for (Object field : new Object[] { seq, row.get("id"), row.get("actor"), row.get("action"), row.get("target"), row.get("department_id"),
                row.get("application_id"), row.get("detail"), row.get("source_ip"), row.get("ts") }) {
            if (field == null) { text.append("|-"); continue; }
            String value = field.toString();
            text.append('|').append(value.length()).append(':').append(value);
        }
        return text.toString();
    }
    /** Rows at or after v2FromSeq use the v2 form; earlier rows were sealed with v1. */
    public static String chainHash(String prevHash, long seq, Map<String,Object> row, long v2FromSeq) {
        return sha256(prevHash + "|" + (seq >= v2FromSeq ? canonicalV2(seq, row) : canonicalV1(row)));
    }

    private static String value(Object column) { return column == null ? "" : column.toString(); }
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
