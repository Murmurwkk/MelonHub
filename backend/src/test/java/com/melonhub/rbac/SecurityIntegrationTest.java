package com.melonhub.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dao.AuthDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.RbacService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class SecurityIntegrationTest {
    private static final String PASSWORD = "Test-Only-Passw0rd";
    private static final AdminPrincipal ROOT = TestAdmins.superAdmin("security-test-root");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RbacService r;
    @Autowired AuthService auth;
    @Autowired AuthDao authDao;
    @Autowired JdbcTemplate db;
    @Value("${melonhub.admin-username}") String bootstrap;
    long d1, a1, a2;
    String suffix;

    @BeforeEach void fixture() {
        suffix = Credentials.token().substring(0, 10);
        d1 = r.department(new Requests.Department("Security", "sec-" + suffix), null, "test");
        a1 = ((Number) r.application(new Requests.Application("SecOne", "s1-" + suffix, ""), "test").get("id")).longValue();
        a2 = ((Number) r.application(new Requests.Application("SecTwo", "s2-" + suffix, ""), "test").get("id")).longValue();
    }

    private String account(String role, List<Long> applications) {
        String name = role.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + suffix;
        auth.createAccount(new Requests.Account(name, null, role, PASSWORD, true, applications), ROOT);
        return name;
    }
    private ResultActions attempt(String user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", user, "password", password))));
    }
    private String login(String user) throws Exception {
        String body = attempt(user, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }

    @Test void batchGrantHonorsApplicationWriteScope() throws Exception {
        String manager = account(AdminPrincipal.APP_ADMIN, List.of(a1));
        String bearer = "Bearer " + auth.login(new Requests.Login(manager, PASSWORD)).get("token");
        long user = r.user(new Requests.User("Batch", "batch-" + suffix, "", List.of(d1)), null, "test");
        long role = r.role(new Requests.Role("Batch", "batch", ""), null, d1, a1, "test");
        String body = json(new Requests.BatchUserRoles(List.of(user), List.of(role), null));
        mvc.perform(post("/api/admin/assignments/batch").header("Authorization", bearer)
                .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(a2))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/assignments/batch").header("Authorization", bearer)
                .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(a1))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.grantsAdded").value(1));
        String auditor = account(AdminPrincipal.AUDITOR, List.of());
        String readOnly = "Bearer " + auth.login(new Requests.Login(auditor, PASSWORD)).get("token");
        mvc.perform(post("/api/admin/assignments/batch").header("Authorization", readOnly)
                .param("departmentId", String.valueOf(d1)).param("applicationId", String.valueOf(a1))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test void exportsIncludeAllFilteredUsersAndOnlyVisibleAuditRows() throws Exception {
        String manager = account(AdminPrincipal.APP_ADMIN, List.of(a1));
        String bearer = "Bearer " + auth.login(new Requests.Login(manager, PASSWORD)).get("token");
        var rows = new java.util.ArrayList<Object[]>();
        for (int i = 0; i < 205; i++) rows.add(new Object[]{"Export", "export-" + suffix + "-" + i, ""});
        db.batchUpdate("INSERT INTO users(name,external_id,email) VALUES (?,?,?)", rows);
        mvc.perform(get("/api/admin/users/export").header("Authorization", bearer).param("keyword", "export-" + suffix))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(205)).andExpect(jsonPath("$.items.length()").value(205));
        r.audit("export-" + suffix, "EXPORT_TEST", "visible", d1, a1, "");
        r.audit("export-" + suffix, "EXPORT_TEST", "hidden", d1, a2, "");
        mvc.perform(get("/api/admin/audit/export").header("Authorization", bearer).param("actor", "export-" + suffix))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].target").value("visible"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/api;x=1/admin/users", "/%61pi/admin/users", "/api/admin;x/users", "/api/%61dmin/departments",
            "/api/admin/../admin/users", "/api//admin/users", "/api/./admin/users", "/api/admin/users/", "/api/admin/users;jsessionid=1"})
    void nonCanonicalPathsCannotBypassAuthentication(String path) throws Exception {
        mvc.perform(get(URI.create(path))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
    }

    @Test void everyApiPathExceptHealthAndLoginNeedsCredentials() throws Exception {
        mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/API/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/departments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test void responsesCarryRequestIdAndSecurityHeaders() throws Exception {
        mvc.perform(get("/api/health").header("X-Request-Id", "trace-12345678"))
            .andExpect(header().string("X-Request-Id", "trace-12345678"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/admin/users").header("X-Request-Id", "bad id with spaces"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.requestId").exists())
            .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad id with spaces")));
    }

    @Test void auditorCanReadButNotWrite() throws Exception {
        String token = login(account(AdminPrincipal.AUDITOR, null));
        mvc.perform(get("/api/admin/users").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.total").exists());
        mvc.perform(get("/api/admin/audit").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/departments").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "x", "code", "x-" + suffix)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/roles").param("departmentId", "" + d1).param("applicationId", "" + a1).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "R", "code", "r")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/accounts").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test void applicationAdministratorIsConfinedToAssignedApplications() throws Exception {
        String token = login(account(AdminPrincipal.APP_ADMIN, List.of(a1)));
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(jsonPath("$.applicationIds[0]").value(a1));
        mvc.perform(get("/api/admin/applications").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(a1));
        mvc.perform(post("/api/admin/roles").param("departmentId", "" + d1).param("applicationId", "" + a1).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "R", "code", "r")))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/roles").param("departmentId", "" + d1).param("applicationId", "" + a2).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "R", "code", "r")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/roles").param("departmentId", "" + d1).param("applicationId", "" + a2).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").param("applicationId", "" + a2).header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/admin/applications/" + a2 + "/keys").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "k")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/applications/" + a1 + "/keys").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "k")))).andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").exists());
        mvc.perform(post("/api/admin/departments").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "x", "code", "x-" + suffix)))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/applications/" + a1 + "/status").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
    }

    @Test void repeatedFailuresLockAnAccountUntilUnlocked() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        for (int i = 1; i <= 4; i++) attempt(user, "wrong-password-" + i).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        attempt(user, "wrong-password-5").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        attempt(user, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        auth.unlock(user, ROOT);
        login(user);
        attempt("missing-" + suffix, PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test void disablingAnAccountEndsItsSessionsAndBlocksLogin() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        String token = login(user);
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        auth.updateAccount(user, new Requests.AccountUpdate(null, AdminPrincipal.ADMIN, false, null), ROOT);
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        attempt(user, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test void idleSessionsExpireAndLogoutEndsTheSession() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        String idle = login(user), active = login(user);
        db.update("UPDATE admin_sessions SET last_seen_at=TIMESTAMPADD(MINUTE,-31,CURRENT_TIMESTAMP) WHERE token_hash=?", Credentials.hash(idle.substring(7)));
        mvc.perform(get("/api/auth/me").header("Authorization", idle)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header("Authorization", active)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", active)).andExpect(status().isUnauthorized());
    }

    @Test void changingOwnPasswordSignsOutOtherSessions() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        String first = login(user), second = login(user);
        mvc.perform(put("/api/auth/password").header("Authorization", first).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", "not-the-password", "newPassword", PASSWORD + "-next")))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/password").header("Authorization", first).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD + "-next")))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", second)).andExpect(status().isUnauthorized());
        attempt(user, PASSWORD + "-next").andExpect(status().isOk());
    }

    @Test void accountManagementIsReservedForSuperAdministrators() throws Exception {
        String admin = login(account(AdminPrincipal.ADMIN, null));
        mvc.perform(get("/api/admin/accounts").header("Authorization", admin)).andExpect(status().isForbidden());
        String root = login(account(AdminPrincipal.SUPER_ADMIN, null));
        mvc.perform(get("/api/admin/accounts").header("Authorization", root)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/accounts").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "short-" + suffix, "role", "ADMIN", "password", "too-short")))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/accounts/super-admin-" + suffix).header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("role", "ADMIN", "enabled", true)))).andExpect(status().isBadRequest());
    }

    @Test void theLastEnabledSuperAdministratorCannotBeRemoved() {
        assertThatThrownBy(() -> auth.updateAccount(bootstrap, new Requests.AccountUpdate(null, AdminPrincipal.ADMIN, true, null), ROOT))
            .isInstanceOf(ApiException.class).hasMessageContaining("超级管理员");
        assertThatThrownBy(() -> auth.deleteAccount(bootstrap, ROOT)).isInstanceOf(ApiException.class).hasMessageContaining("超级管理员");
    }

    @Test void credentialChangesEndSessionsIncludingOneStillBeingCreated() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        String token = login(user);
        var checked = authDao.account(user).orElseThrow();
        auth.resetPassword(user, new Requests.PasswordReset(PASSWORD + "-reset"), ROOT);
        mvc.perform(get("/api/auth/me").header("Authorization", token)).andExpect(status().isUnauthorized());
        // A login that verified the old password just before the reset cannot create its session afterwards.
        assertThat(authDao.createSession(Credentials.hash(Credentials.token()), checked, Instant.now().plusSeconds(600), "127.0.0.1")).isZero();
        // Nor does a session row written with the old credential version authenticate.
        String late = Credentials.token();
        db.update("INSERT INTO admin_sessions(token_hash,username,expires_at,source_ip,credential_version) VALUES (?,?,TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP),'127.0.0.1',?)",
                Credentials.hash(late), user, checked.credentialVersion());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + late)).andExpect(status().isUnauthorized());
        String body = attempt(user, PASSWORD + "-reset").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = "Bearer " + mapper.readTree(body).get("token").asText();
        var current = authDao.account(user).orElseThrow();
        auth.updateAccount(user, new Requests.AccountUpdate(null, AdminPrincipal.AUDITOR, true, null), ROOT);
        mvc.perform(get("/api/auth/me").header("Authorization", fresh)).andExpect(status().isUnauthorized());
        assertThat(authDao.createSession(Credentials.hash(Credentials.token()), current, Instant.now().plusSeconds(600), "127.0.0.1")).isZero();
    }

    @Test void aLoginCheckedAgainstADeletedAccountCannotOpenASessionForItsNamesake() throws Exception {
        String user = account(AdminPrincipal.ADMIN, null);
        var checked = authDao.account(user).orElseThrow();
        auth.deleteAccount(user, ROOT);
        auth.createAccount(new Requests.Account(user, null, AdminPrincipal.AUDITOR, PASSWORD, true, null), ROOT);
        // Same name and same credential version: only the password hash tells the two accounts apart.
        assertThat(authDao.account(user).orElseThrow().credentialVersion()).isEqualTo(checked.credentialVersion());
        assertThat(authDao.createSession(Credentials.hash(Credentials.token()), checked, Instant.now().plusSeconds(600), "127.0.0.1")).isZero();
        attempt(user, PASSWORD).andExpect(status().isOk());
    }

    @Test void accountNamesWithAtSignsAndDotsWorkInAccountPaths() throws Exception {        String root = login(account(AdminPrincipal.SUPER_ADMIN, null));
        String name = "ops." + suffix + "@corp.example";
        mvc.perform(post("/api/admin/accounts").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", name, "role", "ADMIN", "password", PASSWORD)))).andExpect(status().isOk());
        mvc.perform(put("/api/admin/accounts/" + name + "/password").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("password", PASSWORD + "-reset")))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/accounts/" + name + "/unlock").header("Authorization", root)).andExpect(status().isOk());
        attempt(name, PASSWORD + "-reset").andExpect(status().isOk());
        mvc.perform(delete("/api/admin/accounts/" + name).header("Authorization", root)).andExpect(status().isOk());
        // A leading dot would make a path segment the guard refuses, so such a name is never accepted.
        mvc.perform(post("/api/admin/accounts").header("Authorization", root).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", ".hidden-" + suffix, "role", "ADMIN", "password", PASSWORD)))).andExpect(status().isBadRequest());
    }

    @Test void passwordsBeyondSeventyTwoBytesAreRefusedInsteadOfTruncated() throws Exception {
        String longest = "a".repeat(72);
        String user = "bytes-" + suffix;
        auth.createAccount(new Requests.Account(user, null, AdminPrincipal.ADMIN, longest, true, null), ROOT);
        String body = attempt(user, longest).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + mapper.readTree(body).get("token").asText();
        // BCrypt only reads 72 bytes; a longer password must not sign in as its prefix.
        attempt(user, longest + "b").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        String wide = "密".repeat(25);
        mvc.perform(put("/api/auth/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", longest, "newPassword", wide)))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> auth.createAccount(new Requests.Account("wide-" + suffix, null, AdminPrincipal.ADMIN, wide, true, null), ROOT))
                .isInstanceOf(ApiException.class).hasMessageContaining("72 字节");
    }

    @Test void bulkImportReportsInvalidRowsInsteadOfRejectingTheRequest() throws Exception {
        String token = login(account(AdminPrincipal.ADMIN, null));
        var rows = java.util.Arrays.asList(
                Map.of("name", "导入一", "externalId", "imp-" + suffix, "departmentCodes", List.of("sec-" + suffix)),
                Map.of("name", "", "externalId", "imp2-" + suffix, "departmentCodes", List.of("sec-" + suffix)),
                Map.of("name", "缺部门", "externalId", "imp3-" + suffix),
                null);
        mvc.perform(post("/api/admin/users/import").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("users", rows))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed.length()").value(3))
                .andExpect(jsonPath("$.failed[0].line").value(2))
                .andExpect(jsonPath("$.failed[0].error").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(jsonPath("$.failed[1].error").value(org.hamcrest.Matchers.containsString("departmentCodes")))
                .andExpect(jsonPath("$.failed[2].line").value(4));
    }
}
