package com.rigour.erp.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.stream.Stream;

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

    static Stream<Arguments> matchingSpecifications() {
        return Stream.of("purchases", "losses")
                .flatMap(
                        category ->
                                Stream.of(
                                        Arguments.of(category, null, ""),
                                        Arguments.of(category, null, null),
                                        Arguments.of(category, "", null),
                                        Arguments.of(category, " \t", ""),
                                        Arguments.of(category, "12桶/箱", "12桶/箱")));
    }

    @ParameterizedTest
    @MethodSource("matchingSpecifications")
    void linkedSkuAcceptsEquivalentOptionalSpecificationOnSaveAndConfirm(
            String category, String sourceSpecification, String formSpecification) {
        linkedSku(sourceSpecification);
        var c = linkedGoods(formSpecification, "BUCKET");
        service.save(category, null, c);
        verify(store)
                .save(
                        any(),
                        eq(ErpCostService.category(category)),
                        isNull(),
                        eq(c),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
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
        service.action(category, "E", new ErpCostActionCommand(1, ""), true);
        verify(store)
                .transition(
                        any(), eq(ErpCostService.category(category)), eq("E"), any(), any(), any());
        assertThat(c.goods().unit()).isEqualTo("BUCKET");
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "purchases,12桶/箱,6桶/箱,BUCKET,规格",
                "losses,12桶/箱,'',BUCKET,规格",
                "purchases,NULL,12桶/箱,BUCKET,规格",
                "purchases,NULL,'',BOX,单位",
                "losses,NULL,'',桶,单位"
            })
    void linkedSkuStillRejectsChangedSpecificationOrUnit(
            String category,
            String sourceSpecification,
            String formSpecification,
            String unit,
            String field) {
        linkedSku(sourceSpecification);
        assertThatThrownBy(() -> service.save(category, null, linkedGoods(formSpecification, unit)))
                .hasMessageContaining(field + "与 SKU 不一致");
        verify(store, never()).save(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void linkedSku(String specification) {
        when(store.references(any(), eq("products"), any(), eq(1L), isNull(), anyInt(), any()))
                .thenReturn(
                        List.of(new ErpCostReferenceView("1", "P1", "真实商品", 1L, "12桶/箱", "BOX")));
        when(store.references(any(), eq("variants"), any(), eq(2L), eq(1L), anyInt(), any()))
                .thenReturn(
                        List.of(
                                new ErpCostReferenceView(
                                        "2", "SKU2", "真实商品", 1L, specification, "BUCKET")));
    }

    private ErpCostCommand linkedGoods(String specification, String unit) {
        var goods = new HashMap<String, Object>();
        goods.put("productId", 1);
        goods.put("variantId", 2);
        goods.put("productName", "真实商品");
        goods.put("specification", specification);
        goods.put("unit", unit);
        goods.put("unitPrice", 1);
        goods.put("quantity", 1);
        goods.put("supplierName", "供应商");
        goods.put("location", "手填地点");
        goods.put("lossReason", "破损");
        return command("\"amount\":1,\"goods\":" + json.writeValueAsString(goods));
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
