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

/** A permission code of one isolation domain; resource and action are metadata only. */
@Entity
@Table(name = "permissions")
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "department_id") private Department department;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    private String name;
    private String code;
    private String resource;
    private String action;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Permission() {}
    public Long getId() { return id; }
    public Department getDepartment() { return department; }
    public Permission setDepartment(Department department) { this.department = department; return this; }
    public Application getApplication() { return application; }
    public Permission setApplication(Application application) { this.application = application; return this; }
    public String getName() { return name; }
    public Permission setName(String name) { this.name = name; return this; }
    public String getCode() { return code; }
    public Permission setCode(String code) { this.code = code; return this; }
    public String getResource() { return resource; }
    public Permission setResource(String resource) { this.resource = resource; return this; }
    public String getAction() { return action; }
    public Permission setAction(String action) { this.action = action; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
