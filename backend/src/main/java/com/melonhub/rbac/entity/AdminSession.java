package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Console session; only the SHA-256 of the bearer token is stored. */
@Entity
@Table(name = "admin_sessions")
public class AdminSession {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "username") private AdminAccount account;
    private Instant expiresAt;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private Instant lastSeenAt;
    private String sourceIp;
    /** The account's credential version when the session was created; any credential change makes it stale. */
    private long credentialVersion;

    protected AdminSession() {}
    public AdminSession(String tokenHash, AdminAccount account, Instant expiresAt, Instant lastSeenAt, String sourceIp, long credentialVersion) {
        this.tokenHash = tokenHash;
        this.account = account;
        this.expiresAt = expiresAt;
        this.lastSeenAt = lastSeenAt;
        this.sourceIp = sourceIp;
        this.credentialVersion = credentialVersion;
    }
}
