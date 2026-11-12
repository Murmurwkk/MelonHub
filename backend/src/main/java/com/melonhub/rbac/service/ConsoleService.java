package com.melonhub.rbac.service;

import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.AuditDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.dao.WebhookDao;
import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Console-wide helpers: global search and the system status board. */
@Service
public class ConsoleService {
    private static final List<String> DATABASE_DETAILS =
            List.of("counts", "pendingRequests", "openReviews", "webhookBacklog", "expiringIn7Days", "audit");

    private final RbacDao dao;
    private final GovernanceDao gov;
    private final WebhookDao webhooks;
    private final AuditDao audit;
    private final PermissionCache cache;

    public ConsoleService(RbacDao dao, GovernanceDao gov, WebhookDao webhooks, AuditDao audit, PermissionCache cache) {
        this.dao = dao; this.gov = gov; this.webhooks = webhooks; this.audit = audit; this.cache = cache;
    }

    public Map<String,Object> search(String keyword, Visibility visible) {
        var result = new LinkedHashMap<String,Object>();
        String q = keyword == null ? "" : keyword.strip();
        if (q.isEmpty() || q.length() > 64) {
            result.put("users", List.of());
            result.put("departments", List.of());
            result.put("applications", List.of());
            result.put("roles", List.of());
            return result;
        }
        String pattern = RbacDao.like(q);
        result.put("users", dao.searchUsers(pattern, visible.departments()));
        result.put("departments", dao.searchDepartments(pattern, visible.departments()));
        result.put("applications", dao.searchApplications(pattern, visible.applications()));
        result.put("roles", dao.searchRoles(pattern, visible));
        return result;
    }

    /**
     * Aggregates everything the status page shows; a failing database degrades the board instead of breaking it.
     * The response always has the same keys: what the database could not provide is null.
     */
    public Map<String,Object> status(Visibility visible) {
        var result = new LinkedHashMap<String,Object>();
        result.put("version", getClass().getPackage().getImplementationVersion() == null ? "dev" : getClass().getPackage().getImplementationVersion());
        result.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("cache", cache.health());
        long started = System.nanoTime();
        boolean database;
        try { database = dao.healthy(); } catch (RuntimeException e) { database = false; }
        result.put("database", database ? "UP" : "DOWN");
        result.put("databaseLatencyMs", (System.nanoTime() - started) / 1_000_000);
        var details = new LinkedHashMap<String,Object>();
        for (String key : DATABASE_DETAILS) details.put(key, null);
        if (database) {
            try {
                details.put("counts", dao.consoleCounts());
                details.put("pendingRequests", gov.pendingCount(visible));
                details.put("openReviews", gov.openReviewCount(visible));
                details.put("webhookBacklog", webhooks.backlog());
                details.put("expiringIn7Days", dao.expiringCount(Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS))));
                details.put("audit", audit.sealState());
            } catch (DataAccessException e) {
                for (String key : DATABASE_DETAILS) details.put(key, null);
                result.put("database", "DOWN");
            }
        }
        result.putAll(details);
        return result;
    }
}
