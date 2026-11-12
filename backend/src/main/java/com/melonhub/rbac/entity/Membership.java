package com.melonhub.rbac.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** A user's membership of a department. */
@Entity
@Table(name = "memberships")
public class Membership {
    @EmbeddedId private MembershipId id;
    @MapsId("userId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @MapsId("departmentId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "department_id") private Department department;

    public Membership() {}
    public MembershipId getId() { return id; }
    public Membership setId(MembershipId id) { this.id = id; return this; }
    public User getUser() { return user; }
    public Membership setUser(User user) { this.user = user; return this; }
    public Department getDepartment() { return department; }
    public Membership setDepartment(Department department) { this.department = department; return this; }
}
