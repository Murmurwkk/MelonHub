package com.melonhub.rbac.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of UserRole. */
@Embeddable
public class UserRoleId implements Serializable {
    private Long userId;
    private Long roleId;

    protected UserRoleId() {}
    public UserRoleId(Long userId, Long roleId) { this.userId = userId; this.roleId = roleId; }
    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    @Override public boolean equals(Object o) { return o instanceof UserRoleId other && Objects.equals(userId, other.userId) && Objects.equals(roleId, other.roleId); }
    @Override public int hashCode() { return Objects.hash(userId, roleId); }
}
