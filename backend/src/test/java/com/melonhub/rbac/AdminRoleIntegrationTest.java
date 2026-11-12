package com.melonhub.rbac;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.Credentials;
import com.melonhub.rbac.service.AuthService;
import com.melonhub.rbac.service.RbacService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Administrator roles configured as sets of console functions. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ContextConfiguration(initializers=CloudTestDatabaseGuard.class)
class AdminRoleIntegrationTest {
    private static final String PASSWORD = "Role-Test-Passw0rd";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @Autowired RbacService r;
    String suffix, root;

    @BeforeEach void fixture() throws Exception {
        suffix = Credentials.token().substring(0, 8).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "X");
        root = login(account("root", "SUPER_ADMIN", null));
    }

    private String account(String name, String role, List<Long> applications) {
        String username = name.toLowerCase(Locale.ROOT) + "-" + suffix.toLowerCase(Locale.ROOT);
        auth.createAccount(new Requests.Account(username, null, role, PASSWORD, true, applications), TestAdmins.superAdmin("role-test"));
        return username;
    }
    private String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }
    private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request);
    }
    private String role(String name, List<String> functions) throws Exception {
        String code = name + "_" + suffix;
        as(root, post("/api/admin/admin-roles"), new Requests.AdminRoleCreate(code, name, "", functions)).andExpect(status().isOk());
        return code;
    }

    @Test void customRoleGrantsExactlyItsFunctionsAndChangesApplyOnTheNextRequest() throws Exception {
        String code = role("AUDIT_ONLY", List.of("audit.view"));
        String token = login(account("auditonly", code, null));
        as(token, get("/api/auth/me"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(code)).andExpect(jsonPath("$.roleName").value("AUDIT_ONLY"))
                .andExpect(jsonPath("$.functions.length()").value(1)).andExpect(jsonPath("$.functions[0]").value("audit.view"));
        as(token, get("/api/admin/audit"), null).andExpect(status().isOk());
        as(token, get("/api/admin/departments"), null).andExpect(status().isForbidden());

        as(root, put("/api/admin/admin-roles/" + code), new Requests.AdminRoleUpdate("AUDIT_ONLY", "", List.of("audit.view", "org.view")))
                .andExpect(status().isOk());
        as(token, get("/api/admin/departments"), null).andExpect(status().isOk());
        as(token, post("/api/admin/departments"), Map.of("name", "x", "code", "x-" + suffix)).andExpect(status().isForbidden());
        as(root, get("/api/admin/admin-roles"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + code + "')].accountCount").value(1));
    }

    @Test void builtinRolesCannotBeChangedAndRolesInUseCannotBeDeleted() throws Exception {
        as(root, put("/api/admin/admin-roles/ADMIN"), new Requests.AdminRoleUpdate("平台管理员", "", List.of("org.view"))).andExpect(status().isBadRequest());
        as(root, delete("/api/admin/admin-roles/AUDITOR"), null).andExpect(status().isBadRequest());
        String code = role("TEMP", List.of("org.view"));
        String user = account("temp", code, null);
        as(root, delete("/api/admin/admin-roles/" + code), null).andExpect(status().isConflict());
        as(root, delete("/api/admin/accounts/" + user), null).andExpect(status().isOk());
        as(root, delete("/api/admin/admin-roles/" + code), null).andExpect(status().isOk());
        as(root, delete("/api/admin/admin-roles/" + code), null).andExpect(status().isNotFound());
    }

    @Test void operatorsCannotGrantFunctionsTheyDoNotHold() throws Exception {
        String managerRole = role("ROLE_EDITOR", List.of("role.manage", "domain.view"));
        String manager = login(account("editor", managerRole, null));
        as(manager, post("/api/admin/admin-roles"), new Requests.AdminRoleCreate("WIDER_" + suffix, "wider", "", List.of("audit.view")))
                .andExpect(status().isForbidden());
        as(manager, post("/api/admin/admin-roles"), new Requests.AdminRoleCreate("NARROW_" + suffix, "narrow", "", List.of("domain.view")))
                .andExpect(status().isOk());
        as(root, post("/api/admin/admin-roles"), new Requests.AdminRoleCreate("BAD_" + suffix, "bad", "", List.of("no.such.function")))
                .andExpect(status().isBadRequest());
        as(root, post("/api/admin/accounts"), Map.of("username", "unknown-role-" + suffix.toLowerCase(Locale.ROOT), "role", "NO_SUCH_ROLE", "password", PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test void applicationScopeBelongsToTheAccountNotTheRole() throws Exception {
        long dept = r.department(new Requests.Department("Scope", "scope-" + suffix), null, "test");
        long a1 = ((Number) r.application(new Requests.Application("ScopeOne", "sa1-" + suffix, ""), "test").get("id")).longValue();
        long a2 = ((Number) r.application(new Requests.Application("ScopeTwo", "sa2-" + suffix, ""), "test").get("id")).longValue();
        String limited = login(account("limited", "ADMIN", List.of(a1)));
        as(limited, get("/api/auth/me"), null).andExpect(jsonPath("$.allApplications").value(false)).andExpect(jsonPath("$.applicationIds[0]").value(a1));
        as(limited, get("/api/admin/roles").param("departmentId", "" + dept).param("applicationId", "" + a1), null).andExpect(status().isOk());
        as(limited, get("/api/admin/roles").param("departmentId", "" + dept).param("applicationId", "" + a2), null).andExpect(status().isForbidden());
        as(limited, get("/api/admin/applications"), null).andExpect(jsonPath("$.length()").value(1));
        // A platform function stays platform-wide: the limit only applies to per-application operations.
        as(limited, post("/api/admin/departments"), Map.of("name", "d", "code", "d-" + suffix)).andExpect(status().isOk());
    }
}
