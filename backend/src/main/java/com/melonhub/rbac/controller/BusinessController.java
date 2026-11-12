package com.melonhub.rbac.controller;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.ApplicationCaller;
import com.melonhub.rbac.service.ContextService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** API for business systems. The API key fixes the application, so every call stays inside that application's domains. */
@RestController
@RequestMapping("/api/v1")
public class BusinessController {
    private final RbacService r;
    private final ContextService contexts;
    private final GovernanceService governance;
    public BusinessController(RbacService r, ContextService contexts, GovernanceService governance) {
        this.r = r; this.contexts = contexts; this.governance = governance;
    }

    private static long app(HttpServletRequest q) { return ApplicationCaller.of(q).applicationId(); }
    private static String actor(HttpServletRequest q) { return ApplicationCaller.of(q).actor(); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    @GetMapping("/departments") Object departments(HttpServletRequest q) { app(q); return r.departments(null); }
    @GetMapping("/departments/{dept}/users") Object users(@PathVariable long dept, HttpServletRequest q) { app(q); return r.departmentUsers(dept); }
    @GetMapping("/departments/{dept}/roles") Object roles(@PathVariable long dept, HttpServletRequest q) { return r.roles(dept, app(q)); }
    @PostMapping("/departments/{dept}/roles") Object role(@PathVariable long dept, @Valid @RequestBody Requests.Role b, HttpServletRequest q) {
        return Map.of("id", r.role(b, null, dept, app(q), actor(q)));
    }
    @PutMapping("/departments/{dept}/roles/{id}") Object role(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody Requests.Role b, HttpServletRequest q) {
        return Map.of("id", r.role(b, id, dept, app(q), actor(q)));
    }
    @PatchMapping("/departments/{dept}/roles/{id}/status") Object status(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody Requests.Enabled b, HttpServletRequest q) {
        r.roleStatus(id, dept, app(q), b.enabled(), actor(q)); return ok();
    }
    @DeleteMapping("/departments/{dept}/roles/{id}") Object deleteRole(@PathVariable long dept, @PathVariable long id, HttpServletRequest q) {
        r.deleteRole(id, dept, app(q), actor(q)); return ok();
    }
    @GetMapping("/departments/{dept}/permissions") Object permissions(@PathVariable long dept, HttpServletRequest q) { return r.permissions(dept, app(q)); }
    @PostMapping("/departments/{dept}/permissions") Object permission(@PathVariable long dept, @Valid @RequestBody Requests.Permission b, HttpServletRequest q) {
        return Map.of("id", r.permission(b, null, dept, app(q), actor(q)));
    }
    @PutMapping("/departments/{dept}/permissions/{id}") Object permission(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody Requests.Permission b, HttpServletRequest q) {
        return Map.of("id", r.permission(b, id, dept, app(q), actor(q)));
    }
    @DeleteMapping("/departments/{dept}/permissions/{id}") Object deletePermission(@PathVariable long dept, @PathVariable long id, HttpServletRequest q) {
        r.deletePermission(id, dept, app(q), actor(q)); return ok();
    }
    @PutMapping("/departments/{dept}/roles/{id}/permissions") Object rolePermissions(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody Requests.Ids b, HttpServletRequest q) {
        r.rolePermissions(id, dept, app(q), b.ids(), actor(q)); return ok();
    }
    @GetMapping("/departments/{dept}/users/{id}/roles") Object userRoles(@PathVariable long dept, @PathVariable long id, HttpServletRequest q) {
        return r.assignments(id, dept, app(q));
    }
    @PutMapping("/departments/{dept}/users/{id}/roles") Object userRoles(@PathVariable long dept, @PathVariable long id, @Valid @RequestBody Requests.UserRoles b, HttpServletRequest q) {
        r.userRoles(id, dept, app(q), b.ids(), b.expiresInDays(), b.expiries(), actor(q)); return ok();
    }
    // ---- employee self-service (submitted by the business system on the user's behalf) ----
    @PostMapping("/access-requests") Object submitRequest(@Valid @RequestBody Requests.AccessRequestSubmit b, HttpServletRequest q) {
        return governance.submit(app(q), b, actor(q));
    }
    @GetMapping("/access-requests") Object myRequests(@RequestParam String externalId, HttpServletRequest q) {
        return governance.myRequests(app(q), externalId);
    }
    @PostMapping("/access-requests/{id}/cancel") Object cancelRequest(@PathVariable long id, @Valid @RequestBody Requests.AccessRequestCancel b,
                                                                      HttpServletRequest q) {
        governance.cancelOwn(app(q), id, b.externalId(), actor(q));
        return ok();
    }
    @GetMapping("/users/access") Object myAccess(@RequestParam String externalId, @RequestParam long departmentId, HttpServletRequest q) {
        return governance.myAccess(app(q), externalId, departmentId);
    }
    @PostMapping("/contexts") Object issue(@Valid @RequestBody Requests.Context b, HttpServletRequest q) { return contexts.issue(app(q), b, actor(q)); }
    @GetMapping("/contexts/effective") Object effective(@RequestHeader("X-Context-Token") String token, HttpServletRequest q) { return contexts.effective(app(q), token); }
    @PostMapping("/contexts/check") Object check(@RequestHeader("X-Context-Token") String token, @Valid @RequestBody Requests.Check b, HttpServletRequest q) {
        return contexts.check(app(q), token, b.permissions());
    }
    @DeleteMapping("/contexts") Object revoke(@RequestHeader("X-Context-Token") String token, HttpServletRequest q) {
        contexts.revoke(app(q), token, actor(q)); return ok();
    }
}
