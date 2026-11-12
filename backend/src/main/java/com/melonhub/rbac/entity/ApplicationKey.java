package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One of an application's API keys; only the SHA-256 of the key is stored. */
@Entity
@Table(name = "application_keys")
public class ApplicationKey {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    private String name;
    private String keyPrefix;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String keyHash;
    private String createdBy;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    public ApplicationKey() {}

    public Long getId() { return id; }
    public Application getApplication() { return application; }
    public ApplicationKey setApplication(Application application) { this.application = application; return this; }
    public String getName() { return name; }
    public ApplicationKey setName(String name) { this.name = name; return this; }
    public String getKeyPrefix() { return keyPrefix; }
    public ApplicationKey setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; return this; }
    public String getKeyHash() { return keyHash; }
    public ApplicationKey setKeyHash(String keyHash) { this.keyHash = keyHash; return this; }
    public String getCreatedBy() { return createdBy; }
    public ApplicationKey setCreatedBy(String createdBy) { this.createdBy = createdBy; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public ApplicationKey setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
