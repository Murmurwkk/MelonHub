package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.Department;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    long countByIdIn(Collection<Long> ids);
}
