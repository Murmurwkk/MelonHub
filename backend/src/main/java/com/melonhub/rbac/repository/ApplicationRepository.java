package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.Application;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, Long> {
    long countByIdIn(Collection<Long> ids);
}
