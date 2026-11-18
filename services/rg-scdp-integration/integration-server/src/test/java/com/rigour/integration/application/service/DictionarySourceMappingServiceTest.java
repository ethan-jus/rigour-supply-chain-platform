package com.rigour.integration.application.service;

import com.rigour.integration.application.port.out.DictionarySourceMappingStore;
import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 来源隔离、待映射和配置重用必须经过同一导入处理链。 */
class DictionarySourceMappingServiceTest {
    private final DictionarySourceMappingStore store=mock(DictionarySourceMappingStore.class);
    private final BusinessDictionaryBatchClient client=mock(BusinessDictionaryBatchClient.class);
    private final DictionarySourceMappingService service=new DictionarySourceMappingService(store,client);
    private final com.rigour.shared.context.CallerIdentity caller=BusinessDictionaryBatchClient.serviceCaller("test","SYNC",UUID.randomUUID());

    @Test void savingShipmentMappingQueuesOnlyItsTenantAndOriginalSourceField() {
        var work = mock(com.rigour.integration.application.port.out.DhbIncrementalWorkStore.class);
        service.incrementalWork(work);
        var source = new DictionarySourceMappingView(31L,"DHB","DHB_SHIPMENT_LOGISTICS",
                "COST_DELIVERY_MODE","shipment.deliveryMode","85984","COST_DELIVERY_MODE",
                "THIRD_PARTY","MAPPED",true,2,null,null);
        when(store.find(caller.tenantId().toString(),31L)).thenReturn(Optional.of(source));
        when(client.sync(any(),eq("MAPPING_VALIDATE"),any())).thenReturn(new Audit(0,Map.of(),List.of(),
                List.of(new ResolvedValue("COST_DELIVERY_MODE","THIRD_PARTY","COST_DELIVERY_MODE","THIRD_PARTY"))));
        when(store.update(anyString(),eq(31L),any(),anyString())).thenReturn(source);
        try (var context = mockStatic(com.rigour.shared.context.AuthorizationContext.class)) {
            context.when(com.rigour.shared.context.AuthorizationContext::requireCurrent).thenReturn(caller);
            service.update(31L,new com.rigour.integration.api.v1.model.DictionarySourceMappingCommand(
                    "COST_DELIVERY_MODE","THIRD_PARTY",2));
            verify(work).recheckShipmentDictionary(eq(caller.tenantId()),eq("shipment.deliveryMode"),eq("85984"),any());
        }
    }

    @Test void unrelatedMappingsNeverQueueShipments() {
        var work = mock(com.rigour.integration.application.port.out.DhbIncrementalWorkStore.class);
        service.incrementalWork(work);
        var source = new DictionarySourceMappingView(32L,"FEISHU","STORE_TABLE","STORE_STATUS",
                "状态","营业","STORE_STATUS","ACTIVE","MAPPED",true,2,null,null);
        when(store.find(caller.tenantId().toString(),32L)).thenReturn(Optional.of(source));
        when(client.sync(any(),eq("MAPPING_VALIDATE"),any())).thenReturn(new Audit(0,Map.of(),List.of(),
                List.of(new ResolvedValue("STORE_STATUS","ACTIVE","STORE_STATUS","ACTIVE"))));
        when(store.update(anyString(),eq(32L),any(),anyString())).thenReturn(source);
        try (var context = mockStatic(com.rigour.shared.context.AuthorizationContext.class)) {
            context.when(com.rigour.shared.context.AuthorizationContext::requireCurrent).thenReturn(caller);
            service.update(32L,new com.rigour.integration.api.v1.model.DictionarySourceMappingCommand("STORE_STATUS","ACTIVE",2));
            verifyNoInteractions(work);
        }
    }

    @Test void unknownValuesAreRecordedAsPendingWithoutCreatingDictionaryItems() {
        when(store.find(any(DictionarySourceMappingStore.Key.class))).thenReturn(Optional.empty());
        when(client.sync(any(),any(),any())).thenReturn(new Audit(1,Map.of(),List.of(new MappingIssue("STORE_STATUS","状态","未知",1))));
        var result=service.sync(caller,"FEISHU_IMPORT",List.of(new Observation("STORE_STATUS","状态","未知","未知","STORE_TABLE")));
        assertThat(result.unmapped()).isEqualTo(1);
        verify(store).observe(new DictionarySourceMappingStore.Key(caller.tenantId().toString(),"FEISHU","STORE_TABLE","STORE_STATUS","状态","未知"),null,null);
    }

    @Test void manualMappingAppliesOnlyToItsTenantSourceScopeAndField() {
        var configured=new DictionarySourceMappingStore.Key(caller.tenantId().toString(),"FEISHU","STORE_TABLE","STORE_STATUS","状态","营业");
        when(store.find(any(DictionarySourceMappingStore.Key.class))).thenReturn(Optional.empty());
        when(store.find(configured)).thenReturn(Optional.of(new DictionarySourceMappingView(1L,"FEISHU","STORE_TABLE","STORE_STATUS","状态","营业","STORE_STATUS","ACTIVE","MAPPED",true,1,null,null)));
        when(client.sync(any(),any(),any())).thenAnswer(invocation->{
            Collection<Observation> sent=invocation.getArgument(2);
            assertThat(sent).extracting(Observation::sourceValue).containsExactly("ACTIVE","营业");
            return new Audit(1,Map.of(),List.of(new MappingIssue("STORE_STATUS","状态","营业",1)),List.of(new ResolvedValue("STORE_STATUS","ACTIVE","STORE_STATUS","ACTIVE")));
        });
        var result=service.sync(caller,"FEISHU_IMPORT",List.of(new Observation("STORE_STATUS","状态","营业","营业","STORE_TABLE"),new Observation("STORE_STATUS","状态","营业","营业","OTHER_TABLE")));
        assertThat(result.resolved()).singleElement().satisfies(value->assertThat(value.targetItemCode()).isEqualTo("ACTIVE"));
        verify(store).observe(configured,"STORE_STATUS","ACTIVE");
        verify(store).observe(new DictionarySourceMappingStore.Key(caller.tenantId().toString(),"FEISHU","OTHER_TABLE","STORE_STATUS","状态","营业"),null,null);
    }

    @Test void repeatedSourceRowsRequireOneMappingLookupPerScopedValue() {
        var observation = new Observation("STORE_STATUS", "状态", "营业中", "营业中", "STORE_TABLE");
        var key = new DictionarySourceMappingStore.Key(caller.tenantId().toString(), "FEISHU", "STORE_TABLE", "STORE_STATUS", "状态", "营业中");
        when(store.find(key)).thenReturn(Optional.empty());
        when(client.sync(any(), any(), any())).thenReturn(new Audit(0, Map.of(), List.of(),
                List.of(new ResolvedValue("STORE_STATUS", "营业中", "STORE_STATUS", "ACTIVE"))));
        var result = service.sync(caller, "FEISHU_IMPORT", Collections.nCopies(10_000, observation));
        verify(store, times(1)).find(key);
        verify(store, times(1)).observe(key, "STORE_STATUS", "ACTIVE");
        assertThat(result.resolved()).hasSize(1);
    }

    @Test void mappingStoreFailureReturnsAuditWarningAndDoesNotAbortRawImport() {
        when(store.find(any(DictionarySourceMappingStore.Key.class))).thenThrow(new IllegalStateException("database unavailable"));
        var result=service.sync(caller,"FEISHU_IMPORT",List.of(new Observation("STORE_STATUS","状态","营业","营业")));
        assertThat(result.unmapped()).isEqualTo(1);
        verifyNoInteractions(client);
    }
}
