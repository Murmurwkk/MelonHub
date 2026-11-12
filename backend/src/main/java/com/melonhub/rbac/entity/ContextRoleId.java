package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Primary key of ContextRole. */
@Embeddable
public class ContextRoleId implements Serializable {
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String contextId;
    private Long roleId;

    protected ContextRoleId() {}
    public ContextRoleId(String contextId, Long roleId) { this.contextId = contextId; this.roleId = roleId; }
    public String getContextId() { return contextId; }
    public Long getRoleId() { return roleId; }
    @Override public boolean equals(Object o) { return o instanceof ContextRoleId other && Objects.equals(contextId, other.contextId) && Objects.equals(roleId, other.roleId); }
    @Override public int hashCode() { return Objects.hash(contextId, roleId); }
}
