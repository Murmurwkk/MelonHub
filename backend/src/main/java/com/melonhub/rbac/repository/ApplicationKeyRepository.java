package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.ApplicationKey;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApplicationKeyRepository extends JpaRepository<ApplicationKey, Long> {
    interface KeyState {
        long getKeyId();
        long getApplicationId();
        boolean isApplicationEnabled();
        Instant getRevokedAt();
        Instant getExpiresAt();
    }

    @Query("""
            select k.id as keyId, a.id as applicationId, a.enabled as applicationEnabled, k.revokedAt as revokedAt, k.expiresAt as expiresAt
            from ApplicationKey k join k.application a where k.keyHash = :hash""")
    Optional<KeyState> byHash(String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationKey k set k.lastUsedAt = :now where k.id in :ids")
    int markUsed(Collection<Long> ids, Instant now);
}
