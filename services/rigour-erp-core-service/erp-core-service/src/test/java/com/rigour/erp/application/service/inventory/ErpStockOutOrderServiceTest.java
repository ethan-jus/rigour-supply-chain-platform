package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutCommand;
import com.rigour.erp.api.v1.model.InternalSalesStockOutLineCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderLineView;
import com.rigour.erp.api.v1.model.InternalStockOutOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalGenericStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ExternalStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.SalesStockOutWrite;
import com.rigour.erp.application.port.out.ErpStockOutOrderStore.StockOutOrderSearchCriteria;
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

class ErpStockOutOrderServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-3300-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-3300-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();
    private static final Instant STOCK_OUT_TIME = Instant.parse("2026-08-31T04:00:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void confirmSalesStockOutCreatesUnifiedStockOutCommandWithSalesType() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.existsActiveSalesStockOut(TENANT, 1L)).thenReturn(false);
        when(store.existsByStockOutNo(TENANT, "SO202608201234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmSalesStockOut(eq(TENANT), eq("SO202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(100L, "SO202608201234"));

        service.confirmSalesStockOut(command());

        ArgumentCaptor<SalesStockOutWrite> write = ArgumentCaptor.forClass(SalesStockOutWrite.class);
        verify(store).confirmSalesStockOut(eq(TENANT), eq("SO202608201234"), write.capture(), eq(ACTOR));
        assertThat(write.getValue().salesOrderId()).isEqualTo(1L);
        assertThat(write.getValue().salesOrderNo()).isEqualTo("DD202608200001");
        assertThat(write.getValue().warehouseId()).isEqualTo(2L);
        assertThat(write.getValue().customerId()).isEqualTo(3L);
        assertThat(write.getValue().customerNameSnapshot()).isEqualTo("上海静安店");
        assertThat(write.getValue().stockOutTypeCode()).isEqualTo("SALES");
        assertThat(write.getValue().statusCode()).isEqualTo("CONFIRMED");
        assertThat(write.getValue().stockOutTime()).isEqualTo(STOCK_OUT_TIME);
        assertThat(write.getValue().lines()).hasSize(1);
        assertThat(write.getValue().lines().get(0).salesOrderLineId()).isEqualTo(10L);
        assertThat(write.getValue().lines().get(0).productCode()).isEqualTo("PRD-1");
        assertThat(write.getValue().lines().get(0).variantCode()).isEqualTo("SKU-1");
        assertThat(write.getValue().lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(write.getValue().lines().get(0).flowNo()).startsWith("SF20260820110000000");
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:read"));
        when(store.stockOutOrders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<InternalStockOutOrderSummaryView>(0, 0, 20, List.of()));
        Instant from = Instant.parse("2026-08-20T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");

        service.stockOutOrders(0, 20, " so ", " sales ", 2L,
                " dd ", " tr ", " 静安 ", " confirmed ", from, to);

        ArgumentCaptor<StockOutOrderSearchCriteria> criteria =
                ArgumentCaptor.forClass(StockOutOrderSearchCriteria.class);
        verify(store).stockOutOrders(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().stockOutNo()).isEqualTo("so");
        assertThat(criteria.getValue().stockOutTypeCode()).isEqualTo("SALES");
        assertThat(criteria.getValue().warehouseId()).isEqualTo(2L);
        assertThat(criteria.getValue().salesOrderNo()).isEqualTo("dd");
        assertThat(criteria.getValue().transferOrderNo()).isEqualTo("tr");
        assertThat(criteria.getValue().customerName()).isEqualTo("静安");
        assertThat(criteria.getValue().statusCode()).isEqualTo("CONFIRMED");
        assertThat(criteria.getValue().stockOutTimeFrom()).isEqualTo(from);
        assertThat(criteria.getValue().stockOutTimeTo()).isEqualTo(to);
    }

    @Test
    void confirmRejectsDuplicateSalesStockOut() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.existsActiveSalesStockOut(TENANT, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.confirmSalesStockOut(command()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmSalesStockOut(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmExternalSalesStockOutIsIdempotentBySourceDocumentNotSalesOrder() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.stockOutOrderBySource(TENANT, "DINGHUOBAO", "FH.20260820.0001"))
                .thenReturn(Optional.empty());
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.existsByStockOutNo(TENANT, "SO202608311234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmExternalStockOut(eq(TENANT), eq("SO202608311234"), any(), eq(ACTOR)))
                .thenReturn(detail(101L, "SO202608311234", "DINGHUOBAO",
                        "FH.20260820.0001", "SALES"));

        service.confirmExternalStockOut(externalCommand("FH.20260820.0001", "SALES"));

        ArgumentCaptor<ExternalStockOutWrite> write = ArgumentCaptor.forClass(ExternalStockOutWrite.class);
        verify(store).confirmExternalStockOut(eq(TENANT), eq("SO202608311234"), write.capture(), eq(ACTOR));
        verify(store, never()).existsActiveSalesStockOut(eq(TENANT), any());
        assertThat(write.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(write.getValue().sourceDocumentNo()).isEqualTo("FH.20260820.0001");
        assertThat(write.getValue().stockOutTypeCode()).isEqualTo("SALES");
        assertThat(write.getValue().salesOrderId()).isEqualTo(1L);
        assertThat(write.getValue().salesOrderNo()).isEqualTo("DD202608200001");
    }

    @Test
    void confirmExternalTransferStockOutMustUseTransferOrderFlow() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.stockOutOrderBySource(TENANT, "DINGHUOBAO", "FH.20260820.0002"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmExternalStockOut(
                externalCommand("FH.20260820.0002", "TRANSFER")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).confirmExternalStockOut(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmExternalStockOutReturnsExistingSourceWithoutInventoryWrite() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        InternalStockOutOrderDetailView existing = detail(103L, "SO202608201999",
                "DINGHUOBAO", "FH.20260820.0003", "SALES");
        when(store.stockOutOrderBySource(TENANT, "DINGHUOBAO", "FH.20260820.0003"))
                .thenReturn(Optional.of(existing));

        InternalStockOutOrderDetailView result =
                service.confirmExternalStockOut(externalCommand("FH.20260820.0003", "SALES"));

        assertThat(result).isSameAs(existing);
        verify(store, never()).confirmExternalStockOut(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmExternalGenericStockOutUsesInternalProductVariantSnapshot() {
        ErpStockOutOrderStore store = mock(ErpStockOutOrderStore.class);
        ErpStockOutOrderService service = new ErpStockOutOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.stockOutOrderBySource(TENANT, "DINGHUOBAO", "FH.20260820.0099"))
                .thenReturn(Optional.empty());
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 20L, 21L)).thenReturn(Optional.of(
                new ProductVariantSnapshot(20L, 21L, "PRD-ERP", "SKU-ERP", "ERP商品", "BOX")));
        when(store.existsByStockOutNo(TENANT, "SO202608311234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmExternalGenericStockOut(eq(TENANT), eq("SO202608311234"), any(), eq(ACTOR)))
                .thenReturn(detail(104L, "SO202608311234", "DINGHUOBAO",
                        "FH.20260820.0099", "OTHER"));

        service.confirmExternalGenericStockOut(new ExternalGenericStockOutProjectionCommand(
                "DINGHUOBAO", "FH.20260820.0099", "OTHER", 2L, STOCK_OUT_TIME,
                List.of(new ExternalGenericStockOutProjectionLineCommand(
                        20L, 21L, "SOURCE-PRD", "SOURCE-SKU", "订货宝商品",
                        null, new BigDecimal("2"), "订货宝其他出库")),
                "订货宝其他出库"));

        ArgumentCaptor<ExternalGenericStockOutWrite> write =
                ArgumentCaptor.forClass(ExternalGenericStockOutWrite.class);
        verify(store).confirmExternalGenericStockOut(eq(TENANT), eq("SO202608311234"),
                write.capture(), eq(ACTOR));
        assertThat(write.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(write.getValue().sourceDocumentNo()).isEqualTo("FH.20260820.0099");
        assertThat(write.getValue().stockOutTypeCode()).isEqualTo("OTHER");
        assertThat(write.getValue().lines()).hasSize(1);
        assertThat(write.getValue().lines().getFirst().productCode()).isEqualTo("PRD-ERP");
        assertThat(write.getValue().lines().getFirst().variantCode()).isEqualTo("SKU-ERP");
        assertThat(write.getValue().lines().getFirst().productName()).isEqualTo("ERP商品");
        assertThat(write.getValue().lines().getFirst().unitCode()).isEqualTo("BOX");
    }

    private static InternalSalesStockOutCommand command() {
        return new InternalSalesStockOutCommand(1L, " DD202608200001 ", 2L, 3L,
                " 上海静安店 ", STOCK_OUT_TIME,
                List.of(new InternalSalesStockOutLineCommand(10L, 20L, 21L,
                        "PRD-1", "SKU-1", "酸麻粉面菜蛋", "box",
                        new BigDecimal("2"), " 本次出库 ")),
                " 销售出库 ");
    }

    private static ExternalStockOutProjectionCommand externalCommand(
            String sourceDocumentNo, String stockOutTypeCode) {
        return new ExternalStockOutProjectionCommand("DINGHUOBAO", sourceDocumentNo,
                stockOutTypeCode, 2L,
                "SALES".equals(stockOutTypeCode) ? 1L : null,
                "SALES".equals(stockOutTypeCode) ? "DD202608200001" : null,
                null, null,
                "SALES".equals(stockOutTypeCode) ? 3L : null,
                "SALES".equals(stockOutTypeCode) ? "上海静安店" : null,
                STOCK_OUT_TIME,
                List.of(new ExternalStockOutProjectionLineCommand(
                        "SALES".equals(stockOutTypeCode) ? 10L : null,
                        null, 20L, 21L, "PRD-1", "SKU-1",
                        "酸麻粉面菜蛋", "BOX", new BigDecimal("2"), "订货宝出库")),
                "订货宝出库");
    }

    private static InternalStockOutOrderDetailView detail(Long id, String stockOutNo) {
        return detail(id, stockOutNo, null, null, "SALES");
    }

    private static InternalStockOutOrderDetailView detail(
            Long id, String stockOutNo, String sourceSystemCode, String sourceDocumentNo,
            String stockOutTypeCode) {
        return new InternalStockOutOrderDetailView(id, stockOutNo, sourceSystemCode, sourceDocumentNo,
                stockOutTypeCode, 2L, "默认仓",
                1L, "DD202608200001", null, null, 3L, "上海静安店",
                "CONFIRMED", STOCK_OUT_TIME, new BigDecimal("2"), 1,
                List.of(new InternalStockOutOrderLineView(1L, 1, 10L, null, 20L, 21L,
                        "PRD-1", "SKU-1", "酸麻粉面菜蛋", "BOX", new BigDecimal("2"), null)),
                "销售出库", 1, ACTOR, Instant.now(), ACTOR, Instant.now());
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
