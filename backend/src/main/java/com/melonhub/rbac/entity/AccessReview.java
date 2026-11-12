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

/** A periodic access review of one application, optionally one department. */
@Entity
@Table(name = "access_reviews")
public class AccessReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id") private Department department;
    private String status;
    private Instant dueAt;
    private String createdBy;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private String closedBy;
    private Instant closedAt;
    private String summary;
    private String departmentName;

    public AccessReview() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public AccessReview setName(String name) { this.name = name; return this; }
    public Application getApplication() { return application; }
    public AccessReview setApplication(Application application) { this.application = application; return this; }
    public Department getDepartment() { return department; }
    public AccessReview setDepartment(Department department) { this.department = department; return this; }
    public String getStatus() { return status; }
    public AccessReview setStatus(String status) { this.status = status; return this; }
    public Instant getDueAt() { return dueAt; }
    public AccessReview setDueAt(Instant dueAt) { this.dueAt = dueAt; return this; }
    public String getCreatedBy() { return createdBy; }
    public AccessReview setCreatedBy(String createdBy) { this.createdBy = createdBy; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public String getClosedBy() { return closedBy; }
    public AccessReview setClosedBy(String closedBy) { this.closedBy = closedBy; return this; }
    public Instant getClosedAt() { return closedAt; }
    public AccessReview setClosedAt(Instant closedAt) { this.closedAt = closedAt; return this; }
    public String getSummary() { return summary; }
    public AccessReview setSummary(String summary) { this.summary = summary; return this; }
    public String getDepartmentName() { return departmentName; }
    public AccessReview setDepartmentName(String departmentName) { this.departmentName = departmentName; return this; }
}
