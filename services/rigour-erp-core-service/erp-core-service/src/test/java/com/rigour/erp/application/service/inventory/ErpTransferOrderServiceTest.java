package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.ExternalTransferOrderProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferOrderProjectionLineCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockInProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderLineCommand;
import com.rigour.erp.api.v1.model.InternalTransferOrderLineView;
import com.rigour.erp.api.v1.model.InternalTransferOrderSummaryView;
import com.rigour.erp.api.v1.model.InternalTransferStockInCommand;
import com.rigour.erp.api.v1.model.InternalTransferStockOutCommand;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpTransferOrderStore;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.ExternalTransferStockOutWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.ProductVariantSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderLineSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderSnapshot;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferOrderWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockInWrite;
import com.rigour.erp.application.port.out.ErpTransferOrderStore.TransferStockOutWrite;
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

class ErpTransferOrderServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-3200-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-3200-7000-8000-000000000002");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb700-3200-7000-8000-000000000003");
    private static final String TENANT = TENANT_ID.toString();
    private static final String CONNECTOR = CONNECTOR_ID.toString();
    private static final String ACTOR = USER_ID.toString();
    private static final Instant STOCK_OUT_TIME = Instant.parse("2026-08-29T04:00:00Z");
    private static final Instant STOCK_IN_TIME = Instant.parse("2026-08-30T04:00:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createGeneratesTransferNoAndStoresProductSnapshots() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.warehouseActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-1", "SKU-1", "咖啡豆", "BOX")));
        when(store.existsByTransferNo(TENANT, "TR202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("TR202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608201234", "DRAFT", null, 1));

        service.create(new InternalTransferOrderCommand(1L, 2L,
                List.of(new InternalTransferOrderLineCommand(10L, 11L, new BigDecimal("3"), " 调拨 ")),
                " 城市仓补货 ", null));

        ArgumentCaptor<TransferOrderWrite> command = ArgumentCaptor.forClass(TransferOrderWrite.class);
        verify(store).create(eq(TENANT), eq("TR202608201234"), command.capture(), eq(ACTOR));
        TransferOrderWrite write = command.getValue();
        assertThat(write.sourceWarehouseId()).isEqualTo(1L);
        assertThat(write.targetWarehouseId()).isEqualTo(2L);
        assertThat(write.statusCode()).isEqualTo("DRAFT");
        assertThat(write.remark()).isEqualTo("城市仓补货");
        assertThat(write.lines()).hasSize(1);
        assertThat(write.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(write.lines().get(0).productCode()).isEqualTo("PRD-1");
        assertThat(write.lines().get(0).variantCode()).isEqualTo("SKU-1");
        assertThat(write.lines().get(0).productName()).isEqualTo("咖啡豆");
        assertThat(write.lines().get(0).unitCode()).isEqualTo("BOX");
        assertThat(write.lines().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(write.lines().get(0).remark()).isEqualTo("调拨");
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:read"));
        when(store.transferOrders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<InternalTransferOrderSummaryView>(0, 0, 20, List.of()));
        Instant from = Instant.parse("2026-08-20T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");

        service.transferOrders(0, 20, " tr ", 1L, 2L, " out_confirmed ", from, to);

        ArgumentCaptor<TransferOrderSearchCriteria> criteria =
                ArgumentCaptor.forClass(TransferOrderSearchCriteria.class);
        verify(store).transferOrders(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().transferNo()).isEqualTo("tr");
        assertThat(criteria.getValue().sourceWarehouseId()).isEqualTo(1L);
        assertThat(criteria.getValue().targetWarehouseId()).isEqualTo(2L);
        assertThat(criteria.getValue().statusCode()).isEqualTo("OUT_CONFIRMED");
        assertThat(criteria.getValue().stockOutTimeFrom()).isEqualTo(from);
        assertThat(criteria.getValue().stockOutTimeTo()).isEqualTo(to);
    }

    @Test
    void createRejectsSameWarehouse() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        assertThatThrownBy(() -> service.create(new InternalTransferOrderCommand(1L, 1L,
                List.of(new InternalTransferOrderLineCommand(10L, 11L, BigDecimal.ONE, null)),
                null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).create(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmStockOutCreatesUnifiedStockOutCommandWithTransferType() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockOut(TENANT, 1L)).thenReturn(Optional.of(transferSnapshot("DRAFT", 2)));
        when(store.existsByStockOutNo(TENANT, "SO202608201234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmStockOut(eq(TENANT), eq("SO202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608201234", "OUT_CONFIRMED", "SO202608201234", 3));

        service.confirmStockOut(1L, new InternalTransferStockOutCommand(2, STOCK_OUT_TIME, " 调拨出库 "));

        ArgumentCaptor<TransferStockOutWrite> command = ArgumentCaptor.forClass(TransferStockOutWrite.class);
        verify(store).confirmStockOut(eq(TENANT), eq("SO202608201234"), command.capture(), eq(ACTOR));
        TransferStockOutWrite write = command.getValue();
        assertThat(write.transferOrderId()).isEqualTo(1L);
        assertThat(write.transferRevision()).isEqualTo(2);
        assertThat(write.transferNo()).isEqualTo("TR202608201234");
        assertThat(write.stockOutTypeCode()).isEqualTo("TRANSFER");
        assertThat(write.stockOutStatusCode()).isEqualTo("CONFIRMED");
        assertThat(write.nextTransferStatusCode()).isEqualTo("OUT_CONFIRMED");
        assertThat(write.stockOutTime()).isEqualTo(STOCK_OUT_TIME);
        assertThat(write.sourceWarehouseId()).isEqualTo(1L);
        assertThat(write.remark()).isEqualTo("调拨出库");
        assertThat(write.lines()).hasSize(1);
        assertThat(write.lines().get(0).transferOrderLineId()).isEqualTo(101L);
        assertThat(write.lines().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(write.lines().get(0).flowNo()).startsWith("SF20260820110000000");
    }

    @Test
    void confirmStockOutRejectsAlreadyConfirmedTransferOrder() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockOut(TENANT, 1L)).thenReturn(Optional.of(transferSnapshot("OUT_CONFIRMED", 2)));

        assertThatThrownBy(() -> service.confirmStockOut(
                1L, new InternalTransferStockOutCommand(2, STOCK_OUT_TIME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmStockOut(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmStockOutRejectsExternalTransferOrder() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockOut(TENANT, 1L))
                .thenReturn(Optional.of(externalTransferSnapshot("DRAFT", 2)));

        assertThatThrownBy(() -> service.confirmStockOut(
                1L, new InternalTransferStockOutCommand(2, STOCK_OUT_TIME, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("外部来源调拨单已按来源出入库凭证同步，无需在ERP重复确认出库")
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmStockOut(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmStockInCreatesUnifiedStockInCommandWithTransferType() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockIn(TENANT, 1L))
                .thenReturn(Optional.of(transferSnapshot("OUT_CONFIRMED", 3)));
        when(store.existsByStockInNo(TENANT, "SI202608201234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmStockIn(eq(TENANT), eq("SI202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608201234", "IN_CONFIRMED", "SO202608201234", "SI202608201234", 4));

        service.confirmStockIn(1L, new InternalTransferStockInCommand(3, STOCK_IN_TIME, " 调拨入库 "));

        ArgumentCaptor<TransferStockInWrite> command = ArgumentCaptor.forClass(TransferStockInWrite.class);
        verify(store).confirmStockIn(eq(TENANT), eq("SI202608201234"), command.capture(), eq(ACTOR));
        TransferStockInWrite write = command.getValue();
        assertThat(write.transferOrderId()).isEqualTo(1L);
        assertThat(write.transferRevision()).isEqualTo(3);
        assertThat(write.transferNo()).isEqualTo("TR202608201234");
        assertThat(write.stockInTypeCode()).isEqualTo("TRANSFER");
        assertThat(write.stockInStatusCode()).isEqualTo("CONFIRMED");
        assertThat(write.nextTransferStatusCode()).isEqualTo("IN_CONFIRMED");
        assertThat(write.stockInTime()).isEqualTo(STOCK_IN_TIME);
        assertThat(write.targetWarehouseId()).isEqualTo(2L);
        assertThat(write.remark()).isEqualTo("调拨入库");
        assertThat(write.lines()).hasSize(1);
        assertThat(write.lines().get(0).transferOrderLineId()).isEqualTo(101L);
        assertThat(write.lines().get(0).quantity()).isEqualByComparingTo("3");
        assertThat(write.lines().get(0).flowNo()).startsWith("SF20260820110000000");
    }

    @Test
    void confirmStockInRejectsTransferOrderNotStockedOut() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockIn(TENANT, 1L)).thenReturn(Optional.of(transferSnapshot("DRAFT", 2)));

        assertThatThrownBy(() -> service.confirmStockIn(
                1L, new InternalTransferStockInCommand(2, STOCK_IN_TIME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmStockIn(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmStockInRejectsExternalTransferOrder() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.transferOrderForStockIn(TENANT, 1L))
                .thenReturn(Optional.of(externalTransferSnapshot("OUT_CONFIRMED", 2)));

        assertThatThrownBy(() -> service.confirmStockIn(
                1L, new InternalTransferStockInCommand(2, STOCK_IN_TIME, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("外部来源调拨单已按来源出入库凭证同步，无需在ERP重复确认入库")
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).confirmStockIn(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmExternalStockOutCreatesTransferOrderAndStockOutBySourceDocument() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(serviceCaller("erp:supply:write"));
        when(store.transferOrderBySource(TENANT, CONNECTOR, "DINGHUOBAO", "FH.20260829.0018"))
                .thenReturn(Optional.empty());
        when(store.warehouseActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-ERP", "SKU-ERP", "ERP调拨商品", "BOX")));
        when(store.existsByTransferNo(TENANT, "TR202608291234")).thenReturn(false);
        when(store.existsByStockOutNo(TENANT, "SO202608291234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmExternalStockOut(eq(TENANT), eq("TR202608291234"),
                eq("SO202608291234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608291234", "OUT_CONFIRMED", "SO202608291234", 1));

        service.confirmExternalStockOut(new ExternalTransferStockOutProjectionCommand(
                CONNECTOR_ID, "DINGHUOBAO", "FH.20260829.0018", 1L, 2L, STOCK_OUT_TIME,
                Boolean.FALSE,
                null, null, "EMP-001", "王睿晗",
                List.of(new ExternalTransferStockOutProjectionLineCommand(
                        10L, 11L, "SOURCE-PRD", "SOURCE-SKU", "订货宝调拨商品",
                        null, new BigDecimal("3"), "订货宝调拨出库")),
                "订货宝调拨出库"));

        ArgumentCaptor<ExternalTransferStockOutWrite> write =
                ArgumentCaptor.forClass(ExternalTransferStockOutWrite.class);
        verify(store).confirmExternalStockOut(eq(TENANT), eq("TR202608291234"),
                eq("SO202608291234"), write.capture(), eq(ACTOR));
        assertThat(write.getValue().connectorId()).isEqualTo(CONNECTOR);
        assertThat(write.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(write.getValue().sourceDocumentNo()).isEqualTo("FH.20260829.0018");
        assertThat(write.getValue().sourceWarehouseId()).isEqualTo(1L);
        assertThat(write.getValue().targetWarehouseId()).isEqualTo(2L);
        assertThat(write.getValue().stockOutTime()).isEqualTo(STOCK_OUT_TIME);
        assertThat(write.getValue().affectStockBalance()).isFalse();
        assertThat(write.getValue().inboundOperatorStaffCode()).isEqualTo("EMP-001");
        assertThat(write.getValue().inboundOperatorStaffNameSnapshot()).isEqualTo("王睿晗");
        assertThat(write.getValue().lines()).hasSize(1);
        assertThat(write.getValue().lines().getFirst().productCode()).isEqualTo("PRD-ERP");
        assertThat(write.getValue().lines().getFirst().variantCode()).isEqualTo("SKU-ERP");
        assertThat(write.getValue().lines().getFirst().productName()).isEqualTo("ERP调拨商品");
        assertThat(write.getValue().lines().getFirst().unitCode()).isEqualTo("BOX");
        assertThat(write.getValue().lines().getFirst().flowNo()).startsWith("SF20260829120000000");
    }

    @Test
    void upsertExternalTransferOrderCreatesDraftLocalTransferBySourceDocument() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(serviceCaller("erp:supply:write"));
        when(store.transferOrderBySource(TENANT, CONNECTOR, "DINGHUOBAO", "DB.20260829.0018"))
                .thenReturn(Optional.empty());
        when(store.warehouseActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-ERP", "SKU-ERP", "ERP调拨商品", "BOX")));
        when(store.existsByTransferNo(TENANT, "TR202608291234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("TR202608291234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608291234", "DRAFT", null, 1));

        service.upsertExternalTransferOrder(new ExternalTransferOrderProjectionCommand(
                CONNECTOR_ID, "DINGHUOBAO", "DB.20260829.0018", 1L, 2L, STOCK_OUT_TIME,
                "待出库", "已审核", "EMP-OUT", "肖涵月", "EMP-IN", "姜志鹏",
                List.of(new ExternalTransferOrderProjectionLineCommand(
                        10L, 11L, "SOURCE-PRD", "SOURCE-SKU", "订货宝调拨商品",
                        null, new BigDecimal("3"), "订货宝调拨主单")),
                "订货宝调拨主单"));

        ArgumentCaptor<TransferOrderWrite> write = ArgumentCaptor.forClass(TransferOrderWrite.class);
        verify(store).create(eq(TENANT), eq("TR202608291234"), write.capture(), eq(ACTOR));
        assertThat(write.getValue().connectorId()).isEqualTo(CONNECTOR);
        assertThat(write.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(write.getValue().sourceDocumentNo()).isEqualTo("DB.20260829.0018");
        assertThat(write.getValue().statusCode()).isEqualTo("DRAFT");
        assertThat(write.getValue().outboundOperatorStaffCode()).isEqualTo("EMP-OUT");
        assertThat(write.getValue().outboundOperatorStaffNameSnapshot()).isEqualTo("肖涵月");
        assertThat(write.getValue().inboundOperatorStaffCode()).isEqualTo("EMP-IN");
        assertThat(write.getValue().inboundOperatorStaffNameSnapshot()).isEqualTo("姜志鹏");
        assertThat(write.getValue().lines()).hasSize(1);
        assertThat(write.getValue().lines().getFirst().productCode()).isEqualTo("PRD-ERP");
        assertThat(write.getValue().lines().getFirst().variantCode()).isEqualTo("SKU-ERP");
        assertThat(write.getValue().lines().getFirst().productName()).isEqualTo("ERP调拨商品");
        assertThat(write.getValue().lines().getFirst().unitCode()).isEqualTo("BOX");
    }

    @Test
    void confirmExternalStockOutUsesTransferSourceDocumentNoAsLocalTransferEvidence() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(serviceCaller("erp:supply:write"));
        when(store.transferOrderBySource(TENANT, CONNECTOR, "DINGHUOBAO", "DB.20260829.0018"))
                .thenReturn(Optional.of(detail(1L, "TR202608291234", "DRAFT", null, 2)));
        when(store.warehouseActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-ERP", "SKU-ERP", "ERP调拨商品", "BOX")));
        when(store.update(eq(TENANT), eq(1L), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608291234", "DRAFT", null, 3));
        when(store.transferOrderForStockOut(TENANT, 1L))
                .thenReturn(Optional.of(transferSnapshot("TR202608291234", "DRAFT", 3)));
        when(store.existsByStockOutNo(TENANT, "SO202608291234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmStockOut(eq(TENANT), eq("SO202608291234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608291234", "OUT_CONFIRMED", "SO202608291234", 4));

        service.confirmExternalStockOut(new ExternalTransferStockOutProjectionCommand(
                CONNECTOR_ID, "DINGHUOBAO", "FH.20260829.0018", "DB.20260829.0018",
                1L, 2L, STOCK_OUT_TIME, Boolean.FALSE,
                "EMP-OUT", "肖涵月", "EMP-IN", "姜志鹏",
                List.of(new ExternalTransferStockOutProjectionLineCommand(
                        10L, 11L, "SOURCE-PRD", "SOURCE-SKU", "订货宝调拨商品",
                        null, new BigDecimal("3"), "订货宝调拨出库")),
                "订货宝调拨出库"));

        ArgumentCaptor<TransferStockOutWrite> stockOut =
                ArgumentCaptor.forClass(TransferStockOutWrite.class);
        verify(store).confirmStockOut(eq(TENANT), eq("SO202608291234"), stockOut.capture(), eq(ACTOR));
        assertThat(stockOut.getValue().transferOrderId()).isEqualTo(1L);
        assertThat(stockOut.getValue().transferNo()).isEqualTo("TR202608291234");
        assertThat(stockOut.getValue().connectorId()).isEqualTo(CONNECTOR);
        assertThat(stockOut.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(stockOut.getValue().sourceDocumentNo()).isEqualTo("FH.20260829.0018");
        assertThat(stockOut.getValue().affectStockBalance()).isFalse();
        assertThat(stockOut.getValue().lines()).hasSize(1);
        assertThat(stockOut.getValue().lines().getFirst().flowNo()).startsWith("SF20260829120000000");
        verify(store, never()).confirmExternalStockOut(eq(TENANT), any(), any(), any(), eq(ACTOR));
    }

    @Test
    void confirmExternalStockInUsesTransferSourceDocumentNoAndCreatesStockIn() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(serviceCaller("erp:supply:write"));
        when(store.transferOrderBySource(TENANT, CONNECTOR, "DINGHUOBAO", "DB.20260829.0018"))
                .thenReturn(Optional.of(detail(1L, "TR202608291234", "OUT_CONFIRMED",
                        "SO202608291234", null, 4)));
        when(store.transferOrderForStockIn(TENANT, 1L))
                .thenReturn(Optional.of(transferSnapshot("TR202608291234", "OUT_CONFIRMED", 4)));
        when(store.existsByStockInNo(TENANT, "SI202608301234")).thenReturn(false);
        when(store.existsByFlowNo(eq(TENANT), any())).thenReturn(false);
        when(store.confirmStockIn(eq(TENANT), eq("SI202608301234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "TR202608291234", "IN_CONFIRMED",
                        "SO202608291234", "SI202608301234", 5));

        service.confirmExternalStockIn(new ExternalTransferStockInProjectionCommand(
                CONNECTOR_ID, "DINGHUOBAO", "RK.20260830.0001", "DB.20260829.0018",
                null, STOCK_IN_TIME, "订货宝调拨入库"));

        ArgumentCaptor<TransferStockInWrite> stockIn =
                ArgumentCaptor.forClass(TransferStockInWrite.class);
        verify(store).confirmStockIn(eq(TENANT), eq("SI202608301234"), stockIn.capture(), eq(ACTOR));
        assertThat(stockIn.getValue().transferOrderId()).isEqualTo(1L);
        assertThat(stockIn.getValue().transferNo()).isEqualTo("TR202608291234");
        assertThat(stockIn.getValue().stockInTime()).isEqualTo(STOCK_IN_TIME);
        assertThat(stockIn.getValue().targetWarehouseId()).isEqualTo(2L);
        assertThat(stockIn.getValue().lines()).hasSize(1);
        assertThat(stockIn.getValue().lines().getFirst().flowNo()).startsWith("SF20260830120000000");
    }

    @Test
    void tenantCannotCallExternalTransferProjection() {
        ErpTransferOrderStore store = mock(ErpTransferOrderStore.class);
        ErpTransferOrderService service = new ErpTransferOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        assertThatThrownBy(() -> service.confirmExternalStockOut(
                new ExternalTransferStockOutProjectionCommand(
                        CONNECTOR_ID, "DINGHUOBAO", "FH.20260829.0100", 1L, 2L,
                        STOCK_OUT_TIME, Boolean.FALSE, null, null, null, null,
                        List.of(new ExternalTransferStockOutProjectionLineCommand(
                                10L, 11L, "SOURCE-PRD", "SOURCE-SKU", "订货宝调拨商品",
                                null, new BigDecimal("3"), "订货宝调拨出库")),
                        "订货宝调拨出库")))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);

        verify(store, never()).confirmExternalStockOut(eq(TENANT), any(), any(), any(), eq(ACTOR));
    }

    private static ProductVariantSnapshot snapshot(
            Long productId, Long variantId, String productCode, String variantCode, String productName, String unitCode) {
        return new ProductVariantSnapshot(productId, variantId, productCode, variantCode, productName, unitCode);
    }

    private static TransferOrderSnapshot transferSnapshot(String statusCode, int revision) {
        return transferSnapshot("TR202608201234", statusCode, revision);
    }

    private static TransferOrderSnapshot transferSnapshot(String transferNo, String statusCode, int revision) {
        return new TransferOrderSnapshot(1L, transferNo, null, null, 1L, 2L, statusCode, revision,
                List.of(new TransferOrderLineSnapshot(101L, 1, 10L, 11L, "PRD-1", "SKU-1",
                        "咖啡豆", "BOX", new BigDecimal("3"), "明细备注")));
    }

    private static TransferOrderSnapshot externalTransferSnapshot(String statusCode, int revision) {
        return new TransferOrderSnapshot(1L, "TR202608201234", "DINGHUOBAO", "DB.20260820.0001",
                1L, 2L, statusCode, revision,
                List.of(new TransferOrderLineSnapshot(101L, 1, 10L, 11L, "PRD-1", "SKU-1",
                        "咖啡豆", "BOX", new BigDecimal("3"), "明细备注")));
    }

    private static InternalTransferOrderDetailView detail(
            Long id, String transferNo, String statusCode, String stockOutNo, int revision) {
        return detail(id, transferNo, statusCode, stockOutNo, null, revision);
    }

    private static InternalTransferOrderDetailView detail(
            Long id, String transferNo, String statusCode, String stockOutNo, String stockInNo, int revision) {
        return new InternalTransferOrderDetailView(id, transferNo, null, null, 1L, "默认仓", 2L, "城市仓",
                null, null, null, null,
                statusCode, stockOutNo == null ? null : STOCK_OUT_TIME, stockInNo == null ? null : STOCK_IN_TIME,
                stockOutNo == null ? null : 9L, stockOutNo, stockInNo == null ? null : 10L, stockInNo,
                new BigDecimal("3"),
                List.of(new InternalTransferOrderLineView(101L, 1, 10L, 11L, "PRD-1", "SKU-1",
                        "咖啡豆", "BOX", new BigDecimal("3"), "明细备注")),
                "调拨备注", revision, ACTOR, Instant.now(), ACTOR, Instant.now());
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

    private static CallerIdentity serviceCaller(String permission) {
        return new CallerIdentity("SERVICE", USER_ID, TENANT_ID, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
