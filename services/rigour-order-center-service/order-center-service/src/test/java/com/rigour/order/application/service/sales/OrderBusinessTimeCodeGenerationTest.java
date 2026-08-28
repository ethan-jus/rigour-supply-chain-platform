package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderFundDocumentStore;
import com.rigour.order.application.port.out.OrderFundDocumentStore.FundDocumentWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore.SalesPaymentWrite;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore.SalesRefundWrite;
import com.rigour.order.application.port.out.OrderSalesShipmentStore;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentWrite;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.code.BusinessCodeGenerator;
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

class OrderBusinessTimeCodeGenerationTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb700-0000-7000-8000-0000000000d0");
    private static final Instant BUSINESS_TIME = Instant.parse("2026-08-18T16:30:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void salesShipmentNoUsesShipTime() {
        OrderSalesShipmentStore store = mock(OrderSalesShipmentStore.class);
        OrderSalesOrderStore orderStore = orderStore();
        OrderSalesShipmentService service = new OrderSalesShipmentService(store, orderStore, generator());
        TestAuthorizationContext.set(caller());
        when(store.create(eq(TENANT_ID.toString()), any(), any(), any()))
                .thenAnswer(invocation -> shipment(invocation.getArgument(1), invocation.getArgument(2)));

        SalesShipmentDetailView created = service.create(new SalesShipmentCommand(
                1L, 2L, 3L, "SO202608190001", "SHIPPED", "顺丰", "SF1",
                BUSINESS_TIME, List.of(), "订货宝发货", 0));

        assertThat(created.shipmentNo()).isEqualTo("FH202608198888");
    }

    @Test
    void serviceSalesShipmentKeepsSourceIdentityAndUsesSourceTime() {
        OrderSalesShipmentStore store = mock(OrderSalesShipmentStore.class);
        OrderSalesShipmentService service = new OrderSalesShipmentService(store, orderStore(), generator());
        TestAuthorizationContext.set(serviceCaller());
        when(store.create(eq(TENANT_ID.toString()), any(), any(), any()))
                .thenAnswer(invocation -> shipment(invocation.getArgument(1), invocation.getArgument(2)));

        service.create(new SalesShipmentCommand(
                CONNECTOR_ID, "DINGHUOBAO", "FH.20260818.0001",
                1L, 2L, 3L, "SO202608190001", "SHIPPED", "顺丰", "SF1",
                BUSINESS_TIME, List.of(), "订货宝发货", 0));

        ArgumentCaptor<SalesShipmentWrite> captor = ArgumentCaptor.forClass(SalesShipmentWrite.class);
        verify(store).create(eq(TENANT_ID.toString()), eq("FH202608198888"), captor.capture(), any());
        assertThat(captor.getValue().connectorId()).isEqualTo(CONNECTOR_ID);
        assertThat(captor.getValue().sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(captor.getValue().sourceDocumentNo()).isEqualTo("FH.20260818.0001");
    }

    @Test
    void tenantCannotForgeExternalSalesPaymentSourceFields() {
        OrderSalesPaymentRecordStore store = mock(OrderSalesPaymentRecordStore.class);
        OrderSalesPaymentRecordService service = new OrderSalesPaymentRecordService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());

        assertThatThrownBy(() -> service.create(new SalesPaymentRecordCommand(
                CONNECTOR_ID, "DINGHUOBAO", "FR.20260818.0001",
                1L, "RY202608190001", "收款人", BUSINESS_TIME, "CASH",
                new BigDecimal("100.00"), List.of(), "订货宝收款", 0)))
                .isInstanceOf(AuthorizationDeniedException.class);

        verify(store, never()).create(any(), any(), any(), any());
    }

    @Test
    void salesPaymentNoUsesPaymentTime() {
        OrderSalesPaymentRecordStore store = mock(OrderSalesPaymentRecordStore.class);
        OrderSalesPaymentRecordService service = new OrderSalesPaymentRecordService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());
        when(store.create(eq(TENANT_ID.toString()), any(), any(), any()))
                .thenAnswer(invocation -> payment(invocation.getArgument(1), invocation.getArgument(2)));

        SalesPaymentRecordDetailView created = service.create(new SalesPaymentRecordCommand(
                1L, "RY202608190001", "收款人", BUSINESS_TIME, "CASH",
                new BigDecimal("100.00"), List.of(), "订货宝收款", 0));

        assertThat(created.paymentNo()).isEqualTo("PAY202608198888");
    }

    @Test
    void salesRefundNoUsesRefundTime() {
        OrderSalesRefundRecordStore store = mock(OrderSalesRefundRecordStore.class);
        OrderSalesRefundRecordService service = new OrderSalesRefundRecordService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());
        when(store.create(eq(TENANT_ID.toString()), any(), any(), any()))
                .thenAnswer(invocation -> refund(invocation.getArgument(1), invocation.getArgument(2)));

        SalesRefundRecordDetailView created = service.create(new SalesRefundRecordCommand(
                1L, "RY202608190001", "退款人", BUSINESS_TIME, "CASH", "CONFIRMED",
                new BigDecimal("12.00"), List.of(), "订货宝退款", 0));

        assertThat(created.refundNo()).isEqualTo("TK202608198888");
    }

    @Test
    void fundDocumentNoUsesOccurredTime() {
        OrderFundDocumentStore store = mock(OrderFundDocumentStore.class);
        OrderFundDocumentService service = new OrderFundDocumentService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());
        when(store.create(eq(TENANT_ID.toString()), any(), any(), any()))
                .thenAnswer(invocation -> fundDocument(invocation.getArgument(1), invocation.getArgument(2)));

        FundDocumentDetailView created = service.create(new FundDocumentCommand(
                "RECEIPT", 1L, null, null, null, null, null, null, null,
                "RY202608190001", "经办人", BUSINESS_TIME, "CASH", "ORDER_RECEIPT",
                "CONFIRMED", new BigDecimal("100.00"), List.of(), "订货宝资金", 0));

        assertThat(created.documentNo()).isEqualTo("SK202608198888");
    }

    @Test
    void fundDocumentSearchKeepsSourceKeysSeparate() {
        OrderFundDocumentStore store = mock(OrderFundDocumentStore.class);
        OrderFundDocumentService service = new OrderFundDocumentService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());
        when(store.fundDocuments(eq(TENANT_ID.toString()), eq(0), eq(20), any()))
                .thenReturn(new OrderPageView<>(0, 0, 20, List.of()));

        service.fundDocuments(0, 20, "SER-any", "RECEIPT", "SK202608260001",
                "FR.20260826.0247", "SO202608260001", "DH.20260826.1371",
                "252112_FR.20260826.0247", "柒号台球", "RY202608190001",
                "BANK_TRANSFER", "RECHARGE", "PENDING", BUSINESS_TIME.minusSeconds(60), BUSINESS_TIME);

        ArgumentCaptor<OrderFundDocumentStore.FundDocumentSearchCriteria> captor =
                ArgumentCaptor.forClass(OrderFundDocumentStore.FundDocumentSearchCriteria.class);
        verify(store).fundDocuments(eq(TENANT_ID.toString()), eq(0), eq(20), captor.capture());

        OrderFundDocumentStore.FundDocumentSearchCriteria criteria = captor.getValue();
        assertThat(criteria.keyword()).isEqualTo("SER-any");
        assertThat(criteria.documentNo()).isEqualTo("SK202608260001");
        assertThat(criteria.sourceDocumentNo()).isEqualTo("FR.20260826.0247");
        assertThat(criteria.salesOrderNo()).isEqualTo("SO202608260001");
        assertThat(criteria.sourceOrderNo()).isEqualTo("DH.20260826.1371");
        assertThat(criteria.paymentSerialNo()).isEqualTo("252112_FR.20260826.0247");
    }

    @Test
    void fundDocumentDetailResolvesCosAttachmentUrlsWithoutHidingRawRefs() {
        OrderFundDocumentStore store = mock(OrderFundDocumentStore.class);
        OrderFundDocumentService service = new OrderFundDocumentService(
                store, orderStore(), emptyStaffClient(), generator(),
                (tenantId, objectKey) -> "https://cos.test/" + objectKey);
        TestAuthorizationContext.set(caller());
        String objectKey = TENANT_ID + "/fund-attachments/FR_20260826_0247/ATT/hash.png";
        when(store.fundDocument(eq(TENANT_ID.toString()), eq(40L))).thenReturn(Optional.of(
                new FundDocumentDetailView(40L, "SK202608260001", "RECEIPT",
                        1L, "SO202608260001", 11L, "31348", "北派桌球棋牌",
                        "CUSTOMER", "31348", "北派桌球棋牌", null, null,
                        BUSINESS_TIME, "BANK_TRANSFER", "CUSTOMER_RECHARGE", "PENDING",
                        new BigDecimal("156.00"), "FR.20260826.0123", "DH.20260826.1371",
                        "252112_FR.20260826.0123", "北京瑞盖文化传媒有限公司",
                        "中国建设银行股份有限公司北京经济技术开发区分行", "11050171360000002801",
                        BUSINESS_TIME, null, List.of(objectKey), List.of("legacy-proof.png"), List.of(),
                        "订货宝资金", 1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME)));

        FundDocumentDetailView detail = service.fundDocument(40L);

        assertThat(detail.attachments()).hasSize(2);
        assertThat(detail.attachments().get(0).objectKey()).isEqualTo(objectKey);
        assertThat(detail.attachments().get(0).fileName()).isEqualTo("hash.png");
        assertThat(detail.attachments().get(0).url()).isEqualTo("https://cos.test/" + objectKey);
        assertThat(detail.attachments().get(1).objectKey()).isEqualTo("legacy-proof.png");
        assertThat(detail.attachments().get(1).url()).isNull();
    }

    @Test
    void tenantCannotDeleteExternalShipment() {
        OrderSalesShipmentStore store = mock(OrderSalesShipmentStore.class);
        OrderSalesShipmentService service = new OrderSalesShipmentService(store, orderStore(), generator());
        TestAuthorizationContext.set(caller());
        when(store.shipment(eq(TENANT_ID.toString()), eq(10L))).thenReturn(Optional.of(
                new SalesShipmentDetailView(10L, "FH202608198888", CONNECTOR_ID, "DINGHUOBAO",
                        "FH.20260818.0001", 1L, "DD202608190001", 11L,
                        "CUS202608190001", "上海静安店", "13800000000", "EAST",
                        "RY202608190001", 2L, 3L, "SO202608190001", "SHIPPED",
                        "顺丰", "SF1", BUSINESS_TIME, BigDecimal.ONE, "订货宝发货",
                        1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME, List.of())));

        assertThatThrownBy(() -> service.delete(10L, 1))
                .isInstanceOf(AuthorizationDeniedException.class);

        verify(store, never()).delete(any(), any(), eq(1), any());
    }

    @Test
    void tenantCannotDeleteExternalFundDocument() {
        OrderFundDocumentStore store = mock(OrderFundDocumentStore.class);
        OrderFundDocumentService service = new OrderFundDocumentService(
                store, orderStore(), emptyStaffClient(), generator());
        TestAuthorizationContext.set(caller());
        when(store.fundDocument(eq(TENANT_ID.toString()), eq(40L))).thenReturn(Optional.of(
                new FundDocumentDetailView(40L, "SK202608198888", CONNECTOR_ID, "DINGHUOBAO",
                        "RECEIPT", 1L, "SO202608190001", 11L, "CUS202608190001",
                        "上海静安店", "CUSTOMER", "CUS202608190001", "上海静安店",
                        null, null, BUSINESS_TIME, "CASH", "ORDER_RECEIPT", "CONFIRMED",
                        new BigDecimal("100.00"), "FR.20260818.0001", "DD202608190001",
                        "SERIAL-1", null, null, null, BUSINESS_TIME, BUSINESS_TIME,
                        List.of(), List.of(), List.of(), "订货宝资金", 1, "SYSTEM",
                        BUSINESS_TIME, "SYSTEM", BUSINESS_TIME)));

        assertThatThrownBy(() -> service.delete(40L, 1))
                .isInstanceOf(AuthorizationDeniedException.class);

        verify(store, never()).delete(any(), any(), eq(1), any());
    }

    private static BusinessCodeGenerator generator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "8888");
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("order"), Set.of("order:write", "order:read"));
    }

    private static CallerIdentity serviceCaller() {
        return new CallerIdentity("SERVICE", USER_ID, TENANT_ID, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("DHB_ORDER_SYNC_SERVICE"),
                Set.of("order:write", "order:read", "iam:staff:read"));
    }

    private static IamStaffDisplayClient emptyStaffClient() {
        return (caller, staffCodes) -> List.of();
    }

    private static OrderSalesOrderStore orderStore() {
        OrderSalesOrderStore store = mock(OrderSalesOrderStore.class);
        when(store.salesOrder(eq(TENANT_ID.toString()), eq(1L))).thenReturn(Optional.of(order()));
        return store;
    }

    private static SalesOrderDetailView order() {
        return new SalesOrderDetailView(1L, "DD202608190001", 11L, "CUS202608190001",
                "上海静安店", "张三", "13800000000", "EAST", "sales-1", "李四",
                BUSINESS_TIME, "SUBMITTED", "NORMAL", "CASH", "UNPAID", "PENDING",
                BigDecimal.ONE, new BigDecimal("100.00"), null, BigDecimal.ZERO,
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                "订单", 1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME,
                List.of(new SalesOrderLineView(101L, 1, 201L, 301L, "PRD1", "SKU1",
                        "商品", "规格", "PCS", BigDecimal.ONE, new BigDecimal("100.00"),
                        null, BigDecimal.ZERO, new BigDecimal("100.00"), null)));
    }

    private static SalesShipmentDetailView shipment(String shipmentNo, SalesShipmentWrite write) {
        return new SalesShipmentDetailView(10L, shipmentNo, write.connectorId(),
                write.sourceSystemCode(), write.sourceDocumentNo(), write.salesOrderId(),
                write.salesOrderNoSnapshot(), write.customerId(), write.customerCodeSnapshot(),
                write.customerNameSnapshot(), write.contactPhoneSnapshot(), write.regionCode(),
                write.ownerStaffCode(), write.warehouseId(), write.stockOutOrderId(),
                write.stockOutNo(), write.shipmentStatusCode(), write.logisticsCompany(),
                write.trackingNo(), write.shipTime(), write.totalQuantity(), write.remark(),
                1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME, List.of());
    }

    private static SalesPaymentRecordDetailView payment(String paymentNo, SalesPaymentWrite write) {
        return new SalesPaymentRecordDetailView(20L, paymentNo, write.connectorId(),
                write.sourceSystemCode(), write.sourceDocumentNo(), write.orderId(),
                write.salesOrderNoSnapshot(), write.customerId(), write.customerCodeSnapshot(),
                write.customerNameSnapshot(), write.collectorStaffCode(), write.collectorNameSnapshot(),
                write.paymentTime(), write.paymentMethodCode(), write.paidAmount(), write.voucherKeys(),
                write.remark(), 1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME);
    }

    private static SalesRefundRecordDetailView refund(String refundNo, SalesRefundWrite write) {
        return new SalesRefundRecordDetailView(30L, refundNo, write.orderId(),
                write.salesOrderNoSnapshot(), write.customerId(), write.customerCodeSnapshot(),
                write.customerNameSnapshot(), write.refundStaffCode(), write.refundStaffNameSnapshot(),
                write.refundTime(), write.refundMethodCode(), write.refundStatusCode(),
                write.refundAmount(), write.voucherKeys(), write.remark(), 1,
                "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME);
    }

    private static FundDocumentDetailView fundDocument(String documentNo, FundDocumentWrite write) {
        return new FundDocumentDetailView(40L, documentNo, write.connectorId(), write.sourceSystemCode(),
                write.directionCode(),
                write.relatedOrderId(), write.salesOrderNoSnapshot(), write.customerId(),
                write.customerCodeSnapshot(), write.customerNameSnapshot(), write.counterpartyTypeCode(),
                write.counterpartyCodeSnapshot(), write.counterpartyNameSnapshot(), write.handlerStaffCode(),
                write.handlerStaffNameSnapshot(), write.occurredTime(), write.settlementMethodCode(),
                write.businessTypeCode(), write.documentStatusCode(), write.amount(), write.sourceDocumentNo(),
                write.sourceOrderNo(), write.paymentSerialNo(), write.bankAccountName(), write.bankName(),
                write.bankAccountNo(), write.submittedAt(), write.confirmedAt(), write.sourceAttachmentKeys(),
                write.voucherKeys(), List.of(), write.remark(), 1, "SYSTEM", BUSINESS_TIME, "SYSTEM", BUSINESS_TIME);
    }
}
