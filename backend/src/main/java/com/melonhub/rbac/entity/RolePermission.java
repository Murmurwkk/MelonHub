package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A permission granted by a role. */
@Entity
@Table(name = "role_permissions")
public class RolePermission {
    @EmbeddedId private RolePermissionId id;
    @MapsId("roleId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_id") private Role role;
    @MapsId("permissionId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "permission_id") private Permission permission;
    @Column(name = "department_id") private Long departmentId;
    @Column(name = "application_id") private Long applicationId;

    public RolePermission() {}
    public RolePermissionId getId() { return id; }
    public RolePermission setId(RolePermissionId id) { this.id = id; return this; }
    public Role getRole() { return role; }
    public RolePermission setRole(Role role) { this.role = role; return this; }
    public Permission getPermission() { return permission; }
    public RolePermission setPermission(Permission permission) { this.permission = permission; return this; }
    public Long getDepartmentId() { return departmentId; }
    public RolePermission setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public Long getApplicationId() { return applicationId; }
    public RolePermission setApplicationId(Long applicationId) { this.applicationId = applicationId; return this; }
}
