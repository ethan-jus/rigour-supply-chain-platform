package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.CallerIdentity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class DhbScheduledHistoryProtectionTest {
    @ParameterizedTest
    @ValueSource(strings = {"order", "payment"})
    void historicalBusinessDateIsExcludedBeforeAnyWriteEvenIfOperationIsAfterCutover(String object)
            throws Exception {
        var tenant = UUID.randomUUID();
        var actor =
                new CallerIdentity(
                        "SERVICE",
                        UUID.randomUUID(),
                        tenant,
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("DHB_PROTECT_HISTORY"),
                        Set.of());
        var task =
                new DhbSyncStore.SyncTaskContext(
                        tenant,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "orders",
                        "ORDER",
                        "ACTIVE",
                        "https://example.test",
                        "test",
                        "ACTIVE",
                        100,
                        1,
                        0,
                        true);
        var store = mock(DhbSyncStore.class);
        var client = mock(DhbClient.class);
        var projection = mock(OrderSalesOrderProjectionClient.class);
        var hr = mock(HrDhbStaffSyncClient.class);
        var service = new DhbOrderSyncService(store, client, projection, hr);
        var old = Instant.parse("2026-09-02T16:00:00Z");
        var operation = Instant.parse("2026-09-23T08:00:00Z");
        java.lang.reflect.Method method;
        Object outcome;
        if (object.equals("order")) {
            var data =
                    new DhbClient.OrderSummary(
                            "D1",
                            "D1",
                            "cancelled",
                            BigDecimal.ONE,
                            operation,
                            operation,
                            null,
                            null,
                            Map.of("OrderDate", old.getEpochSecond()));
            method =
                    DhbOrderSyncService.class.getDeclaredMethod(
                            "projectOrder",
                            CallerIdentity.class,
                            DhbSyncStore.SyncTaskContext.class,
                            UUID.class,
                            DhbClient.OrderSummary.class,
                            Map.class,
                            Map.class);
            method.setAccessible(true);
            outcome =
                    method.invoke(
                            service,
                            actor,
                            task,
                            UUID.randomUUID(),
                            data,
                            new HashMap<>(),
                            new HashMap<>());
        } else {
            var data =
                    new DhbClient.Payment(
                            "P1",
                            "P1",
                            null,
                            "D1",
                            null,
                            null,
                            null,
                            null,
                            BigDecimal.ONE,
                            "cancelled",
                            old,
                            operation,
                            operation,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Map.of());
            method =
                    DhbOrderSyncService.class.getDeclaredMethod(
                            "projectPayment",
                            CallerIdentity.class,
                            DhbSyncStore.SyncTaskContext.class,
                            UUID.class,
                            DhbClient.Payment.class);
            method.setAccessible(true);
            outcome = method.invoke(service, actor, task, UUID.randomUUID(), data);
        }
        assertThat(outcome).isEqualTo(DhbOrderSyncService.ProjectionOutcome.DUPLICATE);
        verifyNoInteractions(store, client, projection, hr);
    }
    @ParameterizedTest
    @ValueSource(strings = {"SYNCED", "HISTORY_REVIEW", "SOURCE_CHANGED_REVIEW", "STALE"})
    void historicalReceiptUsesStatusOnlyEndpointAndRetainsUnresolvedWork(String state) throws Exception {
        var tenant = UUID.randomUUID();
        var actor = new CallerIdentity("SERVICE", UUID.randomUUID(), tenant, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("DHB_PROTECT_HISTORY"), Set.of());
        var task = new DhbSyncStore.SyncTaskContext(tenant, UUID.randomUUID(), UUID.randomUUID(),
                "orders", "ORDER", "ACTIVE", "https://example.test", "test", "ACTIVE", 100, 1, 0, true);
        var store = mock(DhbSyncStore.class);
        var projection = mock(OrderSalesOrderProjectionClient.class);
        var service = new DhbOrderSyncService(store, mock(DhbClient.class), projection, mock(HrDhbStaffSyncClient.class));
        var old = Instant.parse("2026-08-30T16:00:00Z");
        var updated = Instant.parse("2026-09-24T05:10:50Z");
        var data = new DhbClient.Receipt("R1", "R1", "D1", null, null, null, null, new BigDecimal("702"),
                "pend_receipted", old, old, updated, null, null, null, null, null, Map.of());
        var raw = new DhbSyncStore.RawObjectPersistResult(UUID.randomUUID(), "hash", true);
        when(store.persistRawObject(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(raw);
        when(projection.syncHistoricalReceiptStatus(any(), any()))
                .thenReturn(new com.rigour.order.api.v1.model.HistorySyncModels.Intake(state, null, "review"));
        var method = DhbOrderSyncService.class.getDeclaredMethod("projectReceipt", CallerIdentity.class,
                DhbSyncStore.SyncTaskContext.class, UUID.class, DhbClient.Receipt.class);
        method.setAccessible(true);
        var result = method.invoke(service, actor, task, UUID.randomUUID(), data);
        assertThat(result).isEqualTo(switch (state) {
            case "SYNCED" -> DhbOrderSyncService.ProjectionOutcome.ACCEPTED;
            case "STALE" -> DhbOrderSyncService.ProjectionOutcome.DUPLICATE;
            default -> DhbOrderSyncService.ProjectionOutcome.REJECTED;
        });
        verify(projection).syncHistoricalReceiptStatus(any(), argThat(c -> "CONFIRMED".equals(c.status())
                && old.equals(c.occurredAt()) && updated.equals(c.updatedAt())));
        verify(projection, never()).registerReceipt(any(), any());
        verify(projection, never()).createFundDocument(any(), any());
        verify(projection, never()).updateFundDocument(any(), any(), any());
        if (state.endsWith("REVIEW")) verify(store, never()).markRawProcessed(any(), any());
    }

}
