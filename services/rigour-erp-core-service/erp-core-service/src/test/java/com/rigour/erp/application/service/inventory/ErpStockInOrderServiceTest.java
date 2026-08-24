package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.InternalProcurementStockInCommand;
import com.rigour.erp.api.v1.model.InternalProcurementStockInLineCommand;
import com.rigour.erp.api.v1.model.InternalStockInOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockInOrderLineView;
import com.rigour.erp.api.v1.model.InternalStockInOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockInOrderStore;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderLineSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementOrderSnapshot;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.ProcurementStockInWrite;
import com.rigour.erp.application.port.out.ErpStockInOrderStore.StockInOrderSearchCriteria;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpStockInOrderServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-3100-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-3100-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();
    private static final Instant STOCK_IN_TIME = Instant.parse("2026-08-28T04:00:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void confirmProcurementStockInCreatesConfirmedStockInAndFlowWrites() {
        ErpStockInOrderStore store = mock(ErpStockInOrderStore.class);
        ErpStockInOrderService service = new ErpStockInOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.procurementOrderForStockIn(TENANT, 1L)).thenReturn(Optional.of(procurementOrder("SUBMITTED", 2,
                line(11L, 1, "10", "2"),
                line(12L, 2, "5", "0"))));
        when(store.existsByStockInNo(TENANT, "SI202608201234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmProcurementStockIn(eq(TENANT), eq("SI202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(100L, "SI202608201234"));

        service.confirmProcurementStockIn(new InternalProcurementStockInCommand(
                1L, 2, STOCK_IN_TIME,
                List.of(new InternalProcurementStockInLineCommand(11L, new BigDecimal("3"), " 本次入库 ")),
                " 采购到货 "));

        ArgumentCaptor<ProcurementStockInWrite> command = ArgumentCaptor.forClass(ProcurementStockInWrite.class);
        verify(store).confirmProcurementStockIn(eq(TENANT), eq("SI202608201234"), command.capture(), eq(ACTOR));
        ProcurementStockInWrite write = command.getValue();
        assertThat(write.procurementOrderId()).isEqualTo(1L);
        assertThat(write.procurementRevision()).isEqualTo(2);
        assertThat(write.stockInTypeCode()).isEqualTo("PURCHASE");
        assertThat(write.statusCode()).isEqualTo("CONFIRMED");
        assertThat(write.nextProcurementStatusCode()).isEqualTo("PARTIAL_IN");
        assertThat(write.stockInTime()).isEqualTo(STOCK_IN_TIME);
        assertThat(write.warehouseId()).isEqualTo(2L);
        assertThat(write.supplierId()).isEqualTo(3L);
        assertThat(write.procurementNo()).isEqualTo("PO202608200001");
        assertThat(write.remark()).isEqualTo("采购到货");
        assertThat(write.lines()).hasSize(1);
        assertThat(write.lines().get(0).procurementOrderLineId()).isEqualTo(11L);
        assertThat(write.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(write.lines().get(0).productCode()).isEqualTo("PRD-1");
        assertThat(write.lines().get(0).variantCode()).isEqualTo("SKU-1");
        assertThat(write.lines().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(write.lines().get(0).unitPrice()).isEqualByComparingTo("8.50");
        assertThat(write.lines().get(0).amount()).isEqualByComparingTo("25.50");
        assertThat(write.lines().get(0).flowNo()).startsWith("SF20260820110000000");
        assertThat(write.lines().get(0).remark()).isEqualTo("本次入库");
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpStockInOrderStore store = mock(ErpStockInOrderStore.class);
        ErpStockInOrderService service = new ErpStockInOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:read"));
        when(store.stockInOrders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<InternalStockInOrderSummaryView>(0, 0, 20, List.of()));
        Instant from = Instant.parse("2026-08-20T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");

        service.stockInOrders(0, 20, " si ", " purchase ", 1L, 2L, 3L, " confirmed ", from, to);

        ArgumentCaptor<StockInOrderSearchCriteria> criteria =
                ArgumentCaptor.forClass(StockInOrderSearchCriteria.class);
        verify(store).stockInOrders(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().stockInNo()).isEqualTo("si");
        assertThat(criteria.getValue().stockInTypeCode()).isEqualTo("PURCHASE");
        assertThat(criteria.getValue().procurementOrderId()).isEqualTo(1L);
        assertThat(criteria.getValue().warehouseId()).isEqualTo(2L);
        assertThat(criteria.getValue().supplierId()).isEqualTo(3L);
        assertThat(criteria.getValue().statusCode()).isEqualTo("CONFIRMED");
        assertThat(criteria.getValue().stockInTimeFrom()).isEqualTo(from);
        assertThat(criteria.getValue().stockInTimeTo()).isEqualTo(to);
    }

    @Test
    void confirmRejectsOverReceiving() {
        ErpStockInOrderStore store = mock(ErpStockInOrderStore.class);
        ErpStockInOrderService service = new ErpStockInOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.procurementOrderForStockIn(TENANT, 1L)).thenReturn(Optional.of(procurementOrder("SUBMITTED", 1,
                line(11L, 1, "10", "8"))));

        assertThatThrownBy(() -> service.confirmProcurementStockIn(new InternalProcurementStockInCommand(
                1L, 1, STOCK_IN_TIME,
                List.of(new InternalProcurementStockInLineCommand(11L, new BigDecimal("3"), null)),
                null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmProcurementStockIn(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmRejectsDraftProcurementOrder() {
        ErpStockInOrderStore store = mock(ErpStockInOrderStore.class);
        ErpStockInOrderService service = new ErpStockInOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.procurementOrderForStockIn(TENANT, 1L)).thenReturn(Optional.of(procurementOrder("DRAFT", 1,
                line(11L, 1, "10", "0"))));

        assertThatThrownBy(() -> service.confirmProcurementStockIn(new InternalProcurementStockInCommand(
                1L, 1, STOCK_IN_TIME,
                List.of(new InternalProcurementStockInLineCommand(11L, BigDecimal.ONE, null)),
                null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmProcurementStockIn(eq(TENANT), any(), any(), eq(ACTOR));
    }

    private static ProcurementOrderSnapshot procurementOrder(
            String statusCode, int revision, ProcurementOrderLineSnapshot... lines) {
        return new ProcurementOrderSnapshot(1L, "PO202608200001", 3L, 2L, statusCode, revision, List.of(lines));
    }

    private static ProcurementOrderLineSnapshot line(Long id, int lineNo, String quantity, String receivedQuantity) {
        return new ProcurementOrderLineSnapshot(id, lineNo, 10L + lineNo, 20L + lineNo,
                "PRD-" + lineNo, "SKU-" + lineNo, "商品" + lineNo, "BOX", new BigDecimal(quantity),
                new BigDecimal("8.50"), new BigDecimal(quantity).multiply(new BigDecimal("8.50")),
                new BigDecimal(receivedQuantity));
    }

    private static InternalStockInOrderDetailView detail(Long id, String stockInNo) {
        return new InternalStockInOrderDetailView(id, stockInNo, "PURCHASE", 1L, "PO202608200001",
                null, null, 2L, "默认仓", 3L, "供应商一", "CONFIRMED", STOCK_IN_TIME, new BigDecimal("3"),
                new BigDecimal("25.50"), List.of(new InternalStockInOrderLineView(1L, 1, 11L, null,
                11L, 21L, "PRD-1", "SKU-1", "商品1", "BOX", new BigDecimal("3"),
                new BigDecimal("8.50"), new BigDecimal("25.50"), null)), "采购到货", 1,
                ACTOR, Instant.now(), ACTOR, Instant.now());
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
