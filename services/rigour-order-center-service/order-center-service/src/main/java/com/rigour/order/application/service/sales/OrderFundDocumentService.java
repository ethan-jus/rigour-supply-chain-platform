package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.FundDocumentSummaryView;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderFundDocumentStore;
import com.rigour.order.application.port.out.OrderFundDocumentStore.FundDocumentSearchCriteria;
import com.rigour.order.application.port.out.OrderFundDocumentStore.FundDocumentWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.domain.code.OrderBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;
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

/** Order 资金收付款单用例；付款单不直接冲减销售订单实收金额。 */
@Service
public final class OrderFundDocumentService {
    private static final Logger log = LoggerFactory.getLogger(OrderFundDocumentService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final String DIRECTION_RECEIPT = "RECEIPT";
    private static final String DIRECTION_PAYMENT = "PAYMENT";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> IAM_STAFF_READ_PERMISSIONS = Set.of("iam:staff:read");

    private final OrderFundDocumentStore store;
    private final OrderSalesOrderStore orderStore;
    private final IamStaffDisplayClient iamStaffDisplayClient;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderFundDocumentService(OrderFundDocumentStore store,
                                    OrderSalesOrderStore orderStore,
                                    IamStaffDisplayClient iamStaffDisplayClient) {
        this(store, orderStore, iamStaffDisplayClient, new BusinessCodeGenerator());
    }

    OrderFundDocumentService(OrderFundDocumentStore store,
                             OrderSalesOrderStore orderStore,
                             IamStaffDisplayClient iamStaffDisplayClient,
                             BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.orderStore = Objects.requireNonNull(orderStore, "orderStore");
        this.iamStaffDisplayClient = Objects.requireNonNull(iamStaffDisplayClient, "iamStaffDisplayClient");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<FundDocumentSummaryView> fundDocuments(
            int begin, int step, String directionCode, String documentNo, String salesOrderNo,
            String counterpartyName, String handlerStaffCode, String settlementMethodCode,
            String businessTypeCode, String documentStatusCode,
            Instant occurredTimeFrom, Instant occurredTimeTo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        if (occurredTimeFrom != null && occurredTimeTo != null && occurredTimeFrom.isAfter(occurredTimeTo)) {
            throw badRequest("occurredTimeFrom不能晚于occurredTimeTo");
        }
        FundDocumentSearchCriteria criteria = new FundDocumentSearchCriteria(
                direction(directionCode, false),
                text(documentNo, 50, "documentNo"),
                text(salesOrderNo, 50, "salesOrderNo"),
                text(counterpartyName, 200, "counterpartyName"),
                text(handlerStaffCode, 50, "handlerStaffCode"),
                code(settlementMethodCode, "settlementMethodCode", false),
                code(businessTypeCode, "businessTypeCode", false),
                code(documentStatusCode, "documentStatusCode", false),
                occurredTimeFrom,
                occurredTimeTo);
        OrderPageView<FundDocumentSummaryView> result =
                store.fundDocuments(actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
        result = withStaffNames(actor, result);
        log.debug("Order资金单据列表查询完成 tenantId={} direction={} count={} total={}",
                actor.tenantId(), value(criteria.directionCode()), result.items().size(), result.total());
        return result;
    }

    public FundDocumentDetailView fundDocument(Long id) {
        CallerIdentity actor = actor(READ_PERMISSION);
        FundDocumentDetailView result = store.fundDocument(actor.tenantId().toString(), requireId(id, "资金单据ID无效"))
                .orElseThrow(() -> notFound("资金单据不存在"));
        return withStaffName(actor, result);
    }

    public FundDocumentDetailView create(FundDocumentCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        FundDocumentWrite normalized = normalize(tenantId, command, false);
        String documentNo = codeGenerator.generateUnique(rule(normalized.directionCode()),
                candidate -> !store.existsByNo(tenantId, candidate));
        FundDocumentDetailView created = store.create(tenantId, documentNo, normalized, actor.principalId().toString());
        created = withStaffName(actor, created);
        log.info("Order资金单据创建完成 tenantId={} documentId={} documentNo={} direction={} amount={} actorId={}",
                tenantId, created.id(), created.documentNo(), created.directionCode(), created.amount(), actor.principalId());
        return created;
    }

    public FundDocumentDetailView update(Long id, FundDocumentCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        FundDocumentWrite normalized = normalize(tenantId, command, true);
        FundDocumentDetailView updated = store.update(
                tenantId, requireId(id, "资金单据ID无效"), normalized, actor.principalId().toString());
        updated = withStaffName(actor, updated);
        log.info("Order资金单据修改完成 tenantId={} documentId={} documentNo={} revision={} actorId={}",
                tenantId, updated.id(), updated.documentNo(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        Long documentId = requireId(id, "资金单据ID无效");
        store.delete(actor.tenantId().toString(), documentId, revision, actor.principalId().toString());
        log.info("Order资金单据逻辑删除完成 tenantId={} documentId={} revision={} actorId={}",
                actor.tenantId(), documentId, revision, actor.principalId());
    }

    private FundDocumentWrite normalize(String tenantId, FundDocumentCommand command, boolean update) {
        if (command == null) throw badRequest("资金单据参数不能为空");
        checkRevision(command.revision(), update);
        String direction = direction(command.directionCode(), true);
        SalesOrderDetailView order = null;
        if (command.relatedOrderId() != null) {
            order = orderStore.salesOrder(tenantId, requireId(command.relatedOrderId(), "relatedOrderId无效"))
                    .orElseThrow(() -> notFound("销售订单不存在"));
        }
        String handlerStaffCode = text(command.handlerStaffCode(), 50, "handlerStaffCode");
        String counterpartyType = code(first(command.counterpartyTypeCode(), order == null ? null : "CUSTOMER"),
                "counterpartyTypeCode", false);
        String customerCode = first(command.customerCodeSnapshot(), order == null ? null : order.customerCodeSnapshot());
        String customerName = first(command.customerNameSnapshot(), order == null ? null : order.customerNameSnapshot());
        String counterpartyCode = first(command.counterpartyCodeSnapshot(), customerCode);
        String counterpartyName = first(command.counterpartyNameSnapshot(), customerName);
        return new FundDocumentWrite(
                direction,
                order == null ? null : order.id(),
                text(first(command.salesOrderNoSnapshot(), order == null ? null : order.orderNo()), 50, "salesOrderNoSnapshot"),
                order == null ? command.customerId() : order.customerId(),
                text(customerCode, 50, "customerCodeSnapshot"),
                text(customerName, 200, "customerNameSnapshot"),
                counterpartyType,
                text(counterpartyCode, 80, "counterpartyCodeSnapshot"),
                text(counterpartyName, 200, "counterpartyNameSnapshot"),
                handlerStaffCode,
                text(first(command.handlerStaffNameSnapshot(), staffNameSnapshot(handlerStaffCode)), 100,
                        "handlerStaffNameSnapshot"),
                requireInstant(command.occurredTime(), "occurredTime不能为空"),
                code(command.settlementMethodCode(), "settlementMethodCode", false),
                code(first(command.businessTypeCode(), "OTHER"), "businessTypeCode", true),
                code(first(command.documentStatusCode(), "CONFIRMED"), "documentStatusCode", true),
                money(command.amount(), "amount"),
                voucherKeys(command.voucherKeys()),
                text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private OrderPageView<FundDocumentSummaryView> withStaffNames(
            CallerIdentity actor, OrderPageView<FundDocumentSummaryView> page) {
        Set<String> staffCodes = new LinkedHashSet<>();
        for (FundDocumentSummaryView item : page.items()) {
            if (item.handlerStaffCode() != null && !item.handlerStaffCode().isBlank()) {
                staffCodes.add(item.handlerStaffCode().strip());
            }
        }
        Map<String, String> names = staffNames(actor, staffCodes);
        if (names.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream().map(item -> withStaffName(item, names)).toList());
    }

    private FundDocumentDetailView withStaffName(CallerIdentity actor, FundDocumentDetailView detail) {
        if (detail.handlerStaffCode() == null || detail.handlerStaffCode().isBlank()) return detail;
        String staffName = staffNames(actor, Set.of(detail.handlerStaffCode().strip()))
                .get(detail.handlerStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return detail;
        return new FundDocumentDetailView(detail.id(), detail.documentNo(), detail.directionCode(),
                detail.relatedOrderId(), detail.salesOrderNoSnapshot(), detail.customerId(),
                detail.customerCodeSnapshot(), detail.customerNameSnapshot(), detail.counterpartyTypeCode(),
                detail.counterpartyCodeSnapshot(), detail.counterpartyNameSnapshot(), detail.handlerStaffCode(),
                staffName, detail.occurredTime(), detail.settlementMethodCode(), detail.businessTypeCode(),
                detail.documentStatusCode(), detail.amount(), detail.voucherKeys(), detail.remark(),
                detail.revision(), detail.createdBy(), detail.createdTime(), detail.updatedBy(), detail.updatedTime());
    }

    private FundDocumentSummaryView withStaffName(FundDocumentSummaryView item, Map<String, String> staffNames) {
        if (item.handlerStaffCode() == null || item.handlerStaffCode().isBlank()) return item;
        String staffName = staffNames.get(item.handlerStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return item;
        return new FundDocumentSummaryView(item.id(), item.documentNo(), item.directionCode(),
                item.relatedOrderId(), item.salesOrderNoSnapshot(), item.customerId(),
                item.customerCodeSnapshot(), item.customerNameSnapshot(), item.counterpartyTypeCode(),
                item.counterpartyCodeSnapshot(), item.counterpartyNameSnapshot(), item.handlerStaffCode(),
                staffName, item.occurredTime(), item.settlementMethodCode(), item.businessTypeCode(),
                item.documentStatusCode(), item.amount(), item.revision(), item.updatedTime());
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
            log.warn("IAM人员展示名查询失败，Order返回资金单据经办人姓名快照 tenantId={} staffCount={} reason={}",
                    actor.tenantId(), staffCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private static BusinessCodeRule rule(String directionCode) {
        return DIRECTION_PAYMENT.equals(directionCode)
                ? OrderBusinessCodeRules.FUND_PAYMENT
                : OrderBusinessCodeRules.FUND_RECEIPT;
    }

    private static String direction(String value, boolean required) {
        String normalized = upper(value);
        if (normalized == null) {
            if (required) throw badRequest("directionCode不能为空");
            return null;
        }
        if (!DIRECTION_RECEIPT.equals(normalized) && !DIRECTION_PAYMENT.equals(normalized)) {
            throw badRequest("directionCode只能是RECEIPT或PAYMENT");
        }
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

    private static List<String> voucherKeys(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .map(value -> text(value, 500, "voucherKey"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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
