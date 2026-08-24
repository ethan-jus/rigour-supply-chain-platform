package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.merchant.api.v1.model.SyncObjectResult;
import com.rigour.merchant.api.v1.model.SyncResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DhbSyncOrchestrationServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb100-0000-7000-8000-000000000001");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000002");
    private static final UUID PRODUCT_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000101");
    private static final UUID SUPPLY_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000102");
    private static final UUID CRM_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000103");
    private static final UUID ORDER_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000104");
    private static final UUID DICTIONARY_TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000105");

    @Mock
    private DhbIntegrationStore store;
    @Mock
    private ErpDhbDomainSyncClient erpClient;
    @Mock
    private CrmDhbDomainSyncClient crmClient;
    @Mock
    private IamDhbStaffSyncClient iamClient;
    @Mock
    private DhbClient dhbClient;
    @Mock
    private DhbOrderSyncService orderSyncService;

    private DhbSyncOrchestrationService service;

    @BeforeEach
    void setUp() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, iamClient,
                dhbClient, orderSyncService, properties,
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());
        when(store.activeProductMasterSyncTargets()).thenReturn(List.of(target(PRODUCT_TASK_ID)));
        when(store.activeSupplyChainSyncTargets()).thenReturn(List.of(target(SUPPLY_TASK_ID)));
        when(store.activeCrmMasterSyncTargets()).thenReturn(List.of(target(CRM_TASK_ID)));
        when(store.activeOrderSyncTargets()).thenReturn(List.of(target(ORDER_TASK_ID)));
        when(store.activeBusinessDictionarySyncTargets()).thenReturn(List.of(target(DICTIONARY_TASK_ID)));
        when(store.connector(TENANT_ID, CONNECTOR_ID)).thenReturn(new ConnectorView(CONNECTOR_ID,
                TENANT_ID, "DHB_TEST", "订货宝测试连接", "https://dhb.example",
                "env://DHB_TEST", "ACTIVE", 0));
        when(dhbClient.getStaff(any(), any())).thenAnswer(invocation -> {
            DhbClient.StaffQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query.page(), 0, List.of());
        });
    }

    @Test
    void scheduledRunCallsDomainsInBusinessDependencyOrder() {
        List<String> calls = new ArrayList<>();
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType), eq(100)))
                    .thenAnswer(invocation -> {
                        calls.add("ERP:" + objectType);
                        return erpResult(objectType);
                    });
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType), eq(100)))
                    .thenAnswer(invocation -> {
                        calls.add("ERP:" + objectType);
                        return erpResult(objectType);
                    });
        }
        when(crmClient.sync(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(100)))
                .thenAnswer(invocation -> {
                    calls.add("CRM:CRM_MASTER_DATA");
                    return crmResult();
                });
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), isNull(), eq(100)))
                .thenAnswer(invocation -> {
                    calls.add("ORDER:ORDER_DOMAIN");
                    return orderResult();
                });

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(calls).containsExactly(
                "ERP:CATEGORY",
                "ERP:BRAND",
                "ERP:SPECIFICATION",
                "ERP:TAG",
                "ERP:PRODUCT_SPU",
                "CRM:CRM_MASTER_DATA",
                "ERP:SUPPLIER",
                "ERP:WAREHOUSE",
                "ERP:PURCHASE_ORDER",
                "ERP:PURCHASE_RETURN",
                "ERP:WAREHOUSING_RECEIPT",
                "ERP:INVENTORY",
                "ORDER:ORDER_DOMAIN");
    }

    @Test
    void scheduledRunStopsDependentDomainsAfterErpFailure() {
        List<String> calls = new ArrayList<>();
        when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq("CATEGORY"), eq(100)))
                .thenAnswer(invocation -> {
                    calls.add("ERP:CATEGORY");
                    return erpResult("CATEGORY");
                });
        when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq("BRAND"), eq(100)))
                .thenAnswer(invocation -> {
                    calls.add("ERP:BRAND");
                    throw new IllegalStateException("brand failed");
                });

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(calls).containsExactly("ERP:CATEGORY", "ERP:BRAND");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.steps()).extracting("objectType")
                        .containsExactly("BUSINESS_DICTIONARY", "STAFF", "CATEGORY", "BRAND"));
    }

    @Test
    void scheduledRunAggregatesPartialOrderAsPartial() {
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType), eq(100)))
                    .thenReturn(erpResult(objectType));
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType), eq(100)))
                    .thenReturn(erpResult(objectType));
        }
        when(crmClient.sync(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(100)))
                .thenReturn(crmResult());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), isNull(), eq(100)))
                .thenReturn(new SyncRunView(UUID.randomUUID(), ORDER_TASK_ID, "PARTIAL",
                        Instant.parse("2026-08-21T07:00:00Z"),
                        Instant.parse("2026-08-21T08:00:00Z"),
                        12, 6, 0, 6,
                        "DHB_ORDER_PROJECTION_PARTIAL", "部分订单未投影"));

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.status()).isEqualTo("PARTIAL"));
    }

    private static SyncTargetView target(UUID taskId) {
        return new SyncTargetView(taskId, TENANT_ID, CONNECTOR_ID);
    }

    private static ErpDataSyncResult erpResult(String objectType) {
        return new ErpDataSyncResult(UUID.randomUUID(), objectType, "SUCCEEDED", CONNECTOR_ID,
                1, 1, 0, 0, 0, 0, Map.of(), 1, Instant.parse("2026-08-21T08:00:00Z"));
    }

    private static SyncResult crmResult() {
        SyncObjectResult object = new SyncObjectResult(UUID.randomUUID(), "CRM_MASTER_DATA",
                "SUCCEEDED", 1, 1, 0, 0, 0, 0, 0, 1,
                Instant.parse("2026-08-21T08:00:00Z"), 0, Map.of());
        return new SyncResult(UUID.randomUUID(), "SUCCEEDED", List.of(object));
    }

    private static SyncRunView orderResult() {
        return new SyncRunView(UUID.randomUUID(), ORDER_TASK_ID, "SUCCEEDED",
                Instant.parse("2026-08-21T07:00:00Z"), Instant.parse("2026-08-21T08:00:00Z"),
                1, 1, 0, 0, null, null);
    }
}
