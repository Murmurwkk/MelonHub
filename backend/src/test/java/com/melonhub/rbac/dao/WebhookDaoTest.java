package com.melonhub.rbac.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.CloudTestDatabaseGuard;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/** Queue rules of the delivery table: retention, expired leases and claims by attempt count. */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class WebhookDaoTest {
    @Autowired JdbcTemplate db;
    @Autowired WebhookDao dao;
    @Autowired RbacService r;
    @Autowired WebhookService webhooks;
    long hook;

    @BeforeEach void setUp() {
        String suffix = Credentials.token().substring(0, 10);
        long app = ((Number) r.application(new Requests.Application("Queue", "q-" + suffix, ""), "test").get("id")).longValue();
        hook = ((Number) webhooks.create(app, new Requests.Webhook("Queue", "http://127.0.0.1:1/hook", null, List.of("*"), true), "test")
                .get("id")).longValue();
    }

    @Test void retentionPreservesPendingAndInFlightDeliveries() {
        long pending = insert("PENDING", 2, 31, -1);
        long sending = insert("SENDING", 3, 31, 5);
        long delivered = insert("DELIVERED", 1, 31, -1);
        long failed = insert("FAILED", 8, 31, -1);
        long recent = insert("DELIVERED", 1, 1, -1);

        assertThat(dao.purge(30, 5000)).isGreaterThanOrEqualTo(2);
        assertThat(db.queryForList("SELECT id FROM webhook_deliveries WHERE webhook_id=? ORDER BY id", Long.class, hook))
                .containsExactly(pending, sending, recent).doesNotContain(delivered, failed);
        // The retained in-flight delivery can still record its outcome after cleanup.
        assertThat(dao.delivered(sending, 3, 200)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 8})
    void expiredFinalAttemptFailsInsteadOfStartingAnotherAttempt(int maxAttempts) {
        long id = insert("SENDING", maxAttempts, 1, -1);
        dao.requeueStale(maxAttempts);

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(dao.claim(id, maxAttempts)).isZero();
        assertThat(dao.delivered(id, maxAttempts, 200)).isZero();
        assertThat(db.queryForObject("SELECT attempts FROM webhook_deliveries WHERE id=?", Integer.class, id)).isEqualTo(maxAttempts);
        assertThat(db.queryForObject("SELECT last_error FROM webhook_deliveries WHERE id=?", String.class, id)).contains("lease expired");
    }

    @Test void expiredEarlierAttemptRetriesWhileLiveLeaseIsPreserved() {
        long expired = insert("SENDING", 3, 1, -1);
        long live = insert("SENDING", 3, 1, 5);
        assertThat(dao.requeueStale(8)).isGreaterThanOrEqualTo(1);

        assertThat(status(expired)).isEqualTo("PENDING");
        assertThat(dao.claim(expired, 3)).isEqualTo(1);
        assertThat(dao.delivered(expired, 3, 200)).isZero();
        assertThat(dao.delivered(expired, 4, 200)).isEqualTo(1);
        assertThat(status(live)).isEqualTo("SENDING");
    }

    private long insert(String status, int attempts, int ageDays, int leaseMinutes) {
        db.update("INSERT INTO webhook_deliveries(webhook_id,event_id,event_type,payload,status,attempts,created_at,next_attempt_at) "
                + "VALUES (?,?,'webhook.test','{}',?,?,TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP),TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP))",
                hook, UUID.randomUUID().toString(), status, attempts, ageDays, leaseMinutes);
        return db.queryForObject("SELECT MAX(id) FROM webhook_deliveries WHERE webhook_id=?", Long.class, hook);
    }
    private String status(long id) {
        return db.queryForObject("SELECT status FROM webhook_deliveries WHERE id=?", String.class, id);
    }
}
