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

/** A person known to the business applications by external id; departments are memberships. */
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String externalId;
    private String email;
    private boolean enabled;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "active_department_id") private Department activeDepartment;
    private long contextVersion;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public User() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public User setName(String name) { this.name = name; return this; }
    public String getExternalId() { return externalId; }
    public User setExternalId(String externalId) { this.externalId = externalId; return this; }
    public String getEmail() { return email; }
    public User setEmail(String email) { this.email = email; return this; }
    public boolean isEnabled() { return enabled; }
    public User setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public Department getActiveDepartment() { return activeDepartment; }
    public User setActiveDepartment(Department activeDepartment) { this.activeDepartment = activeDepartment; return this; }
    public long getContextVersion() { return contextVersion; }
    public User setContextVersion(long contextVersion) { this.contextVersion = contextVersion; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
