package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesPaymentRecordSummaryView;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore.SalesPaymentSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesPaymentRecordStore.SalesPaymentWrite;
import com.rigour.order.domain.code.OrderBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Order 销售回款记录用例；回款记录维护销售订单收款汇总。 */
@Service
public final class OrderSalesPaymentRecordService {
    private static final Logger log = LoggerFactory.getLogger(OrderSalesPaymentRecordService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> IAM_STAFF_READ_PERMISSIONS = Set.of("iam:staff:read");

    private final OrderSalesPaymentRecordStore store;
    private final OrderSalesOrderStore orderStore;
    private final IamStaffDisplayClient iamStaffDisplayClient;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderSalesPaymentRecordService(OrderSalesPaymentRecordStore store,
                                          OrderSalesOrderStore orderStore,
                                          IamStaffDisplayClient iamStaffDisplayClient) {
        this(store, orderStore, iamStaffDisplayClient, new BusinessCodeGenerator());
    }

    OrderSalesPaymentRecordService(OrderSalesPaymentRecordStore store,
                                   OrderSalesOrderStore orderStore,
                                   IamStaffDisplayClient iamStaffDisplayClient,
                                   BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.orderStore = Objects.requireNonNull(orderStore, "orderStore");
        this.iamStaffDisplayClient = Objects.requireNonNull(iamStaffDisplayClient, "iamStaffDisplayClient");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<SalesPaymentRecordSummaryView> payments(
            int begin, int step, String paymentNo, String salesOrderNo, String customerName,
            String collectorStaffCode, String paymentMethodCode,
            Instant paymentTimeFrom, Instant paymentTimeTo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (paymentTimeFrom != null && paymentTimeTo != null && paymentTimeFrom.isAfter(paymentTimeTo)) {
            throw badRequest("paymentTimeFrom不能晚于paymentTimeTo");
        }
        SalesPaymentSearchCriteria criteria = new SalesPaymentSearchCriteria(
                text(paymentNo, 50, "paymentNo"),
                text(salesOrderNo, 50, "salesOrderNo"),
                text(customerName, 200, "customerName"),
                text(collectorStaffCode, 50, "collectorStaffCode"),
                code(paymentMethodCode, "paymentMethodCode", false),
                paymentTimeFrom,
                paymentTimeTo);
        OrderPageView<SalesPaymentRecordSummaryView> result =
                store.payments(tenantId, pageBegin(begin), pageStep(step), criteria);
        result = withStaffNames(actor, result);
        log.debug("Order销售回款记录列表查询完成 tenantId={} paymentNo={} salesOrderNo={} customerName={} count={} total={}",
                tenantId, value(criteria.paymentNo()), value(criteria.salesOrderNo()),
                value(criteria.customerName()), result.items().size(), result.total());
        return result;
    }

    public SalesPaymentRecordDetailView payment(Long id) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesPaymentRecordDetailView result = store.payment(tenantId, requireId(id, "销售回款记录ID无效"))
                .orElseThrow(() -> notFound("销售回款记录不存在"));
        result = withStaffName(actor, result);
        log.debug("Order销售回款记录详情查询完成 tenantId={} paymentId={} paymentNo={}",
                tenantId, result.id(), result.paymentNo());
        return result;
    }

    public SalesPaymentRecordDetailView create(SalesPaymentRecordCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesPaymentWrite normalized = normalize(tenantId, command, false, actor, null);
        String paymentNo = codeGenerator.generateUnique(OrderBusinessCodeRules.PAYMENT_RECORD,
                normalized.paymentTime(), candidate -> !store.existsByNo(tenantId, candidate));
        SalesPaymentRecordDetailView created =
                store.create(tenantId, paymentNo, normalized, OrderAuditActors.writeActor(actor));
        created = withStaffName(actor, created);
        log.info("Order销售回款记录创建完成 tenantId={} paymentId={} paymentNo={} salesOrderId={} paidAmount={} actorId={}",
                tenantId, created.id(), created.paymentNo(), created.orderId(),
                created.paidAmount(), actor.principalId());
        return created;
    }

    public SalesPaymentRecordDetailView update(Long id, SalesPaymentRecordCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        Long paymentId = requireId(id, "销售回款记录ID无效");
        SalesPaymentRecordDetailView existing = store.payment(tenantId, paymentId)
                .orElseThrow(() -> notFound("销售回款记录不存在"));
        requireExternalMutationAllowed(actor, existing.sourceSystemCode(), "销售回款记录");
        SalesPaymentWrite normalized = normalize(tenantId, command, true, actor, existing.sourceSystemCode());
        SalesPaymentRecordDetailView updated = store.update(
                tenantId, paymentId, normalized,
                OrderAuditActors.writeActor(actor));
        updated = withStaffName(actor, updated);
        log.info("Order销售回款记录修改完成 tenantId={} paymentId={} paymentNo={} revision={} actorId={}",
                tenantId, updated.id(), updated.paymentNo(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        Long paymentId = requireId(id, "销售回款记录ID无效");
        SalesPaymentRecordDetailView existing = store.payment(tenantId, paymentId)
                .orElseThrow(() -> notFound("销售回款记录不存在"));
        requireExternalMutationAllowed(actor, existing.sourceSystemCode(), "销售回款记录");
        store.delete(tenantId, paymentId, revision, OrderAuditActors.writeActor(actor));
        log.info("Order销售回款记录逻辑删除完成 tenantId={} paymentId={} revision={} actorId={}",
                tenantId, paymentId, revision, actor.principalId());
    }

    private SalesPaymentWrite normalize(String tenantId, SalesPaymentRecordCommand command, boolean update,
                                        CallerIdentity actor, String existingSourceSystemCode) {
        if (command == null) throw badRequest("销售回款记录参数不能为空");
        checkRevision(command.revision(), update);
        String sourceSystemCode = sourceSystemCode(command.sourceSystemCode());
        String sourceDocumentNo = text(command.sourceDocumentNo(), 128, "sourceDocumentNo");
        requireSourceFieldsAllowed(actor, command.connectorId(), sourceSystemCode, sourceDocumentNo);
        if (hasText(existingSourceSystemCode) && !hasText(sourceSystemCode)) {
            throw badRequest("外部来源销售回款记录更新必须保留sourceSystemCode");
        }
        if (hasText(sourceSystemCode)) {
            if (command.connectorId() == null) throw badRequest("外部来源销售回款记录connectorId不能为空");
            if (!hasText(sourceDocumentNo)) throw badRequest("外部来源销售回款记录sourceDocumentNo不能为空");
            if (command.paymentTime() == null) throw badRequest("外部来源销售回款记录paymentTime必须使用来源交易时间");
        }
        Long orderId = requireId(command.orderId(), "orderId无效");
        SalesOrderDetailView order = orderStore.salesOrder(tenantId, orderId)
                .orElseThrow(() -> notFound("销售订单不存在"));
        BigDecimal paidAmount = money(command.paidAmount(), "paidAmount");
        String collectorStaffCode = text(command.collectorStaffCode(), 50, "collectorStaffCode");
        return new SalesPaymentWrite(
                command.connectorId(),
                sourceSystemCode,
                sourceDocumentNo,
                order.id(),
                order.orderNo(),
                order.customerId(),
                order.customerCodeSnapshot(),
                order.customerNameSnapshot(),
                collectorStaffCode,
                text(first(command.collectorNameSnapshot(), staffNameSnapshot(collectorStaffCode)), 100,
                        "collectorNameSnapshot"),
                requireInstant(command.paymentTime(), "paymentTime不能为空"),
                code(command.paymentMethodCode(), "paymentMethodCode", false),
                paidAmount,
                voucherKeys(command.voucherKeys()),
                text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private List<String> voucherKeys(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .map(value -> text(value, 500, "voucherKey"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private OrderPageView<SalesPaymentRecordSummaryView> withStaffNames(
            CallerIdentity actor, OrderPageView<SalesPaymentRecordSummaryView> page) {
        Set<String> staffCodes = new LinkedHashSet<>();
        for (SalesPaymentRecordSummaryView item : page.items()) {
            if (item.collectorStaffCode() != null && !item.collectorStaffCode().isBlank()) {
                staffCodes.add(item.collectorStaffCode().strip());
            }
        }
        Map<String, String> names = staffNames(actor, staffCodes);
        if (names.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream().map(item -> withStaffName(item, names)).toList());
    }

    private SalesPaymentRecordDetailView withStaffName(CallerIdentity actor, SalesPaymentRecordDetailView detail) {
        if (detail.collectorStaffCode() == null || detail.collectorStaffCode().isBlank()) return detail;
        String staffName = staffNames(actor, Set.of(detail.collectorStaffCode().strip()))
                .get(detail.collectorStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return detail;
        return new SalesPaymentRecordDetailView(detail.id(), detail.paymentNo(), detail.connectorId(),
                detail.sourceSystemCode(), detail.sourceDocumentNo(), detail.orderId(),
                detail.salesOrderNoSnapshot(), detail.customerId(), detail.customerCodeSnapshot(),
                detail.customerNameSnapshot(), detail.collectorStaffCode(), staffName, detail.paymentTime(),
                detail.paymentMethodCode(), detail.paidAmount(), detail.voucherKeys(), detail.remark(),
                detail.revision(), detail.createdBy(), detail.createdTime(), detail.updatedBy(),
                detail.updatedTime());
    }

    private SalesPaymentRecordSummaryView withStaffName(
            SalesPaymentRecordSummaryView item, Map<String, String> staffNames) {
        if (item.collectorStaffCode() == null || item.collectorStaffCode().isBlank()) return item;
        String staffName = staffNames.get(item.collectorStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return item;
        return new SalesPaymentRecordSummaryView(item.id(), item.paymentNo(), item.connectorId(),
                item.sourceSystemCode(), item.sourceDocumentNo(), item.orderId(),
                item.salesOrderNoSnapshot(), item.customerId(), item.customerCodeSnapshot(),
                item.customerNameSnapshot(), item.collectorStaffCode(), staffName, item.paymentTime(),
                item.paymentMethodCode(), item.paidAmount(), item.revision(), item.updatedTime());
    }

    private String staffNameSnapshot(String collectorStaffCode) {
        if (collectorStaffCode == null || collectorStaffCode.isBlank()) return null;
        try {
            CallerIdentity caller = AuthorizationContext.requireCurrent();
            return staffNames(caller, Set.of(collectorStaffCode.strip())).get(collectorStaffCode.strip());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, String> staffNames(CallerIdentity actor, Set<String> staffCodes) {
        if (staffCodes == null || staffCodes.isEmpty()) return Map.of();
        CallerIdentity serviceCaller = iamServiceCaller(actor.tenantId());
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (IamStaffDisplayClient.StaffDisplay item : iamStaffDisplayClient.resolve(serviceCaller, staffCodes)) {
                if (item == null || item.staffCode() == null || item.staffCode().isBlank()
                        || item.staffName() == null || item.staffName().isBlank()) {
                    continue;
                }
                result.put(item.staffCode().strip(), item.staffName().strip());
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("IAM人员展示名查询失败，Order返回回款人姓名快照 tenantId={} staffCount={} reason={}",
                    actor.tenantId(), staffCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private static BigDecimal money(BigDecimal value, String name) {
        if (value == null || value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value.stripTrailingZeros();
    }

    private static Instant requireInstant(Instant value, String message) {
        if (value == null) throw badRequest(message);
        return value;
    }

    private static Long requireId(Long value, String message) {
        if (value == null || value < 1) throw badRequest(message);
        return value;
    }

    private static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
    }

    private static void checkRevision(Integer revision, boolean update) {
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增时revision必须为空或0");
    }

    private static void requireRevision(int revision) {
        if (revision < 1) throw badRequest("revision必须大于0");
    }

    private static String code(String value, String name, boolean required) {
        String normalized = upper(value);
        if (normalized == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String upper(String value) {
        String normalized = text(value, 64, "code");
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String sourceSystemCode(String value) {
        String normalized = upper(value);
        if (normalized == null) return null;
        if (normalized.length() > 64) throw badRequest("sourceSystemCode长度不能超过64");
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireSourceFieldsAllowed(
            CallerIdentity actor, UUID connectorId, String sourceSystemCode, String sourceDocumentNo) {
        if (!isServiceActor(actor) && (connectorId != null || hasText(sourceSystemCode) || hasText(sourceDocumentNo))) {
            throw new AuthorizationDeniedException("external-source-write");
        }
    }

    private static void requireExternalMutationAllowed(
            CallerIdentity actor, String sourceSystemCode, String documentName) {
        if (hasText(sourceSystemCode) && !isServiceActor(actor)) {
            throw new AuthorizationDeniedException(documentName + "-external-readonly");
        }
    }

    private static boolean isServiceActor(CallerIdentity actor) {
        return actor != null && "SERVICE".equals(actor.principalScope());
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static CallerIdentity iamServiceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_CENTER"), IAM_STAFF_READ_PERMISSIONS);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
