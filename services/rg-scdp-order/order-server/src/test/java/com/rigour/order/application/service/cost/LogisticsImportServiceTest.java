package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.port.out.LogisticsImportStore;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

class LogisticsImportServiceTest {
    final LogisticsImportStore store = mock(LogisticsImportStore.class);
    final OrderLogisticsCostService costs = mock(OrderLogisticsCostService.class);
    final LogisticsImportService service = new LogisticsImportService(store, costs);
    final JsonMapper json = JsonMapper.builder().build();
    UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();

    @BeforeEach
    void setup() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:logistics-cost:write")));
    }

    @AfterEach
    void cleanup() {
        TestAuthorizationContext.clear();
    }

    CarrierOrder bill(String status) {
        return new CarrierOrder(
                "67596986",
                "3616894445226123730",
                status,
                "2026-11-06 10:00:00",
                new BigDecimal("40.50"),
                Map.of("额外费用", "6.54"),
                List.of(),
                4);
    }

    Row row(String status, String costId) {
        return new Row("R1", 1, "UNMATCHED", costId, "货拉拉.xlsx", bill(status));
    }

    LogisticsCostCommand command() {
        return json.readValue(
                """
{"costDate":"2026-11-06","ledger":{"schemaVersion":2,"transportPurposeCode":"DELIVERY","deliveryModeCode":"THIRD_PARTY","carrierCode":"HLL","trackingNo":"3616894445226123730",
"sources":[{"orderTypeCode":"SALES_ORDER","orderCode":"SO001","linkRoleCode":"COST","allocations":[]}],
"feeItems":[{"feeKey":"F1","feeAmount":40.5,"ourAmount":40.5,"bearerCode":"COMPANY"}]}}
""",
                LogisticsCostCommand.class);
    }

    @Test
    void uploadOnlyStagesTheOriginalBillAndNeverCreatesOrConfirmsCosts() {
        var upload = new Upload("货拉拉.xlsx", "a".repeat(64), List.of(bill("已完成")));
        service.stage(upload);
        verify(store).stage(tenant.toString(), user.toString(), upload);
        verifyNoInteractions(costs);
    }

    @Test
    void leanTemplateDoesNotRequireEnterpriseAccountAndDeduplicatesByCarrierOrderNumber() {
        var bill = bill("已完成");
        var lean =
                new CarrierOrder(
                        "",
                        bill.orderNo(),
                        bill.status(),
                        bill.useTime(),
                        bill.paidAmount(),
                        bill.raw(),
                        List.of(),
                        2);
        var upload = new Upload("精简模板.xlsx", "a".repeat(64), List.of(lean));
        service.stage(upload);
        verify(store).stage(tenant.toString(), user.toString(), upload);
        assertThatThrownBy(
                        () ->
                                service.stage(
                                        new Upload("重复.xlsx", "a".repeat(64), List.of(lean, bill))))
                .hasMessageContaining("重复");
        verifyNoInteractions(costs);
    }

    @Test
    void generationUsesTheBillAmountOnceAndIsIdempotent() {
        var saved =
                json.readValue(
                        "{\"id\":\"C1\",\"statusCode\":\"DRAFT\",\"revision\":1}",
                        LogisticsCostDetailView.class);
        when(store.lock(tenant.toString(), user.toString(), "R1")).thenReturn(row("已完成", null));
        when(costs.save(eq("logistics"), isNull(), any())).thenReturn(saved);
        service.generate("R1", new Generated(1, command()));
        verify(costs).save("logistics", null, command());
        verify(store).generated(tenant.toString(), "R1", 1, "C1");
        when(store.lock(tenant.toString(), user.toString(), "R1")).thenReturn(row("已完成", "C1"));
        when(costs.entry("logistics", "C1")).thenReturn(saved);
        assertThat(service.generate("R1", new Generated(1, command()))).isSameAs(saved);
        verify(costs, times(1)).save(any(), any(), any());
        verify(costs, never()).action(any(), any(), any(), anyBoolean());
    }

    @Test
    void missingSalesOrderCanceledBillAndChangedAmountCannotGenerateCosts() {
        when(store.lock(any(), any(), eq("R1"))).thenReturn(row("已完成", null));
        var missing =
                json.readValue(
                        json.writeValueAsString(command()).replace("SALES_ORDER", "NONE"),
                        LogisticsCostCommand.class);
        assertThatThrownBy(() -> service.generate("R1", new Generated(1, missing)))
                .hasMessageContaining("已有销售单");
        var changed =
                json.readValue(
                        json.writeValueAsString(command()).replace("40.5", "47.04"),
                        LogisticsCostCommand.class);
        assertThatThrownBy(() -> service.generate("R1", new Generated(1, changed)))
                .hasMessageContaining("实付金额");
        when(store.lock(any(), any(), eq("R1"))).thenReturn(row("已取消", null));
        assertThatThrownBy(() -> service.generate("R1", new Generated(1, command())))
                .hasMessageContaining("已取消");
        verifyNoInteractions(costs);
    }

    @Test
    void duplicateCarrierCostAndStaleRevisionAreBlocked() {
        when(store.lock(any(), any(), eq("R1"))).thenReturn(row("已完成", null));
        assertThatThrownBy(() -> service.generate("R1", new Generated(0, command())))
                .hasMessageContaining("版本");
        when(store.trackingExists(tenant.toString(), bill("已完成").orderNo())).thenReturn(true);
        assertThatThrownBy(() -> service.generate("R1", new Generated(1, command())))
                .hasMessageContaining("避免重复");
        verifyNoInteractions(costs);
    }

    @Test
    void invalidWorkbookRowsAreRejectedBeforeAnyStaging() {
        var invalid =
                new CarrierOrder(
                        "67596986",
                        "3.616E18",
                        "已完成",
                        "2026-11-06",
                        BigDecimal.ZERO,
                        Map.of(),
                        List.of(),
                        2);
        assertThatThrownBy(
                        () ->
                                service.stage(
                                        new Upload("货拉拉.xlsx", "a".repeat(64), List.of(invalid))))
                .hasMessageContaining("完整文本");
        var badDate =
                new CarrierOrder(
                        "67596986",
                        "HL000003",
                        "已完成",
                        "2026-02-31",
                        BigDecimal.ZERO,
                        Map.of(),
                        List.of(),
                        3);
        assertThatThrownBy(
                        () ->
                                service.stage(
                                        new Upload(
                                                "货拉拉.xlsx",
                                                "a".repeat(64),
                                                List.of(bill("已完成"), badDate))))
                .hasMessageContaining("日期无效");
        verifyNoInteractions(store, costs);
    }

    @Test
    void readOnlyUserCannotStageOrGenerate() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("order:logistics-cost:read")));
        assertThatThrownBy(() -> service.rows(0)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.generate("R1", new Generated(1, command())))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store, costs);
    }
}
