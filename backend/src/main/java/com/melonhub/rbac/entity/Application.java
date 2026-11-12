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

    protected Application() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public boolean isEnabled() { return enabled; }
    public Integer getRateLimit() { return rateLimit; }
}
