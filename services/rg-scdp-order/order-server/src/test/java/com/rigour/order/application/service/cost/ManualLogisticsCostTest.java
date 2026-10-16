package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class ManualLogisticsCostTest {
    final OrderLogisticsCostStore store = mock(OrderLogisticsCostStore.class);
    final OrderLogisticsReferences refs = mock(OrderLogisticsReferences.class);
    final OrderLogisticsCostService service = new OrderLogisticsCostService(store, refs);
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
                        Set.of("order:logistics-cost:write")));
        when(refs.validCodes(any(), any())).thenReturn(Set.of("UNPAID", "COMPANY", "EXPRESS"));
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    LogisticsCostCommand command(String mode, String sources, String extra) {
        return json.readValue(
                "{\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"title\":\"运费\",\"cityCode\":\"C1\",\"cityName\":\"城市\",\"costDate\":\"2026-10-15\",\"paymentStatusCode\":\"UNPAID\",\"logistics\":{\"legCode\":\"DELIVERY\",\"entryMode\":\""
                        + mode
                        + "\",\"sourceIds\":"
                        + sources
                        + ",\"transportMethodCode\":\"EXPRESS\",\"payerCode\":\"COMPANY\",\"senderName\":\"城市仓\",\"receiverName\":\"客户\",\"originAddress\":\"武汉\",\"destinationAddress\":\"门店\""
                        + extra
                        + "}}",
                LogisticsCostCommand.class);
    }

    String dhb =
            ",\"externalDocuments\":[{\"sourceSystem\":\"DHB\",\"documentType\":\"DELIVERY\",\"documentNo\":\"DHB-ORDER-9\",\"documentDate\":\"2026-10-01\",\"senderName\":\"城市仓\",\"receiverName\":\"客户\"}]";

    @Test
    void manualDhbSourceSavesWithoutLocalSalesOrderAndKeepsUnknownAmount() {
        var c = command("MANUAL", "[]", dhb);
        service.save("logistics", null, c);
        verify(store, never()).sources(any(), any(), any(), any(), any());
        verify(store)
                .save(
                        any(),
                        eq("LOGISTICS"),
                        isNull(),
                        eq(c),
                        eq(List.of()),
                        isNull(),
                        isNull(),
                        any(),
                        eq("order:logistics-cost:write"));
        assertThat(c.amount()).isNull();
    }

    @Test
    void manualSourceMustIncludeOriginalDocument() {
        assertThatThrownBy(() -> service.save("logistics", null, command("MANUAL", "[]", "")))
                .hasMessageContaining("外部来源单据");
    }

    @Test
    void manualAndLocalIdsCannotBeMixed() {
        assertThatThrownBy(() -> service.save("logistics", null, command("MANUAL", "[1]", dhb)))
                .hasMessageContaining("手工录入不能");
    }

    @Test
    void localModeRequiresRealAccessibleOrders() {
        when(store.sources(any(), any(), any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.save("logistics", null, command("LINKED", "[1]", "")))
                .hasMessageContaining("无访问权限");
    }

    @Test
    void untrustedLocalCustomerNameIsRejected() {
        when(refs.party(eq("customers"), eq(1L), any(), any())).thenReturn("另一个客户");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "logistics",
                                        null,
                                        command("MANUAL", "[]", dhb + ",\"customerId\":1")))
                .hasMessageContaining("客户不一致");
    }

    @Test
    void manualReferencesMustMatchTheBusinessRolesOfEachLeg() {
        for (String leg : List.of("PURCHASE", "TRANSFER", "DELIVERY")) {
            String wrong = leg.equals("DELIVERY") ? ",\"supplierId\":1" : ",\"customerId\":1";
            var original = command("MANUAL", "[]", dhb + wrong);
            var c =
                    json.readValue(
                            json.writeValueAsString(original).replace("DELIVERY", leg),
                            LogisticsCostCommand.class);
            assertThatThrownBy(() -> service.save("logistics", null, c))
                    .hasMessageContaining("只能关联");
        }
        verifyNoInteractions(store);
    }

    @Test
    void manualSupplierAndCentralWarehouseKeepTheirVerifiedNames() {
        var original = command("MANUAL", "[]", dhb + ",\"supplierId\":1,\"targetWarehouseId\":2");
        var c =
                json.readValue(
                        json.writeValueAsString(original)
                                .replace("DELIVERY", "PURCHASE")
                                .replace("城市仓", "供应商A")
                                .replace("客户", "中心仓B"),
                        LogisticsCostCommand.class);
        when(refs.party(eq("suppliers"), eq(1L), any(), any())).thenReturn("供应商A");
        when(refs.warehouseDetail(eq(2L), any()))
                .thenReturn(
                        new LogisticsCostWarehouseView("2", "中心仓B", "中心仓B", "CENTER", "C1", "地址"));
        service.save("logistics", null, c);
        verify(store)
                .save(
                        any(),
                        eq("LOGISTICS"),
                        isNull(),
                        eq(c),
                        eq(List.of()),
                        isNull(),
                        isNull(),
                        any(),
                        eq("order:logistics-cost:write"));
    }

    @Test
    void lackingWritePermissionCannotRecordExternalSource() {
        TestAuthorizationContext.clear();
        assertThatThrownBy(() -> service.save("logistics", null, command("MANUAL", "[]", dhb)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    LogisticsCostSourceView source(long id, String city, Long warehouse, String customer) {
        return json.readValue(
                "{\"id\":"
                        + id
                        + ",\"documentNo\":\"S"
                        + id
                        + "\",\"sourceType\":\"DELIVERY\",\"cityCode\":\""
                        + city
                        + "\",\"warehouseId\":"
                        + warehouse
                        + ",\"senderName\":\"城市仓\",\"receiverName\":\""
                        + customer
                        + "\",\"warehouseTypeCode\":\"CITY\",\"sourceSystem\":\"SYSTEM\",\"statusCode\":\"APPROVED\"}",
                LogisticsCostSourceView.class);
    }

    @Test
    void importedOrderRequiresExplicitWarehouseWithoutChangingItsSourceSnapshot() {
        var missing = source(1, "C1", null, "客户");
        when(store.sources(any(), any(), any(), any(), any())).thenReturn(List.of(missing));
        var validator = new LogisticsCostValidator(store, refs);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "T", command("LINKED", "[1]", ""), "cost:write", false))
                .hasMessageContaining("补充本次实际发货城市仓");
        when(refs.warehouseDetail(eq(7L), any()))
                .thenReturn(new LogisticsCostWarehouseView("7", "城市仓", "城市仓", "CITY", "C1", "地址"));
        var completed =
                command(
                        "LINKED",
                        "[1]",
                        ",\"sourceWarehouseId\":7,\"carrierName\":\"承运商\",\"deliveryStops\":[{\"sourceId\":1,\"customerName\":\"客户\",\"address\":\"门店\"}]");
        assertThat(validator.validate("T", completed, "cost:confirm", true))
                .containsExactly(missing);
        assertThat(missing.warehouseId()).isNull();
        when(refs.warehouseDetail(eq(7L), any()))
                .thenReturn(new LogisticsCostWarehouseView("7", "城市仓", "城市仓", "CITY", "C2", "地址"));
        assertThatThrownBy(() -> validator.validate("T", completed, "cost:write", false))
                .hasMessageContaining("归属城市不一致");
    }

    @Test
    void supplementalWarehouseCannotOverrideAKnownOrderWarehouse() {
        when(store.sources(any(), any(), any(), any(), any()))
                .thenReturn(List.of(source(1, "C1", null, "客户"), source(2, "C1", 8L, "乙")));
        when(refs.warehouseDetail(eq(7L), any()))
                .thenReturn(new LogisticsCostWarehouseView("7", "城市仓", "城市仓", "CITY", "C1", "地址"));
        assertThatThrownBy(
                        () ->
                                new LogisticsCostValidator(store, refs)
                                        .validate(
                                                "T",
                                                command(
                                                        "LINKED",
                                                        "[1,2]",
                                                        ",\"sourceWarehouseId\":7"),
                                                "cost:write",
                                                false))
                .hasMessageContaining("不能跨仓合运");
    }

    @Test
    void sameCityWarehouseMultipleCustomersRequireEachDeliveryAddress() {
        when(store.sources(any(), any(), any(), any(), any()))
                .thenReturn(List.of(source(1, "C1", 7L, "甲"), source(2, "C1", 7L, "乙")));
        var validator = new LogisticsCostValidator(store, refs);
        var c =
                command(
                        "LINKED",
                        "[1,2]",
                        ",\"carrierName\":\"承运商\",\"deliveryStops\":[{\"sourceId\":1,\"customerName\":\"甲\",\"address\":\"门店甲\"}]");
        assertThat(validator.validate("T", c, "cost:write", false)).hasSize(2);
        assertThatThrownBy(() -> validator.validate("T", c, "cost:confirm", true))
                .hasMessageContaining("每张销售订单");
        var complete =
                command(
                        "LINKED",
                        "[1,2]",
                        ",\"carrierName\":\"承运商\",\"deliveryStops\":[{\"sourceId\":1,\"customerName\":\"甲\",\"address\":\"门店甲\"},{\"sourceId\":2,\"customerName\":\"乙\",\"address\":\"门店乙\"}]");
        assertThat(validator.validate("T", complete, "cost:confirm", true)).hasSize(2);
    }

    @Test
    void crossWarehouseOrdersAndForgedLinkedPartyAreRejected() {
        when(store.sources(any(), any(), any(), any(), any()))
                .thenReturn(List.of(source(1, "C1", 7L, "甲"), source(2, "C1", 8L, "乙")));
        assertThatThrownBy(() -> service.save("logistics", null, command("LINKED", "[1,2]", "")))
                .hasMessageContaining("同一");
        when(store.sources(any(), any(), any(), any(), any()))
                .thenReturn(List.of(source(1, "C1", 7L, "甲")));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "logistics",
                                        null,
                                        command("LINKED", "[1]", ",\"customerId\":99")))
                .hasMessageContaining("手填对象引用");
    }

    @Test
    void noDocumentDraftCanBeSavedButConfirmationRequiresCarrierReasonAndPartiesExplanation() {
        var validator = new LogisticsCostValidator(store, refs);
        var draft = command("NONE", "[]", "");
        assertThat(validator.validate("T", draft, "cost:write", false)).isEmpty();
        assertThatThrownBy(() -> validator.validate("T", draft, "cost:confirm", true))
                .hasMessageContaining("承运方");
        var carrier = command("NONE", "[]", ",\"carrierName\":\"自配送\"");
        assertThatThrownBy(() -> validator.validate("T", carrier, "cost:confirm", true))
                .hasMessageContaining("原因");
        var completed =
                command(
                        "NONE",
                        "[]",
                        ",\"carrierName\":\"自配送\",\"noSourceReason\":\"临时补送无单\",\"manualPartyReason\":\"外部城市仓实际配送\"");
        assertThat(validator.validate("T", completed, "cost:confirm", true)).isEmpty();
    }

    @Test
    void mixedSourcesRemainOneRecordAndPreserveExternalIdentity() {
        when(store.sources(any(), any(), any(), any(), any()))
                .thenReturn(List.of(source(1, "C1", 7L, "客户")));
        when(refs.warehouseDetail(eq(7L), any()))
                .thenReturn(new LogisticsCostWarehouseView("7", "城市仓", "城市仓", "CITY", "C1", "地址"));
        var c = command("MIXED", "[1]", dhb + ",\"sourceWarehouseId\":7");
        service.save("logistics", null, c);
        verify(store, times(1))
                .save(
                        any(),
                        eq("LOGISTICS"),
                        isNull(),
                        eq(c),
                        argThat(rows -> rows.size() == 1),
                        any(),
                        any(),
                        any(),
                        any());
        assertThat(c.logistics().externalDocuments().getFirst().sourceSystem()).isEqualTo("DHB");
    }
}
