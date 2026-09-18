package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.api.v1.model.DhbPageSyncCommand;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationCommand;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbObjectCheckpointStore;
import com.rigour.integration.application.port.out.DhbOrchestrationLease;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;
import com.rigour.integration.application.port.out.HrDhbStaffSyncClient;
import com.rigour.merchant.api.v1.model.SyncObjectResult;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.MappingIssue;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private HrDhbStaffSyncClient hrEmployeeClient;
    @Mock
    private DhbClient dhbClient;
    @Mock
    private DhbOrderSyncService orderSyncService;
    @Mock
    private DhbObjectCheckpointStore objectCheckpoints;
    @Mock
    private BusinessDictionaryBatchClient dictionaryClient;

    private DhbSyncOrchestrationService service;

    @BeforeEach
    void setUp() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(), properties,
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());
        lenient().when(store.activeProductMasterSyncTargets()).thenReturn(List.of(target(PRODUCT_TASK_ID)));
        lenient().when(store.activeSupplyChainSyncTargets()).thenReturn(List.of(target(SUPPLY_TASK_ID)));
        lenient().when(store.activeCrmMasterSyncTargets()).thenReturn(List.of(target(CRM_TASK_ID)));
        lenient().when(store.activeOrderSyncTargets()).thenReturn(List.of(target(ORDER_TASK_ID)));
        lenient().when(store.activeBusinessDictionarySyncTargets()).thenReturn(List.of(target(DICTIONARY_TASK_ID)));
        lenient().when(store.configuredProductMasterSyncTargets()).thenReturn(List.of(target(PRODUCT_TASK_ID)));
        lenient().when(store.configuredSupplyChainSyncTargets()).thenReturn(List.of(target(SUPPLY_TASK_ID)));
        lenient().when(store.configuredCrmMasterSyncTargets()).thenReturn(List.of(target(CRM_TASK_ID)));
        lenient().when(store.configuredOrderSyncTargets()).thenReturn(List.of(target(ORDER_TASK_ID)));
        lenient().when(store.configuredBusinessDictionarySyncTargets()).thenReturn(List.of(target(DICTIONARY_TASK_ID)));
        lenient().when(store.connector(TENANT_ID, CONNECTOR_ID)).thenReturn(new ConnectorView(CONNECTOR_ID,
                TENANT_ID, "DHB_TEST", "订货宝测试连接", "https://dhb.example",
                "env://DHB_TEST", "ACTIVE", 0));
        lenient().when(dhbClient.getStaff(any(), any())).thenAnswer(invocation -> {
            DhbClient.StaffQuery query = invocation.getArgument(1);
            return new DhbClient.Page<>(query == null ? DhbClient.PageRequest.first(1_000) : query.page(),
                    0, List.of());
        });
    }

    @Test
    void scheduledRunCallsDomainsInBusinessDependencyOrder() {
        List<String> calls = new ArrayList<>();
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
                    .thenAnswer(invocation -> {
                        calls.add("ERP:" + objectType);
                        return erpResult(objectType);
                    });
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
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
        when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq("CATEGORY"),
                eq(100), eq("SCHEDULED"), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    calls.add("ERP:CATEGORY");
                    return erpResult("CATEGORY");
                });
        when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq("BRAND"),
                eq(100), eq("SCHEDULED"), isNull(), isNull()))
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
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
                    .thenReturn(erpResult(objectType));
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
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

    @Test
    void scheduledRunPassesConfiguredWindowFromToStaffAndOrderSteps() {
        when(store.activeProductMasterSyncTargets()).thenReturn(List.of());
        when(store.activeSupplyChainSyncTargets()).thenReturn(List.of());
        when(store.activeCrmMasterSyncTargets()).thenReturn(List.of());
        when(store.activeBusinessDictionarySyncTargets()).thenReturn(List.of());
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setScheduledWindowFrom("2026-08-01T00:00:00Z");
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(), properties,
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());
        when(dhbClient.getStaff(any(), argThat(query ->
                query != null
                        && query.createdWindow() == null
                        && query.updatedWindow() != null
                        && Instant.parse("2026-08-01T00:00:00Z").equals(query.updatedWindow().from())
                        && Instant.parse("2026-08-21T07:58:00Z").equals(query.updatedWindow().to()))))
                .thenAnswer(invocation -> {
                    DhbClient.StaffQuery query = invocation.getArgument(1);
                    return new DhbClient.Page<>(query.page(), 0, List.of());
                });
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(command ->
                command != null
                        && Instant.parse("2026-08-01T00:00:00Z").equals(command.from())
                        && Instant.parse("2026-08-21T07:58:00Z").equals(command.to())
                        && command.pageSize() == null
                        && command.sourceObjectType() == null
                        && command.sourceId() == null), eq(100)))
                .thenReturn(orderResult());

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.steps()).extracting("objectType")
                        .containsExactly("BUSINESS_DICTIONARY", "STAFF", "PRODUCT_MASTER_DATA",
                                "CRM_MASTER_DATA", "SUPPLY_CHAIN_DATA", "ORDER_DOMAIN"));
    }

    @Test
    void scheduledRunPassesConfiguredWindowToUnifiedDomainSyncSteps() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setScheduledWindowFrom("2026-09-01");
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(), properties,
                Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());
        Instant from = Instant.parse("2026-08-31T16:00:00Z");
        Instant to = Instant.parse("2026-09-02T07:58:00Z");
        List<String> calls = new ArrayList<>();
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), eq(from), eq(to))).thenAnswer(invocation -> {
                calls.add("ERP:" + objectType);
                return erpResult(objectType);
            });
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), eq(from), eq(to))).thenAnswer(invocation -> {
                calls.add("ERP:" + objectType);
                return erpResult(objectType);
            });
        }
        when(crmClient.sync(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(100), eq(from), eq(to)))
                .thenAnswer(invocation -> {
                    calls.add("CRM:CRM_MASTER_DATA");
                    return crmResult();
                });
        when(dhbClient.getStaff(any(), argThat(query ->
                query != null
                        && query.updatedWindow() != null
                        && from.equals(query.updatedWindow().from())
                        && to.equals(query.updatedWindow().to()))))
                .thenAnswer(invocation -> {
                    DhbClient.StaffQuery query = invocation.getArgument(1);
                    return new DhbClient.Page<>(query.page(), 0, List.of());
                });
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(command ->
                command != null && from.equals(command.from()) && to.equals(command.to())), eq(100)))
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
    void scheduledRunRejectsEnabledSchedulerWithoutConfiguredWindowFrom() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setEnabled(true);
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(), properties,
                Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());

        assertThatThrownBy(service::runScheduled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduled-window-from不能为空");
    }

    @Test
    void scheduledRunSkipsTenantWhenDistributedOrchestrationLeaseIsBusy() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, busyLease(), properties,
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.tenants()).singleElement().satisfies(tenant -> {
            assertThat(tenant.status()).isEqualTo("SKIPPED");
            assertThat(tenant.steps()).singleElement().satisfies(step -> {
                assertThat(step.domain()).isEqualTo("INTEGRATION");
                assertThat(step.objectType()).isEqualTo("DHB_ORCHESTRATION");
                assertThat(step.message()).contains("已有统一同步编排批次运行中");
            });
        });
    }

    @Test
    void dictionaryBootstrapWarningsAreVisibleButDoNotStopDependentDomains() {
        when(dictionaryClient.sync(any(), eq("DHB_ORCHESTRATION_BASELINE"), any()))
                .thenReturn(new Audit(1, Map.of("DHB_PAYMENT_METHOD", -1L),
                        List.of(new MappingIssue("DHB_PAYMENT_METHOD", "fund.typeId",
                                "Offline", 1))));
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
                    .thenReturn(erpResult(objectType));
        }
        for (String objectType : List.of("SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER",
                "PURCHASE_RETURN", "WAREHOUSING_RECEIPT", "INVENTORY")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(SUPPLY_TASK_ID), eq(objectType),
                    eq(100), eq("SCHEDULED"), isNull(), isNull()))
                    .thenReturn(erpResult(objectType));
        }
        when(crmClient.sync(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(100)))
                .thenReturn(crmResult());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), isNull(), eq(100)))
                .thenReturn(orderResult());

        DhbSyncOrchestrationResult result = service.runScheduled();

        assertThat(result.status()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.steps().getFirst().message())
                        .contains("DHB_PAYMENT_METHOD.fund.typeId=Offlinex1"));
    }

    @Test
    void manualRunCanExecuteErpProductWithoutSupplyChainOrOtherDomains() {
        List<String> calls = new ArrayList<>();
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(10), eq("MANUAL"), isNull(), isNull()))
                    .thenAnswer(invocation -> {
                        calls.add("ERP:" + objectType);
                        return erpResult(objectType);
                    });
        }

        DhbSyncOrchestrationCommand command = new DhbSyncOrchestrationCommand(
                10, null, false, false, false, false, true, false);
        DhbSyncOrchestrationResult result = service.runManual(manualCaller(), command);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(calls).containsExactly(
                "ERP:CATEGORY",
                "ERP:BRAND",
                "ERP:SPECIFICATION",
                "ERP:TAG",
                "ERP:PRODUCT_SPU");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.steps()).extracting("objectType")
                        .containsExactly("CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU"));
    }

    @Test
    void manualRunUsesConfiguredTargetsWhenScheduledProductTaskIsPaused() {
        lenient().when(store.activeProductMasterSyncTargets()).thenReturn(List.of());
        when(store.configuredProductMasterSyncTargets()).thenReturn(List.of(target(PRODUCT_TASK_ID)));
        List<String> calls = new ArrayList<>();
        for (String objectType : List.of("CATEGORY", "BRAND", "SPECIFICATION", "TAG",
                "PRODUCT_SPU")) {
            when(erpClient.sync(any(), eq(CONNECTOR_ID), eq(PRODUCT_TASK_ID), eq(objectType),
                    eq(10), eq("MANUAL"), isNull(), isNull()))
                    .thenAnswer(invocation -> {
                        calls.add("ERP:" + objectType);
                        return erpResult(objectType);
                    });
        }

        DhbSyncOrchestrationCommand command = new DhbSyncOrchestrationCommand(
                10, null, false, false, false, false, true, false);
        DhbSyncOrchestrationResult result = service.runManual(manualCaller(), command);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(calls).containsExactly(
                "ERP:CATEGORY",
                "ERP:BRAND",
                "ERP:SPECIFICATION",
                "ERP:TAG",
                "ERP:PRODUCT_SPU");
    }

    @Test
    void manualRunStillWorksWhenSchedulerIsDisabledByConfiguration() {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setEnabled(false);
        service = new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(), properties,
                Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC),
                JsonMapper.builder().build());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), isNull(), eq(10)))
                .thenReturn(orderResult());

        DhbSyncOrchestrationCommand command = new DhbSyncOrchestrationCommand(
                10, false, false, true, false, false);
        DhbSyncOrchestrationResult result = service.runManual(manualCaller(), command);

        assertThat(result.triggerType()).isEqualTo("MANUAL");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.tenants()).singleElement().satisfies(tenant ->
                assertThat(tenant.steps()).singleElement().satisfies(step -> {
                    assertThat(step.domain()).isEqualTo("ORDER");
                    assertThat(step.objectType()).isEqualTo("ORDER_DOMAIN");
                    assertThat(step.status()).isEqualTo("SUCCEEDED");
                }));
    }

    @Test
    void receiptPageDispatchesOnlyReceiptAndNoMasterData() {
        var from=Instant.parse("2026-08-01T00:00:00Z");var to=Instant.parse("2026-08-20T00:00:00Z");
        when(orderSyncService.runOrderPull(any(),eq(ORDER_TASK_ID),argThat(c->"RECEIPT".equals(c.syncScope())&&from.equals(c.from())&&to.equals(c.to())),eq(10))).thenReturn(orderResult());
        var result=service.runPage(manualCaller(),new com.rigour.integration.api.v1.model.DhbPageSyncCommand(com.rigour.integration.api.v1.model.DhbPageSyncCommand.Scope.RECEIPT,CONNECTOR_ID,from,to,10));
        assertThat(result.tenants().getFirst().steps()).extracting("objectType").containsExactly("RECEIPT");
        org.mockito.Mockito.verifyNoInteractions(erpClient,crmClient,hrEmployeeClient,dhbClient,dictionaryClient);
        org.mockito.Mockito.verify(orderSyncService).runOrderPull(any(),eq(ORDER_TASK_ID),any(),eq(10));
        org.mockito.Mockito.verifyNoMoreInteractions(orderSyncService);
    }

    @Test
    void orderSalesPackageRunsThreeScopesInOrderAndKeepsFailedScopeResult() {
        var from=Instant.parse("2026-08-01T00:00:00Z");var to=Instant.parse("2026-08-20T00:00:00Z");
        List<String> calls=new ArrayList<>();
        when(orderSyncService.runOrderPull(any(),eq(ORDER_TASK_ID),
                argThat(c->"SALES_ORDER".equals(c.syncScope())&&from.equals(c.from())&&to.equals(c.to())),eq(10)))
                .thenAnswer(invocation->{calls.add("SALES_ORDER");return orderResult();});
        when(orderSyncService.runOrderPull(any(),eq(ORDER_TASK_ID),
                argThat(c->"RECEIPT".equals(c.syncScope())&&from.equals(c.from())&&to.equals(c.to())),eq(10)))
                .thenAnswer(invocation->{calls.add("RECEIPT");throw new IllegalStateException("receipt failed");});
        when(orderSyncService.runOrderPull(any(),eq(ORDER_TASK_ID),
                argThat(c->"PAYMENT".equals(c.syncScope())&&from.equals(c.from())&&to.equals(c.to())),eq(10)))
                .thenAnswer(invocation->{calls.add("PAYMENT");return orderResult();});

        var result=service.runPage(manualCaller(),
                new com.rigour.integration.api.v1.model.DhbPageSyncCommand(
                        com.rigour.integration.api.v1.model.DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE,
                        CONNECTOR_ID,from,to,10));

        assertThat(calls).containsExactly("SALES_ORDER","RECEIPT","PAYMENT");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.tenants().getFirst().steps())
                .extracting("objectType").containsExactly("SALES_ORDER","RECEIPT","PAYMENT");
        assertThat(result.tenants().getFirst().steps())
                .extracting("status").containsExactly("SUCCEEDED","FAILED","SUCCEEDED");
    }

    @Test
    void incrementalOrderPackageUsesPerScopeCheckpointWindowsAndAdvancesSuccessfulSteps() {
        service = serviceWithIncrementalWindow("2026-08-01T00:00:00+08:00",
                Instant.parse("2026-08-05T08:00:00Z"));
        Instant bootstrap = Instant.parse("2026-07-31T16:00:00Z");
        Instant end = Instant.parse("2026-08-05T07:58:00Z");
        Instant receiptCursor = Instant.parse("2026-08-04T08:00:00Z");
        when(objectCheckpoints.successfulTo(TENANT_ID, CONNECTOR_ID, "SALES_ORDER")).thenReturn(null);
        when(objectCheckpoints.successfulTo(TENANT_ID, CONNECTOR_ID, "RECEIPT")).thenReturn(receiptCursor);
        when(objectCheckpoints.successfulTo(TENANT_ID, CONNECTOR_ID, "PAYMENT")).thenReturn(null);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "SALES_ORDER".equals(c.syncScope()) && bootstrap.equals(c.from()) && end.equals(c.to())), eq(10)))
                .thenReturn(orderResult());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "RECEIPT".equals(c.syncScope())
                        && receiptCursor.minusSeconds(900).equals(c.from()) && end.equals(c.to())), eq(10)))
                .thenReturn(orderResult());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "PAYMENT".equals(c.syncScope()) && bootstrap.equals(c.from()) && end.equals(c.to())), eq(10)))
                .thenReturn(orderResult());

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.tenants().getFirst().steps())
                .extracting("objectType").containsExactly("SALES_ORDER", "RECEIPT", "PAYMENT");
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("SALES_ORDER"), eq(end), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("RECEIPT"), eq(end), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("PAYMENT"), eq(end), any());
    }

    @Test
    void incrementalOrderPackageBootstrapsAtCutoverDefaultWithoutScheduledWindowFrom() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-09-18T08:00:00Z"));
        Instant cutover = Instant.parse("2026-09-03T16:00:00Z");
        Instant firstSliceTo = Instant.parse("2026-09-10T16:00:00Z");
        Map<String, List<SyncRunCommand>> windows = new LinkedHashMap<>();
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(null);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), any(), eq(10)))
                .thenAnswer(invocation -> {
                    SyncRunCommand command = invocation.getArgument(2);
                    windows.computeIfAbsent(command.syncScope(), key -> new ArrayList<>()).add(command);
                    return orderResult();
                });

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.tenants().getFirst().steps())
                .extracting("status").containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertThat(result.tenants().getFirst().steps()).extracting("message")
                .allSatisfy(message -> assertThat(String.valueOf(message))
                        .contains("再次点击")
                        .doesNotContain("scheduled-window-from"));
        for (String scope : List.of("SALES_ORDER", "RECEIPT", "PAYMENT")) {
            assertThat(windows.get(scope)).hasSize(1);
            assertThat(windows.get(scope).getFirst().from()).isEqualTo(cutover);
            assertThat(windows.get(scope).getFirst().to()).isEqualTo(firstSliceTo);
            assertThat(windows.get(scope)).allSatisfy(command ->
                    assertThat(command.from()).isAfterOrEqualTo(cutover));
        }
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("SALES_ORDER"),
                eq(firstSliceTo), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("RECEIPT"),
                eq(firstSliceTo), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("PAYMENT"),
                eq(firstSliceTo), any());
    }

    @Test
    void incrementalOrderPackageContinuesFromExistingCheckpointWithOverlap() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-09-18T08:00:00Z"));
        Instant cursor = Instant.parse("2026-09-15T00:00:00Z");
        Instant expectedFrom = cursor.minusSeconds(900);
        Instant end = Instant.parse("2026-09-18T07:58:00Z");
        List<SyncRunCommand> commands = new ArrayList<>();
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(cursor);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), any(), eq(10)))
                .thenAnswer(invocation -> {
                    commands.add(invocation.getArgument(2));
                    return orderResult();
                });

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(commands).hasSize(3).allSatisfy(command -> {
            assertThat(command.from()).isEqualTo(expectedFrom);
            assertThat(command.to()).isEqualTo(end);
        });
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("SALES_ORDER"), eq(end), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("RECEIPT"), eq(end), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("PAYMENT"), eq(end), any());
    }

    @Test
    void incrementalOrderPackageClampsCheckpointOverlapAtCutoverBoundary() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-09-18T08:00:00Z"));
        Instant cutover = Instant.parse("2026-09-03T16:00:00Z");
        List<SyncRunCommand> commands = new ArrayList<>();
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(cutover);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), any(), eq(10)))
                .thenAnswer(invocation -> {
                    commands.add(invocation.getArgument(2));
                    return orderResult();
                });

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(commands).isNotEmpty();
        assertThat(commands.getFirst().from()).isEqualTo(cutover);
        assertThat(commands).allSatisfy(command ->
                assertThat(command.from()).isAfterOrEqualTo(cutover));
    }

    @Test
    void incrementalOrderPackageSkipsWhenIncrementalWindowEndIsAtCutoverBoundary() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-09-03T16:02:00Z"));
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(null);

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.tenants().getFirst().steps())
                .extracting("status").containsExactly("SKIPPED", "SKIPPED", "SKIPPED");
        verify(orderSyncService, never()).runOrderPull(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void incrementalOrderPackageFailureAdvancesSuccessfulScopesOnly() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-09-12T08:00:00Z"));
        Instant bootstrap = Instant.parse("2026-09-03T16:00:00Z");
        Instant firstSliceTo = Instant.parse("2026-09-10T16:00:00Z");
        Instant end = Instant.parse("2026-09-12T07:58:00Z");
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(null);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "SALES_ORDER".equals(c.syncScope()) && bootstrap.equals(c.from())
                        && firstSliceTo.equals(c.to())), eq(10)))
                .thenReturn(orderResult());
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "RECEIPT".equals(c.syncScope())), eq(10)))
                .thenThrow(new IllegalStateException("receipt failed"));
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), argThat(c ->
                "PAYMENT".equals(c.syncScope())), eq(10)))
                .thenReturn(orderResult());

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.tenants().getFirst().steps())
                .extracting("status").containsExactly("SUCCEEDED", "FAILED", "SUCCEEDED");
        assertThat(result.tenants().getFirst().steps().getFirst().message()).contains("再次点击");
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("SALES_ORDER"),
                eq(firstSliceTo), any());
        verify(objectCheckpoints, never()).completed(eq(TENANT_ID), eq(CONNECTOR_ID),
                eq("SALES_ORDER"), eq(end), any());
        verify(objectCheckpoints, never()).completed(eq(TENANT_ID), eq(CONNECTOR_ID),
                eq("RECEIPT"), any(), any());
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("PAYMENT"),
                eq(firstSliceTo), any());
        verify(objectCheckpoints, never()).completed(eq(TENANT_ID), eq(CONNECTOR_ID),
                eq("PAYMENT"), eq(end), any());
    }

    @Test
    void incrementalOrderPackageStopsAfterOneSlicePerClickAndAsksForAnotherClick() {
        service = serviceWithIncrementalWindow(null, Instant.parse("2026-11-01T00:00:00Z"));
        Map<String, Integer> calls = new LinkedHashMap<>();
        when(objectCheckpoints.successfulTo(any(), any(), any())).thenReturn(null);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), any(), eq(10)))
                .thenAnswer(invocation -> {
                    SyncRunCommand command = invocation.getArgument(2);
                    calls.merge(command.syncScope(), 1, Integer::sum);
                    return orderResult();
                });

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, null, null, 10, true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(calls).containsEntry("SALES_ORDER", 1)
                .containsEntry("RECEIPT", 1).containsEntry("PAYMENT", 1);
        assertThat(result.tenants().getFirst().steps()).extracting("message")
                .allSatisfy(message -> assertThat(String.valueOf(message)).contains("再次点击"));
        verify(objectCheckpoints).completed(eq(TENANT_ID), eq(CONNECTOR_ID), eq("SALES_ORDER"),
                eq(Instant.parse("2026-09-10T16:00:00Z")), any());
    }

    @Test
    void orderPackageStepExposesUpdatedAsChangedCount() {
        var from=Instant.parse("2026-08-01T00:00:00Z");var to=Instant.parse("2026-08-20T00:00:00Z");
        SyncRunView granular = new SyncRunView(UUID.randomUUID(), ORDER_TASK_ID, "SUCCEEDED",
                from, to, 10, 8, 1, 2, 3, 5, 0, null, null);
        when(orderSyncService.runOrderPull(any(), eq(ORDER_TASK_ID), any(), eq(10)))
                .thenReturn(granular);

        var result = service.runPage(manualCaller(), new DhbPageSyncCommand(
                DhbPageSyncCommand.Scope.ORDER_SALES_PACKAGE, CONNECTOR_ID, from, to, 10));

        var step = result.tenants().getFirst().steps().getFirst();
        assertThat(step.fetched()).isEqualTo(10);
        assertThat(step.created()).isEqualTo(3);
        assertThat(step.updated()).isEqualTo(5);
        assertThat(step.repaired()).isZero();
        assertThat(step.duplicates()).isEqualTo(1);
        assertThat(step.rejected()).isEqualTo(2);
    }

    private DhbSyncOrchestrationService serviceWithIncrementalWindow(String incrementalFrom, Instant clockAt) {
        DhbSyncOrchestrationProperties properties = new DhbSyncOrchestrationProperties();
        properties.setIncrementalWindowFrom(incrementalFrom);
        return new DhbSyncOrchestrationService(store, erpClient, crmClient, hrEmployeeClient,
                dhbClient, orderSyncService, objectCheckpoints, dictionaryClient, passthroughLease(),
                properties, Clock.fixed(clockAt, ZoneOffset.UTC), JsonMapper.builder().build());
    }

    @Test
    void areaPageTranslatesToOnlyCrmAreaObject() {
        var from=Instant.parse("2026-08-01T00:00:00Z");var to=Instant.parse("2026-08-20T00:00:00Z");
        when(crmClient.syncObject(any(),eq(CONNECTOR_ID),eq(CRM_TASK_ID),eq("CUSTOMER_AREA"),eq(10),eq(from),eq(to))).thenReturn(crmResult());
        service.runPage(manualCaller(),new com.rigour.integration.api.v1.model.DhbPageSyncCommand(com.rigour.integration.api.v1.model.DhbPageSyncCommand.Scope.AREA,CONNECTOR_ID,from,to,10));
        org.mockito.Mockito.verifyNoInteractions(erpClient,orderSyncService,hrEmployeeClient,dhbClient,dictionaryClient);
        org.mockito.Mockito.verify(crmClient).syncObject(any(),eq(CONNECTOR_ID),eq(CRM_TASK_ID),eq("CUSTOMER_AREA"),eq(10),eq(from),eq(to));
        org.mockito.Mockito.verifyNoMoreInteractions(crmClient);
    }

    @Test
    void customerPageUsesServerCursorWithoutCallingOtherDomains() {
        var actor = manualCaller();
        when(crmClient.syncLatestCustomers(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(10), eq(actor.principalId()))).thenReturn(crmResult());
        service.runPage(actor, new com.rigour.integration.api.v1.model.DhbPageSyncCommand(
                com.rigour.integration.api.v1.model.DhbPageSyncCommand.Scope.CUSTOMER, CONNECTOR_ID, null, null, 10, true));
        org.mockito.Mockito.verify(crmClient).syncLatestCustomers(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(10), eq(actor.principalId()));
        org.mockito.Mockito.verifyNoMoreInteractions(crmClient);
        org.mockito.Mockito.verifyNoInteractions(erpClient, orderSyncService, hrEmployeeClient, dhbClient, dictionaryClient);
    }

    @Test
    void customerResultPreservesCreatedUpdatedRepairedAndProblemCounts() {
        var actor = manualCaller();
        var object = new SyncObjectResult(UUID.randomUUID(), "CUSTOMER", "SUCCEEDED_WITH_WARNINGS",
                16, 2, 3, 4, 1, 0, 1, 1, Instant.now(), 5, Map.of());
        when(crmClient.syncLatestCustomers(any(), eq(CONNECTOR_ID), eq(CRM_TASK_ID), eq(10), eq(actor.principalId())))
                .thenReturn(new SyncResult(UUID.randomUUID(), "SUCCEEDED_WITH_WARNINGS", List.of(object)));
        var result = service.runPage(actor, new com.rigour.integration.api.v1.model.DhbPageSyncCommand(
                com.rigour.integration.api.v1.model.DhbPageSyncCommand.Scope.CUSTOMER, CONNECTOR_ID, null, null, 10, true));
        var step = result.tenants().getFirst().steps().getFirst();
        assertThat(step.created()).isEqualTo(2L);
        assertThat(step.updated()).isEqualTo(3L);
        assertThat(step.repaired()).isEqualTo(4L);
        assertThat(step.duplicates()).isEqualTo(1L);
        assertThat(step.rejected()).isEqualTo(1L);
        assertThat(step.unmapped()).isEqualTo(5L);
        assertThat(step.changed()).isEqualTo(9L);
    }

    @Test
    void receiptUnknownStateIsNeverAnEffectivePayment() {
        assertThat(DhbOrderSyncService.verifiedReceiptStatus("unexpected-status")).isEqualTo("UNKNOWN");
        assertThat(DhbOrderSyncService.verifiedReceiptStatus(null)).isEqualTo("UNKNOWN");
        assertThat(DhbOrderSyncService.verifiedReceiptStatus("pend_receipted")).isEqualTo("PENDING");
        assertThat(DhbOrderSyncService.verifiedReceiptStatus("cancelled")).isEqualTo("CANCELLED");
    }

    private static SyncTargetView target(UUID taskId) {
        return new SyncTargetView(taskId, TENANT_ID, CONNECTOR_ID);
    }

    private static ErpDataSyncResult erpResult(String objectType) {
        return new ErpDataSyncResult(UUID.randomUUID(), objectType, "SUCCEEDED", CONNECTOR_ID,
                1, 1, 0, 0, 0, 0, Map.of(), Map.of(), 1,
                Instant.parse("2026-08-21T08:00:00Z"));
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

    private static CallerIdentity manualCaller() {
        UUID userId = UUID.randomUUID();
        return new CallerIdentity("TENANT", userId, TENANT_ID, userId, null,
                UUID.randomUUID(), 0, 0, 1,
                Set.of("INTEGRATION_ADMIN"), Set.of("integration:dhb:read", "integration:dhb:write"));
    }

    private static DhbOrchestrationLease passthroughLease() {
        return new DhbOrchestrationLease() {
            @Override
            public <T> T execute(UUID tenantId, UUID connectorId, String ownerId,
                                 java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
    }

    private static DhbOrchestrationLease busyLease() {
        return new DhbOrchestrationLease() {
            @Override
            public <T> T execute(UUID tenantId, UUID connectorId, String ownerId,
                                 java.util.function.Supplier<T> action) {
                throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                        "当前订货宝连接器已有统一同步编排批次运行中", List.of());
            }
        };
    }
}
