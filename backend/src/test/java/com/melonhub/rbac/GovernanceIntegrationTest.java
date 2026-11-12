package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dao.AuditDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.WebhookDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.security.GlobalRateLimiter;
import com.melonhub.rbac.security.Totp;
import com.melonhub.rbac.service.AuditSealService;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.ConsoleService;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Governance features from V3: requests, reviews, expiry, webhooks, MFA, audit chain, manifest, bulk import. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class GovernanceIntegrationTest {
    private static final String ACTOR = "gov-test";
    @Autowired RbacService r;
    @Autowired ContextService c;
    @Autowired GovernanceService g;
    @Autowired ConsoleService console;
    @Autowired WebhookService webhooks;
    @Autowired AuditSealService sealer;
    @Autowired AuthService auth;
    @Autowired GlobalRateLimiter globalRateLimiter;
    @Autowired GovernanceDao gov;
    @Autowired AuditDao auditDao;
    @Autowired WebhookDao webhookDao;
    @Autowired MockMvc mvc;
    @Value("${melonhub.admin-username}") String bootstrap;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;

    long d1, d2, a1, u1, u2, r1, r2;
    String external, external2, code1, code2, key1;

    @BeforeEach void fixture() {
        String suffix = Credentials.token().substring(0, 12);
        external = "gov-" + suffix;
        external2 = external + "-second";
        code1 = "gd1-" + suffix;
        code2 = "gd2-" + suffix;
        d1 = r.department(new Requests.Department("Gov One", code1), null, ACTOR);
        d2 = r.department(new Requests.Department("Gov Two", code2), null, ACTOR);
        var app = r.application(new Requests.Application("Gov App", "ga-" + suffix, ""), ACTOR);
        a1 = ((Number) app.get("id")).longValue();
        key1 = (String) app.get("apiKey");
        u1 = r.user(new Requests.User("GovUser", external, "", List.of(d1, d2)), null, ACTOR);
        u2 = r.user(new Requests.User("GovSecond", external2, "", List.of(d1)), null, ACTOR);
        long p1 = r.permission(new Requests.Permission("Read", "item:read", "item", "read"), null, d1, a1, ACTOR);
        long p2 = r.permission(new Requests.Permission("Write", "item:write", "item", "write"), null, d1, a1, ACTOR);
        r1 = r.role(new Requests.Role("Reader", "reader", ""), null, d1, a1, ACTOR);
        r2 = r.role(new Requests.Role("Writer", "writer", ""), null, d1, a1, ACTOR);
        r.rolePermissions(r1, d1, a1, List.of(p1), ACTOR);
        r.rolePermissions(r2, d1, a1, List.of(p2), ACTOR);
        r.userRoles(u1, d1, a1, List.of(r1), ACTOR);
    }

    private String issue() { return (String) c.issue(a1, new Requests.Context(external, d1, null), ACTOR).get("contextToken"); }
    private long requestId(Map<String,Object> request) { return ((Number) request.get("id")).longValue(); }

    @Test void approvedRequestGrantsRoleUntilItsExpiry() throws Exception {
        var submitted = g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "月末报表需要写权限", 30), "app:" + a1);
        assertThat(submitted.get("status")).isEqualTo("PENDING");
        assertThatThrownBy(() -> g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "重复", null), "app:" + a1))
                .isInstanceOf(ApiException.class).hasMessageContaining("待审批");
        long id = requestId(submitted);
        var approved = g.approve(id, new Requests.RequestApprove(null, "同意"), "admin");
        assertThat(approved.get("status")).isEqualTo("APPROVED");
        assertThat(approved.get("grantExpiresAt")).isNotNull();
        assertThatThrownBy(() -> g.approve(id, new Requests.RequestApprove(null, null), "admin"))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被处理");
        assertThat(c.effective(a1, issue()).get("permissions")).isEqualTo(List.of("item:read", "item:write"));
        // Once the assignment lapses, the next context no longer contains it.
        db.update("UPDATE user_roles SET expires_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP) WHERE user_id=? AND role_id=?", u1, r2);
        assertThat(c.effective(a1, issue()).get("permissions")).isEqualTo(List.of("item:read"));
        assertThat((List<Object>) g.myAccess(a1, external, d1).get("permissions")).containsExactly("item:read");
    }

    @Test void rejectionAndEmployeeCancellation() throws Exception {
        long hook = ((Number) webhooks.create(a1, new Requests.Webhook("驳回通知", "http://127.0.0.1:1/hook", null, List.of("access.rejected"), true), ACTOR)
                .get("id")).longValue();
        var rejected = g.reject(requestId(g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "需要", null), "app:" + a1)),
                new Requests.RequestReject("权限过大"), "admin");
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("decisionComment")).isEqualTo("权限过大");
        // The business system can show the reason without asking the console.
        String payload = db.queryForObject("SELECT payload FROM webhook_deliveries WHERE webhook_id=?", String.class, hook);
        assertThat(mapper.readTree(payload).path("data").path("comment").asText()).isEqualTo("权限过大");
        var again = g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "再次申请", null), "app:" + a1);
        assertThatThrownBy(() -> g.cancelOwn(a1, requestId(again), external2, "app:" + a1)).isInstanceOf(ApiException.class);
        g.cancelOwn(a1, requestId(again), external, "app:" + a1);
        assertThat(g.request(requestId(again)).get("status")).isEqualTo("CANCELLED");
        assertThat(g.myRequests(a1, external)).hasSize(2);
    }

    @Test void closingReviewAppliesRevokeDecisions() {
        r.userRoles(u2, d1, a1, List.of(r2), ACTOR);
        String token = issue();
        var review = g.createReview(new Requests.ReviewCreate("三季度复核", a1, d1, 7), "admin");
        long reviewId = requestId(review);
        assertThat(((Number) review.get("items")).longValue()).isEqualTo(2);
        var items = (List<Map<String,Object>>) g.reviewItems(reviewId, null, 1, 50).get("items");
        long keepItem = -1, revokeItem = -1;
        for (var item : items) {
            if (((Number) item.get("userId")).longValue() == u1) revokeItem = requestId(item);
            else keepItem = requestId(item);
        }
        final long revoke = revokeItem, keep = keepItem;
        g.decideReviewItem(reviewId, revoke, new Requests.ReviewItemDecision("REVOKE", "不再需要"), "admin");
        g.decideReviewItem(reviewId, keep, new Requests.ReviewItemDecision("REVOKE", null), "admin");
        // A reviewer can change their mind until the review is closed.
        g.decideReviewItem(reviewId, keep, new Requests.ReviewItemDecision("KEEP", "仍在使用"), "admin");
        var kept = (List<Map<String,Object>>) g.reviewItems(reviewId, "KEEP", 1, 50).get("items");
        assertThat(kept).singleElement().satisfies(item -> assertThat(item.get("comment")).isEqualTo("仍在使用"));
        var closed = g.closeReview(reviewId, "admin");
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("summary").toString()).contains("回收 1 条");
        assertThat(r.assignments(u1, d1, a1).get("roleIds")).isEqualTo(List.of());
        assertThat(r.assignments(u2, d1, a1).get("roleIds")).isEqualTo(List.of(r2));
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> g.decideReviewItem(reviewId, keep, new Requests.ReviewItemDecision("REVOKE", null), "admin"))
                .isInstanceOf(ApiException.class).hasMessageContaining("已关闭");
    }

    @Test void webhookDeliveryIsSignedAndFailuresAreRescheduled() throws Exception {
        var received = new CountDownLatch(1);
        var signature = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var event = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst("MelonHub-Signature"));
            event.set(exchange.getRequestHeaders().getFirst("MelonHub-Event"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            String secret = "wh-test-secret-0123456789";
            webhooks.create(a1, new Requests.Webhook("测试钩子", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                    secret, List.of("user.roles.changed"), true), ACTOR);
            r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
            webhooks.deliverNow();
            assertThat(received.await(5, TimeUnit.SECONDS)).as("webhook delivered").isTrue();
            assertThat(event.get()).isEqualTo("user.roles.changed");
            String[] parts = signature.get().split(",");
            String timestamp = parts[0].substring(2);
            assertThat(parts[1].substring(3)).isEqualTo(WebhookService.hmacSha256(secret, timestamp + "." + body.get()));
            assertThat(mapper.readTree(body.get()).path("data").path("externalId").asText()).isEqualTo(external);
            Long deliveries = db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE status='DELIVERED'", Long.class);
            assertThat(deliveries).isEqualTo(1);
        } finally {
            server.stop(0);
        }
        // A dead endpoint stays PENDING with backoff instead of being lost.
        webhooks.create(a1, new Requests.Webhook("不可用钩子", "http://127.0.0.1:1/hook", null, List.of("user.roles.changed"), true), ACTOR);
        r.userRoles(u1, d1, a1, List.of(r1), ACTOR);
        webhooks.deliverNow();
        var row = db.queryForMap("SELECT status,attempts,last_error FROM webhook_deliveries ORDER BY id DESC LIMIT 1");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
    }

    @Test void mfaRequiresCurrentCodeAndNeverReplaysOne() {
        var root = auth.principal(bootstrap);
        String username = "mfa-" + Credentials.token().substring(0, 8);
        String password = "mfa-password-123";
        auth.createAccount(new Requests.Account(username, "MFA 用户", "AUDITOR", password, true, null), root);
        var principal = TestAdmins.self(username, "MFA 用户", "AUDITOR");
        String beforeEnable = (String) auth.login(new Requests.Login(username, password)).get("token");
        var setup = auth.setupMfa(principal);
        String secret = (String) setup.get("secret");
        assertThat((String) setup.get("uri")).contains("otpauth://totp/MelonHub:" + username);
        assertThatThrownBy(() -> auth.confirmMfa(principal, "000000")).isInstanceOf(ApiException.class);
        auth.confirmMfa(principal, Totp.codeAt(secret, Totp.currentStep()));
        assertThatThrownBy(() -> auth.admin("Bearer " + beforeEnable)).isInstanceOf(ApiException.class);
        // Without a code the login is refused with MFA_REQUIRED.
        assertThatThrownBy(() -> auth.login(new Requests.Login(username, password)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MFA_REQUIRED");
        String code = Totp.codeAt(secret, Totp.currentStep() + 1);
        String beforeDisable = (String) auth.login(new Requests.Login(username, password, code)).get("token");
        assertThat(auth.admin("Bearer " + beforeDisable).username()).isEqualTo(username);
        // The same code can never sign in twice, even inside its acceptance window.
        assertThatThrownBy(() -> auth.login(new Requests.Login(username, password, code)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MFA_INVALID");
        // Turning MFA off needs a fresh code too; the one just used to sign in is refused.
        assertThatThrownBy(() -> auth.disableMfa(principal, new Requests.MfaDisable(password, code)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("MFA_INVALID");
        // Stand in for the next 30 s window instead of sleeping until it arrives.
        db.update("UPDATE admin_accounts SET mfa_last_step=mfa_last_step-1 WHERE username=?", username);
        auth.disableMfa(principal, new Requests.MfaDisable(password, code));
        assertThatThrownBy(() -> auth.admin("Bearer " + beforeDisable)).isInstanceOf(ApiException.class);
        String beforeReset = (String) auth.login(new Requests.Login(username, password)).get("token");
        auth.resetMfa(username, root);
        assertThatThrownBy(() -> auth.admin("Bearer " + beforeReset)).isInstanceOf(ApiException.class);
    }

    @Test void auditChainVerifiesAndDetectsTampering() {
        rbacAudit(0);
        long first = lastAuditId();
        rbacAudit(1);
        rbacAudit(2);
        sealer.sealNow();
        long from = sealSeq(first);
        var before = sealer.verify(from, 10_000);
        assertThat(before.get("ok")).isEqualTo(true);
        assertThat(before.get("complete")).isEqualTo(true);
        long tampered = lastAuditId();
        db.update("UPDATE audit_events SET detail='篡改内容' WHERE id=?", tampered);
        var after = sealer.verify(from, 10_000);
        assertThat(after.get("ok")).isEqualTo(false);
        assertThat(after.get("problem")).isEqualTo("MODIFIED");
        assertThat(((Number) after.get("brokenAt")).longValue()).isEqualTo(tampered);
    }

    @Test void lateCommittedRowsJoinTheChainAndRemovedTailIsDetected() {
        sealer.sealNow();
        rbacAudit(0);
        long late = lastAuditId();
        rbacAudit(1);
        long sealedFirst = lastAuditId();
        // Stand in for a transaction that has not committed yet: its id exists, its row is not visible.
        var row = db.queryForMap("SELECT actor,action,target,detail,source_ip FROM audit_events WHERE id=?", late);
        db.update("DELETE FROM audit_events WHERE id=?", late);
        sealer.sealNow();
        db.update("INSERT INTO audit_events(id,actor,action,target,detail,source_ip) VALUES (?,?,?,?,?,?)",
                late, row.get("actor"), row.get("action"), row.get("target"), row.get("detail"), row.get("source_ip"));
        sealer.sealNow();
        long from = sealSeq(sealedFirst);
        assertThat(sealSeq(late)).as("late row is appended, not skipped").isEqualTo(from + 1);
        var whole = sealer.verify(from, 10_000);
        assertThat(whole.get("ok")).isEqualTo(true);
        assertThat(whole.get("checked")).isEqualTo(2L);
        assertThat(whole.get("complete")).isEqualTo(true);
        // Removing the newest sealed row leaves an intact but shorter chain; the recorded chain end exposes it.
        db.update("DELETE FROM audit_events WHERE id=?", late);
        var cut = sealer.verify(from, 10_000);
        assertThat(cut.get("ok")).isEqualTo(false);
        assertThat(cut.get("problem")).isEqualTo("TAIL_MISSING");
        db.update("DELETE FROM audit_events WHERE seal_seq IS NOT NULL");
        var emptied = sealer.verify(null, 10_000);
        assertThat(emptied.get("ok")).isEqualTo(false);
        assertThat(emptied.get("problem")).isEqualTo("TAIL_MISSING");
    }

    @Test void retentionCleanupCutsAPrefixAndLeavesAVerifiableChain() {
        rbacAudit(0);
        rbacAudit(1);
        long kept = lastAuditId();
        rbacAudit(2);
        long newest = lastAuditId();
        sealer.sealNow();
        long keptSeq = sealSeq(kept);
        db.update("UPDATE audit_events SET created_at=TIMESTAMPADD(DAY,-200,CURRENT_TIMESTAMP) WHERE seal_seq<?", keptSeq);
        while (sealer.purge(180) == AuditSealService.PURGE_BATCH) { /* drain */ }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE seal_seq<?", Long.class, keptSeq)).isZero();
        assertThat(db.queryForObject("SELECT anchor_seq FROM audit_seal WHERE id=1", Long.class)).isEqualTo(keptSeq - 1);
        var result = sealer.verify(null, 10_000);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("firstId")).isEqualTo(kept);
        // Even when everything is past retention the chain end is kept, so the end can still be checked.
        db.update("UPDATE audit_events SET created_at=TIMESTAMPADD(DAY,-200,CURRENT_TIMESTAMP) WHERE seal_seq IS NOT NULL");
        sealer.purge(180);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE seal_seq IS NOT NULL", Long.class)).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT seal_seq FROM audit_events WHERE id=?", Long.class, newest)).isNotNull();
    }

    private void rbacAudit(int i) { r.userStatus(u2, i % 2 == 0, ACTOR + "-chain"); }
    private long lastAuditId() { return db.queryForObject("SELECT MAX(id) FROM audit_events", Long.class); }
    private long sealSeq(long id) { return db.queryForObject("SELECT seal_seq FROM audit_events WHERE id=?", Long.class, id); }

    @Test void manifestExportAndImportRoundTrip() {
        var exported = g.exportManifest(a1, d1, null);
        assertThat((List<?>) exported.get("departments")).hasSize(1);
        var manifest = mapper.convertValue(exported, Requests.Manifest.class);
        db.update("UPDATE roles SET name='改名',description='漂移' WHERE id=?", r1);
        db.update("DELETE FROM role_permissions WHERE role_id=?", r2);
        var result = g.importManifest(a1, manifest, "admin", null);
        assertThat(((Number) result.get("rolesUpdated")).intValue()).isEqualTo(2);
        assertThat(((Number) result.get("permissionsUpdated")).intValue()).isEqualTo(2);
        var roles = r.roles(d1, a1);
        var restored = roles.stream().filter(role -> role.get("id").equals(r1) || ((Number) role.get("id")).longValue() == r1).findFirst().orElseThrow();
        assertThat(restored.get("name")).isEqualTo("Reader");
        var writer = roles.stream().filter(role -> ((Number) role.get("id")).longValue() == r2).findFirst().orElseThrow();
        assertThat((List<?>) writer.get("permissionIds")).hasSize(1);
        // Importing into another department creates the configuration there.
        var second = g.importManifest(a1, manifest, "admin", null);
        var target = new Requests.ManifestDepartment(code2, manifest.departments().get(0).permissions(), manifest.departments().get(0).roles());
        var copied = g.importManifest(a1, new Requests.Manifest(1, List.of(target)), "admin", null);
        assertThat(((Number) copied.get("permissionsCreated")).intValue()).isEqualTo(2);
        assertThat(((Number) copied.get("rolesCreated")).intValue()).isEqualTo(2);
        assertThat(r.roles(d2, a1)).hasSize(2);
        assertThat(second.get("rolesUpdated")).isEqualTo(2);
    }

    @Test void bulkImportCreatesUpdatesAndReportsRowErrors() {
        var result = g.importUsers(new Requests.UserImport(List.of(
                new Requests.UserImportRow("新员工", external + "-new", "new@example.com", List.of(code1, code2)),
                new Requests.UserImportRow("改名", external, "", List.of(code1)),
                new Requests.UserImportRow("坏行", external + "-bad", "", List.of("no-such-dept")))), "admin");
        assertThat(((Number) result.get("created")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("updated")).intValue()).isEqualTo(1);
        assertThat(((List<?>) result.get("failed")).size()).isEqualTo(1);
        var imported = db.queryForMap("SELECT name FROM users WHERE external_id=?", external + "-new");
        assertThat(imported.get("name")).isEqualTo("新员工");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM memberships m JOIN users u ON u.id=m.user_id WHERE u.external_id=?", Long.class, external + "-new"))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id=?", Long.class, u1)).isEqualTo(2);
    }

    @Test void reverseLookupSearchAndStatus() {
        var holders = g.permissionHolders(a1, "item:read", null, null);
        assertThat(((Number) holders.get("total")).intValue()).isEqualTo(1);
        assertThat(((Number) g.permissionHolders(a1, "item:write", null, null).get("total")).longValue()).isZero();
        assertThat(((Number) g.permissionHolders(a1, "no:such", null, null).get("total")).longValue()).isZero();
        var found = console.search(external, Visibility.ALL);
        assertThat((List<?>) found.get("users")).isNotEmpty();
        assertThat((List<?>) console.search("reader", Visibility.ALL).get("roles")).isNotEmpty();
        var status = console.status(Visibility.ALL);
        assertThat(status.get("database")).isEqualTo("UP");
        assertThat(((Map<?,?>) status.get("counts")).get("users")).isNotNull();
        assertThat(status.get("audit")).isNotNull();
    }

    @Test void expiringAssignmentsAppearInReport() {
        r.userRoles(u1, d1, a1, List.of(r1), 7, ACTOR);
        assertThat(g.expiringAssignments(30, Visibility.ALL, a1)).anyMatch(row -> ((Number) row.get("userId")).longValue() == u1);
        assertThat(g.expiringAssignments(3, Visibility.ALL, a1)).noneMatch(row -> ((Number) row.get("userId")).longValue() == u1);
        // An expired assignment is excluded from new contexts immediately.
        db.update("UPDATE user_roles SET expires_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP) WHERE user_id=? AND role_id=?", u1, r1);
        assertThat((List<?>) c.effective(a1, issue()).get("permissions")).isEmpty();
    }

    @Test void globalRateLimiterSharesOneRedisWindow() {
        assumeFalse("DISABLED".equals(cacheHealth()), "cache disabled");
        String id = Credentials.token().substring(0, 8);
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isTrue();
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isTrue();
        assertThat(globalRateLimiter.tryAcquire("test", id, 2, 60)).isFalse();
    }

    private String cacheHealth() {
        try { return console.status(Visibility.ALL).get("cache").toString(); } catch (RuntimeException e) { return "DOWN"; }
    }

    @Test void contextCreationCanRefuseToSwitchTheCurrentDepartment() throws Exception {
        // The very first context has no current department to protect, so it may set one.
        String token = (String) c.issue(a1, new Requests.Context(external, d1, null, false), ACTOR).get("contextToken");
        assertThatThrownBy(() -> c.issue(a1, new Requests.Context(external, d2, null, false), ACTOR))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DEPARTMENT_MISMATCH");
        mvc.perform(post("/api/v1/contexts").header("X-API-Key", key1).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("externalId", external, "departmentId", d2, "switchDepartment", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_MISMATCH"));
        c.issue(a1, new Requests.Context(external, d1, null, false), ACTOR);
        assertThat(c.effective(a1, token).get("departmentId")).isEqualTo(d1);
        // Only an explicit switch moves the user and ends the old department's contexts.
        c.issue(a1, new Requests.Context(external, d2, null, true), ACTOR);
        assertThatThrownBy(() -> c.effective(a1, token)).isInstanceOf(ApiException.class);
    }

    @Test void disabledWebhookKeepsItsQueueUntilEnabledAgain() {
        var hook = new Requests.Webhook("停用钩子", "http://127.0.0.1:1/hook", null, List.of("user.roles.changed"), true);
        long id = ((Number) webhooks.create(a1, hook, ACTOR).get("id")).longValue();
        r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        webhooks.update(id, new Requests.Webhook(hook.name(), hook.url(), null, hook.events(), false), ACTOR);
        webhooks.deliverNow();
        var queued = db.queryForMap("SELECT status,attempts FROM webhook_deliveries WHERE webhook_id=?", id);
        assertThat(queued.get("status")).isEqualTo("PENDING");
        assertThat(((Number) queued.get("attempts")).intValue()).isZero();
        webhooks.update(id, hook, ACTOR);
        webhooks.deliverNow();
        assertThat(db.queryForObject("SELECT attempts FROM webhook_deliveries WHERE webhook_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void manifestImportAnnouncesRolesItDisablesOrRegrants() {
        long hook = ((Number) webhooks.create(a1, new Requests.Webhook("清单通知", "http://127.0.0.1:1/hook", null, List.of("*"), true), ACTOR)
                .get("id")).longValue();
        var section = mapper.convertValue(g.exportManifest(a1, d1, null), Requests.Manifest.class).departments().get(0);
        var roles = section.roles().stream().map(role -> switch (role.code()) {
            case "reader" -> new Requests.ManifestRole(role.code(), role.name(), role.description(), false, role.permissions());
            case "writer" -> new Requests.ManifestRole(role.code(), role.name(), role.description(), true, List.of("item:read", "item:write"));
            default -> role;
        }).toList();
        var manifest = new Requests.Manifest(1, List.of(new Requests.ManifestDepartment(section.code(), section.permissions(), roles)));
        g.importManifest(a1, manifest, "admin", null);
        var events = db.queryForList("SELECT payload FROM webhook_deliveries WHERE webhook_id=? ORDER BY id", String.class, hook);
        assertThat(events).hasSize(2);
        assertThat(events).anySatisfy(payload -> assertThat(payload).contains("\"role.disabled\"").contains("\"roleId\":" + r1));
        assertThat(events).anySatisfy(payload -> assertThat(payload).contains("\"role.permissions.changed\"").contains("\"roleId\":" + r2));
        // Importing the same manifest again changes nothing, so nothing is announced.
        g.importManifest(a1, manifest, "admin", null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE webhook_id=?", Long.class, hook)).isEqualTo(2);
    }

    @Test @SuppressWarnings("unchecked") void simulationReportsLapsedRolesWithoutBlocking() {
        r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        db.update("UPDATE user_roles SET expires_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP) WHERE user_id=? AND role_id=?", u1, r2);
        var result = r.simulate(new Requests.Simulation(u1, d1, a1, null, List.of("item:read", "item:write")));
        assertThat(result.get("eligible")).isEqualTo(true);
        assertThat((List<String>) result.get("issues")).isEmpty();
        assertThat((List<String>) result.get("notices")).singleElement().asString().contains("writer");
        assertThat((Map<String,Boolean>) result.get("decisions")).containsEntry("item:read", true).containsEntry("item:write", false);
        // Asking for the lapsed role explicitly would make context creation fail, so that stays a blocking issue.
        var explicit = r.simulate(new Requests.Simulation(u1, d1, a1, List.of(r2), null));
        assertThat(explicit.get("eligible")).isEqualTo(false);
    }

    @Test void ownRequestsAreFilteredByApplicationBeforeTheyAreLimited() {
        var other = r.application(new Requests.Application("Gov Other", "go-" + Credentials.token().substring(0, 12), ""), ACTOR);
        long a2 = ((Number) other.get("id")).longValue();
        long otherRole = r.role(new Requests.Role("Other", "other", ""), null, d1, a2, ACTOR);
        long mine = requestId(g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "本软件的申请", null), "app:" + a1));
        for (int i = 0; i < 60; i++)
            db.update("INSERT INTO access_requests(user_id,department_id,application_id,role_id,reason,status,requested_by) VALUES (?,?,?,?,?,'CANCELLED',?)",
                    u1, d1, a2, otherRole, "其他软件 " + i, "app:" + a2);
        assertThat(g.myRequests(a1, external)).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(mine);
        assertThat(g.myRequests(a2, external)).hasSize(50);
    }

    @Test void governanceEndpointsAreExposedAndGuarded() throws Exception {
        mvc.perform(get("/api/admin/access-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/access-requests")
                        .header("X-API-Key", key1)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("externalId", external2, "departmentId", d1, "roleId", r2, "reason", "联调"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/v1/access-requests").header("X-API-Key", key1).param("externalId", external2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mvc.perform(get("/api/v1/users/access").header("X-API-Key", key1).param("externalId", external2).param("departmentId", String.valueOf(d1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray());
        mvc.perform(get("/api/admin/system/status")).andExpect(status().isUnauthorized());
    }

    @Test @SuppressWarnings("unchecked") void reassigningKeepsUntouchedExpiriesAndAcceptsPerRoleOnes() {
        r.userRoles(u1, d1, a1, List.of(r1), 7, ACTOR);
        Instant week = expiresAt(u1, r1);
        String grant = grantId(u1, r1);
        assertThat(week).isNotNull();
        // Adding a role without saying anything about expiry leaves the existing grant exactly as it was.
        r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        assertThat(expiresAt(u1, r1)).isEqualTo(week);
        assertThat(grantId(u1, r1)).isEqualTo(grant);
        assertThat(expiresAt(u1, r2)).isNull();
        var soon = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0);
        r.userRoles(u1, d1, a1, List.of(r1, r2), null, List.of(new Requests.RoleExpiry(r2, soon), new Requests.RoleExpiry(r1, null)), ACTOR);
        assertThat(expiresAt(u1, r2)).isEqualTo(soon.toInstant());
        assertThat(expiresAt(u1, r1)).isNull();
        var members = (List<Map<String,Object>>) r.domainAssignments(d1, a1, null, 1, 50).get("items");
        var mine = members.stream().filter(m -> ((Number) m.get("id")).longValue() == u1).findFirst().orElseThrow();
        assertThat(((Map<Long,Object>) mine.get("roleExpiries")).keySet()).containsExactly(r2);
        // The single-user listing carries the same expiry map, keyed by role id.
        var own = r.assignments(u1, d1, a1);
        assertThat((List<Long>) own.get("roleIds")).containsExactly(r1, r2);
        assertThat(((Map<Long,Object>) own.get("roleExpiries")).keySet()).containsExactly(r2);
        assertThatThrownBy(() -> r.userRoles(u1, d1, a1, List.of(r1), null,
                List.of(new Requests.RoleExpiry(r1, OffsetDateTime.now().minusMinutes(1))), ACTOR)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> r.userRoles(u1, d1, a1, List.of(r1), null, List.of(new Requests.RoleExpiry(r2, soon)), ACTOR))
                .isInstanceOf(ApiException.class);
    }

    @Test @SuppressWarnings("unchecked") void closingReviewOnlyRevokesTheGrantItSnapshotted() {
        r.userRoles(u2, d1, a1, List.of(r2), ACTOR);
        long reviewId = requestId(g.createReview(new Requests.ReviewCreate("变更跳过", a1, d1, 7), "admin"));
        for (var item : (List<Map<String,Object>>) g.reviewItems(reviewId, null, 1, 50).get("items"))
            g.decideReviewItem(reviewId, requestId(item), new Requests.ReviewItemDecision("REVOKE", null), "admin");
        // Removed and granted again after the snapshot: a newer decision the reviewer never saw.
        r.userRoles(u1, d1, a1, List.of(), ACTOR);
        r.userRoles(u1, d1, a1, List.of(r1), ACTOR);
        var closed = g.closeReview(reviewId, "admin");
        assertThat(closed.get("summary").toString()).contains("回收 1 条").contains("1 条在复核期间已变更");
        assertThat(((Number) closed.get("skipped")).intValue()).isEqualTo(1);
        assertThat(r.assignments(u1, d1, a1).get("roleIds")).isEqualTo(List.of(r1));
        assertThat(r.assignments(u2, d1, a1).get("roleIds")).isEqualTo(List.of());
    }

    @Test @SuppressWarnings("unchecked") void governanceHistorySurvivesDeletingTheUserOrRole() {
        long approved = requestId(g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "需要写权限", null), "app:" + a1));
        g.approve(approved, new Requests.RequestApprove(null, "同意"), "admin");
        long pending = requestId(g.submit(a1, new Requests.AccessRequestSubmit(external2, d1, r2, "也需要", null), "app:" + a1));
        long reviewId = requestId(g.createReview(new Requests.ReviewCreate("删除前快照", a1, d1, 7), "admin"));
        r.deleteRole(r2, d1, a1, ACTOR);
        var cancelled = g.request(pending);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("roleId")).isNull();
        assertThat(cancelled.get("roleCode")).isEqualTo("writer");
        r.deleteUser(u1, ACTOR, null);
        var history = g.request(approved);
        assertThat(history.get("status")).isEqualTo("APPROVED");
        assertThat(history.get("userId")).isNull();
        assertThat(history.get("externalId")).isEqualTo(external);
        assertThat(history.get("userName")).isEqualTo("GovUser");
        assertThat(history.get("roleName")).isEqualTo("Writer");
        assertThat(((Number) g.requests(a1, Visibility.ALL, null, external, 1, 20).get("total")).intValue()).isEqualTo(2);
        var items = (List<Map<String,Object>>) g.reviewItems(reviewId, null, 1, 50).get("items");
        assertThat(items).hasSize(2).allSatisfy(item -> assertThat(item.get("externalId")).isEqualTo(external));
        for (var item : items) g.decideReviewItem(reviewId, requestId(item), new Requests.ReviewItemDecision("REVOKE", null), "admin");
        assertThat(((Number) g.closeReview(reviewId, "admin").get("skipped")).intValue()).isEqualTo(2);
    }

    @Test void theDatabaseKeepsOnePendingRequestPerUserAndRole() {
        g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "第一次", null), "app:" + a1);
        assertThatThrownBy(() -> db.update("INSERT INTO access_requests(user_id,department_id,application_id,role_id,pending_role_id,reason,status,requested_by) "
                + "VALUES (?,?,?,?,?,'并发提交','PENDING','test')", u1, d1, a1, r2, r2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void approverCanGrantPermanentlyOrForAnotherDuration() {
        long first = requestId(g.submit(a1, new Requests.AccessRequestSubmit(external, d1, r2, "申请 30 天", 30), "app:" + a1));
        assertThatThrownBy(() -> g.approve(first, new Requests.RequestApprove(7, null, true), "admin")).isInstanceOf(ApiException.class);
        var approved = g.approve(first, new Requests.RequestApprove(null, "长期需要", true), "admin");
        assertThat(approved.get("grantExpiresAt")).isNull();
        assertThat(expiresAt(u1, r2)).isNull();
        long second = requestId(g.submit(a1, new Requests.AccessRequestSubmit(external2, d1, r2, "申请永久", null), "app:" + a1));
        g.approve(second, new Requests.RequestApprove(7, null, false), "admin");
        assertThat(expiresAt(u2, r2)).isBetween(Instant.now().plus(6, ChronoUnit.DAYS), Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test void staleWebhookReadsCannotClaimOrFinishADelivery() {
        long hook = ((Number) webhooks.create(a1, new Requests.Webhook("认领", "http://127.0.0.1:1/hook", null, List.of("user.roles.changed"), true), ACTOR)
                .get("id")).longValue();
        r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        long delivery = db.queryForObject("SELECT id FROM webhook_deliveries WHERE webhook_id=?", Long.class, hook);
        assertThat(webhookDao.claim(delivery, 1)).as("attempt count differs from the read").isZero();
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(MINUTE,5,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        assertThat(webhookDao.claim(delivery, 0)).as("not due yet").isZero();
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        assertThat(webhookDao.claim(delivery, 0)).isEqualTo(1);
        assertThat(webhookDao.claim(delivery, 0)).isZero();
        // The first worker's lease lapses and another worker claims the delivery again.
        db.update("UPDATE webhook_deliveries SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?", delivery);
        webhookDao.requeueStale(8);
        assertThat(webhookDao.claim(delivery, 1)).isEqualTo(1);
        assertThat(webhookDao.delivered(delivery, 1, 200)).as("the lapsed claim cannot write its result").isZero();
        assertThat(webhookDao.delivered(delivery, 2, 200)).isEqualTo(1);
    }

    @Test void permissionHoldersAreCountedPerUserAndDepartment() {
        long read = db.queryForObject("SELECT id FROM permissions WHERE department_id=? AND application_id=? AND code='item:read'", Long.class, d1, a1);
        long write = db.queryForObject("SELECT id FROM permissions WHERE department_id=? AND application_id=? AND code='item:write'", Long.class, d1, a1);
        r.rolePermissions(r2, d1, a1, List.of(read, write), ACTOR);
        r.userRoles(u1, d1, a1, List.of(r1, r2), ACTOR);
        r.userRoles(u2, d1, a1, List.of(r2), ACTOR);
        var holders = g.permissionHolders(a1, "item:read", null, null);
        assertThat(((Number) holders.get("total")).longValue()).isEqualTo(2);
        assertThat(holders.get("truncated")).isEqualTo(false);
        assertThat((List<?>) holders.get("holders")).hasSize(2).anySatisfy(holder ->
                assertThat((List<?>) ((Map<?,?>) holder).get("roles")).hasSize(2));
    }

    @Test void movingTextBetweenAuditFieldsIsDetected() {
        var original = new HashMap<String,Object>(Map.of("id", 1L, "actor", "left|mid", "action", "right", "target", "t", "detail", "", "source_ip", "", "ts", 0L));
        var shifted = new HashMap<>(original);
        shifted.put("actor", "left");
        shifted.put("action", "mid|right");
        // The original "|"-joined form cannot tell these rows apart; the length-prefixed form can.
        assertThat(AuditDao.canonicalV1(shifted)).isEqualTo(AuditDao.canonicalV1(original));
        assertThat(AuditDao.canonicalV2(1, shifted)).isNotEqualTo(AuditDao.canonicalV2(1, original));
        rbacAudit(0);
        long id = lastAuditId();
        db.update("UPDATE audit_events SET actor='left|mid',action='right' WHERE id=?", id);
        sealer.sealNow();
        long from = sealSeq(id);
        assertThat(sealer.verify(from, 10_000).get("ok")).isEqualTo(true);
        db.update("UPDATE audit_events SET actor='left',action='mid|right' WHERE id=?", id);
        var result = sealer.verify(from, 10_000);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("problem")).isEqualTo("MODIFIED");
    }

    @Test void batchAddsSeveralRolesToSeveralMembersAndKeepsExistingGrants() {
        r.userRoles(u1, d1, a1, List.of(r1), 7, ACTOR);
        Instant originalExpiry = expiresAt(u1, r1);
        String originalGrant = grantId(u1, r1);
        var result = r.addUserRoles(d1, a1, new Requests.BatchUserRoles(List.of(u1, u2), List.of(r1, r2), 30), ACTOR);
        assertThat(result).containsEntry("changedUsers", 2).containsEntry("grantsAdded", 3);
        assertThat(expiresAt(u1, r1)).isEqualTo(originalExpiry);
        assertThat(grantId(u1, r1)).isEqualTo(originalGrant);
        assertThat(expiresAt(u2, r1)).isBetween(Instant.now().plus(29, ChronoUnit.DAYS), Instant.now().plus(31, ChronoUnit.DAYS));
        assertThat(r.assignments(u2, d1, a1).get("roleIds")).isEqualTo(List.of(r1, r2));
        assertThat(r.addUserRoles(d1, a1, new Requests.BatchUserRoles(List.of(u1, u2), List.of(r1, r2), null), ACTOR))
                .containsEntry("changedUsers", 0).containsEntry("grantsAdded", 0);
        assertThat(expiresAt(u2, r1)).isNotNull();
    }

    @Test void batchCannotAssignAcrossDepartmentsOrRolesOutsideTheDomain() {
        long outsider = r.user(new Requests.User("Outside", external + "-outside", "", List.of(d2)), null, ACTOR);
        long otherRole = r.role(new Requests.Role("Other", "other", ""), null, d2, a1, ACTOR);
        assertThatThrownBy(() -> r.addUserRoles(d1, a1, new Requests.BatchUserRoles(List.of(u2, outsider), List.of(r1), null), ACTOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> r.addUserRoles(d1, a1, new Requests.BatchUserRoles(List.of(u2), List.of(r1, otherRole), null), ACTOR))
                .isInstanceOf(ApiException.class);
        assertThat(r.assignments(u2, d1, a1).get("roleIds")).isEqualTo(List.of());
    }

    @Test void staleTimedFormCannotRecreateAPurgedGrantPermanently() {
        var expired = OffsetDateTime.now().minusMinutes(1);
        db.update("DELETE FROM user_roles WHERE user_id=? AND role_id=?", u1, r1);
        assertThatThrownBy(() -> r.userRoles(u1, d1, a1, List.of(r1), null, List.of(new Requests.RoleExpiry(r1, expired)), ACTOR))
                .isInstanceOf(ApiException.class).hasMessageContaining("到期时间");
        assertThat(r.assignments(u1, d1, a1).get("roleIds")).isEqualTo(List.of());
    }

    @Test void roleMemberAdditionRecordsWhoGrantedWhichMember() {
        r.roleMembers(r2, d1, a1, List.of(u2), ACTOR);
        assertThat(db.queryForObject("SELECT granted_by FROM user_roles WHERE user_id=? AND role_id=?", String.class, u2, r2)).isEqualTo(ACTOR);
        assertThat(db.queryForList("SELECT detail FROM audit_events WHERE actor=? AND action='ROLE_MEMBERS_UPDATED' AND target=?", String.class, ACTOR, "user:" + u2))
                .anySatisfy(detail -> assertThat(detail).contains("writer"));
    }

    @Test void webhookListReturnsArraysAndPermissionMutationsAreAnnounced() {
        long hook = requestId(webhooks.create(a1, new Requests.Webhook("变更", "http://127.0.0.1:1/hook", null, List.of("*"), true), ACTOR));
        assertThat(webhooks.webhooks(a1).get(0).get("events")).isEqualTo(List.of("*"));
        webhooks.update(hook, new Requests.Webhook("变更", "http://127.0.0.1:1/hook", null, List.of("permission.updated", "permission.deleted", "role.enabled", "role.deleted", "user.contexts.revoked"), true), ACTOR);
        assertThat((List<?>) webhooks.webhooks(a1).get(0).get("events")).hasSize(5);
        long permission = db.queryForObject("SELECT id FROM permissions WHERE department_id=? AND application_id=? AND code='item:write'", Long.class, d1, a1);
        r.permission(new Requests.Permission("Write", "item:edit", "item", "edit"), permission, d1, a1, ACTOR);
        r.deletePermission(permission, d1, a1, ACTOR);
        r.roleStatus(r2, d1, a1, true, ACTOR);
        r.deleteRole(r2, d1, a1, ACTOR);
        r.revokeUserContexts(u1, ACTOR, null);
        assertThat(db.queryForList("SELECT event_type FROM webhook_deliveries WHERE webhook_id=? ORDER BY id", String.class, hook))
                .containsExactly("permission.updated", "permission.deleted", "role.enabled", "role.deleted", "user.contexts.revoked");
    }

    private Instant expiresAt(long user, long role) {
        var at = db.queryForObject("SELECT expires_at FROM user_roles WHERE user_id=? AND role_id=?", Timestamp.class, user, role);
        return at == null ? null : at.toInstant();
    }
    private String grantId(long user, long role) {
        return db.queryForObject("SELECT grant_id FROM user_roles WHERE user_id=? AND role_id=?", String.class, user, role);
    }
}
