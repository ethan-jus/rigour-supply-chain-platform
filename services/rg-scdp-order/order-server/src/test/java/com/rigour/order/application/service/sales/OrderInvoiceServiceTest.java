package com.rigour.order.application.service.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceApplyCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceCompleteCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceListItemView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.InvoiceAttachmentStorage;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.application.port.out.OrderInvoiceStore.InvoicePageCriteria;
import com.rigour.order.application.port.out.OrderInvoiceStore.OrderInvoiceProfileRow;
import com.rigour.order.application.port.out.OrderInvoiceStore.OrderInvoiceRow;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.order.domain.invoice.OrderInvoiceStatus;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 开票登记状态机：申请→待开票、上传附件、完成开票→已开票、撤回保留痕迹。 */
class OrderInvoiceServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8e");
    private static final UUID USER_ID = UUID.fromString("019fa000-0000-7000-8000-000000000001");

    private final InMemoryInvoiceStore invoiceStore = new InMemoryInvoiceStore();
    private final RecordingAttachmentStorage storage = new RecordingAttachmentStorage();
    private final OrderRegisterStore orderStore = mock(OrderRegisterStore.class);

    private OrderInvoiceService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FundAttachmentUrlResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(FundAttachmentUrlResolver.NONE);
        return new OrderInvoiceService(invoiceStore, orderStore, storage, provider);
    }

    private void orderExists() {
        when(orderStore.findOrder(eq(TENANT_ID.toString()), eq("A001")))
                .thenReturn(Optional.of(order("A001")));
    }

    @Test
    void applyCreatesPendingInvoiceWithAmountSnapshot() {
        orderExists();
        TestAuthorizationContext.set(caller("order:invoice:write"));

        OrderInvoiceView view = service().apply(applyCommand("A001"));

        assertThat(view.statusCode()).isEqualTo("PENDING");
        assertThat(view.statusName()).isEqualTo("待开票");
        assertThat(view.amount()).isEqualByComparingTo("1000.00");
        assertThat(view.appliedBy()).isEqualTo(USER_ID.toString());
        assertThat(view.appliedAt()).isNotNull();
    }

    @Test
    void applyRejectedWhenInvoiceAlreadyCompleted() {
        orderExists();
        invoiceStore.insert(row("A001", "INVOICED"));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        assertThatThrownBy(() -> service().apply(applyCommand("A001")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVOICE_STATE_CONFLICT);
    }

    @Test
    void applyFromRevokedResetsAttachmentsAndBecomesPendingAgain() {
        orderExists();
        invoiceStore.insert(rowWithAttachments("A001", "REVOKED", List.of("t/order-invoices/A001/a.pdf")));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        OrderInvoiceView view = service().apply(applyCommand("A001"));

        assertThat(view.statusCode()).isEqualTo("PENDING");
        assertThat(view.attachments()).isEmpty();
    }

    @Test
    void applyKeepsAttachmentsWhilePending() {
        orderExists();
        invoiceStore.insert(rowWithAttachments("A001", "PENDING", List.of("t/order-invoices/A001/a.pdf")));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        OrderInvoiceView view = service().apply(applyCommand("A001"));

        assertThat(view.attachments()).hasSize(1);
    }

    @Test
    void uploadAppendsAttachmentAndRejectsUnsupportedType() {
        OrderInvoiceRow pending = invoiceStore.insert(row("A001", "PENDING"));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        OrderInvoiceView uploaded =
                service()
                        .uploadAttachments(
                                pending.id(),
                                List.of(new MockMultipartFile(
                                        "files", "invoice.pdf", "application/pdf", "%PDF".getBytes())));
        assertThat(uploaded.attachments()).hasSize(1);
        assertThat(storage.uploads).hasSize(1);

        assertThatThrownBy(() -> service().uploadAttachments(
                        pending.id(),
                        List.of(new MockMultipartFile("files", "x.gif", "image/gif", "GIF".getBytes()))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVOICE_ATTACHMENT_INVALID);
    }

    @Test
    void completeRequiresAttachmentThenMarksInvoiced() {
        OrderInvoiceRow pending = invoiceStore.insert(row("A001", "PENDING"));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        assertThatThrownBy(() -> service().complete(
                        pending.id(),
                        new OrderInvoiceCompleteCommand("INV-1", Instant.parse("2026-09-20T16:00:00Z"))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVOICE_ATTACHMENT_INVALID);

        service().uploadAttachments(
                pending.id(),
                List.of(new MockMultipartFile("files", "invoice.pdf", "application/pdf", "%PDF".getBytes())));
        OrderInvoiceView view =
                service().complete(
                                pending.id(),
                                new OrderInvoiceCompleteCommand(
                                        "INV-1", Instant.parse("2026-09-20T16:00:00Z")));

        assertThat(view.statusCode()).isEqualTo("INVOICED");
        assertThat(view.statusName()).isEqualTo("已开票");
        assertThat(view.invoiceNo()).isEqualTo("INV-1");
        assertThat(view.invoicedAt()).isEqualTo(Instant.parse("2026-09-20T16:00:00Z"));
    }

    @Test
    void completeRequiresInvoiceNumberAndDate() {
        OrderInvoiceRow pending = invoiceStore.insert(rowWithAttachments(
                "A001", "PENDING", List.of("t/order-invoices/A001/a.pdf")));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        assertThatThrownBy(() -> service().complete(pending.id(), new OrderInvoiceCompleteCommand(" ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void withdrawKeepsTraceAndOnlyPendingCanWithdraw() {
        OrderInvoiceRow pending = invoiceStore.insert(row("A001", "PENDING"));
        TestAuthorizationContext.set(caller("order:invoice:write"));

        OrderInvoiceView revoked = service().withdraw(pending.id());
        assertThat(revoked.statusCode()).isEqualTo("REVOKED");
        assertThat(revoked.statusName()).isEqualTo("已撤回");

        OrderInvoiceRow stored =
                invoiceStore.findById(TENANT_ID.toString(), pending.id()).orElseThrow();
        invoiceStore.replace(rowWithStatus(stored, "INVOICED"));
        assertThatThrownBy(() -> service().withdraw(pending.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVOICE_STATE_CONFLICT);
    }

    @Test
    void viewReturnsNullWhenNoRecordAndRevokedForTrace() {
        TestAuthorizationContext.set(caller("order:read"));
        assertThat(service().view("A001")).isNull();

        invoiceStore.insert(row("A001", "REVOKED"));
        OrderInvoiceView view = service().view("A001");
        assertThat(view.statusCode()).isEqualTo("REVOKED");
        assertThat(view.statusName()).isEqualTo("已撤回");
    }

    @Test
    void pageFiltersByStatusAndReturnsStatusCounts() {
        invoiceStore.insert(row("A001", "PENDING"));
        invoiceStore.insert(row("A002", "INVOICED"));
        TestAuthorizationContext.set(caller("order:read"));

        var view = service().page(0, 20, "PENDING", null, null, null, null);

        assertThat(view.page().items()).hasSize(1);
        assertThat(view.page().items().get(0).orderNo()).isEqualTo("A001");
        assertThat(view.page().items().get(0).statusName()).isEqualTo("待开票");
        assertThat(view.statusCounts())
                .containsEntry("PENDING", 1L)
                .containsEntry("INVOICED", 1L);
    }

    @Test
    void applyStoresCustomerSnapshotAndSavesProfile() {
        orderExists();
        TestAuthorizationContext.set(caller("order:invoice:write"));

        service().apply(applyCommand("A001"));

        OrderInvoiceRow stored =
                invoiceStore.findByOrderNo(TENANT_ID.toString(), "A001").orElseThrow();
        assertThat(stored.customerId()).isEqualTo(200L);
        assertThat(stored.customerCode()).isEqualTo("C200");
        assertThat(invoiceStore.profiles()).hasSize(1);
        assertThat(invoiceStore.profiles().get(0).title()).isEqualTo("杭州测试有限公司");
    }

    @Test
    void profilesByOrderNoReturnsCustomerProfilesForDropdown() {
        orderExists();
        invoiceStore.saveProfile(
                TENANT_ID.toString(),
                new OrderInvoiceProfileRow(
                        null, 200L, "C200", "COMPANY", "杭州测试有限公司", "91330100TEST", "SPECIAL",
                        "工商银行", "622202", "杭州市西湖区", "0571-88888888", null, null, null),
                USER_ID.toString());
        TestAuthorizationContext.set(caller("order:read"));

        var profiles = service().profilesByOrderNo("A001");

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).title()).isEqualTo("杭州测试有限公司");
        assertThat(profiles.get(0).invoiceTypeName()).isEqualTo("专用发票");
    }

    private static OrderInvoiceApplyCommand applyCommand(String orderNo) {
        return new OrderInvoiceApplyCommand(
                orderNo, "COMPANY", "杭州测试有限公司", "91330100TEST", "NORMAL",
                null, null, null, null, null, null);
    }

    private static OrderRegisterOrderView order(String orderNo) {
        return new OrderRegisterOrderView(
                1L, orderNo, null, "DINGHUOBAO", orderNo, null, null,
                200L, "C200", "测试客户",
                null, null,
                "EMP001", "张三",
                null, null,
                null, null,
                null,
                null, null,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                new BigDecimal("1000.00"), BigDecimal.ZERO,
                Instant.parse("2026-09-01T00:00:00Z"),
                null, null, null, null, null, null, null,
                1);
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity(
                "TENANT", USER_ID, TENANT_ID, USER_ID, null, UUID.randomUUID(), 0, 0, 0,
                Set.of("order"), Set.of(permission));
    }

    private static OrderInvoiceRow row(String orderNo, String status) {
        return rowWithAttachments(orderNo, status, List.of());
    }

    private static OrderInvoiceRow rowWithAttachments(String orderNo, String status, List<String> keys) {
        return new OrderInvoiceRow(
                null, 1L, orderNo, 200L, "C200", status, "COMPANY", "杭州测试有限公司", "91330100TEST",
                "NORMAL",
                null, null, null, null, null, null, new BigDecimal("1000.00"), keys, null,
                USER_ID.toString(), Instant.parse("2026-09-20T02:00:00Z"), null, null, null, null, 1);
    }

    private static OrderInvoiceRow withRevision(OrderInvoiceRow source, int revision) {
        return new OrderInvoiceRow(
                source.id(), source.salesOrderId(), source.orderNo(), source.customerId(),
                source.customerCode(), source.status(), source.titleType(), source.title(),
                source.taxNo(), source.invoiceType(), source.bankName(), source.bankAccount(),
                source.registerAddress(), source.registerPhone(), source.email(), source.remark(),
                source.amount(), source.attachmentKeys(), source.invoiceNo(), source.appliedBy(),
                source.appliedAt(), source.invoicedBy(), source.invoicedAt(), source.updatedBy(),
                source.updatedAt(), revision);
    }

    private static OrderInvoiceRow rowWithStatus(OrderInvoiceRow source, String status) {
        return new OrderInvoiceRow(
                source.id(), source.salesOrderId(), source.orderNo(), source.customerId(),
                source.customerCode(), status, source.titleType(),
                source.title(), source.taxNo(), source.invoiceType(), source.bankName(),
                source.bankAccount(), source.registerAddress(), source.registerPhone(), source.email(),
                source.remark(), source.amount(), source.attachmentKeys(), source.invoiceNo(),
                source.appliedBy(), source.appliedAt(), source.invoicedBy(), source.invoicedAt(),
                source.updatedBy(), source.updatedAt(), source.revision());
    }

    @Test
    void completeRejectsWhenStatusChangedByAnotherOperator() {
        orderExists();
        TestAuthorizationContext.set(caller("order:invoice:write"));
        OrderInvoiceView applied = service().apply(applyCommand("A001"));

        // 另一个人先撤回（状态已变），再完成开票命中状态校验
        service().withdraw(applied.id());

        assertThatThrownBy(
                        () ->
                                service().complete(
                                        applied.id(),
                                        new OrderInvoiceCompleteCommand(
                                                "INV-001", Instant.parse("2026-09-21T03:00:00Z"))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_INVOICE_STATE_CONFLICT);
        assertThat(invoiceStore.rows.get(applied.id()).status()).isEqualTo("REVOKED");
    }

    @Test
    void completeRejectsWhenAnotherWriterWinsTheRace() {
        orderExists();
        TestAuthorizationContext.set(caller("order:invoice:write"));
        OrderInvoiceView applied = service().apply(applyCommand("A001"));
        // 上传附件，满足完成开票的前置条件
        service().uploadAttachments(
                applied.id(),
                List.of(new org.springframework.mock.web.MockMultipartFile(
                        "files", "invoice.pdf", "application/pdf", new byte[] {1, 2, 3})));

        // 模拟“读完之后、写入之前”另一个人先完成了状态变更（版本 +1）
        java.util.concurrent.atomic.AtomicBoolean raced =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        invoiceStore.beforeSave =
                () -> {
                    OrderInvoiceRow current = invoiceStore.rows.get(applied.id());
                    invoiceStore.rows.put(
                            applied.id(), withRevision(current, current.revision() + 1));
                    invoiceStore.beforeSave = null;
                    raced.set(true);
                };

        assertThatThrownBy(
                        () ->
                                service().complete(
                                        applied.id(),
                                        new OrderInvoiceCompleteCommand(
                                                "INV-RACE-1", Instant.parse("2026-09-21T03:00:00Z"))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(raced).isTrue();
    }

    private static final class RecordingAttachmentStorage implements InvoiceAttachmentStorage {
        private final List<String> uploads = new ArrayList<>();

        @Override
        public String upload(String tenantId, String orderNo, String fileName, String contentType, byte[] content) {
            String key = tenantId + "/order-invoices/" + orderNo + "/" + UUID.randomUUID() + ".pdf";
            uploads.add(key);
            return key;
        }
    }

    private static final class InMemoryInvoiceStore implements OrderInvoiceStore {
        private final Map<Long, OrderInvoiceRow> rows = new LinkedHashMap<>();
        private final List<OrderInvoiceProfileRow> profiles = new ArrayList<>();
        private long sequence = 0;
        private long profileSequence = 0;
        /** 测试钩子：模拟读取之后、写入之前发生的并发写入。 */
        private Runnable beforeSave;

        OrderInvoiceRow insert(OrderInvoiceRow row) {
            sequence += 1;
            OrderInvoiceRow stored =
                    new OrderInvoiceRow(
                            sequence, row.salesOrderId(), row.orderNo(), row.customerId(),
                            row.customerCode(), row.status(), row.titleType(),
                            row.title(), row.taxNo(), row.invoiceType(), row.bankName(),
                            row.bankAccount(), row.registerAddress(), row.registerPhone(), row.email(),
                            row.remark(), row.amount(), row.attachmentKeys(), row.invoiceNo(),
                            row.appliedBy(), row.appliedAt(), row.invoicedBy(), row.invoicedAt(),
                            row.updatedBy(), row.updatedAt(), 1);
            rows.put(sequence, stored);
            return stored;
        }

        void replace(OrderInvoiceRow row) {
            rows.put(row.id(), row);
        }

        List<OrderInvoiceProfileRow> profiles() {
            return profiles;
        }

        @Override
        public Optional<OrderInvoiceRow> findByOrderNo(String tenantId, String orderNo) {
            return rows.values().stream().filter(row -> row.orderNo().equals(orderNo)).findFirst();
        }

        @Override
        public Optional<OrderInvoiceRow> findByOrderId(String tenantId, long salesOrderId) {
            return rows.values().stream()
                    .filter(row -> row.salesOrderId() == salesOrderId)
                    .findFirst();
        }

        @Override
        public Optional<OrderInvoiceRow> findById(String tenantId, long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<OrderInvoiceProfileRow> profilesByCustomer(String tenantId, long customerId) {
            return profiles.stream()
                    .filter(profile -> profile.customerId() == customerId)
                    .sorted(
                            java.util.Comparator.comparing(
                                            OrderInvoiceProfileRow::lastUsedAt,
                                            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                                    .reversed())
                    .toList();
        }

        @Override
        public OrderInvoiceProfileRow saveProfile(
                String tenantId, OrderInvoiceProfileRow row, String actorId) {
            for (int index = 0; index < profiles.size(); index++) {
                OrderInvoiceProfileRow existing = profiles.get(index);
                if (existing.customerId() == row.customerId()
                        && java.util.Objects.equals(existing.title(), row.title())
                        && java.util.Objects.equals(existing.taxNo(), row.taxNo())
                        && java.util.Objects.equals(existing.invoiceType(), row.invoiceType())) {
                    OrderInvoiceProfileRow updated =
                            new OrderInvoiceProfileRow(
                                    existing.id(), row.customerId(), row.customerCode(),
                                    row.titleType(), row.title(), row.taxNo(), row.invoiceType(),
                                    row.bankName(), row.bankAccount(), row.registerAddress(),
                                    row.registerPhone(), row.email(), row.remark(), Instant.now());
                    profiles.set(index, updated);
                    return updated;
                }
            }
            profileSequence += 1;
            OrderInvoiceProfileRow created =
                    new OrderInvoiceProfileRow(
                            profileSequence, row.customerId(), row.customerCode(), row.titleType(),
                            row.title(), row.taxNo(), row.invoiceType(), row.bankName(),
                            row.bankAccount(), row.registerAddress(), row.registerPhone(), row.email(),
                            row.remark(), Instant.now());
            profiles.add(created);
            return created;
        }

        @Override
        public Map<Long, String> statusesByOrderIds(String tenantId, Collection<Long> orderIds) {
            Map<Long, String> result = new LinkedHashMap<>();
            rows.values().stream()
                    .filter(row -> orderIds.contains(row.salesOrderId()))
                    .forEach(row -> result.put(row.salesOrderId(), row.status()));
            return result;
        }

        @Override
        public OrderRegisterPage<OrderInvoiceListItemView> page(
                String tenantId, int begin, int step, InvoicePageCriteria criteria) {
            List<OrderInvoiceRow> filtered =
                    rows.values().stream()
                            .filter(row -> criteria.status() == null || criteria.status().equals(row.status()))
                            .filter(row -> criteria.orderNo() == null || criteria.orderNo().equals(row.orderNo()))
                            .toList();
            List<OrderInvoiceListItemView> items =
                    filtered.stream()
                            .skip(begin)
                            .limit(step)
                            .map(
                                    row ->
                                            new OrderInvoiceListItemView(
                                                    row.id(),
                                                    row.salesOrderId(),
                                                    row.orderNo(),
                                                    "测试客户",
                                                    row.title(),
                                                    "普通发票",
                                                    row.amount(),
                                                    row.status(),
                                                    OrderInvoiceStatus.displayNameOf(row.status()),
                                                    row.appliedBy(),
                                                    row.appliedAt(),
                                                    row.invoiceNo(),
                                                    row.invoicedAt(),
                                                    row.attachmentKeys().size()))
                            .toList();
            return new OrderRegisterPage<>(filtered.size(), begin, step, items, Map.of(), null);
        }

        @Override
        public Map<String, Long> statusCounts(String tenantId, InvoicePageCriteria criteria) {
            Map<String, Long> counts = new LinkedHashMap<>();
            rows.values().forEach(row -> counts.merge(row.status(), 1L, Long::sum));
            return counts;
        }

        @Override
        public OrderInvoiceRow save(
                String tenantId, OrderInvoiceRow row, String expectedStatus, String actorId) {
            if (row.id() == null) return insert(row);
            if (beforeSave != null) beforeSave.run();
            OrderInvoiceRow current = rows.get(row.id());
            boolean statusMatched =
                    expectedStatus == null || expectedStatus.isBlank()
                            || expectedStatus.equals(current == null ? null : current.status());
            if (current == null || !statusMatched || current.revision() != row.revision()) {
                throw new BusinessException(ErrorCode.CONFLICT, "发票状态已变化，请刷新后重试", List.of());
            }
            OrderInvoiceRow stored =
                    new OrderInvoiceRow(
                            row.id(), row.salesOrderId(), row.orderNo(), row.customerId(),
                            row.customerCode(), row.status(), row.titleType(), row.title(),
                            row.taxNo(), row.invoiceType(), row.bankName(), row.bankAccount(),
                            row.registerAddress(), row.registerPhone(), row.email(), row.remark(),
                            row.amount(), row.attachmentKeys(), row.invoiceNo(), row.appliedBy(),
                            row.appliedAt(), row.invoicedBy(), row.invoicedAt(), row.updatedBy(),
                            row.updatedAt(), current.revision() + 1);
            rows.put(row.id(), stored);
            return stored;
        }
    }
}
