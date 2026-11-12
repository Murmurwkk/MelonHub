package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.AdminFunctionDefinition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminFunctionRepository extends JpaRepository<AdminFunctionDefinition, String> {
    List<AdminFunctionDefinition> findAllByOrderBySortOrder();
}
