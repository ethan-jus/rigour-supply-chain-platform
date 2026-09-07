package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentLineCommand;
import com.rigour.order.api.v1.model.SalesShipmentSummaryView;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesShipmentStore;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentLineWrite;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesShipmentStore.SalesShipmentWrite;
import com.rigour.order.domain.code.OrderBusinessCodeRules;
import com.rigour.order.domain.enums.SalesShipmentStatus;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Order 销售发货单用例；发货单保存客户履约和物流信息，不直接扣减库存。 */
@Service
public final class OrderSalesShipmentService {
    private static final Logger log = LoggerFactory.getLogger(OrderSalesShipmentService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final OrderSalesShipmentStore store;
    private final OrderSalesOrderStore orderStore;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderSalesShipmentService(OrderSalesShipmentStore store, OrderSalesOrderStore orderStore) {
        this(store, orderStore, new BusinessCodeGenerator());
    }

    OrderSalesShipmentService(OrderSalesShipmentStore store, OrderSalesOrderStore orderStore,
                              BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.orderStore = Objects.requireNonNull(orderStore, "orderStore");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<SalesShipmentSummaryView> shipments(
            int begin, int step, String shipmentNo, String salesOrderNo, String customerName,
            String trackingNo, String shipmentStatusCode, Instant shipTimeFrom, Instant shipTimeTo) {
        String tenantId = tenant(READ_PERMISSION);
        if (shipTimeFrom != null && shipTimeTo != null && shipTimeFrom.isAfter(shipTimeTo)) {
            throw badRequest("shipTimeFrom不能晚于shipTimeTo");
        }
        SalesShipmentSearchCriteria criteria = new SalesShipmentSearchCriteria(
                text(shipmentNo, 50, "shipmentNo"),
                text(salesOrderNo, 50, "salesOrderNo"),
                text(customerName, 200, "customerName"),
                text(trackingNo, 120, "trackingNo"),
                shipmentStatus(shipmentStatusCode, false),
                shipTimeFrom,
                shipTimeTo);
        OrderPageView<SalesShipmentSummaryView> result =
                store.shipments(tenantId, pageBegin(begin), pageStep(step), criteria);
        log.debug("Order销售发货单列表查询完成 tenantId={} shipmentNo={} salesOrderNo={} customerName={} status={} count={} total={}",
                tenantId, value(criteria.shipmentNo()), value(criteria.salesOrderNo()),
                value(criteria.customerName()), value(criteria.shipmentStatusCode()),
                result.items().size(), result.total());
        return result;
    }

    public SalesShipmentDetailView shipment(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        SalesShipmentDetailView result = store.shipment(tenantId, requireId(id, "销售发货单ID无效"))
                .orElseThrow(() -> notFound("销售发货单不存在"));
        log.debug("Order销售发货单详情查询完成 tenantId={} shipmentId={} shipmentNo={}",
                tenantId, result.id(), result.shipmentNo());
        return result;
    }

    public SalesShipmentDetailView create(SalesShipmentCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesShipmentWrite normalized = normalize(tenantId, command, false, actor, null);
        String shipmentNo = codeGenerator.generateUnique(OrderBusinessCodeRules.SALES_SHIPMENT,
                normalized.shipTime(), candidate -> !store.existsByNo(tenantId, candidate));
        SalesShipmentDetailView created = store.create(
                tenantId, shipmentNo, normalized, OrderAuditActors.writeActor(actor));
        log.info("Order销售发货单创建完成 tenantId={} shipmentId={} shipmentNo={} salesOrderId={} totalQuantity={} actorId={}",
                tenantId, created.id(), created.shipmentNo(), created.salesOrderId(),
                created.totalQuantity(), actor.principalId());
        return created;
    }

    public SalesShipmentDetailView update(Long id, SalesShipmentCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        Long shipmentId = requireId(id, "销售发货单ID无效");
        SalesShipmentDetailView existing = store.shipment(tenantId, shipmentId)
                .orElseThrow(() -> notFound("销售发货单不存在"));
        requireExternalMutationAllowed(actor, existing.sourceSystemCode(), "销售发货单");
        SalesShipmentWrite normalized = normalize(tenantId, command, true, actor, existing.sourceSystemCode());
        SalesShipmentDetailView updated = store.update(
                tenantId, shipmentId, normalized,
                OrderAuditActors.writeActor(actor));
        log.info("Order销售发货单修改完成 tenantId={} shipmentId={} shipmentNo={} revision={} actorId={}",
                tenantId, updated.id(), updated.shipmentNo(), updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        Long shipmentId = requireId(id, "销售发货单ID无效");
        SalesShipmentDetailView existing = store.shipment(tenantId, shipmentId)
                .orElseThrow(() -> notFound("销售发货单不存在"));
        requireExternalMutationAllowed(actor, existing.sourceSystemCode(), "销售发货单");
        store.delete(tenantId, shipmentId, revision, OrderAuditActors.writeActor(actor));
        log.info("Order销售发货单逻辑删除完成 tenantId={} shipmentId={} revision={} actorId={}",
                tenantId, shipmentId, revision, actor.principalId());
    }

    private SalesShipmentWrite normalize(String tenantId, SalesShipmentCommand command, boolean update,
                                         CallerIdentity actor, String existingSourceSystemCode) {
        if (command == null) throw badRequest("销售发货单参数不能为空");
        checkRevision(command.revision(), update);
        String sourceSystemCode = sourceSystemCode(command.sourceSystemCode());
        String sourceDocumentNo = text(command.sourceDocumentNo(), 128, "sourceDocumentNo");
        requireSourceFieldsAllowed(actor, command.connectorId(), sourceSystemCode, sourceDocumentNo);
        if (hasText(existingSourceSystemCode) && !hasText(sourceSystemCode)) {
            throw badRequest("外部来源销售发货单更新必须保留sourceSystemCode");
        }
        if (hasText(sourceSystemCode)) {
            if (command.connectorId() == null) throw badRequest("外部来源销售发货单connectorId不能为空");
            if (!hasText(sourceDocumentNo)) throw badRequest("外部来源销售发货单sourceDocumentNo不能为空");
            if (command.shipTime() == null) throw badRequest("外部来源销售发货单shipTime必须使用来源发货时间");
        }
        Long salesOrderId = requireId(command.salesOrderId(), "salesOrderId无效");
        SalesOrderDetailView order = orderStore.salesOrder(tenantId, salesOrderId)
                .orElseThrow(() -> notFound("销售订单不存在"));
        List<SalesShipmentLineWrite> lines = lines(command.lines(), order);
        BigDecimal totalQuantity = lines.stream()
                .map(SalesShipmentLineWrite::shippedQuantity)
                .reduce(ZERO, BigDecimal::add);
        return new SalesShipmentWrite(
                command.connectorId(),
                sourceSystemCode,
                sourceDocumentNo,
                order.id(),
                order.orderNo(),
                order.customerId(),
                order.customerCodeSnapshot(),
                order.customerNameSnapshot(),
                order.contactPhoneSnapshot(),
                code(order.regionCode(), "regionCode", false),
                text(order.ownerStaffCode(), 50, "ownerStaffCode"),
                optionalId(command.warehouseId(), "warehouseId"),
                optionalId(command.stockOutOrderId(), "stockOutOrderId"),
                text(command.stockOutNo(), 50, "stockOutNo"),
                shipmentStatus(command.shipmentStatusCode(), true),
                text(command.logisticsCompany(), 120, "logisticsCompany"),
                text(command.trackingNo(), 120, "trackingNo"),
                command.shipTime(),
                totalQuantity,
                lines,
                text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private List<SalesShipmentLineWrite> lines(List<SalesShipmentLineCommand> commands, SalesOrderDetailView order) {
        Map<Long, SalesOrderLineView> orderLines = new LinkedHashMap<>();
        for (SalesOrderLineView line : order.lines()) {
            if (line.id() != null) orderLines.put(line.id(), line);
        }
        List<SalesShipmentLineCommand> source = commands == null || commands.isEmpty()
                ? order.lines().stream()
                        .map(line -> new SalesShipmentLineCommand(
                                line.id(), line.productId(), line.productVariantId(),
                                line.productCodeSnapshot(), line.skuCodeSnapshot(),
                                line.productNameSnapshot(), line.specificationSnapshot(),
                                line.unitCode(), line.quantity(), line.remark()))
                        .toList()
                : commands;
        List<SalesShipmentLineWrite> result = new ArrayList<>();
        int lineNo = 1;
        for (SalesShipmentLineCommand command : source) {
            if (command == null) continue;
            SalesOrderLineView orderLine = command.salesOrderLineId() == null
                    ? null : orderLines.get(command.salesOrderLineId());
            BigDecimal quantity = quantity(command.shippedQuantity(), "shippedQuantity");
            result.add(new SalesShipmentLineWrite(
                    command.salesOrderLineId(),
                    lineNo++,
                    first(command.productId(), orderLine == null ? null : orderLine.productId()),
                    first(command.productVariantId(), orderLine == null ? null : orderLine.productVariantId()),
                    text(first(command.productCodeSnapshot(), orderLine == null ? null : orderLine.productCodeSnapshot()), 50, "productCodeSnapshot"),
                    text(first(command.skuCodeSnapshot(), orderLine == null ? null : orderLine.skuCodeSnapshot()), 50, "skuCodeSnapshot"),
                    text(first(command.productNameSnapshot(), orderLine == null ? null : orderLine.productNameSnapshot()), 200, "productNameSnapshot"),
                    text(first(command.specificationSnapshot(), orderLine == null ? null : orderLine.specificationSnapshot()), 500, "specificationSnapshot"),
                    code(first(command.unitCode(), orderLine == null ? null : orderLine.unitCode()), "unitCode", false),
                    quantity,
                    text(command.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("销售发货单明细不能为空");
        return result;
    }

    private static String shipmentStatus(String value, boolean useDefault) {
        String normalized = code(value, "shipmentStatusCode", false);
        if (normalized == null) return useDefault ? SalesShipmentStatus.CREATED.code() : null;
        if (!SalesShipmentStatus.supports(normalized)) throw badRequest("shipmentStatusCode无效");
        return normalized;
    }

    private static BigDecimal quantity(BigDecimal value, String name) {
        if (value == null || value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value.stripTrailingZeros();
    }

    private static Long optionalId(Long value, String name) {
        if (value == null) return null;
        return requireId(value, name + "无效");
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

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String text && text.isBlank()) continue;
            if (value != null) return value;
        }
        return null;
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
