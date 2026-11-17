package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.api.v1.model.DhbApiModels.*;
import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class DhbOpenIssueQueryTest {
    final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
    final DhbIntegrationStore store = mock(DhbIntegrationStore.class);
    final DhbIntegrationService service =
            new DhbIntegrationService(
                    store,
                    mock(DhbClient.class),
                    mock(DhbSyncOrchestrationService.class),
                    mock(ProductMediaStorage.class),
                    mock(ProductImageObjectKeyFactory.class),
                    mock(ProductMediaSyncStore.class));

    DhbOpenIssueQueryTest() {
        context(
                "set",
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        actor,
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("integration:dhb:read")));
    }

    @AfterEach
    void clear() {
        context("clear", null);
    }

    @Test
    void keepsSameNumberFromDifferentConnectionsAndUsesEachOriginalRunEvidence() {
        UUID r1 = UUID.randomUUID(),
                r2 = UUID.randomUUID(),
                c1 = UUID.randomUUID(),
                c2 = UUID.randomUUID();
        var issues = List.of(issue(r1), issue(r2));
        when(store.syncExceptions(tenant, "OPEN", 500)).thenReturn(issues);
        when(store.syncRunsByIds(tenant, Set.of(r1, r2)))
                .thenReturn(List.of(run(r1, c1), run(r2, c2)));
        when(store.openReconciliations(tenant, issues))
                .thenReturn(
                        List.of(
                                evidence(r2, "DH-TWO"),
                                evidence(r1, "DH-ONE"),
                                evidence(UUID.randomUUID(), "DH-WRONG")));
        var result =
                service.openIssues(500).stream().flatMap(group -> group.items().stream()).toList();
        assertThat(result).hasSize(2);
        assertThat(result)
                .anySatisfy(
                        row -> {
                            assertThat(row.connectorId()).isEqualTo(c1);
                            assertThat(row.requiredSourceId()).isEqualTo("DH-ONE");
                        })
                .anySatisfy(
                        row -> {
                            assertThat(row.connectorId()).isEqualTo(c2);
                            assertThat(row.requiredSourceId()).isEqualTo("DH-TWO");
                        });
        verify(store, never()).syncRuns(any(), any(), any(), anyInt());
        verify(store, never()).syncReconciliationCases(any(), any(), any(), anyInt());
    }

    @Test
    void doesNotBorrowEvidenceFromAnotherRunWithSameShipmentNumber() {
        var issue = issue(UUID.randomUUID());
        when(store.syncExceptions(tenant, "OPEN", 20)).thenReturn(List.of(issue));
        when(store.openReconciliations(tenant, List.of(issue)))
                .thenReturn(List.of(evidence(UUID.randomUUID(), "DH-WRONG")));
        var row = service.openIssues(20).getFirst().items().getFirst();
        assertThat(row.requiredSourceId()).isNull();
    }

    @Test
    void noPendingWorkSkipsEvidenceAndRunQueries() {
        assertThat(service.openIssues(20)).isEmpty();
        verify(store, never()).openReconciliations(any(), any());
        verify(store, never()).syncRunsByIds(any(), any());
    }

    @Test
    void taskManagementKeepsDisabledConnectionsButReportsAvailabilityWithoutCredentials() {
        UUID active = UUID.randomUUID(), disabled = UUID.randomUUID();
        when(store.connectors(tenant))
                .thenReturn(
                        List.of(
                                new ConnectorView(
                                        active,
                                        tenant,
                                        "D1",
                                        "订货宝业务账号",
                                        "https://example.test",
                                        "env://PRIVATE",
                                        "ACTIVE",
                                        1),
                                new ConnectorView(
                                        disabled,
                                        tenant,
                                        "D2",
                                        "停用账号",
                                        "https://example.test",
                                        "env://PRIVATE",
                                        "DISABLED",
                                        1)));
        when(store.syncTasks(tenant))
                .thenReturn(
                        List.of(
                                new SyncTaskView(
                                        actor, tenant, active, "ORDER", "ORDER", "IDLE", null, null,
                                        1),
                                new SyncTaskView(
                                        actor, tenant, disabled, "ORDER2", "ORDER", "IDLE", null,
                                        null, 1)));
        assertThat(service.syncTasks())
                .hasSize(2)
                .anySatisfy(
                        task -> {
                            assertThat(task.connectorName()).isEqualTo("订货宝业务账号");
                            assertThat(task.connectorStatus()).isEqualTo("ACTIVE");
                            assertThat(task.toString()).doesNotContain("PRIVATE", "https://");
                        })
                .anySatisfy(task -> assertThat(task.connectorStatus()).isEqualTo("DISABLED"));
    }

    DhbSyncExceptionView issue(UUID run) {
        return new DhbSyncExceptionView(
                UUID.randomUUID(),
                tenant,
                run,
                null,
                "DHB",
                "SALES_SHIPMENT",
                "FH-SAME",
                "OPEN",
                1,
                null,
                "DHB_SHIPMENT_ORDER_MAPPING_MISSING",
                "旧错误",
                null,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    DhbSyncRunAuditView run(UUID run, UUID connector) {
        return new DhbSyncRunAuditView(
                run, tenant, actor, connector, "ORDER", "ORDER", "MANUAL", "PARTIAL", null, null, 1,
                0, 0, 1, null, null, null, null);
    }

    DhbSyncReconciliationCaseView evidence(UUID run, String source) {
        return new DhbSyncReconciliationCaseView(
                UUID.randomUUID(),
                tenant,
                run,
                "DHB",
                "SALES_SHIPMENT",
                "FH-SAME",
                "MAPPING",
                "{}",
                "{\"sourceOrderNo\":\"" + source + "\"}",
                "OPEN",
                "ERROR",
                "",
                null,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    static void context(String method, CallerIdentity caller) {
        try {
            var m =
                    caller == null
                            ? AuthorizationContext.class.getDeclaredMethod(method)
                            : AuthorizationContext.class.getDeclaredMethod(
                                    method, CallerIdentity.class);
            m.setAccessible(true);
            if (caller == null) m.invoke(null);
            else m.invoke(null, caller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
