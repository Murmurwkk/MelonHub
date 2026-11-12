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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One snapshotted assignment in a review and the decision on it. */
@Entity
@Table(name = "access_review_items")
public class AccessReviewItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "review_id") private AccessReview review;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "role_id") private Role role;
    @Column(name = "department_id") private Long departmentId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", insertable = false, updatable = false) private Department department;
    private String decision;
    private String comment;
    private String decidedBy;
    private Instant decidedAt;
    private boolean applied;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private String grantId;
    private boolean skipped;
    private String userName;
    private String externalId;
    private String roleCode;
    private String roleName;
    private String departmentName;

    public AccessReviewItem() {}
    public Long getId() { return id; }
    public AccessReview getReview() { return review; }
    public AccessReviewItem setReview(AccessReview review) { this.review = review; return this; }
    public User getUser() { return user; }
    public AccessReviewItem setUser(User user) { this.user = user; return this; }
    public Role getRole() { return role; }
    public AccessReviewItem setRole(Role role) { this.role = role; return this; }
    public Long getDepartmentId() { return departmentId; }
    public AccessReviewItem setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public Department getDepartment() { return department; }
    public String getDecision() { return decision; }
    public AccessReviewItem setDecision(String decision) { this.decision = decision; return this; }
    public String getComment() { return comment; }
    public AccessReviewItem setComment(String comment) { this.comment = comment; return this; }
    public String getDecidedBy() { return decidedBy; }
    public AccessReviewItem setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; return this; }
    public Instant getDecidedAt() { return decidedAt; }
    public AccessReviewItem setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; return this; }
    public boolean isApplied() { return applied; }
    public AccessReviewItem setApplied(boolean applied) { this.applied = applied; return this; }
    public String getGrantId() { return grantId; }
    public AccessReviewItem setGrantId(String grantId) { this.grantId = grantId; return this; }
    public boolean isSkipped() { return skipped; }
    public AccessReviewItem setSkipped(boolean skipped) { this.skipped = skipped; return this; }
    public String getUserName() { return userName; }
    public AccessReviewItem setUserName(String userName) { this.userName = userName; return this; }
    public String getExternalId() { return externalId; }
    public AccessReviewItem setExternalId(String externalId) { this.externalId = externalId; return this; }
    public String getRoleCode() { return roleCode; }
    public AccessReviewItem setRoleCode(String roleCode) { this.roleCode = roleCode; return this; }
    public String getRoleName() { return roleName; }
    public AccessReviewItem setRoleName(String roleName) { this.roleName = roleName; return this; }
    public String getDepartmentName() { return departmentName; }
    public AccessReviewItem setDepartmentName(String departmentName) { this.departmentName = departmentName; return this; }
}
