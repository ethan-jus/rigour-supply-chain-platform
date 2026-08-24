package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderStockOutCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutResult;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.order.application.port.out.ErpSalesStockOutClient;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutRequest;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderWrite;
import com.rigour.order.domain.enums.SalesOrderOutboundStatus;
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
        TestAuthorizationContext.set(caller("order:write"));

        SalesOrderDetailView created = service.create(dinghuobaoCommand());

        assertThat(created.orderNo()).isEqualTo("DD202608191234");
        assertThat(created.sourceSystemCode()).isEqualTo("DINGHUOBAO");
        assertThat(created.sourceOrderNo()).isEqualTo("DH.20260819.0001");
    }

    @Test
    void createReturnsCurrentIamStaffNameWhenOwnerStaffCodeExists() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:write"));

        SalesOrderDetailView created = service.create(commandWithOwnerStaff());

        assertThat(created.ownerStaffCode()).isEqualTo("RY202608220001");
        assertThat(created.ownerStaffNameSnapshot()).isEqualTo("王五");
        assertThat(store.rows.get(created.id()).ownerStaffNameSnapshot()).isEqualTo("旧姓名快照");
    }

    @Test
    void listUsesIndependentSearchCriteria() {
        FakeStore store = new FakeStore();
        OrderSalesOrderService service = service(store);
        TestAuthorizationContext.set(caller("order:read"));

        service.salesOrders(0, 20, " DD ", " DH.20260824.0341 ", " 门店 ", " 138 ",
                "east", "legacy-sales-user", "RY202608220001", "draft", "unpaid", "pending",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-31T23:59:59Z"));

        assertThat(store.criteria.orderNo()).isEqualTo("DD");
        assertThat(store.criteria.sourceOrderNo()).isEqualTo("DH.20260824.0341");
        assertThat(store.criteria.customerName()).isEqualTo("门店");
        assertThat(store.criteria.contactPhone()).isEqualTo("138");
        assertThat(store.criteria.regionCode()).isEqualTo("EAST");
        assertThat(store.criteria.ownerSalesUserId()).isEqualTo("legacy-sales-user");
        assertThat(store.criteria.ownerStaffCode()).isEqualTo("RY202608220001");
        assertThat(store.criteria.orderStatusCode()).isEqualTo(SalesOrderStatus.DRAFT.code());
        assertThat(store.criteria.paymentStatusCode()).isEqualTo("UNPAID");
        assertThat(store.criteria.outboundStatusCode()).isEqualTo("PENDING");
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

    private static OrderSalesOrderService service(FakeStore store) {
        return service(store, (caller, request) -> {
            throw new IllegalStateException("测试未装配ERP销售出库客户端");
        });
    }

    private static OrderSalesOrderService service(FakeStore store, ErpSalesStockOutClient erp) {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
        return new OrderSalesOrderService(store, erp, new FakeIamStaffDisplayClient(), generator);
    }

    private static SalesOrderCommand command(boolean submit, Integer revision) {
        return new SalesOrderCommand(1L, "CUS-1", "上海静安店", "张三",
                "13800000000", "east", "sales-1", "李四",
                Instant.parse("2026-08-20T03:00:00Z"), "normal", "cash",
                null, new BigDecimal("1.00"), "备注", List.of(line()), submit, revision);
    }

    private static SalesOrderCommand commandWithOwnerStaff() {
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

    private static SalesOrderLineCommand line() {
        return new SalesOrderLineCommand(10L, 11L, "P-1", "SKU-1", "酸麻粉面菜蛋",
                "箱", "box", new BigDecimal("2"), new BigDecimal("10.00"),
                null, BigDecimal.ZERO, null);
    }

    private static CallerIdentity caller(String... permissions) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("order"), Set.of(permissions));
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

    private static final class FakeIamStaffDisplayClient implements IamStaffDisplayClient {
        @Override
        public List<StaffDisplay> resolve(CallerIdentity caller, Set<String> staffCodes) {
            if (staffCodes != null && staffCodes.contains("RY202608220001")) {
                return List.of(new StaffDisplay("RY202608220001", "王五", "ACTIVE"));
            }
            return List.of();
        }
    }

    private static final class FakeStore implements OrderSalesOrderStore {
        private final Map<Long, SalesOrderDetailView> rows = new LinkedHashMap<>();
        private final Set<Long> deleted = new java.util.LinkedHashSet<>();
        private long nextId = 1;
        private SalesOrderSearchCriteria criteria;

        @Override
        public OrderPageView<SalesOrderSummaryView> salesOrders(
                String tenantId, int begin, int step, SalesOrderSearchCriteria criteria) {
            this.criteria = criteria;
            return new OrderPageView<>(0, begin, step, List.of());
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
        public SalesOrderDetailView submit(String tenantId, Long id, int revision, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView submitted = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerStaffCode(),
                    current.ownerStaffNameSnapshot(), current.orderDate(),
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
                    current.sourceSystemCode(), current.sourceOrderNo(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerStaffCode(),
                    current.ownerStaffNameSnapshot(), current.orderDate(),
                    SalesOrderStatus.CANCELLED.code(), current.orderTypeCode(), current.paymentMethodCode(),
                    current.paymentStatusCode(), current.outboundStatusCode(), current.totalQuantity(),
                    current.originalAmount(), current.discountRate(), current.discountAmount(),
                    current.payableAmount(), current.paidAmount(), current.unpaidAmount(), current.remark(),
                    revision + 1, current.createdBy(), current.createdTime(), actorId, Instant.now(),
                    current.lines());
            rows.put(id, cancelled);
            return cancelled;
        }

        @Override
        public SalesOrderDetailView confirmOutbound(String tenantId, Long id, int revision, String actorId) {
            SalesOrderDetailView current = rows.get(id);
            SalesOrderDetailView confirmed = new SalesOrderDetailView(current.id(), current.orderNo(),
                    current.sourceSystemCode(), current.sourceOrderNo(),
                    current.customerId(), current.customerCodeSnapshot(), current.customerNameSnapshot(),
                    current.contactNameSnapshot(), current.contactPhoneSnapshot(), current.regionCode(),
                    current.ownerSalesUserId(), current.ownerSalesName(), current.ownerStaffCode(),
                    current.ownerStaffNameSnapshot(), current.orderDate(),
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
            SalesOrderLineView line = new SalesOrderLineView(1L, 1, 10L, 11L, "P-1", "SKU-1",
                    "酸麻粉面菜蛋", "箱", "BOX", new BigDecimal("2"), new BigDecimal("10.00"),
                    null, BigDecimal.ZERO, new BigDecimal("20.00"), null);
            return new SalesOrderDetailView(id, orderNo, command.sourceSystemCode(),
                    command.sourceOrderNo(), command.customerId(), command.customerCodeSnapshot(),
                    command.customerNameSnapshot(), command.contactNameSnapshot(),
                    command.contactPhoneSnapshot(), command.regionCode(), command.ownerSalesUserId(),
                    command.ownerSalesName(), command.ownerStaffCode(), command.ownerStaffNameSnapshot(),
                    command.orderDate(), command.orderStatusCode(), command.orderTypeCode(),
                    command.paymentMethodCode(), "UNPAID", "PENDING", command.totalQuantity(),
                    command.originalAmount(), command.discountRate(), command.discountAmount(),
                    command.payableAmount(), BigDecimal.ZERO, command.payableAmount(), command.remark(),
                    revision, actorId, Instant.now(), actorId, Instant.now(), List.of(line));
        }
    }
}
