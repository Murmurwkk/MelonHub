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

/** An employee's request for a role. The names are copied so the record stays readable after deletions. */
@Entity
@Table(name = "access_requests")
public class AccessRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id") private Department department;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id") private Application application;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "role_id") private Role role;
    private String reason;
    private Integer durationDays;
    private String status;
    private String requestedBy;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionComment;
    private Instant grantExpiresAt;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    private String userName;
    private String externalId;
    private String roleCode;
    private String roleName;
    private String departmentName;
    private Long pendingRoleId;

    public AccessRequest() {}
    public Long getId() { return id; }
    public User getUser() { return user; }
    public AccessRequest setUser(User user) { this.user = user; return this; }
    public Department getDepartment() { return department; }
    public AccessRequest setDepartment(Department department) { this.department = department; return this; }
    public Application getApplication() { return application; }
    public AccessRequest setApplication(Application application) { this.application = application; return this; }
    public Role getRole() { return role; }
    public AccessRequest setRole(Role role) { this.role = role; return this; }
    public String getReason() { return reason; }
    public AccessRequest setReason(String reason) { this.reason = reason; return this; }
    public Integer getDurationDays() { return durationDays; }
    public AccessRequest setDurationDays(Integer durationDays) { this.durationDays = durationDays; return this; }
    public String getStatus() { return status; }
    public AccessRequest setStatus(String status) { this.status = status; return this; }
    public String getRequestedBy() { return requestedBy; }
    public AccessRequest setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; return this; }
    public String getDecidedBy() { return decidedBy; }
    public AccessRequest setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; return this; }
    public Instant getDecidedAt() { return decidedAt; }
    public AccessRequest setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; return this; }
    public String getDecisionComment() { return decisionComment; }
    public AccessRequest setDecisionComment(String decisionComment) { this.decisionComment = decisionComment; return this; }
    public Instant getGrantExpiresAt() { return grantExpiresAt; }
    public AccessRequest setGrantExpiresAt(Instant grantExpiresAt) { this.grantExpiresAt = grantExpiresAt; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUserName() { return userName; }
    public AccessRequest setUserName(String userName) { this.userName = userName; return this; }
    public String getExternalId() { return externalId; }
    public AccessRequest setExternalId(String externalId) { this.externalId = externalId; return this; }
    public String getRoleCode() { return roleCode; }
    public AccessRequest setRoleCode(String roleCode) { this.roleCode = roleCode; return this; }
    public String getRoleName() { return roleName; }
    public AccessRequest setRoleName(String roleName) { this.roleName = roleName; return this; }
    public String getDepartmentName() { return departmentName; }
    public AccessRequest setDepartmentName(String departmentName) { this.departmentName = departmentName; return this; }
    public Long getPendingRoleId() { return pendingRoleId; }
    public AccessRequest setPendingRoleId(Long pendingRoleId) { this.pendingRoleId = pendingRoleId; return this; }
}
