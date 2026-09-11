package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderSourceStatusCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutResult;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.order.api.v1.model.SalesOrderTotalsView;
import com.rigour.order.application.port.out.ErpSalesStockOutClient;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutRequest;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSourceProjectionWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderWrite;
import com.rigour.order.domain.enums.SalesOrderOutboundStatus;
import com.rigour.order.domain.enums.SalesOrderPaymentStatus;
import com.rigour.order.domain.enums.SalesOrderStatus;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSalesOrderServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createGeneratesOrderNoAndCalculatesAmounts() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));

        SalesOrderDetailView created = service.create(command(false, 0));

        assertThat(created.orderNo()).isEqualTo("DD202608201234");
        assertThat(created.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(created.totalQuantity()).isEqualByComparingTo("2");
        assertThat(created.originalAmount()).isEqualByComparingTo("20.00");
        assertThat(created.discountAmount()).isEqualByComparingTo("1.00");
        assertThat(created.payableAmount()).isEqualByComparingTo("19.00");
        assertThat(created.lines()).hasSize(1);
    }

    @Test
    void createDinghuobaoOrderUsesSourceOrderDateForOrderNo() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));

        SalesOrderDetailView created = service.create(dinghuobaoCommand());

        assertThat(created.orderNo()).isEqualTo("DD202608191234");
        assertThat(created.sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(created.sourceOrderNo()).isEqualTo("DH.20260819.0001");
    }

    @Test
    void createDinghuobaoOrderRejectsMissingSourceOrderDate() {
        OrderSalesOrderService service = service(new FakeStore());
        TestAuthorizationContext.set(serviceCaller("order:write"));

        assertThatThrownBy(() -> service.create(dinghuobaoCommandWithoutOrderDate()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createFeishuOrderAllowsMissingLinesAsReviewDraft() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));

        SalesOrderDetailView created = service.create(feishuCommandWithoutLines(false));

        assertThat(created.sourceSystemCode()).isEqualTo("FEISHU");
        assertThat(created.sourceOrderNo()).isEqualTo("DD202606021009");
        assertThat(created.lines()).isEmpty();
        assertThat(created.totalQuantity()).isEqualByComparingTo("0");
        assertThat(created.payableAmount()).isEqualByComparingTo("0");
        assertThat(created.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(created.dataQualityStatusCode()).isEqualTo("NEEDS_REVIEW");
        assertThat(created.dataQualityMessage()).contains("商品明细");
    }

    @Test
    void createFeishuOrderRejectsSubmitWhenLinesMissing() {
        OrderSalesOrderService service = service(new FakeStore());
        TestAuthorizationContext.set(serviceCaller("order:write"));

        assertThatThrownBy(() -> service.create(feishuCommandWithoutLines(true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void createFeishuOrderResolvesPaymentVoucherOnOrderDetail() {
        FakeStore store = new FakeStore();
        String voucherKey = TENANT_ID + "/feishu-attachments/order/DD202606021010/付款凭证.png";
        OrderSalesOrderService service = serviceWithAttachmentResolver(store,
                (tenantId, objectKey) -> "https://cos.example.com/" + objectKey);
        TestAuthorizationContext.set(serviceCaller("order:write"));

        SalesOrderDetailView created = service.create(feishuCommandWithPaymentVoucher(voucherKey));

        assertThat(created.orderStatusCode()).isEqualTo(SalesOrderStatus.COMPLETED.code());
        assertThat(created.paymentVoucherKeys()).containsExactly(voucherKey);
        assertThat(created.paymentAttachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.objectKey()).isEqualTo(voucherKey);
            assertThat(attachment.fileName()).isEqualTo("付款凭证.png");
            assertThat(attachment.url()).isEqualTo("https://cos.example.com/" + voucherKey);
        });
    }

    @Test
    void salesOrderTotalsUsesSameSearchCriteria() {
        FakeStore store = new FakeStore();
        store.totals = new SalesOrderTotalsView(2, new BigDecimal("12"), new BigDecimal("720"),
                new BigDecimal("144"), new BigDecimal("576"), new BigDecimal("576"), BigDecimal.ZERO);
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:read"));

        SalesOrderTotalsView result = service.salesOrderTotals(null, "DD202608303365", null, null,
                null, null, "CUSAREA20260830216", null, "EMP202608300001",
                null, "PAID", null, Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T15:59:59Z"), null, null, null, null, null, null);

        assertThat(result.discountAmount()).isEqualByComparingTo("144");
        assertThat(store.criteria.sourceOrderNo()).isEqualTo("DD202608303365");
        assertThat(store.criteria.regionCode()).isEqualTo("CUSAREA20260830216");
        assertThat(store.criteria.ownerEmployeeCode()).isEqualTo("EMP202608300001");
        assertThat(store.criteria.paymentStatusCode()).isEqualTo("PAID");
    }

    @Test
    void serviceCallerCanRefreshExternalSalesOrderProjectionAfterCompletion() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView created = service.create(feishuCommandWithPaymentVoucher("voucher-key"));

        SalesOrderDetailView updated = service.update(created.id(),
                externalProjectionCommand(created, List.of(lineWithQuantity("3"))));

        assertThat(created.orderStatusCode()).isEqualTo(SalesOrderStatus.COMPLETED.code());
        assertThat(updated.orderStatusCode()).isEqualTo(SalesOrderStatus.COMPLETED.code());
        assertThat(updated.sourceSystemCode()).isEqualTo("FEISHU");
        assertThat(updated.sourceOrderNo()).isEqualTo("DD202606021010");
        assertThat(updated.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("3");
            assertThat(line.lineAmount()).isEqualByComparingTo("30.00");
        });
        assertThat(updated.payableAmount()).isEqualByComparingTo("30.00");
        assertThat(updated.revision()).isEqualTo(created.revision() + 1);
    }

    @Test
    void serviceCallerMovesCompletedFeishuOrderBackToDraftWhenProjectionNeedsReview() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView created = service.create(feishuCommandWithPaymentVoucher("voucher-key"));

        SalesOrderDetailView updated = service.update(created.id(),
                externalProjectionCommand(created, List.of()));

        assertThat(created.orderStatusCode()).isEqualTo(SalesOrderStatus.COMPLETED.code());
        assertThat(updated.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(updated.dataQualityStatusCode()).isEqualTo("NEEDS_REVIEW");
        assertThat(updated.dataQualityMessage()).contains("商品明细");
    }

    @Test
    void manualUserCanSupplementAndDeleteFeishuReviewDraft() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView draft = service.create(feishuCommandWithoutLines(false));
        TestAuthorizationContext.set(caller("order:write"));

        SalesOrderDetailView updated = service.update(draft.id(), command(false, draft.revision()));
        service.delete(updated.id(), updated.revision());

        assertThat(updated.sourceSystemCode()).isEqualTo("FEISHU");
        assertThat(updated.sourceOrderNo()).isEqualTo("DD202606021009");
        assertThat(updated.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(updated.dataQualityStatusCode()).isEqualTo("COMPLETE");
        assertThat(updated.lines()).isNotEmpty();
        assertThat(store.deleted).contains(updated.id());
    }

    @Test
    void manualUserCannotMutateCompletedFeishuOrder() {
        FakeStore store = new FakeStore();
        FakeErpSalesStockOutClient erp = new FakeErpSalesStockOutClient();
        OrderSalesOrderService service = service(store, erp);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView completed = service.create(feishuCommandWithPaymentVoucher("voucher-key"));
        TestAuthorizationContext.set(caller("order:write", "erp:supply:write"));

        assertThat(completed.orderStatusCode()).isEqualTo(SalesOrderStatus.COMPLETED.code());
        assertThatThrownBy(() -> service.update(completed.id(), command(false, completed.revision())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.submit(completed.id(), completed.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.cancel(completed.id(), completed.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.confirmOutbound(completed.id(), completed.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.confirmStockOut(completed.id(),
                new SalesOrderStockOutCommand(9L, Instant.parse("2026-08-20T05:00:00Z"),
                        "手动出库", completed.revision())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.delete(completed.id(), completed.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThat(erp.request).isNull();
        assertThat(store.deleted).doesNotContain(completed.id());
    }

    @Test
    void serviceCallerCannotChangeExternalSalesOrderSourceIdentity() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView created = service.create(feishuCommandWithPaymentVoucher("voucher-key"));
        SalesOrderCommand command = new SalesOrderCommand(created.customerId(), created.sourceSystemCode(),
                "DD202606029999", created.sourceStatusCode(), created.sourceCreatorId(),
                created.sourceCreatorStaffCode(), created.sourceCreatorName(), created.customerCodeSnapshot(),
                created.customerNameSnapshot(), created.contactNameSnapshot(), created.contactPhoneSnapshot(),
                created.regionCode(), created.ownerSalesUserId(), created.ownerSalesName(),
                created.ownerEmployeeCode(), created.ownerEmployeeNameSnapshot(), created.orderDate(),
                created.orderTypeCode(), created.paymentMethodCode(), created.paymentVoucherKeys(),
                created.discountRate(), created.discountAmount(), created.remark(), List.of(line()),
                false, created.revision());

        assertThatThrownBy(() -> service.update(created.id(), command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void createRejectsManualExternalSource() {
        OrderSalesOrderService service = service(new FakeStore());
        TestAuthorizationContext.set(caller("order:write"));

        assertThatThrownBy(() -> service.create(dinghuobaoCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void createReturnsCurrentHrEmployeeNameWhenOwnerEmployeeCodeExists() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));

        SalesOrderDetailView created = service.create(commandWithOwnerEmployee());

        assertThat(created.ownerEmployeeCode()).isEqualTo("RY202608220001");
        assertThat(created.ownerEmployeeNameSnapshot()).isEqualTo("王五");
        assertThat(store.rows.get(created.id()).ownerEmployeeNameSnapshot()).isEqualTo("旧姓名快照");
    }

    @Test
    void listUsesIndependentSearchCriteria() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:read"));

        service.salesOrders(0, 20, " DD ", " DH.20260824.0341 ", "shipped", "complete", " 门店 ", " 138 ",
                "east", "legacy-sales-user", "RY202608220001", "draft", "unpaid", "pending",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-31T23:59:59Z"),
                1001L, 2001L, " PRD-1001 ", " SKU-2001 ", " 油泼辣子拌面 ", " 桶装 ");

        assertThat(store.criteria.orderNo()).isEqualTo("DD");
        assertThat(store.criteria.sourceOrderNo()).isEqualTo("DH.20260824.0341");
        assertThat(store.criteria.sourceStatusCode()).isEqualTo("shipped");
        assertThat(store.criteria.dataQualityStatusCode()).isEqualTo("COMPLETE");
        assertThat(store.criteria.customerName()).isEqualTo("门店");
        assertThat(store.criteria.contactPhone()).isEqualTo("138");
        assertThat(store.criteria.regionCode()).isEqualTo("EAST");
        assertThat(store.criteria.ownerSalesUserId()).isEqualTo("legacy-sales-user");
        assertThat(store.criteria.ownerEmployeeCode()).isEqualTo("RY202608220001");
        assertThat(store.criteria.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(store.criteria.paymentStatusCode()).isEqualTo("UNPAID");
        assertThat(store.criteria.outboundStatusCode()).isEqualTo("PENDING");
        assertThat(store.criteria.productId()).isEqualTo(1001L);
        assertThat(store.criteria.productVariantId()).isEqualTo(2001L);
        assertThat(store.criteria.productCodeSnapshot()).isEqualTo("PRD-1001");
        assertThat(store.criteria.skuCodeSnapshot()).isEqualTo("SKU-2001");
        assertThat(store.criteria.productNameSnapshot()).isEqualTo("油泼辣子拌面");
        assertThat(store.criteria.specificationSnapshot()).isEqualTo("桶装");
    }

    @Test
    void createRejectsDuplicateProductVariant() {
        OrderSalesOrderService service = service(new FakeStore());
        TestAuthorizationContext.set(caller("order:write"));
        SalesOrderLineCommand line = line();
        SalesOrderCommand command = new SalesOrderCommand(1L, "CUS-1", "上海静安店",
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(line, line), false, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void submitAndCancelDelegateToStoreWithRevision() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));
        SalesOrderDetailView created = service.create(command(false, 0));

        SalesOrderDetailView submitted = service.submit(created.id(), created.revision());
        SalesOrderDetailView cancelled = service.cancel(submitted.id(), submitted.revision());

        assertThat(submitted.orderStatusCode()).isEqualTo(SalesOrderStatus.SUBMITTED.code());
        assertThat(cancelled.orderStatusCode()).isEqualTo(SalesOrderStatus.CANCELLED.code());
    }

    @Test
    void confirmOutboundUpdatesOrderOutboundStatus() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));
        SalesOrderDetailView created = service.create(command(false, 0));
        SalesOrderDetailView submitted = service.submit(created.id(), created.revision());

        SalesOrderDetailView confirmed = service.confirmOutbound(submitted.id(), submitted.revision());

        assertThat(confirmed.orderStatusCode()).isEqualTo(SalesOrderStatus.SUBMITTED.code());
        assertThat(confirmed.outboundStatusCode()).isEqualTo(SalesOrderOutboundStatus.OUT_CONFIRMED.code());
        assertThat(confirmed.revision()).isEqualTo(submitted.revision() + 1);
    }

    @Test
    void confirmStockOutCallsErpAndUpdatesOrderAfterErpSuccess() {
        FakeStore store = new FakeStore();
        FakeErpSalesStockOutClient erp = new FakeErpSalesStockOutClient();
        OrderSalesOrderService service = service(store, erp);
        TestAuthorizationContext.set(caller("order:write", "erp:supply:write"));
        SalesOrderDetailView submitted = service.create(command(true, 0));
        Instant stockOutTime = Instant.parse("2026-08-20T05:00:00Z");

        SalesOrderStockOutResult result = service.confirmStockOut(submitted.id(),
                new SalesOrderStockOutCommand(9L, stockOutTime, " 手动出库 ", submitted.revision()));

        assertThat(erp.request).isNotNull();
        assertThat(erp.request.salesOrderId()).isEqualTo(submitted.id());
        assertThat(erp.request.salesOrderNo()).isEqualTo(submitted.orderNo());
        assertThat(erp.request.warehouseId()).isEqualTo(9L);
        assertThat(erp.request.stockOutTime()).isEqualTo(stockOutTime);
        assertThat(erp.request.remark()).isEqualTo("手动出库");
        assertThat(erp.request.lines()).singleElement().satisfies(line -> {
            assertThat(line.salesOrderLineId()).isEqualTo(1L);
            assertThat(line.productId()).isEqualTo(10L);
            assertThat(line.productVariantId()).isEqualTo(11L);
            assertThat(line.variantCodeSnapshot()).isEqualTo("SKU-1");
            assertThat(line.unitCode()).isEqualTo("BOX");
            assertThat(line.quantity()).isEqualByComparingTo("2");
        });
        assertThat(result.stockOutOrderId()).isEqualTo(99L);
        assertThat(result.stockOutNo()).isEqualTo("CK202608201234");
        assertThat(result.salesOrder().outboundStatusCode()).isEqualTo(SalesOrderOutboundStatus.OUT_CONFIRMED.code());
        assertThat(result.salesOrder().shipmentTime()).isEqualTo(stockOutTime);
        assertThat(result.salesOrder().revision()).isEqualTo(submitted.revision() + 1);
    }

    @Test
    void deleteUsesLogicalDelete() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));
        SalesOrderDetailView created = service.create(command(false, 0));

        service.delete(created.id(), created.revision());

        assertThat(store.deleted).contains(created.id());
    }

    @Test
    void manualMutationsRejectExternalSalesOrder() {
        FakeStore store = new FakeStore();
        FakeErpSalesStockOutClient erp = new FakeErpSalesStockOutClient();
        OrderSalesOrderService service = service(store, erp);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView external = service.create(dinghuobaoCommand());
        TestAuthorizationContext.set(caller("order:write", "erp:supply:write"));

        assertThatThrownBy(() -> service.update(external.id(), command(false, external.revision())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.submit(external.id(), external.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.cancel(external.id(), external.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.confirmOutbound(external.id(), external.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.confirmStockOut(external.id(),
                new SalesOrderStockOutCommand(9L, Instant.parse("2026-08-20T05:00:00Z"),
                        "手动出库", external.revision())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.delete(external.id(), external.revision()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThat(erp.request).isNull();
    }

    @Test
    void serviceCallerCanUpdateExternalSourceStatusOnly() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView external = service.create(dinghuobaoCommand());

        SalesOrderDetailView updated = service.updateSourceStatus(external.id(),
                new SalesOrderSourceStatusCommand("finished", external.revision()));

        assertThat(updated.sourceStatusCode()).isEqualTo("finished");
        assertThat(updated.orderStatusCode()).isEqualTo(external.orderStatusCode());
        assertThat(updated.revision()).isEqualTo(external.revision() + 1);
    }

    @Test
    void serviceCallerCanCancelExternalSalesOrderBySource() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(serviceCaller("order:write"));
        SalesOrderDetailView external = service.create(dinghuobaoCommand());

        SalesOrderDetailView cancelled = service.cancelBySource(external.id(), external.revision());

        assertThat(cancelled.orderStatusCode()).isEqualTo(SalesOrderStatus.CANCELLED.code());
        assertThat(cancelled.paymentStatusCode()).isEqualTo(SalesOrderPaymentStatus.CANCELLED.code());
        assertThat(cancelled.unpaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cancelled.revision()).isEqualTo(external.revision() + 1);
    }

    private static OrderSalesOrderService service(FakeStore store) {
        return service(store, (caller, request) -> {
            throw new IllegalStateException("测试未装配ERP销售出库客户端");
        });
    }

    private static OrderSalesOrderService service(FakeStore store, ErpSalesStockOutClient erp) {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
        return new OrderSalesOrderService(store, erp, new FakeHrEmployeeDisplayClient(), generator);
    }

    private static OrderSalesOrderService serviceWithAttachmentResolver(FakeStore store, FundAttachmentUrlResolver resolver) {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
        return new OrderSalesOrderService(store, (caller, request) -> {
            throw new IllegalStateException("测试未装配ERP销售出库客户端");
        }, new FakeHrEmployeeDisplayClient(), generator, resolver);
    }

    private static SalesOrderCommand command(boolean submit, Integer revision) {
        return new SalesOrderCommand(1L, "CUS-1", "上海静安店", "张三",
                "13800000000", "east", "sales-1", "李四",
                Instant.parse("2026-08-20T03:00:00Z"), "normal", "cash",
                null, new BigDecimal("1.00"), "备注", List.of(line()), submit, revision);
    }

    private static SalesOrderCommand commandWithOwnerEmployee() {
        return new SalesOrderCommand(1L, "CUS-1", "上海静安店", "张三",
                "13800000000", "east", "sales-1", "李四",
                "RY202608220001", "旧姓名快照", Instant.parse("2026-08-20T03:00:00Z"),
                "normal", "cash", null, new BigDecimal("1.00"), "备注",
                List.of(line()), false, 0);
    }

    private static SalesOrderCommand dinghuobaoCommand() {
        return new SalesOrderCommand(1L, "DINGHUOBAO", "DH.20260819.0001",
                "CUS-1", "上海静安店", "张三", "13800000000", "east",
                "sales-1", "李四", null, null,
                Instant.parse("2026-08-18T16:30:00Z"), "normal", "cash",
                null, new BigDecimal("1.00"), "订货宝导入", List.of(line()),
                false, 0);
    }

    private static SalesOrderCommand dinghuobaoCommandWithoutOrderDate() {
        return new SalesOrderCommand(1L, "DINGHUOBAO", "DH.20260819.0002",
                "CUS-1", "上海静安店", "张三", "13800000000", "east",
                "sales-1", "李四", null, null, null, "normal", "cash",
                null, new BigDecimal("1.00"), "订货宝导入", List.of(line()),
                false, 0);
    }

    private static SalesOrderCommand feishuCommandWithoutLines(boolean submit) {
        return new SalesOrderCommand(1L, "FEISHU", "DD202606021009",
                "C1001", "喜刻台球", null, null, "NORTH",
                null, "张三", null, null,
                Instant.parse("2026-06-02T02:00:00Z"), "normal", "cash",
                null, BigDecimal.ZERO, "飞书导入零明细订单", List.of(), submit, 0);
    }

    private static SalesOrderCommand feishuCommandWithPaymentVoucher(String voucherKey) {
        return new SalesOrderCommand(1L, "FEISHU", "DD202606021010",
                null, null, null, null, "C1001", "喜刻台球",
                null, null, "NORTH", null, "张三", null, null,
                Instant.parse("2026-06-02T02:00:00Z"), "normal", "cash",
                List.of(voucherKey, voucherKey), null, BigDecimal.ZERO,
                "飞书导入订单付款凭证", List.of(line()), false, 0);
    }

    private static SalesOrderCommand externalProjectionCommand(SalesOrderDetailView current,
                                                               List<SalesOrderLineCommand> lines) {
        return new SalesOrderCommand(current.customerId(), current.sourceSystemCode(), current.sourceOrderNo(),
                current.sourceStatusCode(), current.sourceCreatorId(), current.sourceCreatorStaffCode(),
                current.sourceCreatorName(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                current.ownerSalesUserId(), current.ownerSalesName(), current.ownerEmployeeCode(),
                current.ownerEmployeeNameSnapshot(), current.orderDate(), current.orderTypeCode(),
                current.paymentMethodCode(), current.paymentVoucherKeys(), current.discountRate(),
                current.discountAmount(), current.remark(), lines, false, current.revision());
    }

    private static SalesOrderLineCommand line() {
        return lineWithQuantity("2");
    }

    private static SalesOrderLineCommand lineWithQuantity(String quantity) {
        return new SalesOrderLineCommand(10L, 11L, "P-1", "SKU-1", "酸麻粉面菜蛋",
                "箱", "box", new BigDecimal(quantity), new BigDecimal("10.00"),
                null, BigDecimal.ZERO, null);
    }

    private static CallerIdentity caller(String... permissions) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("order"), Set.of(permissions));
    }

    private static CallerIdentity serviceCaller(String... permissions) {
        return new CallerIdentity("SERVICE", USER_ID, TENANT_ID, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("DHB_ORDER_SYNC_SERVICE"), Set.of(permissions));
    }

    private static final class FakeErpSalesStockOutClient implements ErpSalesStockOutClient {
        private SalesStockOutRequest request;

        @Override
        public ErpSalesStockOutClient.SalesStockOutResult confirmSalesStockOut(
                CallerIdentity caller, SalesStockOutRequest request) {
            this.request = request;
            return new ErpSalesStockOutClient.SalesStockOutResult(99L, "CK202608201234", request.stockOutTime());
        }
    }

    private static final class FakeHrEmployeeDisplayClient implements HrEmployeeDisplayClient {
        @Override
        public List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes) {
            if (employeeCodes != null && employeeCodes.contains("RY202608220001")) {
                return List.of(new EmployeeDisplay("RY202608220001", "王五", "ACTIVE"));
            }
            return List.of();
        }
    }

    private static final class FakeStore implements OrderSalesOrderStore {
        private final Map<Long, SalesOrderDetailView> rows = new LinkedHashMap<>();
        private final Set<Long> deleted = new java.util.LinkedHashSet<>();
        private long nextId = 1;
        private SalesOrderSearchCriteria criteria;
        private SalesOrderTotalsView totals = new SalesOrderTotalsView(0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        @Override
        public OrderPageView<SalesOrderSummaryView> salesOrders(
                String tenantId, int begin, int step, SalesOrderSearchCriteria criteria) {
            this.criteria = criteria;
            return new OrderPageView<>(0, begin, step, List.of());
        }

        @Override
        public SalesOrderTotalsView salesOrderTotals(String tenantId, SalesOrderSearchCriteria criteria) {
            this.criteria = criteria;
            return totals;
        }

        @Override
        public Optional<SalesOrderDetailView> salesOrder(String tenantId, Long id) {
            if (deleted.contains(id)) return Optional.empty();
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean existsByNo(String tenantId, String orderNo) {
            return rows.values().stream().anyMatch(row -> row.orderNo().equals(orderNo));
        }

        @Override
        public SalesOrderDetailView create(String tenantId, String orderNo, SalesOrderWrite command, String actorId) {
            Long id = nextId++;
            SalesOrderDetailView row = view(id, orderNo, command, actorId, 1);
            rows.put(id, row);
            return row;
        }

        @Override
        public SalesOrderDetailView update(String tenantId, Long id, SalesOrderWrite command, String actorId) {
            SalesOrderDetailView row = view(id, rows.get(id).orderNo(), command, actorId, command.revision() + 1);
            rows.put(id, row);
            return row;
        }

        @Override
        public SalesOrderDetailView updateExternalProjection(
                String tenantId, Long id, SalesOrderWrite command, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView row = view(id, current.orderNo(), command, actorId, command.revision() + 1);
            if (shouldPreserveExistingOrderStatus(current, command)) {
                row = new SalesOrderDetailView(row.id(), row.orderNo(), row.sourceSystemCode(), row.sourceOrderNo(),
                        row.sourceStatusCode(), row.sourceCreatorId(), row.sourceCreatorStaffCode(),
                        row.sourceCreatorName(), row.dataQualityStatusCode(), row.dataQualityMessage(),
                        row.customerId(), row.customerCodeSnapshot(), row.customerNameSnapshot(),
                        row.contactNameSnapshot(), row.contactPhoneSnapshot(), row.regionCode(),
                        row.ownerSalesUserId(), row.ownerSalesName(), row.ownerEmployeeCode(),
                        row.ownerEmployeeNameSnapshot(), row.orderDate(), current.paymentTime(),
                        current.shipmentTime(), current.shipmentStatusCode(), current.orderStatusCode(),
                        row.orderTypeCode(), row.paymentMethodCode(), current.paymentStatusCode(),
                        current.outboundStatusCode(), row.totalQuantity(), row.originalAmount(),
                        row.discountRate(), row.discountAmount(), row.payableAmount(), current.paidAmount(),
                        row.payableAmount().subtract(current.paidAmount()), row.remark(), row.revision(),
                        current.createdBy(), current.createdTime(), actorId, Instant.now(),
                        row.paymentVoucherKeys(), current.paymentAttachments(), row.lines());
            }
            rows.put(id, row);
            return row;
        }

        private static boolean shouldPreserveExistingOrderStatus(
                SalesOrderDetailView current, SalesOrderWrite command) {
            if (SalesOrderStatus.DRAFT.code().equals(current.orderStatusCode())) return false;
            return !"FEISHU".equalsIgnoreCase(current.sourceSystemCode())
                    || !"NEEDS_REVIEW".equals(command.dataQualityStatusCode());
        }

        @Override
        public SalesOrderDetailView updateSourceStatus(
                String tenantId, Long id, String sourceStatusCode, int revision, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView row = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(), sourceStatusCode,
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerEmployeeCode(),
                    current.ownerEmployeeNameSnapshot(), current.orderDate(),
                    current.paymentTime(), current.shipmentTime(), current.orderStatusCode(),
                    current.orderTypeCode(), current.paymentMethodCode(),
                    current.paymentStatusCode(), current.outboundStatusCode(), current.totalQuantity(),
                    current.originalAmount(), current.discountRate(), current.discountAmount(),
                    current.payableAmount(), current.paidAmount(), current.unpaidAmount(), current.remark(),
                    revision + 1, current.createdBy(), current.createdTime(), actorId, Instant.now(),
                    current.lines());
            rows.put(id, row);
            return row;
        }

        @Override
        public SalesOrderDetailView updateSourceProjection(
                String tenantId, Long id, SalesOrderSourceProjectionWrite command, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView row = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(), command.sourceStatusCode(),
                    command.sourceCreatorId(), command.sourceCreatorStaffCode(), command.sourceCreatorName(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    command.ownerSalesUserId(), command.ownerSalesName(), command.ownerEmployeeCode(),
                    command.ownerEmployeeNameSnapshot(), current.orderDate(), current.paymentTime(),
                    current.shipmentTime(), current.shipmentStatusCode(), current.orderStatusCode(),
                    current.orderTypeCode(), current.paymentMethodCode(), current.paymentStatusCode(),
                    current.outboundStatusCode(), current.totalQuantity(), current.originalAmount(),
                    current.discountRate(), current.discountAmount(), current.payableAmount(),
                    current.paidAmount(), current.unpaidAmount(), current.remark(), command.revision() + 1,
                    current.createdBy(), current.createdTime(), actorId, Instant.now(), current.lines());
            rows.put(id, row);
            return row;
        }

        @Override
        public SalesOrderDetailView submit(String tenantId, Long id, int revision, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView submitted = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(), current.sourceStatusCode(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerEmployeeCode(),
                    current.ownerEmployeeNameSnapshot(), current.orderDate(),
                    current.paymentTime(), current.shipmentTime(),
                    SalesOrderStatus.SUBMITTED.code(), current.orderTypeCode(), current.paymentMethodCode(),
                    current.paymentStatusCode(), current.outboundStatusCode(), current.totalQuantity(),
                    current.originalAmount(), current.discountRate(), current.discountAmount(),
                    current.payableAmount(), current.paidAmount(), current.unpaidAmount(), current.remark(),
                    revision + 1, current.createdBy(), current.createdTime(), actorId, Instant.now(),
                    current.lines());
            rows.put(id, submitted);
            return submitted;
        }

        @Override
        public SalesOrderDetailView cancel(String tenantId, Long id, int revision, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView cancelled = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(), current.sourceStatusCode(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerEmployeeCode(),
                    current.ownerEmployeeNameSnapshot(), current.orderDate(),
                    current.paymentTime(), current.shipmentTime(),
                    SalesOrderStatus.CANCELLED.code(), current.orderTypeCode(), current.paymentMethodCode(),
                    SalesOrderPaymentStatus.CANCELLED.code(), current.outboundStatusCode(), current.totalQuantity(),
                    current.originalAmount(), current.discountRate(), current.discountAmount(),
                    current.payableAmount(), current.paidAmount(), BigDecimal.ZERO, current.remark(),
                    revision + 1, current.createdBy(), current.createdTime(), actorId, Instant.now(),
                    current.lines());
            rows.put(id, cancelled);
            return cancelled;
        }

        @Override
        public SalesOrderDetailView cancelBySource(String tenantId, Long id, int revision, String actorId) {
            return cancel(tenantId, id, revision, actorId);
        }

        @Override
        public SalesOrderDetailView confirmOutbound(
                String tenantId, Long id, int revision, Instant shipmentTime, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView confirmed = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(), current.sourceStatusCode(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerEmployeeCode(),
                    current.ownerEmployeeNameSnapshot(), current.orderDate(),
                    current.paymentTime(), shipmentTime,
                    current.orderStatusCode(), current.orderTypeCode(), current.paymentMethodCode(),
                    current.paymentStatusCode(), SalesOrderOutboundStatus.OUT_CONFIRMED.code(),
                    current.totalQuantity(), current.originalAmount(), current.discountRate(),
                    current.discountAmount(), current.payableAmount(), current.paidAmount(),
                    current.unpaidAmount(), current.remark(), revision + 1, current.createdBy(),
                    current.createdTime(), actorId, Instant.now(), current.lines());
            rows.put(id, confirmed);
            return confirmed;
        }

        @Override
        public void delete(String tenantId, Long id, int revision, String actorId) {
            deleted.add(id);
        }

        private static SalesOrderDetailView view(
                Long id, String orderNo, SalesOrderWrite command, String actorId, int revision) {
            List<SalesOrderLineView> lines = command.lines().stream()
                    .map(item -> new SalesOrderLineView((long) item.lineNo(), item.lineNo(),
                            item.productId(), item.productVariantId(), item.productCodeSnapshot(),
                            item.skuCodeSnapshot(), item.productNameSnapshot(), item.specificationSnapshot(),
                            item.unitCode(), item.quantity(), item.unitPrice(), item.discountRate(),
                            item.discountAmount(), item.lineAmount(), item.remark()))
                    .toList();
            return new SalesOrderDetailView(id, orderNo, command.sourceSystemCode(),
                    command.sourceOrderNo(), command.sourceStatusCode(), command.sourceCreatorId(),
                    command.sourceCreatorStaffCode(), command.sourceCreatorName(),
                    command.dataQualityStatusCode(), command.dataQualityMessage(),
                    command.customerId(), command.customerCodeSnapshot(),
                    command.customerNameSnapshot(), command.contactNameSnapshot(),
                    command.contactPhoneSnapshot(), command.regionCode(), command.ownerSalesUserId(),
                    command.ownerSalesName(), command.ownerEmployeeCode(), command.ownerEmployeeNameSnapshot(),
                    command.orderDate(), null, null, null, command.orderStatusCode(), command.orderTypeCode(),
                    command.paymentMethodCode(), "UNPAID", "PENDING", command.totalQuantity(),
                    command.originalAmount(), command.discountRate(), command.discountAmount(),
                    command.payableAmount(), BigDecimal.ZERO, command.payableAmount(), command.remark(),
                    revision, actorId, Instant.now(), actorId, Instant.now(), command.paymentVoucherKeys(),
                    List.of(), lines);
        }
    }
}
