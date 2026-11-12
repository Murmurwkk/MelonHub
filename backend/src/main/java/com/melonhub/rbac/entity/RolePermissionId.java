package com.melonhub.rbac.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of RolePermission. */
@Embeddable
public class RolePermissionId implements Serializable {
    private Long roleId;
    private Long permissionId;

    protected RolePermissionId() {}
    public RolePermissionId(Long roleId, Long permissionId) { this.roleId = roleId; this.permissionId = permissionId; }
    public Long getRoleId() { return roleId; }
    public Long getPermissionId() { return permissionId; }
    @Override public boolean equals(Object o) { return o instanceof RolePermissionId other && Objects.equals(roleId, other.roleId) && Objects.equals(permissionId, other.permissionId); }
    @Override public int hashCode() { return Objects.hash(roleId, permissionId); }
}
