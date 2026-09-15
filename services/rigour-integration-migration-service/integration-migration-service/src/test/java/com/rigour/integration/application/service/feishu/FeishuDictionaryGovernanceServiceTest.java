package com.rigour.integration.application.service.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredBatch;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.integration.application.service.DictionarySourceMappingService;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.AuthorizationContext;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 扫描只观察已有导入记录，范围不足和持久化失败必须如实返回。 */
class FeishuDictionaryGovernanceServiceTest {
    private final FeishuImportStore store = mock(FeishuImportStore.class);
    private final DictionarySourceMappingService mappings = mock(DictionarySourceMappingService.class);
    private final FeishuDictionaryGovernanceService service = new FeishuDictionaryGovernanceService(store, mappings);
    private final UUID tenant = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();

    @BeforeEach
    void setContext() throws ReflectiveOperationException {
        UUID actor = UUID.randomUUID();
        var set = AuthorizationContext.class.getDeclaredMethod("set", CallerIdentity.class);
        set.setAccessible(true);
        set.invoke(null, new CallerIdentity("TENANT", actor, tenant, actor, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("business-settings:dict:write")));
        when(store.recentBatches(tenant, 100)).thenReturn(List.of(batch()));
        when(store.rawRowsForBatch(tenant, batch, 100_000)).thenReturn(List.of());
        when(mappings.syncObserved(any(), eq("FEISHU_IMPORT"), any()))
                .thenReturn(new Audit(0, Map.of(), List.of()));
    }

    @AfterEach
    void clearContext() throws ReflectiveOperationException {
        var clear = AuthorizationContext.class.getDeclaredMethod("clear");
        clear.setAccessible(true);
        clear.invoke(null);
    }

    @Test
    void orderRowsDoNotBecomeStoreStatusObservations() {
        var row = new StoredRawRow(UUID.randomUUID(), batch, UUID.randomUUID(), tenant,
                "订单", "FEISHU_SALES_ORDER", "ORDER", "SALES_ORDER", 2, "ORDER-1", null,
                "PROJECTED", Map.of("付款方式", "转账"), Map.of());
        when(store.rawRowsForBatch(tenant, batch, 100_000)).thenReturn(List.of(row));
        var result = service.rescan("STORE_STATUS");
        assertThat(result.rows()).isEqualTo(1);
        assertThat(result.observedValues()).isZero();
        verify(mappings).syncObserved(any(), eq("FEISHU_IMPORT"), eq(Set.of()));
    }

    @Test
    void sourceValuesAreDeduplicatedWithinTheirTableAndRetainTheirScope() {
        var row = new StoredRawRow(UUID.randomUUID(), batch, UUID.randomUUID(), tenant,
                "门店", "STORE_TABLE", "CRM", "CUSTOMER", 2, "STORE-1", null,
                "PROJECTED", Map.of("门店状态", "营业中"), Map.of());
        when(store.rawRowsForBatch(tenant, batch, 100_000)).thenReturn(List.of(row, row));
        when(mappings.syncObserved(any(), eq("FEISHU_IMPORT"), any())).thenAnswer(call -> {
            Collection<Observation> observed = call.getArgument(2);
            assertThat(observed).containsExactly(new Observation("STORE_STATUS", "门店状态", "营业中", "营业中", "STORE_TABLE"));
            return new Audit(0, Map.of(), List.of());
        });
        assertThat(service.rescan("STORE_STATUS").observedValues()).isEqualTo(1);
    }

    @Test
    void reachingRepositoryLimitDoesNotClaimFullCoverage() {
        when(store.recentBatches(tenant, 100)).thenReturn(Collections.nCopies(100, batch()));
        assertThat(service.rescan("STORE_STATUS").truncated()).isTrue();
    }

    @Test
    void failedMappingPersistenceIsNotReportedAsSuccessfulScan() {
        when(mappings.syncObserved(any(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.rescan("STORE_STATUS")).isInstanceOf(IllegalStateException.class);
    }

    private StoredBatch batch() {
        return new StoredBatch(batch, tenant, "FEISHU", "PARTIAL", "source.xlsx", "hash", null,
                0, 1, 0, 0, null, null);
    }
}
