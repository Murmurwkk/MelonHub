package com.melonhub.rbac.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;

/** A department: one half of an isolation domain (department x application). Departments are flat. */
@Entity
@Table(name = "departments")
public class Department {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String code;
    /** Set by the database default; read back after insert. */
    @Generated @Column(insertable = false, updatable = false) private Instant createdAt;

    public Department() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public Department setName(String name) { this.name = name; return this; }
    public String getCode() { return code; }
    public Department setCode(String code) { this.code = code; return this; }
    public Instant getCreatedAt() { return createdAt; }
}
