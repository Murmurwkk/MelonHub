package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.security.Totp;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Races that only show up when several connections commit for real, so there is no test transaction here.
 * Every row a test creates is removed in its finally block.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class ConcurrencyIntegrationTest {
    private static final String ACTOR = "race-test";
    private static final AdminPrincipal ROOT = TestAdmins.superAdmin(ACTOR);
    @Autowired AuthService auth;
    @Autowired RbacService r;
    @Autowired WebhookService webhooks;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;

    @Test void concurrentLoginsCannotShareOneTotpCode() throws Exception {
        String username = "mfa-race-" + Credentials.token().substring(0, 8);
        String password = "mfa-password-123";
        auth.createAccount(new Requests.Account(username, "并发登录", "AUDITOR", password, true, null), ROOT);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var principal = TestAdmins.self(username, "并发登录", "AUDITOR");
            String secret = (String) auth.setupMfa(principal).get("secret");
            auth.confirmMfa(principal, Totp.codeAt(secret, Totp.currentStep()));
            String code = Totp.codeAt(secret, Totp.currentStep() + 1);
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) attempts.add(pool.submit(() -> {
                start.await();
                try {
                    auth.login(new Requests.Login(username, password, code));
                    return "OK";
                } catch (ApiException e) {
                    return e.code();
                }
            }));
            start.countDown();
            var outcomes = new ArrayList<String>();
            for (var attempt : attempts) outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsOnlyOnce("OK");
            assertThat(outcomes.stream().filter(outcome -> !outcome.equals("OK")).toList()).hasSize(3).containsOnly("MFA_INVALID");
        } finally {
            pool.shutdownNow();
            db.update("DELETE FROM admin_sessions WHERE username=?", username);
            db.update("DELETE FROM admin_accounts WHERE username=?", username);
            db.update("DELETE FROM audit_events WHERE actor IN (?,?) OR target=?", ACTOR, username, username);
        }
    }

    /**
     * A user who joins the role while a replacement is waiting for the role lock is only seen by the replacement's
     * locking read. Meanwhile a change to that user holds the user and waits for the replacement's assignment
     * locks; locking the user at that point would close a cycle, so the replacement starts over instead.
     */
    @Test void aMemberWhoJoinsDuringAReplacementDoesNotDeadlockIt() throws Exception {
        String suffix = Credentials.token().substring(0, 12);
        long dept = -1, app = -1, stay = -1, late = -1;
        var pool = Executors.newFixedThreadPool(3);
        try {
            dept = r.department(new Requests.Department("Race Dept", "race-d-" + suffix), null, ACTOR);
            app = ((Number) r.application(new Requests.Application("Race App", "race-a-" + suffix, ""), ACTOR).get("id")).longValue();
            stay = r.user(new Requests.User("Stays", "race-stay-" + suffix, "", List.of(dept)), null, ACTOR);
            late = r.user(new Requests.User("Joins late", "race-late-" + suffix, "", List.of(dept)), null, ACTOR);
            long role = r.role(new Requests.Role("Race", "race", ""), null, dept, app, ACTOR);
            long d = dept, a = app, member = stay, joiner = late;
            var joined = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var tx = new TransactionTemplate(transactions);
            Future<?> joining = pool.submit(() -> tx.executeWithoutResult(status -> {
                db.queryForObject("SELECT id FROM roles WHERE id=? FOR UPDATE", Long.class, role);
                db.update("INSERT INTO user_roles(user_id,role_id,department_id,application_id) VALUES (?,?,?,?)", joiner, role, d, a);
                joined.countDown();
                try { commit.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertThat(joined.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> replacing = pool.submit(() -> r.roleMembers(role, d, a, List.of(member), ACTOR));
            Thread.sleep(500);
            Future<?> changing = pool.submit(() -> r.userRoles(joiner, d, a, List.of(role), ACTOR));
            Thread.sleep(500);
            assertThat(replacing.isDone()).as("the replacement waits for the role").isFalse();
            assertThat(changing.isDone()).as("the change to the late member waits for its assignments").isFalse();
            commit.countDown();
            joining.get(10, TimeUnit.SECONDS);
            changing.get(20, TimeUnit.SECONDS);
            replacing.get(20, TimeUnit.SECONDS);
            assertThat(db.queryForList("SELECT user_id FROM user_roles WHERE role_id=?", Long.class, role)).containsExactly(stay);
        } finally {
            pool.shutdownNow();
            db.update("DELETE FROM users WHERE id IN (?,?)", stay, late);
            if (app > 0) {
                db.update("DELETE FROM roles WHERE application_id=?", app);
                db.update("DELETE FROM applications WHERE id=?", app);
            }
            if (dept > 0) db.update("DELETE FROM departments WHERE id=?", dept);
            db.update("DELETE FROM audit_events WHERE actor=? OR application_id=?", ACTOR, app);
        }
    }

    @Test void slowWebhookReceiversNeitherHoldTheSchedulerNorEachOther() throws Exception {        int deliveries = 3;
        var received = new CountDownLatch(deliveries);
        var receivers = Executors.newFixedThreadPool(deliveries);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(receivers);
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        long app = -1;
        try {
            String suffix = Credentials.token().substring(0, 12);
            app = ((Number) r.application(new Requests.Application("Race App", "race-" + suffix, ""), ACTOR).get("id")).longValue();
            long hook = ((Number) webhooks.create(app, new Requests.Webhook("慢接收方", "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
                    null, List.of("*"), true), ACTOR).get("id")).longValue();
            for (int i = 0; i < deliveries; i++) webhooks.test(hook, ACTOR);
            long started = System.nanoTime();
            webhooks.scheduledDeliveries();
            long dispatchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(dispatchMillis).as("the scheduler thread only claims and hands over").isLessThan(1000);
            assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
            long allMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(allMillis).as("deliveries run side by side, not one after another").isLessThan(deliveries * 1500L);
            long deadline = System.currentTimeMillis() + 5000;
            long delivered = 0;
            while (System.currentTimeMillis() < deadline) {
                delivered = db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE webhook_id=? AND status='DELIVERED'", Long.class, hook);
                if (delivered == deliveries) break;
                Thread.sleep(100);
            }
            assertThat(delivered).isEqualTo(deliveries);
        } finally {
            server.stop(0);
            receivers.shutdownNow();
            if (app > 0) db.update("DELETE FROM applications WHERE id=?", app);
            db.update("DELETE FROM audit_events WHERE actor=? OR application_id=?", ACTOR, app);
        }
    }
}
