package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordCommand;
import com.rigour.order.api.v1.model.SalesRefundRecordDetailView;
import com.rigour.order.api.v1.model.SalesRefundRecordSummaryView;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore.SalesRefundSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesRefundRecordStore.SalesRefundWrite;
import com.rigour.order.domain.code.OrderBusinessCodeRules;
import com.rigour.order.domain.enums.SalesRefundStatus;
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

/** Order 销售退款记录用例；退款记录会抵扣销售订单实收金额。 */
@Service
public final class OrderSalesRefundRecordService {
    private static final Logger log = LoggerFactory.getLogger(OrderSalesRefundRecordService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> IAM_STAFF_READ_PERMISSIONS = Set.of("iam:staff:read");

    private final OrderSalesRefundRecordStore store;
    private final OrderSalesOrderStore orderStore;
    private final IamStaffDisplayClient iamStaffDisplayClient;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderSalesRefundRecordService(OrderSalesRefundRecordStore store,
                                         OrderSalesOrderStore orderStore,
                                         IamStaffDisplayClient iamStaffDisplayClient) {
        this(store, orderStore, iamStaffDisplayClient, new BusinessCodeGenerator());
    }

    OrderSalesRefundRecordService(OrderSalesRefundRecordStore store,
                                  OrderSalesOrderStore orderStore,
                                  IamStaffDisplayClient iamStaffDisplayClient,
                                  BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.orderStore = Objects.requireNonNull(orderStore, "orderStore");
        this.iamStaffDisplayClient = Objects.requireNonNull(iamStaffDisplayClient, "iamStaffDisplayClient");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<SalesRefundRecordSummaryView> refunds(
            int begin, int step, String refundNo, String salesOrderNo, String customerName,
            String refundStaffCode, String refundMethodCode, String refundStatusCode,
            Instant refundTimeFrom, Instant refundTimeTo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (refundTimeFrom != null && refundTimeTo != null && refundTimeFrom.isAfter(refundTimeTo)) {
            throw badRequest("refundTimeFrom不能晚于refundTimeTo");
        }
        SalesRefundSearchCriteria criteria = new SalesRefundSearchCriteria(
                text(refundNo, 50, "refundNo"),
                text(salesOrderNo, 50, "salesOrderNo"),
                text(customerName, 200, "customerName"),
                text(refundStaffCode, 50, "refundStaffCode"),
                code(refundMethodCode, "refundMethodCode", false),
                refundStatus(refundStatusCode, false),
                refundTimeFrom,
                refundTimeTo);
        OrderPageView<SalesRefundRecordSummaryView> result =
                store.refunds(tenantId, pageBegin(begin), pageStep(step), criteria);
        result = withStaffNames(actor, result);
        log.debug("Order销售退款记录列表查询完成 tenantId={} refundNo={} salesOrderNo={} count={} total={}",
                tenantId, value(criteria.refundNo()), value(criteria.salesOrderNo()),
                result.items().size(), result.total());
        return result;
    }

    public SalesRefundRecordDetailView refund(Long id) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesRefundRecordDetailView result = store.refund(tenantId, requireId(id, "销售退款记录ID无效"))
                .orElseThrow(() -> notFound("销售退款记录不存在"));
        result = withStaffName(actor, result);
        log.debug("Order销售退款记录详情查询完成 tenantId={} refundId={} refundNo={}",
                tenantId, result.id(), result.refundNo());
        return result;
    }

    public SalesRefundRecordDetailView create(SalesRefundRecordCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesRefundWrite normalized = normalize(tenantId, command, false);
        String refundNo = codeGenerator.generateUnique(OrderBusinessCodeRules.REFUND_RECORD,
                normalized.refundTime(), candidate -> !store.existsByNo(tenantId, candidate));
        SalesRefundRecordDetailView created =
                store.create(tenantId, refundNo, normalized, OrderAuditActors.writeActor(actor));
        created = withStaffName(actor, created);
        log.info("Order销售退款记录创建完成 tenantId={} refundId={} refundNo={} salesOrderId={} amount={} actorId={}",
                tenantId, created.id(), created.refundNo(), created.orderId(),
                created.refundAmount(), actor.principalId());
        return created;
    }

    public SalesRefundRecordDetailView update(Long id, SalesRefundRecordCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesRefundWrite normalized = normalize(tenantId, command, true);
        SalesRefundRecordDetailView updated = store.update(
                tenantId, requireId(id, "销售退款记录ID无效"), normalized,
                OrderAuditActors.writeActor(actor));
        updated = withStaffName(actor, updated);
        log.info("Order销售退款记录修改完成 tenantId={} refundId={} refundNo={} revision={} actorId={}",
                tenantId, updated.id(), updated.refundNo(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        Long refundId = requireId(id, "销售退款记录ID无效");
        store.delete(tenantId, refundId, revision, OrderAuditActors.writeActor(actor));
        log.info("Order销售退款记录逻辑删除完成 tenantId={} refundId={} revision={} actorId={}",
                tenantId, refundId, revision, actor.principalId());
    }

    private SalesRefundWrite normalize(String tenantId, SalesRefundRecordCommand command, boolean update) {
        if (command == null) throw badRequest("销售退款记录参数不能为空");
        checkRevision(command.revision(), update);
        Long orderId = requireId(command.orderId(), "orderId无效");
        SalesOrderDetailView order = orderStore.salesOrder(tenantId, orderId)
                .orElseThrow(() -> notFound("销售订单不存在"));
        String refundStaffCode = text(command.refundStaffCode(), 50, "refundStaffCode");
        return new SalesRefundWrite(
                order.id(),
                order.orderNo(),
                order.customerId(),
                order.customerCodeSnapshot(),
                order.customerNameSnapshot(),
                refundStaffCode,
                text(first(command.refundStaffNameSnapshot(), staffNameSnapshot(refundStaffCode)), 100,
                        "refundStaffNameSnapshot"),
                requireInstant(command.refundTime(), "refundTime不能为空"),
                code(command.refundMethodCode(), "refundMethodCode", false),
                refundStatus(command.refundStatusCode(), true),
                money(command.refundAmount(), "refundAmount"),
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

    private OrderPageView<SalesRefundRecordSummaryView> withStaffNames(
            CallerIdentity actor, OrderPageView<SalesRefundRecordSummaryView> page) {
        Set<String> staffCodes = new LinkedHashSet<>();
        for (SalesRefundRecordSummaryView item : page.items()) {
            if (item.refundStaffCode() != null && !item.refundStaffCode().isBlank()) {
                staffCodes.add(item.refundStaffCode().strip());
            }
        }
        Map<String, String> names = staffNames(actor, staffCodes);
        if (names.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream().map(item -> withStaffName(item, names)).toList());
    }

    private SalesRefundRecordDetailView withStaffName(CallerIdentity actor, SalesRefundRecordDetailView detail) {
        if (detail.refundStaffCode() == null || detail.refundStaffCode().isBlank()) return detail;
        String staffName = staffNames(actor, Set.of(detail.refundStaffCode().strip()))
                .get(detail.refundStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return detail;
        return new SalesRefundRecordDetailView(detail.id(), detail.refundNo(), detail.orderId(),
                detail.salesOrderNoSnapshot(), detail.customerId(), detail.customerCodeSnapshot(),
                detail.customerNameSnapshot(), detail.refundStaffCode(), staffName, detail.refundTime(),
                detail.refundMethodCode(), detail.refundStatusCode(), detail.refundAmount(),
                detail.voucherKeys(), detail.remark(), detail.revision(), detail.createdBy(),
                detail.createdTime(), detail.updatedBy(), detail.updatedTime());
    }

    private SalesRefundRecordSummaryView withStaffName(
            SalesRefundRecordSummaryView item, Map<String, String> staffNames) {
        if (item.refundStaffCode() == null || item.refundStaffCode().isBlank()) return item;
        String staffName = staffNames.get(item.refundStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return item;
        return new SalesRefundRecordSummaryView(item.id(), item.refundNo(), item.orderId(),
                item.salesOrderNoSnapshot(), item.customerId(), item.customerCodeSnapshot(),
                item.customerNameSnapshot(), item.refundStaffCode(), staffName, item.refundTime(),
                item.refundMethodCode(), item.refundStatusCode(), item.refundAmount(),
                item.revision(), item.updatedTime());
    }

    private String staffNameSnapshot(String staffCode) {
        if (staffCode == null || staffCode.isBlank()) return null;
        try {
            CallerIdentity caller = AuthorizationContext.requireCurrent();
            return staffNames(caller, Set.of(staffCode.strip())).get(staffCode.strip());
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
            log.warn("IAM人员展示名查询失败，Order返回退款人姓名快照 tenantId={} staffCount={} reason={}",
                    actor.tenantId(), staffCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private static String refundStatus(String value, boolean required) {
        String normalized = code(value, "refundStatusCode", required);
        if (normalized == null) return null;
        if (!SalesRefundStatus.supports(normalized)) throw badRequest("refundStatusCode无效");
        return normalized;
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
