package com.melonhub.rbac.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A role activated in a permission context. */
@Entity
@Table(name = "context_roles")
public class ContextRole {
    @EmbeddedId private ContextRoleId id;
    @MapsId("contextId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "context_id") private PermissionContext context;
    @MapsId("roleId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_id") private Role role;

    public ContextRole() {}
    public ContextRoleId getId() { return id; }
    public ContextRole setId(ContextRoleId id) { this.id = id; return this; }
    public PermissionContext getContext() { return context; }
    public ContextRole setContext(PermissionContext context) { this.context = context; return this; }
    public Role getRole() { return role; }
    public ContextRole setRole(Role role) { this.role = role; return this; }
}
