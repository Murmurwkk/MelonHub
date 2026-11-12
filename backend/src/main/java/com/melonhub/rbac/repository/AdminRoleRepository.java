package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.AdminRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdminRoleRepository extends JpaRepository<AdminRole, String> {
    interface RoleUsage {
        String getRole();
        long getAccounts();
    }

    @Query("select distinct r from AdminRole r left join fetch r.functions where r.code = :code")
    Optional<AdminRole> withFunctions(String code);

    @Query("select distinct r from AdminRole r left join fetch r.functions order by r.builtin desc, r.createdAt, r.code")
    List<AdminRole> listed();

    @Query("select a.role.code as role, count(a) as accounts from AdminAccount a group by a.role.code")
    List<RoleUsage> usage();

    @Query("select count(a) from AdminAccount a where a.role.code = :code")
    long accounts(String code);
}
