package com.melonhub.rbac.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.melonhub.rbac.dao.Visibility;
import com.melonhub.rbac.cache.PermissionCache;
import com.melonhub.rbac.dao.AuditDao;
import com.melonhub.rbac.dao.GovernanceDao;
import com.melonhub.rbac.dao.RbacDao;
import com.melonhub.rbac.dao.WebhookDao;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ConsoleServiceTest {
    private static final List<String> DETAILS = List.of("counts", "pendingRequests", "openReviews", "webhookBacklog", "expiringIn7Days", "audit");
    private final RbacDao dao = mock(RbacDao.class);
    private final GovernanceDao governance = mock(GovernanceDao.class);
    private final PermissionCache cache = mock(PermissionCache.class);
    private final ConsoleService console = new ConsoleService(dao, governance, mock(WebhookDao.class), mock(AuditDao.class), cache);

    @Test void anUnreachableDatabaseStillAnswersWithEveryKey() {
        when(dao.healthy()).thenThrow(new DataAccessResourceFailureException("down"));
        var status = console.status(new Visibility(List.of(), List.of()));
        assertThat(status).containsEntry("database", "DOWN").containsKeys(DETAILS.toArray(String[]::new));
        DETAILS.forEach(key -> assertThat(status.get(key)).as(key).isNull());
    }

    @Test void aDatabaseFailingHalfwayDropsWhatItAlreadyReturned() {
        when(dao.healthy()).thenReturn(true);
        when(dao.consoleCounts()).thenReturn(Map.<String,Object>of("users", 3L));
        when(governance.pendingCount(any())).thenThrow(new DataAccessResourceFailureException("lost"));
        var status = console.status(new Visibility(List.of(), List.of()));
        assertThat(status).containsEntry("database", "DOWN").containsKeys(DETAILS.toArray(String[]::new));
        DETAILS.forEach(key -> assertThat(status.get(key)).as(key).isNull());
    }
}
