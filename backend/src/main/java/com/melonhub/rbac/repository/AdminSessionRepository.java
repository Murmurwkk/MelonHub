package com.melonhub.rbac.repository;

import com.melonhub.rbac.entity.AdminSession;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AdminSessionRepository extends JpaRepository<AdminSession, String> {
    interface ActiveSession {
        String getUsername();
        String getDisplayName();
        String getRole();
        Instant getLastSeenAt();
        boolean isAllApplications();
    }
    interface SessionCount {
        String getUsername();
        long getSessions();
    }

    /** A session is usable while unexpired, recently used, created under the current credentials and the account is enabled. */
    @Query("""
            select a.username as username, a.displayName as displayName, a.role.code as role, s.lastSeenAt as lastSeenAt,
                   a.allApplications as allApplications
            from AdminSession s join s.account a
            where s.tokenHash = :hash and s.credentialVersion = a.credentialVersion and a.enabled = true
              and s.expiresAt > :now and s.lastSeenAt > :idleSince""")
    Optional<ActiveSession> active(String hash, Instant now, Instant idleSince);

    @Query("select s.account.username as username, count(s) as sessions from AdminSession s where s.expiresAt > :now group by s.account.username")
    List<SessionCount> activeCounts(Instant now);

    @Query("select s.tokenHash from AdminSession s where s.expiresAt < :now or s.lastSeenAt < :idleSince")
    List<String> ended(Instant now, Instant idleSince, Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminSession s set s.lastSeenAt = :now where s.tokenHash = :hash")
    int touch(String hash, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AdminSession s set s.credentialVersion = :version where s.tokenHash = :hash and s.account.username = :username")
    int moveToVersion(String hash, String username, long version);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.tokenHash in :hashes")
    int deleteByHashes(Collection<String> hashes);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.account.username = :username")
    int deleteForAccount(String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSession s where s.account.username = :username and s.tokenHash <> :keepHash")
    int deleteOthers(String username, String keepHash);
}
