package com.melonhub.rbac.controller;

import static com.melonhub.rbac.security.AdminFunction.*;

import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.AdminPrincipal;
import com.melonhub.rbac.security.RequiresFunction;
import com.melonhub.rbac.service.AuditSealService;
import com.melonhub.rbac.service.ConsoleService;
import com.melonhub.rbac.service.GovernanceService;
import com.melonhub.rbac.service.RbacService;
import com.melonhub.rbac.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Governance console API: approvals, reviews, webhooks, manifests, bulk import, reports and system status. */
@RestController
@RequestMapping("/api/admin")
public class GovernanceController {
    private final GovernanceService g;
    private final ConsoleService console;
    private final WebhookService webhooks;
    private final AuditSealService auditSeal;
    private final RbacService rbac;

    public GovernanceController(GovernanceService g, ConsoleService console, WebhookService webhooks,
                                AuditSealService auditSeal, RbacService rbac) {
        this.g = g; this.console = console; this.webhooks = webhooks; this.auditSeal = auditSeal; this.rbac = rbac;
    }

    private static Visibility visible(AdminPrincipal principal) { return principal.visibility(); }
    private static int page(int page) { return Math.max(1, page); }
    private static int size(int size) { return Math.min(200, Math.max(1, size)); }
    private static long appOf(Map<String,Object> row) { return ((Number) row.get("applicationId")).longValue(); }
    /** The record's department; null when it was deleted, which only operators over all departments may then act on. */
    private static Long deptOf(Map<String,Object> row) { return row.get("departmentId") == null ? null : ((Number) row.get("departmentId")).longValue(); }
    private static void requireDomain(HttpServletRequest req, Map<String,Object> row) { AdminAccess.domain(req, deptOf(row), appOf(row)); }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    // ---- access requests ----
    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/access-requests") Object requests(@RequestParam(required=false) Long applicationId, @RequestParam(required=false) String status,
                                                    @RequestParam(required=false) String keyword, @RequestParam(defaultValue="1") int page,
                                                    @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (applicationId != null) AdminAccess.application(req, applicationId);
        return g.requests(applicationId, visible(principal), status, keyword, page(page), size(size));
    }
    @RequiresFunction(REQUEST_APPROVE)
    @PostMapping("/access-requests/{id}/approve") Object approve(@PathVariable long id, @Valid @RequestBody Requests.RequestApprove body,
                                                                 HttpServletRequest req) {
        requireDomain(req, g.request(id));
        return g.approve(id, body, AdminAccess.of(req).actor());
    }
    @RequiresFunction(REQUEST_APPROVE)
    @PostMapping("/access-requests/{id}/reject") Object reject(@PathVariable long id, @Valid @RequestBody Requests.RequestReject body,
                                                               HttpServletRequest req) {
        requireDomain(req, g.request(id));
        return g.reject(id, body, AdminAccess.of(req).actor());
    }

    // ---- access reviews ----
    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews") Object reviews(@RequestParam(required=false) Long applicationId, @RequestParam(required=false) String status,
                                           HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (applicationId != null) AdminAccess.application(req, applicationId);
        return g.reviews(applicationId, visible(principal), status);
    }
    @RequiresFunction(REVIEW_MANAGE)
    @PostMapping("/reviews") Object createReview(@Valid @RequestBody Requests.ReviewCreate body, HttpServletRequest req) {
        return g.createReview(body, AdminAccess.domain(req, body.departmentId(), body.applicationId()).actor());
    }
    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews/{id}") Object review(@PathVariable long id, HttpServletRequest req) {
        var review = g.review(id);
        requireDomain(req, review);
        return review;
    }
    @RequiresFunction(GOVERNANCE_VIEW)
    @GetMapping("/reviews/{id}/items") Object reviewItems(@PathVariable long id, @RequestParam(required=false) String decision,
                                                          @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                                                          HttpServletRequest req) {
        requireDomain(req, g.review(id));
        return g.reviewItems(id, decision, page(page), size(size));
    }
    @RequiresFunction(REVIEW_MANAGE)
    @PutMapping("/reviews/{id}/items/{itemId}") Object decideItem(@PathVariable long id, @PathVariable long itemId,
                                                                  @Valid @RequestBody Requests.ReviewItemDecision body, HttpServletRequest req) {
        requireDomain(req, g.review(id));
        g.decideReviewItem(id, itemId, body, AdminAccess.of(req).actor());
        return ok();
    }
    @RequiresFunction(REVIEW_MANAGE)
    @PostMapping("/reviews/{id}/close") Object closeReview(@PathVariable long id, HttpServletRequest req) {
        requireDomain(req, g.review(id));
        return g.closeReview(id, AdminAccess.of(req).actor());
    }

    // ---- webhooks ----
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/applications/{id}/webhooks") Object webhooks(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, id);
        return webhooks.webhooks(id);
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/applications/{id}/webhooks") Object createWebhook(@PathVariable long id, @Valid @RequestBody Requests.Webhook body,
                                                                     HttpServletRequest req) {
        return webhooks.create(id, body, AdminAccess.application(req, id).actor());
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PutMapping("/webhooks/{id}") Object updateWebhook(@PathVariable long id, @Valid @RequestBody Requests.Webhook body, HttpServletRequest req) {
        AdminAccess.application(req, appOf(webhooks.webhook(id)));
        webhooks.update(id, body, AdminAccess.of(req).actor());
        return ok();
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @DeleteMapping("/webhooks/{id}") Object deleteWebhook(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, appOf(webhooks.webhook(id)));
        webhooks.delete(id, AdminAccess.of(req).actor());
        return ok();
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/webhooks/{id}/deliveries") Object deliveries(@PathVariable long id, @RequestParam(defaultValue="1") int page,
                                                               @RequestParam(defaultValue="20") int size, HttpServletRequest req) {
        AdminAccess.application(req, appOf(webhooks.webhook(id)));
        return webhooks.deliveries(id, page(page), size(size));
    }
    @RequiresFunction(APPLICATION_CREDENTIALS)
    @PostMapping("/webhooks/{id}/test") Object testWebhook(@PathVariable long id, HttpServletRequest req) {
        AdminAccess.application(req, appOf(webhooks.webhook(id)));
        webhooks.test(id, AdminAccess.of(req).actor());
        return ok();
    }

    // ---- permission manifest ----
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/manifest") Object exportManifest(@RequestParam long applicationId, @RequestParam(required=false) Long departmentId,
                                                   HttpServletRequest req) {
        var principal = departmentId == null ? AdminAccess.application(req, applicationId) : AdminAccess.domain(req, departmentId, applicationId);
        return g.exportManifest(applicationId, departmentId, principal.visibleDepartments());
    }
    @RequiresFunction(DOMAIN_TRANSFER)
    @PostMapping("/manifest/import") Object importManifest(@RequestParam long applicationId, @Valid @RequestBody Requests.Manifest body,
                                                           HttpServletRequest req) {
        var principal = AdminAccess.application(req, applicationId);
        return g.importManifest(applicationId, body, principal.actor(), principal.visibleDepartments());
    }

    // ---- bulk operations and reports ----
    @RequiresFunction(USER_MANAGE)
    @PostMapping("/users/import") Object importUsers(@Valid @RequestBody Requests.UserImport body, HttpServletRequest req) {
        return g.importUsers(body, AdminAccess.allDepartments(req).actor());
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/permissions/who") Object permissionHolders(@RequestParam long applicationId, @RequestParam String code,
                                                             @RequestParam(required=false) Long departmentId, HttpServletRequest req) {
        var principal = departmentId == null ? AdminAccess.application(req, applicationId) : AdminAccess.domain(req, departmentId, applicationId);
        return g.permissionHolders(applicationId, code, departmentId, principal.visibleDepartments());
    }
    @RequiresFunction(DOMAIN_VIEW)
    @GetMapping("/assignments/expiring") Object expiring(@RequestParam(defaultValue="7") int days, @RequestParam(required=false) Long applicationId,
                                                         HttpServletRequest req) {
        var principal = AdminAccess.of(req);
        if (days < 1 || days > 365) throw com.melonhub.rbac.exception.ApiException.bad("天数需在 1-365 之间");
        if (applicationId != null) AdminAccess.application(req, applicationId);
        return g.expiringAssignments(days, visible(principal), applicationId);
    }
    @RequiresFunction(APPLICATION_MANAGE)
    @PutMapping("/applications/{id}/rate-limit") Object rateLimit(@PathVariable long id, @Valid @RequestBody Requests.RateLimitUpdate body,
                                                                  HttpServletRequest req) {
        rbac.applicationRateLimit(id, body.perSecond(), AdminAccess.of(req).actor());
        return ok();
    }

    // ---- search, status and audit chain ----
    @RequiresFunction(ORG_VIEW)
    @GetMapping("/search") Object search(@RequestParam String q, HttpServletRequest req) {
        return console.search(q, visible(AdminAccess.of(req)));
    }
    @RequiresFunction(SYSTEM_VIEW)
    @GetMapping("/system/status") Object status(HttpServletRequest req) {
        return console.status(visible(AdminAccess.of(req)));
    }
    @RequiresFunction(AUDIT_VIEW)
    @GetMapping("/audit/verify") Object verifyAudit(@RequestParam(required=false) Long fromSeq,
                                                    @RequestParam(defaultValue="5000") int limit, HttpServletRequest req) {
        AdminAccess.of(req);
        return auditSeal.verify(fromSeq, Math.min(50_000, Math.max(1, limit)));
    }
}
