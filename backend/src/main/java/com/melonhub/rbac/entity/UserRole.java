package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A role assigned to a user, optionally until expiresAt. grantId identifies this grant; it changes when the role is granted again. */
@Entity
@Table(name = "user_roles")
public class UserRole {
    @EmbeddedId private UserRoleId id;
    @MapsId("userId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @MapsId("roleId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_id") private Role role;
    @Column(name = "department_id") private Long departmentId;
    @Column(name = "application_id") private Long applicationId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", insertable = false, updatable = false) private Department department;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "application_id", insertable = false, updatable = false) private Application application;
    private Instant expiresAt;
    private String grantedBy;
    private Instant grantedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private String grantId;

    public UserRole() {}
    public UserRoleId getId() { return id; }
    public UserRole setId(UserRoleId id) { this.id = id; return this; }
    public User getUser() { return user; }
    public UserRole setUser(User user) { this.user = user; return this; }
    public Role getRole() { return role; }
    public UserRole setRole(Role role) { this.role = role; return this; }
    public Long getDepartmentId() { return departmentId; }
    public UserRole setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public Long getApplicationId() { return applicationId; }
    public UserRole setApplicationId(Long applicationId) { this.applicationId = applicationId; return this; }
    public Department getDepartment() { return department; }
    public Application getApplication() { return application; }
    public Instant getExpiresAt() { return expiresAt; }
    public UserRole setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
    public String getGrantedBy() { return grantedBy; }
    public UserRole setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; return this; }
    public Instant getGrantedAt() { return grantedAt; }
    public UserRole setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; return this; }
    public String getGrantId() { return grantId; }
    public UserRole setGrantId(String grantId) { this.grantId = grantId; return this; }
}
