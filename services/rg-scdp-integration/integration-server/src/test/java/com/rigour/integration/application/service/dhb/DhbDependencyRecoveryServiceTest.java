package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.*;
import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Supplier;

class DhbDependencyRecoveryServiceTest {
    final UUID tenant = UUID.randomUUID(),
            user = UUID.randomUUID(),
            connector = UUID.randomUUID(),
            runId = UUID.randomUUID();
    final DhbIntegrationStore store = mock(DhbIntegrationStore.class);
    final DhbSyncStore mappings = mock(DhbSyncStore.class);
    final DhbDependencyTargets targets = mock(DhbDependencyTargets.class);
    final DhbOrchestrationLease lease = mock(DhbOrchestrationLease.class);
    final DhbDependencyRecoveryService service =
            new DhbDependencyRecoveryService(store, mappings, targets, lease);
    final JsonMapper json =
            JsonMapper.builder()
                    .disable(
                            tools.jackson.databind.DeserializationFeature
                                    .FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build();

    @BeforeEach
    void setup() throws Exception {
        var setter = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        setter.setAccessible(true);
        setter.invoke(
                null,
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        user,
                        1,
                        1,
                        1,
                        Set.of(),
                        Set.of("integration:dhb:write")));
        when(lease.execute(eq(tenant), eq(connector), anyString(), any()))
                .thenAnswer(c -> ((Supplier<?>) c.getArgument(3)).get());
        when(store.openException(tenant, connector, "SALES_SHIPMENT", "FH001"))
                .thenReturn(
                        read(
                                DhbSyncExceptionView.class,
                                Map.of(
                                        "sourceObjectType",
                                        "SALES_SHIPMENT",
                                        "sourceId",
                                        "FH001",
                                        "runId",
                                        runId.toString(),
                                        "lastErrorCode",
                                        "DHB_ORDER_MAPPING_MISSING")));
        when(store.syncRun(tenant, runId))
                .thenReturn(
                        read(
                                DhbSyncRunAuditView.class,
                                Map.of(
                                        "runId",
                                        runId.toString(),
                                        "connectorId",
                                        connector.toString())));
        when(store.openReconciliation(tenant, runId, "SALES_SHIPMENT", "FH001"))
                .thenReturn(
                        read(
                                DhbSyncReconciliationCaseView.class,
                                Map.of(
                                        "expectedValueJson",
                                        "{\"required\":\"客户 mapping\"}",
                                        "actualValueJson",
                                        "{\"sourceOrderNo\":\"DH001\",\"sourceIds\":[\"CUS1\"]}")));
        when(targets.require(any(), eq("CUSTOMER"), eq(3L), isNull()))
                .thenReturn(new DhbDependencyTargets.Target("CRM", "CUSTOMER", 3L, "C001"));
    }

    @AfterEach
    void cleanup() throws Exception {
        var clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    <T> T read(Class<T> type, Map<String, ?> data) {
        return json.readValue(json.writeValueAsString(data), type);
    }

    DhbDependencyRecoveryCommand command(String requiredId) {
        return new DhbDependencyRecoveryCommand(
                connector, "SALES_SHIPMENT", "FH001", 3L, null, "CUSTOMER", requiredId);
    }

    @Test
    void explicitHumanSelectionUsesOriginalSourceAndUserScopeAndDoesNotCreateTransport() {
        service.link(command("CUS1"));
        verify(targets)
                .require(
                        argThat(
                                c ->
                                        c.userId().equals(user)
                                                && c.principalScope().equals("TENANT")),
                        eq("CUSTOMER"),
                        eq(3L),
                        isNull());
        verify(mappings)
                .upsertExternalObjectMapping(
                        eq(tenant),
                        eq(user),
                        argThat(
                                m ->
                                        m.sourceObjectId().equals("CUS1")
                                                && m.internalObjectId().equals(3L)
                                                && m.remark().contains("FH001")));
        verifyNoMoreInteractions(targets);
    }

    @Test
    void sourceChangedOrDifferentConnectorCannotRebindUsingStaleForm() {
        assertThatThrownBy(() -> service.link(command("OLD-CUSTOMER"))).hasMessageContaining("已变化");
        when(store.syncRun(tenant, runId))
                .thenReturn(
                        read(
                                DhbSyncRunAuditView.class,
                                Map.of(
                                        "runId",
                                        runId.toString(),
                                        "connectorId",
                                        UUID.randomUUID().toString())));
        assertThatThrownBy(() -> service.link(command("CUS1"))).hasMessageContaining("不属于");
        verifyNoInteractions(targets, mappings);
    }

    @Test
    void unauthorizedTargetAndExistingDifferentRelationNeverWriteMapping() {
        when(targets.require(any(), anyString(), anyLong(), any()))
                .thenThrow(new AuthorizationDeniedException("customer-read"));
        assertThatThrownBy(() -> service.link(command("CUS1")))
                .isInstanceOf(AuthorizationDeniedException.class);
        verify(mappings, never()).upsertExternalObjectMapping(any(), any(), any());
    }

    @Test
    void identicalRelationIsIdempotentButDifferentTargetIsBlocked() {
        when(mappings.findActiveMapping(tenant, connector, "CUSTOMER", "CUS1"))
                .thenReturn(
                        new DhbSyncStore.ExternalObjectMapping(
                                UUID.randomUUID(),
                                "CUSTOMER",
                                "CUS1",
                                "CUS1",
                                "CRM",
                                "CUSTOMER",
                                3L,
                                "C001",
                                "ACTIVE",
                                null));
        service.link(command("CUS1"));
        when(mappings.findActiveMapping(tenant, connector, "CUSTOMER", "CUS1"))
                .thenReturn(
                        new DhbSyncStore.ExternalObjectMapping(
                                UUID.randomUUID(),
                                "CUSTOMER",
                                "CUS1",
                                "CUS1",
                                "CRM",
                                "CUSTOMER",
                                4L,
                                "C002",
                                "ACTIVE",
                                null));
        assertThatThrownBy(() -> service.link(command("CUS1"))).hasMessageContaining("关联其他");
        verify(mappings, never()).upsertExternalObjectMapping(any(), any(), any());
    }

    @Test
    void saveReadsEvidenceOnlyFromTheChosenConnectionsOriginalRun() {
        service.link(command("CUS1"));
        verify(store).openException(tenant, connector, "SALES_SHIPMENT", "FH001");
        verify(store).openReconciliation(tenant, runId, "SALES_SHIPMENT", "FH001");
        verify(store, never()).syncExceptions(any(), anyString(), anyInt());
        verify(store, never()).syncReconciliationCases(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void missingEvidenceCannotWriteFromClientSuppliedRequirement() {
        when(store.openReconciliation(tenant, runId, "SALES_SHIPMENT", "FH001")).thenReturn(null);
        assertThatThrownBy(() -> service.link(command("CUS1"))).hasMessageContaining("不能直接改绑");
        verifyNoInteractions(targets, mappings);
    }

    @Test
    void requirementUsesRecordedBusinessEvidenceAndNeverGlobalDefaultSku() {
        assertThat(
                        DhbDependencyRequirement.from(
                                        "DHB_ORDER_MAPPING_MISSING",
                                        "{\"required\":\"商品规格 mapping\"}",
                                        "{\"sourceIds\":[\"0\",\"P001::0\"]}")
                                .sourceId())
                .isEqualTo("P001::0");
        assertThat(
                        DhbDependencyRequirement.from(
                                "DHB_ORDER_MAPPING_MISSING",
                                "{\"required\":\"商品规格 mapping\"}",
                                "{\"sourceIds\":[\"0\"]}"))
                .isNull();
        assertThat(DhbDependencyRequirement.from("DHB_DEPENDENCY_IDENTITY_CONFLICT", null, null))
                .isNull();
    }
}
