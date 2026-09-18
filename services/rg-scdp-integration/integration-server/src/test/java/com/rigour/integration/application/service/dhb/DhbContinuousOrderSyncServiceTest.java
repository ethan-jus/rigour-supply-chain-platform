package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.api.v1.model.DhbApiModels.*;
import com.rigour.integration.application.port.out.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

/** 一类失败不能推进其游标，也不能阻止回款和其他对象接续。 */
class DhbContinuousOrderSyncServiceTest {
    @Test
    void failedOrdersDoNotBlockReceiptsOrAdvanceOrderCursor() {
        var store = mock(DhbIntegrationStore.class);
        var order = mock(DhbOrderSyncService.class);
        var checkpoints = mock(DhbObjectCheckpointStore.class);
        UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), task = UUID.randomUUID();
        when(store.activeOrderSyncTargets())
                .thenReturn(List.of(new SyncTargetView(task, tenant, connector)));
        var properties = new DhbSyncOrchestrationProperties();
        properties.setScheduledWindowFrom("2026-09-04");
        Instant cursor = Instant.parse("2026-09-10T00:00:00Z");
        when(checkpoints.successfulTo(tenant, connector, "RECEIPT")).thenReturn(cursor);
        List<String> calls = new ArrayList<>();
        when(order.runOrderPull(any(), eq(task), any(), eq(100)))
                .thenAnswer(
                        i -> {
                            SyncRunCommand c = i.getArgument(2);
                            calls.add(c.syncScope());
                            if (c.syncScope().equals("RECEIPT"))
                                assertThat(c.from()).isEqualTo(cursor.minusSeconds(900));
                            return new SyncRunView(
                                    UUID.randomUUID(),
                                    task,
                                    c.syncScope().equals("SALES_ORDER") ? "PARTIAL" : "SUCCEEDED",
                                    c.from(),
                                    c.to(),
                                    1,
                                    1,
                                    0,
                                    0,
                                    null,
                                    null);
                        });
        DhbOrchestrationLease lease =
                new DhbOrchestrationLease() {
                    public <T> T execute(
                            UUID t, UUID c, String owner, java.util.function.Supplier<T> action) {
                        return action.get();
                    }
                };
        new DhbContinuousOrderSyncService(store, order, checkpoints, lease, properties)
                .synchronize();
        assertThat(calls)
                .containsExactly("SALES_ORDER", "RECEIPT", "SHIPMENT", "TRANSFER", "PAYMENT");
        verify(checkpoints, never())
                .completed(eq(tenant), eq(connector), eq("SALES_ORDER"), any(), any());
        verify(checkpoints).completed(eq(tenant), eq(connector), eq("RECEIPT"), any(), any());
    }
}
