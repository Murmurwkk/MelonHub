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

/** An audit record. The sealer chains it with SHA-256; department and application are not foreign keys, so they may name deleted rows. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String actor;
    private String action;
    private String target;
    @Column(name = "department_id") private Long departmentId;
    @Column(name = "application_id") private Long applicationId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id", insertable = false, updatable = false) private Department department;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "application_id", insertable = false, updatable = false) private Application application;
    private String detail;
    private String sourceIp;
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String prevHash;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 64) private String chainHash;
    private Long sealSeq;

    public AuditEvent() {}
    public Long getId() { return id; }
    public String getActor() { return actor; }
    public AuditEvent setActor(String actor) { this.actor = actor; return this; }
    public String getAction() { return action; }
    public AuditEvent setAction(String action) { this.action = action; return this; }
    public String getTarget() { return target; }
    public AuditEvent setTarget(String target) { this.target = target; return this; }
    public Long getDepartmentId() { return departmentId; }
    public AuditEvent setDepartmentId(Long departmentId) { this.departmentId = departmentId; return this; }
    public Long getApplicationId() { return applicationId; }
    public AuditEvent setApplicationId(Long applicationId) { this.applicationId = applicationId; return this; }
    public Department getDepartment() { return department; }
    public Application getApplication() { return application; }
    public String getDetail() { return detail; }
    public AuditEvent setDetail(String detail) { this.detail = detail; return this; }
    public String getSourceIp() { return sourceIp; }
    public AuditEvent setSourceIp(String sourceIp) { this.sourceIp = sourceIp; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public String getPrevHash() { return prevHash; }
    public AuditEvent setPrevHash(String prevHash) { this.prevHash = prevHash; return this; }
    public String getChainHash() { return chainHash; }
    public AuditEvent setChainHash(String chainHash) { this.chainHash = chainHash; return this; }
    public Long getSealSeq() { return sealSeq; }
    public AuditEvent setSealSeq(Long sealSeq) { this.sealSeq = sealSeq; return this; }
}
