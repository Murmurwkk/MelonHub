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

/** A role of one isolation domain (department x application). */
@Entity
@Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "department_id") private Department department;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    private String name;
    private String code;
    private String description;
    private boolean enabled;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Role() {}
    public Long getId() { return id; }
    public Department getDepartment() { return department; }
    public Role setDepartment(Department department) { this.department = department; return this; }
    public Application getApplication() { return application; }
    public Role setApplication(Application application) { this.application = application; return this; }
    public String getName() { return name; }
    public Role setName(String name) { this.name = name; return this; }
    public String getCode() { return code; }
    public Role setCode(String code) { this.code = code; return this; }
    public String getDescription() { return description; }
    public Role setDescription(String description) { this.description = description; return this; }
    public boolean isEnabled() { return enabled; }
    public Role setEnabled(boolean enabled) { this.enabled = enabled; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
