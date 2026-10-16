package com.rigour.erp.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class ManualErpCostTest {
    final ErpCostStore store = mock(ErpCostStore.class);
    final ErpProductDictionary dict = mock(ErpProductDictionary.class);
    final CostReferenceSource refs = mock(CostReferenceSource.class);
    final ErpCostService service = new ErpCostService(store, dict, refs);
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        when(refs.city(eq("C1"), any())).thenReturn("城市");
        UUID u = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        u,
                        UUID.randomUUID(),
                        u,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("*:*:*")));
        when(dict.validCodes(anyString(), anyString()))
                .thenReturn(Set.of("UNPAID", "PAID", "OFFICE", "WAREHOUSE", "PROMOTION"));
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    ErpCostCommand command(String extra) {
        return json.readValue(
                "{\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"title\":\"台账\",\"cityCode\":\"C1\",\"cityName\":\"城市\",\"costDate\":\"2026-10-15\",\"paymentStatusCode\":\"UNPAID\","
                        + extra
                        + "}",
                ErpCostCommand.class);
    }

    @Test
    void priceOnlyPurchaseStaysUnknownAndHasNoInventoryDependency() {
        var c = command("\"goods\":{\"productName\":\"手填品\",\"unit\":\"箱\",\"unitPrice\":12.34}");
        service.save("purchases", null, c);
        verify(store)
                .save(
                        anyString(),
                        eq("PURCHASE"),
                        isNull(),
                        eq(c),
                        eq(List.of()),
                        isNull(),
                        isNull(),
                        anyString(),
                        eq("erp:cost:purchase:write"));
        verify(refs, never()).employees(any());
        verify(refs, never()).deliveries(any(), any());
    }

    @Test
    void officeDoesNotRequireWarehouseContractOrDeposit() {
        service.save(
                "warehouse-rentals",
                null,
                command(
                        "\"costMonth\":\"2026-10\",\"rental\":{\"premisesType\":\"OFFICE\",\"premisesName\":\"办公室\",\"area\":80}"));
        verify(store, never()).warehouse(any(), any(), any());
    }

    @Test
    void manualMarketingOwnerCanBeEnteredWithoutHrMaster() {
        service.save(
                "marketing",
                null,
                command("\"typeCode\":\"PROMOTION\",\"ownerName\":\"经办人\",\"payeeName\":\"广告方\""));
        verify(refs, never()).employees(any());
        verify(refs, never()).deliveries(any(), any());
    }

    @Test
    void lossRequiresPositiveQuantityAndReason() {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "losses",
                                        null,
                                        command(
                                                "\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"quantity\":0,\"lossReason\":\"破损\"}")))
                .hasMessageContaining("数量");
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void linkedProductCannotUseFabricatedSnapshot() {
        when(store.references(any(), eq("products"), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(new ErpCostReferenceView("1", "P1", "真实商品", 1L, "", "箱")));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "purchases",
                                        null,
                                        command(
                                                "\"goods\":{\"productId\":1,\"productName\":\"伪造名\",\"unit\":\"箱\",\"unitPrice\":1}")))
                .hasMessageContaining("主档不一致");
    }

    @Test
    void nonMatchingAmountCanBeSavedButNeedsReasonBeforeConfirmation() {
        var command =
                command(
                        "\"amount\":30,\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"unitPrice\":10,\"quantity\":2,\"supplierName\":\"供应商\"}");
        service.save("purchases", null, command);
        var row =
                new ErpCostDetailView(
                        "E",
                        "CST-1",
                        "PURCHASE",
                        "DRAFT",
                        1,
                        command,
                        List.of(),
                        null,
                        null,
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        "U",
                        "U");
        when(store.entry(any(), eq("PURCHASE"), eq("E"), any())).thenReturn(row);
        assertThatThrownBy(
                        () ->
                                service.action(
                                        "purchases", "E", new ErpCostActionCommand(1, ""), true))
                .hasMessageContaining("不一致");
        verify(store, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void manualDepartmentCannotForgeOwnership() {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "purchases",
                                        null,
                                        command(
                                                "\"departmentId\":1,\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"unitPrice\":1}")))
                .hasMessageContaining("部门或员工");
    }

    @Test
    void dhbDocumentIsSnapshotAndDoesNotCallLocalOrderLookup() {
        var c =
                command(
                        "\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"unitPrice\":1,\"externalDocument\":{\"sourceSystem\":\"DHB\",\"documentType\":\"PURCHASE\",\"documentNo\":\"DHB-10\",\"documentDate\":\"2026-10-01\",\"senderName\":\"厂家\",\"receiverName\":\"仓\"}}");
        service.save("purchases", null, c);
        verify(store, never()).sources(any(), any(), any(), any(), any());
        assertThat(c.goods().externalDocument().sourceSystem()).isEqualTo("DHB");
    }

    @Test
    void confirmationRevalidatesLegacyLossAndRentalRequiredFields() {
        for (String category : List.of("losses", "warehouse-rentals")) {
            var c =
                    category.equals("losses")
                            ? command(
                                    "\"amount\":10,\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"quantity\":1,\"lossReason\":\"破损\"}")
                            : command(
                                    "\"amount\":10,\"costMonth\":\"2026-10\",\"rental\":{\"premisesType\":\"UNKNOWN\",\"premisesName\":\"场地\",\"area\":80}");
            var row =
                    new ErpCostDetailView(
                            "E",
                            "CST-1",
                            ErpCostService.category(category),
                            "DRAFT",
                            1,
                            c,
                            List.of(),
                            null,
                            null,
                            java.time.Instant.now(),
                            java.time.Instant.now(),
                            "U",
                            "U");
            when(store.entry(any(), any(), eq("E"), any())).thenReturn(row);
            assertThatThrownBy(
                            () ->
                                    service.action(
                                            category, "E", new ErpCostActionCommand(1, ""), true))
                    .hasMessageContaining(category.equals("losses") ? "发生地点" : "类型待核对");
        }
        verify(store, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cityNameCannotBeSpoofedEvenForManualRecord() {
        when(refs.city(eq("C1"), any())).thenReturn("实际城市");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "purchases",
                                        null,
                                        command(
                                                "\"goods\":{\"productName\":\"品\",\"unit\":\"箱\",\"unitPrice\":1}")))
                .hasMessageContaining("城市名称");
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
