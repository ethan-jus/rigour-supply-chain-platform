package com.rigour.tenant.iam.infrastructure.persistence.settings;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.application.port.out.AppReferenceClient;
import com.rigour.tenant.iam.application.port.out.StoreDivisionClient;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDivisionCatalogReadTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final JdbcAppSettingsStore settings = mock(JdbcAppSettingsStore.class);
    final AppReferenceClient references = mock(AppReferenceClient.class);
    final StoreDivisionClient divisions = mock(StoreDivisionClient.class);
    final Actor actor = new Actor("TENANT", UUID.randomUUID(), UUID.randomUUID());
    final JdbcStoreTerritoryStore store = new JdbcStoreTerritoryStore(jdbc, settings, references,
            divisions, mock(PlatformTransactionManager.class));

    @Test void readsCatalogWithoutDepartmentDataOrHrDependency() {
        var catalog = new AdminDivisionCatalog("TEST", "test", "test", List.of());
        when(divisions.published(actor.tenantId())).thenReturn(catalog);
        assertThat(store.catalog(actor)).isSameAs(catalog);
        verify(settings).requirePermission(actor, "supply:admin-division:read");
        verifyNoInteractions(jdbc, references);
    }

    @Test void rejectsBeforeCallingFoundationWhenPermissionIsMissing() {
        doThrow(new AccessDeniedException("denied")).when(settings).requirePermission(actor, "supply:admin-division:read");
        assertThatThrownBy(() -> store.catalog(actor)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, references, divisions);
    }
}
