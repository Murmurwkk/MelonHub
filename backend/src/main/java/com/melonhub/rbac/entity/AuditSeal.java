package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The single row holding the end and the anchor of the audit chain. */
@Entity
@Table(name = "audit_seal")
public class AuditSeal {
    @Id @JdbcTypeCode(SqlTypes.TINYINT) private Integer id;
    private long lastId;
    private long lastSeq;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String lastHash;
    private long sealedCount;
    private Instant updatedAt;
    private long anchorSeq;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String anchorHash;
    @Column(name = "v2_from_seq") private long v2FromSeq;

    public AuditSeal() {}
    public Integer getId() { return id; }
    public AuditSeal setId(Integer id) { this.id = id; return this; }
    public long getLastId() { return lastId; }
    public AuditSeal setLastId(long lastId) { this.lastId = lastId; return this; }
    public long getLastSeq() { return lastSeq; }
    public AuditSeal setLastSeq(long lastSeq) { this.lastSeq = lastSeq; return this; }
    public String getLastHash() { return lastHash; }
    public AuditSeal setLastHash(String lastHash) { this.lastHash = lastHash; return this; }
    public long getSealedCount() { return sealedCount; }
    public AuditSeal setSealedCount(long sealedCount) { this.sealedCount = sealedCount; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public AuditSeal setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
    public long getAnchorSeq() { return anchorSeq; }
    public AuditSeal setAnchorSeq(long anchorSeq) { this.anchorSeq = anchorSeq; return this; }
    public String getAnchorHash() { return anchorHash; }
    public AuditSeal setAnchorHash(String anchorHash) { this.anchorHash = anchorHash; return this; }
    public long getV2FromSeq() { return v2FromSeq; }
    public AuditSeal setV2FromSeq(long v2FromSeq) { this.v2FromSeq = v2FromSeq; return this; }
}
