package com.melonhub.rbac.controller;

import static com.melonhub.rbac.security.AdminFunction.*;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.RequiresFunction;
import com.melonhub.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * Console API. Every handler resolves the operator itself, so a request that somehow skipped the filter still
 * fails closed, and application-scoped administrators only see or change their own applications.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final RbacService r;
    public AdminController(RbacService r) { this.r = r; }

    private static Collection<Long> visible(AdminPrincipal principal) { return principal.visibleApplications(); }
    private static int page(int page) { return Math.max(1, page); }
    private static int size(int size) { return Math.min(200, Math.max(1, size)); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    // ---- departments ----
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/departments") Object departments(HttpServletRequest req) { AdminAccess.of(req); return r.departments(); }
    @RequiresFunction(DEPARTMENT_MANAGE)
    @PostMapping("/departments") Object department(@Valid @RequestBody Requests.Department b, HttpServletRequest req) {
        return Map.of("id", r.department(b, null, AdminAccess.of(req).actor()));
    }
    @RequiresFunction(DEPARTMENT_MANAGE)
    @PutMapping("/departments/{id}") Object department(@PathVariable long id, @Valid @RequestBody Requests.Department b, HttpServletRequest req) {
        return Map.of("id", r.department(b, id, AdminAccess.of(req).actor()));
    }
    @RequiresFunction(DEPARTMENT_MANAGE)
    @DeleteMapping("/departments/{id}") Object deleteDepartment(@PathVariable long id, HttpServletRequest req) {
        r.deleteDepartment(id, AdminAccess.of(req).actor()); return ok();
    }

    // ---- applications and API keys ----
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/applications") Object applications(HttpServletRequest req) { return r.applications(visible(AdminAccess.of(req))); }
    @RequiresFunction(APPLICATION_MANAGE)
    @PostMapping("/applications") Object application(@Valid @RequestBody Requests.Application b, HttpServletRequest req) {
        return r.application(b, AdminAccess.of(req).actor());
    }
    @RequiresFunction(APPLICATION_MANAGE)
    @PutMapping("/applications/{id}") Object application(@PathVariable long id, @Valid @RequestBody Requests.Application b, HttpServletRequest req) {
        return Map.of("id", r.updateApplication(id, b, AdminAccess.of(req).actor()));
    }
    @RequiresFunction(APPLICATION_MANAGE)
    @PatchMapping("/applications/{id}/status") Object applicationStatus(@PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.applicationStatus(id, b.enabled(), AdminAccess.of(req).actor()); return ok();
    }
    @RequiresFunction(APPLICATION_MANAGE)
    @DeleteMapping("/applications/{id}") Object deleteApplication(@PathVariable long id, HttpServletRequest req) {
        r.deleteApplication(id, AdminAccess.of(req).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/applications/{id}/keys") Object keys(@PathVariable long id, HttpServletRequest req) { AdminAccess.application(req, id); return r.keys(id); }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/applications/{id}/keys") Object createKey(@PathVariable long id, @Valid @RequestBody Requests.ApiKey b, HttpServletRequest req) {
        return r.createKey(id, b, AdminAccess.application(req, id).actor());
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @DeleteMapping("/applications/{id}/keys/{keyId}") Object revokeKey(@PathVariable long id, @PathVariable long keyId, HttpServletRequest req) {
        r.revokeKey(id, keyId, AdminAccess.application(req, id).actor()); return ok();
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/applications/{id}/rotate-key") Object rotate(@PathVariable long id, HttpServletRequest req) {
        return Map.of("apiKey", r.rotateKey(id, AdminAccess.application(req, id).actor()));
    }

    // ---- users ----
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/users") Object users(@RequestParam(required=false) String keyword, @RequestParam(required=false) Long departmentId,
                                       @RequestParam(required=false) Boolean enabled, @RequestParam(defaultValue="1") int page,
                                       @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.of(req);
        return r.users(keyword, departmentId, enabled, page(page), size(size));
    }
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/users/export") Object exportUsers(@RequestParam(required=false) String keyword,
                                                     @RequestParam(required=false) Long departmentId,
                                                     @RequestParam(required=false) Boolean enabled, HttpServletRequest req) {
        AdminAccess.of(req);
        return r.users(keyword, departmentId, enabled, 1, Integer.MAX_VALUE);
    }
    @RequiresFunction(USER_MANAGE)
    @PostMapping("/users") Object user(@Valid @RequestBody Requests.User b, HttpServletRequest req) {
        return Map.of("id", r.user(b, null, AdminAccess.of(req).actor()));
    }
    @RequiresFunction(USER_MANAGE)
    @PutMapping("/users/{id}") Object user(@PathVariable long id, @Valid @RequestBody Requests.User b, HttpServletRequest req) {
        return Map.of("id", r.user(b, id, AdminAccess.of(req).actor()));
    }
    @RequiresFunction(USER_MANAGE)
    @PatchMapping("/users/{id}/status") Object userStatus(@PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.userStatus(id, b.enabled(), AdminAccess.of(req).actor()); return ok();
    }
    @RequiresFunction(USER_MANAGE)
    @DeleteMapping("/users/{id}") Object deleteUser(@PathVariable long id, HttpServletRequest req) {
        r.deleteUser(id, AdminAccess.of(req).actor()); return ok();
    }
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/users/{id}/access") Object access(@PathVariable long id, HttpServletRequest req) { return r.userAccess(id, visible(AdminAccess.of(req))); }
    @RequiresFunction(USER_MANAGE)
    @DeleteMapping("/users/{id}/contexts") Object revokeContexts(@PathVariable long id, HttpServletRequest req) {
        return Map.of("revoked", r.revokeUserContexts(id, AdminAccess.of(req).actor()));
    }

    // ---- one isolation domain (department x application) ----
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/roles") Object roles(@RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.application(req, applicationId); return r.roles(departmentId, applicationId);
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PostMapping("/roles") Object role(@RequestParam long departmentId, @RequestParam long applicationId, @Valid @RequestBody Requests.Role b, HttpServletRequest req) {
        return Map.of("id", r.role(b, null, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()));
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/roles/{id}") Object role(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                           @Valid @RequestBody Requests.Role b, HttpServletRequest req) {
        return Map.of("id", r.role(b, id, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()));
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PatchMapping("/roles/{id}/status") Object roleStatus(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                                          @Valid @RequestBody Requests.Enabled b, HttpServletRequest req) {
        r.roleStatus(id, departmentId, applicationId, b.enabled(), AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @DeleteMapping("/roles/{id}") Object deleteRole(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        r.deleteRole(id, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/roles/{id}/permissions") Object rolePermissions(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                                                  @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        r.rolePermissions(id, departmentId, applicationId, b.ids(), AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/roles/{id}/members") Object roleMembers(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.application(req, applicationId); return r.roleMembers(id, departmentId, applicationId);
    }
    @RequiresFunction(DOMAIN_ASSIGN)
    @PutMapping("/roles/{id}/members") Object roleMembers(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                                          @Valid @RequestBody Requests.Ids b, HttpServletRequest req) {
        r.roleMembers(id, departmentId, applicationId, b.ids(), AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/permissions") Object permissions(@RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.application(req, applicationId); return r.permissions(departmentId, applicationId);
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PostMapping("/permissions") Object permission(@RequestParam long departmentId, @RequestParam long applicationId,
                                                   @Valid @RequestBody Requests.Permission b, HttpServletRequest req) {
        return Map.of("id", r.permission(b, null, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()));
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @PutMapping("/permissions/{id}") Object permission(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                                       @Valid @RequestBody Requests.Permission b, HttpServletRequest req) {
        return Map.of("id", r.permission(b, id, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()));
    }
    @RequiresFunction(DOMAIN_CONFIGURE)
    @DeleteMapping("/permissions/{id}") Object deletePermission(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        r.deletePermission(id, departmentId, applicationId, AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.application(req, applicationId); return r.assignments(id, departmentId, applicationId);
    }
    @RequiresFunction(DOMAIN_ASSIGN)
    @PutMapping("/users/{id}/roles") Object userRoles(@PathVariable long id, @RequestParam long departmentId, @RequestParam long applicationId,
                                                      @Valid @RequestBody Requests.UserRoles b, HttpServletRequest req) {
        r.userRoles(id, departmentId, applicationId, b.ids(), b.expiresInDays(), b.expiries(), AdminAccess.application(req, applicationId).actor()); return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/assignments") Object assignments(@RequestParam long departmentId, @RequestParam long applicationId, @RequestParam(required=false) String keyword,
                                                   @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.application(req, applicationId);
        return r.domainAssignments(departmentId, applicationId, keyword, page(page), size(size));
    }
    @RequiresFunction(DOMAIN_ASSIGN)
    @PostMapping("/assignments/batch") Object addUserRoles(@RequestParam long departmentId, @RequestParam long applicationId,
                                                           @Valid @RequestBody Requests.BatchUserRoles b, HttpServletRequest req) {
        return r.addUserRoles(departmentId, applicationId, b, AdminAccess.application(req, applicationId).actor());
    }
    @RequiresFunction(DOMAIN_TRANSFER)
    @PostMapping("/domains/copy") Object copy(@Valid @RequestBody Requests.DomainCopy b, HttpServletRequest req) {
        return r.copyDomain(b, AdminAccess.application(req, b.applicationId()).actor());
    }
    @RequiresFunction(DOMAIN_VIEW)
    @PostMapping("/simulate") Object simulate(@Valid @RequestBody Requests.Simulation b, HttpServletRequest req) {
        AdminAccess.application(req, b.applicationId()); return r.simulate(b);
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/overview") Object overview(@RequestParam long departmentId, @RequestParam long applicationId, HttpServletRequest req) {
        AdminAccess.application(req, applicationId); return r.overview(departmentId, applicationId);
    }

    // ---- audit ----
    @RequiresFunction(AUDIT_VIEW)
    @GetMapping("/audit/export") Object exportAudit(@RequestParam(required=false) Long departmentId, @RequestParam(required=false) Long applicationId,
                                                    @RequestParam(required=false) String actor, @RequestParam(required=false) String action,
                                                    @RequestParam(required=false) String keyword,
                                                    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                                                    HttpServletRequest req) {
        return r.events(departmentId, applicationId, visible(AdminAccess.of(req)), actor, action, keyword, from, to, 1, 5000);
    }
    @RequiresFunction(AUDIT_VIEW)
    @GetMapping("/audit") Object audit(@RequestParam(required=false) Long departmentId, @RequestParam(required=false) Long applicationId,
                                       @RequestParam(required=false) String actor, @RequestParam(required=false) String action,
                                       @RequestParam(required=false) String keyword,
                                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        return r.events(departmentId, applicationId, visible(principal), actor, action, keyword, from, to, page(page), size(size));
    }
}
