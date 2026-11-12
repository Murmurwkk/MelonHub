package com.melonhub.rbac.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of Membership. */
@Embeddable
public class MembershipId implements Serializable {
    private Long userId;
    private Long departmentId;

    protected MembershipId() {}
    public MembershipId(Long userId, Long departmentId) { this.userId = userId; this.departmentId = departmentId; }
    public Long getUserId() { return userId; }
    public Long getDepartmentId() { return departmentId; }
    @Override public boolean equals(Object o) { return o instanceof MembershipId other && Objects.equals(userId, other.userId) && Objects.equals(departmentId, other.departmentId); }
    @Override public int hashCode() { return Objects.hash(userId, departmentId); }
}
