package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;

/** A business application that calls the platform with its API keys. */
@Entity
@Table(name = "applications")
public class Application {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String code;
    private String description;
    private String owner;
    private boolean enabled;
    /** Requests per second; null uses the platform default and 0 means unlimited. */
    private Integer rateLimit;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Application() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public Application setName(String name) { this.name = name; return this; }
    public String getCode() { return code; }
    public Application setCode(String code) { this.code = code; return this; }
    public String getDescription() { return description; }
    public Application setDescription(String description) { this.description = description; return this; }
    public String getOwner() { return owner; }
    public Application setOwner(String owner) { this.owner = owner; return this; }
    public boolean isEnabled() { return enabled; }
    public Application setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public Integer getRateLimit() { return rateLimit; }
    public Application setRateLimit(Integer rateLimit) { this.rateLimit = rateLimit; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
