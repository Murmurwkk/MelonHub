package com.melonhub.rbac.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** One console function as described to operators; the rows are written by migrations only. */
@Entity
@Immutable
@Table(name = "admin_functions")
public class AdminFunctionDefinition {
    @Id private String code;
    private String name;
    private String category;
    private int sortOrder;

    protected AdminFunctionDefinition() {}

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCategory() { return category; }
}
