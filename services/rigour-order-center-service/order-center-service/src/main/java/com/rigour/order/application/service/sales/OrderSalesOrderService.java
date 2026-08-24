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
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutLine;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutRequest;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutResult;
import com.rigour.order.application.port.out.IamStaffDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderLineWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderWrite;
import com.rigour.order.domain.code.OrderBusinessCodeRules;
import com.rigour.order.domain.enums.SalesOrderOutboundStatus;
import com.rigour.order.domain.enums.SalesOrderPaymentStatus;
import com.rigour.order.domain.enums.SalesOrderStatus;
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

/** Order 自研销售订单用例；订单头与明细作为一个聚合保存。 */
@Service
public final class OrderSalesOrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderSalesOrderService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final String ERP_STOCK_OUT_PERMISSION = "erp:supply:write";
    private static final String SOURCE_SYSTEM_DINGHUOBAO = "DINGHUOBAO";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> IAM_STAFF_READ_PERMISSIONS = Set.of("iam:staff:read");

    private final OrderSalesOrderStore store;
    private final ErpSalesStockOutClient erpStockOutClient;
    private final IamStaffDisplayClient iamStaffDisplayClient;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderSalesOrderService(OrderSalesOrderStore store, ErpSalesStockOutClient erpStockOutClient,
                                  IamStaffDisplayClient iamStaffDisplayClient) {
        this(store, erpStockOutClient, iamStaffDisplayClient, new BusinessCodeGenerator());
    }

    OrderSalesOrderService(OrderSalesOrderStore store, BusinessCodeGenerator codeGenerator) {
        this(store, unsupportedErpClient(), unsupportedIamStaffDisplayClient(), codeGenerator);
    }

    OrderSalesOrderService(OrderSalesOrderStore store,
                           ErpSalesStockOutClient erpStockOutClient,
                           IamStaffDisplayClient iamStaffDisplayClient,
                           BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.erpStockOutClient = Objects.requireNonNull(erpStockOutClient, "erpStockOutClient");
        this.iamStaffDisplayClient = Objects.requireNonNull(iamStaffDisplayClient, "iamStaffDisplayClient");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String customerName, String contactPhone,
            String regionCode, String ownerSalesUserId, String ownerStaffCode, String orderStatusCode,
            String paymentStatusCode, String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        return salesOrders(begin, step, orderNo, null, customerName, contactPhone,
                regionCode, ownerSalesUserId, ownerStaffCode, orderStatusCode,
                paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo);
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String sourceOrderNo, String customerName, String contactPhone,
            String regionCode, String ownerSalesUserId, String ownerStaffCode, String orderStatusCode,
            String paymentStatusCode, String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (orderDateFrom != null && orderDateTo != null && orderDateFrom.isAfter(orderDateTo)) {
            throw badRequest("orderDateFrom不能晚于orderDateTo");
        }
        SalesOrderSearchCriteria criteria = new SalesOrderSearchCriteria(
                text(orderNo, 50, "orderNo"),
                text(sourceOrderNo, 80, "sourceOrderNo"),
                text(customerName, 200, "customerName"),
                text(contactPhone, 50, "contactPhone"),
                code(regionCode, "regionCode", false),
                text(ownerSalesUserId, 64, "ownerSalesUserId"),
                text(ownerStaffCode, 50, "ownerStaffCode"),
                salesOrderStatus(orderStatusCode, false),
                paymentStatus(paymentStatusCode, false),
                outboundStatus(outboundStatusCode, false),
                orderDateFrom,
                orderDateTo);
        OrderPageView<SalesOrderSummaryView> result = store.salesOrders(
                tenantId, pageBegin(begin), pageStep(step), criteria);
        result = withStaffNames(actor, result);
        log.debug("Order销售订单列表查询完成 tenantId={} orderNo={} customerName={} regionCode={} orderStatusCode={} count={} total={}",
                tenantId, value(criteria.orderNo()), value(criteria.customerName()),
                value(criteria.regionCode()), value(criteria.orderStatusCode()),
                result.items().size(), result.total());
        return result;
    }

    public SalesOrderDetailView salesOrder(Long id) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView result = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        result = withStaffName(actor, result);
        log.debug("Order销售订单详情查询完成 tenantId={} salesOrderId={} orderNo={}",
                tenantId, result.id(), result.orderNo());
        return result;
    }

    public SalesOrderDetailView create(SalesOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderWrite normalized = normalize(command, false);
        String orderNo = codeGenerator.generateUnique(OrderBusinessCodeRules.SALES_ORDER,
                orderCodeBusinessTime(normalized), candidate -> !store.existsByNo(tenantId, candidate));
        SalesOrderDetailView created = store.create(
                tenantId, orderNo, normalized, actor.principalId().toString());
        created = withStaffName(actor, created);
        log.info("Order销售订单创建完成 tenantId={} salesOrderId={} orderNo={} orderStatusCode={} payableAmount={} actorId={}",
                tenantId, created.id(), created.orderNo(), created.orderStatusCode(),
                created.payableAmount(), actor.principalId());
        return created;
    }

    private static Instant orderCodeBusinessTime(SalesOrderWrite command) {
        if (command == null || !SOURCE_SYSTEM_DINGHUOBAO.equalsIgnoreCase(command.sourceSystemCode())) {
            return null;
        }
        return command.orderDate();
    }

    public SalesOrderDetailView update(Long id, SalesOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderWrite normalized = normalize(command, true);
        SalesOrderDetailView updated = store.update(
                tenantId, requireId(id, "销售订单ID无效"), normalized, actor.principalId().toString());
        updated = withStaffName(actor, updated);
        log.info("Order销售订单修改完成 tenantId={} salesOrderId={} orderNo={} orderStatusCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.orderNo(), updated.orderStatusCode(),
                updated.revision(), actor.principalId());
        return updated;
    }

    public SalesOrderDetailView submit(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView submitted = store.submit(
                tenantId, requireId(id, "销售订单ID无效"), revision, actor.principalId().toString());
        submitted = withStaffName(actor, submitted);
        log.info("Order销售订单提交完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, submitted.id(), submitted.orderNo(), submitted.revision(), actor.principalId());
        return submitted;
    }

    public SalesOrderDetailView cancel(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView cancelled = store.cancel(
                tenantId, requireId(id, "销售订单ID无效"), revision, actor.principalId().toString());
        cancelled = withStaffName(actor, cancelled);
        log.info("Order销售订单取消完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, cancelled.id(), cancelled.orderNo(), cancelled.revision(), actor.principalId());
        return cancelled;
    }

    public SalesOrderDetailView confirmOutbound(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView confirmed = store.confirmOutbound(
                tenantId, requireId(id, "销售订单ID无效"), revision, actor.principalId().toString());
        confirmed = withStaffName(actor, confirmed);
        log.info("Order销售订单出库状态确认完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, confirmed.id(), confirmed.orderNo(), confirmed.revision(), actor.principalId());
        return confirmed;
    }

    public SalesOrderStockOutResult confirmStockOut(Long id, SalesOrderStockOutCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        AuthorizationContext.requirePermission(ERP_STOCK_OUT_PERMISSION);
        if (command == null) throw badRequest("销售订单出库参数不能为空");
        if (command.revision() == null || command.revision() < 1) throw badRequest("revision必须大于0");
        Long orderId = requireId(id, "销售订单ID无效");
        Long warehouseId = requireId(command.warehouseId(), "warehouseId无效");
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, orderId)
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (!Objects.equals(current.revision(), command.revision())) {
            throw conflict("销售订单已被其他人修改，请刷新后重试");
        }
        if (!SalesOrderStatus.SUBMITTED.code().equals(current.orderStatusCode())) {
            throw conflict("销售订单只有提交后才能确认出库");
        }
        if (!SalesOrderOutboundStatus.PENDING.code().equals(current.outboundStatusCode())) {
            throw conflict("销售订单当前出库状态不允许重复确认");
        }
        List<SalesStockOutLine> lines = current.lines().stream()
                .map(OrderSalesOrderService::stockOutLine)
                .toList();
        if (lines.isEmpty()) throw badRequest("销售订单没有可出库的商品明细");
        Instant stockOutTime = command.stockOutTime() == null ? Instant.now() : command.stockOutTime();
        SalesStockOutRequest request = new SalesStockOutRequest(
                current.id(),
                current.orderNo(),
                warehouseId,
                current.customerId(),
                current.customerNameSnapshot(),
                stockOutTime,
                lines,
                text(command.remark(), 1000, "remark"));
        SalesStockOutResult stockOut = Objects.requireNonNull(
                erpStockOutClient.confirmSalesStockOut(actor, request), "ERP销售出库结果不能为空");
        SalesOrderDetailView confirmed;
        try {
            confirmed = store.confirmOutbound(
                    tenantId, orderId, command.revision(), actor.principalId().toString());
        } catch (RuntimeException exception) {
            log.error("ERP销售出库已成功但Order订单出库状态回写失败，需人工核对 tenantId={} salesOrderId={} "
                            + "orderNo={} stockOutOrderId={} stockOutNo={} actorId={}",
                    tenantId, current.id(), current.orderNo(), stockOut.stockOutOrderId(),
                    stockOut.stockOutNo(), actor.principalId(), exception);
            throw exception;
        }
        log.info("Order销售订单一键出库完成 tenantId={} salesOrderId={} orderNo={} stockOutOrderId={} "
                        + "stockOutNo={} warehouseId={} revision={} actorId={}",
                tenantId, current.id(), current.orderNo(), stockOut.stockOutOrderId(), stockOut.stockOutNo(),
                warehouseId, confirmed.revision(), actor.principalId());
        return new SalesOrderStockOutResult(
                stockOut.stockOutOrderId(), stockOut.stockOutNo(), stockOut.stockOutTime(),
                withStaffName(actor, confirmed));
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        Long orderId = requireId(id, "销售订单ID无效");
        store.delete(tenantId, orderId, revision, actor.principalId().toString());
        log.info("Order销售订单逻辑删除完成 tenantId={} salesOrderId={} revision={} actorId={}",
                tenantId, orderId, revision, actor.principalId());
    }

    private OrderPageView<SalesOrderSummaryView> withStaffNames(
            CallerIdentity actor, OrderPageView<SalesOrderSummaryView> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<String> staffCodes = new LinkedHashSet<>();
        for (SalesOrderSummaryView item : page.items()) {
            if (item.ownerStaffCode() != null && !item.ownerStaffCode().isBlank()) {
                staffCodes.add(item.ownerStaffCode().strip());
            }
        }
        Map<String, String> staffNames = staffNames(actor, staffCodes);
        if (staffNames.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream()
                        .map(item -> withStaffName(item, staffNames))
                        .toList());
    }

    private SalesOrderDetailView withStaffName(CallerIdentity actor, SalesOrderDetailView detail) {
        if (detail == null || detail.ownerStaffCode() == null || detail.ownerStaffCode().isBlank()) {
            return detail;
        }
        Map<String, String> staffNames = staffNames(actor, Set.of(detail.ownerStaffCode().strip()));
        String staffName = staffNames.get(detail.ownerStaffCode().strip());
        return staffName == null ? detail : withStaffName(detail, staffName);
    }

    private SalesOrderSummaryView withStaffName(SalesOrderSummaryView item, Map<String, String> staffNames) {
        if (item.ownerStaffCode() == null || item.ownerStaffCode().isBlank()) return item;
        String staffName = staffNames.get(item.ownerStaffCode().strip());
        if (staffName == null || staffName.isBlank()) return item;
        return new SalesOrderSummaryView(item.id(), item.orderNo(),
                item.sourceSystemCode(), item.sourceOrderNo(), item.customerId(), item.customerNameSnapshot(),
                item.contactPhoneSnapshot(), item.regionCode(), item.ownerSalesUserId(),
                item.ownerSalesName(), item.ownerStaffCode(), staffName,
                item.orderDate(), item.orderStatusCode(), item.paymentStatusCode(),
                item.outboundStatusCode(), item.totalQuantity(), item.payableAmount(),
                item.paidAmount(), item.unpaidAmount(), item.revision(), item.updatedTime());
    }

    private SalesOrderDetailView withStaffName(SalesOrderDetailView detail, String staffName) {
        return new SalesOrderDetailView(detail.id(), detail.orderNo(),
                detail.sourceSystemCode(), detail.sourceOrderNo(), detail.customerId(), detail.customerCodeSnapshot(),
                detail.customerNameSnapshot(), detail.contactNameSnapshot(), detail.contactPhoneSnapshot(),
                detail.regionCode(), detail.ownerSalesUserId(), detail.ownerSalesName(),
                detail.ownerStaffCode(), staffName, detail.orderDate(),
                detail.orderStatusCode(), detail.orderTypeCode(), detail.paymentMethodCode(),
                detail.paymentStatusCode(), detail.outboundStatusCode(), detail.totalQuantity(),
                detail.originalAmount(), detail.discountRate(), detail.discountAmount(),
                detail.payableAmount(), detail.paidAmount(), detail.unpaidAmount(), detail.remark(),
                detail.revision(), detail.createdBy(), detail.createdTime(), detail.updatedBy(),
                detail.updatedTime(), detail.lines());
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
            log.warn("IAM人员展示名查询失败，Order返回员工姓名快照 tenantId={} staffCount={} reason={}",
                    actor.tenantId(), staffCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private SalesOrderWrite normalize(SalesOrderCommand command, boolean update) {
        if (command == null) throw badRequest("销售订单参数不能为空");
        checkRevision(command.revision(), update);
        List<SalesOrderLineWrite> lines = lines(command.lines());
        BigDecimal totalQuantity = lines.stream().map(SalesOrderLineWrite::quantity).reduce(ZERO, BigDecimal::add);
        BigDecimal originalAmount = lines.stream()
                .map(line -> line.quantity().multiply(line.unitPrice()))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal linePayableAmount = lines.stream()
                .map(SalesOrderLineWrite::lineAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal orderDiscountRate = rate(command.discountRate(), "discountRate");
        BigDecimal orderDiscountAmount = money(command.discountAmount(), "discountAmount");
        if (orderDiscountAmount.compareTo(ZERO) == 0 && orderDiscountRate != null) {
            orderDiscountAmount = linePayableAmount.multiply(orderDiscountRate);
        }
        if (orderDiscountAmount.compareTo(linePayableAmount) > 0) {
            throw badRequest("discountAmount不能大于明细应收合计");
        }
        BigDecimal payableAmount = linePayableAmount.subtract(orderDiscountAmount);
        return new SalesOrderWrite(
                requireId(command.customerId(), "customerId无效"),
                code(command.sourceSystemCode(), "sourceSystemCode", false),
                text(command.sourceOrderNo(), 80, "sourceOrderNo"),
                text(command.customerCodeSnapshot(), 50, "customerCodeSnapshot"),
                required(command.customerNameSnapshot(), "customerNameSnapshot不能为空", 200),
                text(command.contactNameSnapshot(), 100, "contactNameSnapshot"),
                text(command.contactPhoneSnapshot(), 50, "contactPhoneSnapshot"),
                code(command.regionCode(), "regionCode", false),
                text(command.ownerSalesUserId(), 64, "ownerSalesUserId"),
                text(command.ownerSalesName(), 100, "ownerSalesName"),
                text(command.ownerStaffCode(), 50, "ownerStaffCode"),
                text(first(command.ownerStaffNameSnapshot(), command.ownerSalesName()), 100,
                        "ownerStaffNameSnapshot"),
                command.orderDate() == null ? Instant.now() : command.orderDate(),
                Boolean.TRUE.equals(command.submit()) ? SalesOrderStatus.SUBMITTED.code() : SalesOrderStatus.DRAFT.code(),
                code(command.orderTypeCode(), "orderTypeCode", false),
                code(command.paymentMethodCode(), "paymentMethodCode", false),
                totalQuantity,
                originalAmount,
                orderDiscountRate,
                orderDiscountAmount,
                payableAmount,
                lines,
                text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private List<SalesOrderLineWrite> lines(List<SalesOrderLineCommand> source) {
        if (source == null || source.isEmpty()) throw badRequest("销售订单至少需要一条商品明细");
        if (source.size() > 200) throw badRequest("销售订单明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        java.util.ArrayList<SalesOrderLineWrite> result = new java.util.ArrayList<>();
        for (SalesOrderLineCommand item : source) {
            if (item == null) continue;
            Long productId = requireId(item.productId(), "productId无效");
            Long variantId = requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId + "::" + variantId;
            if (!duplicateGuard.add(duplicateKey)) throw badRequest("销售订单明细商品规格不能重复");
            BigDecimal quantity = positive(item.quantity(), "quantity");
            BigDecimal unitPrice = money(item.unitPrice(), "unitPrice");
            BigDecimal originalLineAmount = quantity.multiply(unitPrice);
            BigDecimal lineDiscountRate = rate(item.discountRate(), "lineDiscountRate");
            BigDecimal lineDiscountAmount = money(item.discountAmount(), "lineDiscountAmount");
            if (lineDiscountAmount.compareTo(ZERO) == 0 && lineDiscountRate != null) {
                lineDiscountAmount = originalLineAmount.multiply(lineDiscountRate);
            }
            if (lineDiscountAmount.compareTo(originalLineAmount) > 0) {
                throw badRequest("lineDiscountAmount不能大于明细原价金额");
            }
            result.add(new SalesOrderLineWrite(result.size() + 1, productId, variantId,
                    text(item.productCodeSnapshot(), 128, "productCodeSnapshot"),
                    text(item.skuCodeSnapshot(), 128, "skuCodeSnapshot"),
                    required(item.productNameSnapshot(), "productNameSnapshot不能为空", 200),
                    text(item.specificationSnapshot(), 500, "specificationSnapshot"),
                    code(item.unitCode(), "unitCode", true),
                    quantity, unitPrice, lineDiscountRate, lineDiscountAmount,
                    originalLineAmount.subtract(lineDiscountAmount),
                    text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) throw badRequest("销售订单至少需要一条有效商品明细");
        return List.copyOf(result);
    }

    private static SalesStockOutLine stockOutLine(SalesOrderLineView line) {
        return new SalesStockOutLine(
                requireId(line.id(), "销售订单明细ID无效"),
                requireId(line.productId(), "productId无效"),
                requireId(line.productVariantId(), "productVariantId无效"),
                text(line.productCodeSnapshot(), 50, "productCodeSnapshot"),
                text(line.skuCodeSnapshot(), 50, "skuCodeSnapshot"),
                required(line.productNameSnapshot(), "productNameSnapshot不能为空", 200),
                code(line.unitCode(), "unitCode", true),
                positive(line.quantity(), "quantity"),
                text(line.remark(), 1000, "lineRemark"));
    }

    private static String salesOrderStatus(String value, boolean required) {
        String normalized = code(value, "orderStatusCode", required);
        if (normalized == null) return null;
        if (!SalesOrderStatus.supports(normalized)) throw badRequest("orderStatusCode无效");
        return normalized;
    }

    private static String paymentStatus(String value, boolean required) {
        String normalized = code(value, "paymentStatusCode", required);
        if (normalized == null) return null;
        if (!SalesOrderPaymentStatus.supports(normalized)) throw badRequest("paymentStatusCode无效");
        return normalized;
    }

    private static String outboundStatus(String value, boolean required) {
        String normalized = code(value, "outboundStatusCode", required);
        if (normalized == null) return null;
        if (!SalesOrderOutboundStatus.supports(normalized)) throw badRequest("outboundStatusCode无效");
        return normalized;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        if (value == null) throw badRequest(name + "不能为空");
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value;
    }

    private static BigDecimal money(BigDecimal value, String name) {
        if (value == null) return ZERO;
        if (value.compareTo(ZERO) < 0) throw badRequest(name + "不能小于0");
        return value;
    }

    private static BigDecimal rate(BigDecimal value, String name) {
        if (value == null) return null;
        if (value.compareTo(ZERO) < 0 || value.compareTo(ONE) > 0) {
            throw badRequest(name + "必须在0到1之间");
        }
        return value;
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static Long requireId(Long id, String message) {
        if (id == null || id < 1) throw badRequest(message);
        return id;
    }

    private static void checkRevision(Integer revision, boolean update) {
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增销售订单时revision必须为空或0");
    }

    private static void requireRevision(int revision) {
        if (revision < 1) throw badRequest("revision必须大于0");
    }

    private static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
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

    private static String required(String value, String message, int max) {
        String normalized = text(value, max, message.replace("不能为空", ""));
        if (normalized == null) throw badRequest(message);
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

    private static ErpSalesStockOutClient unsupportedErpClient() {
        return (caller, request) -> {
            throw new IllegalStateException("ERP销售出库客户端未装配");
        };
    }

    private static IamStaffDisplayClient unsupportedIamStaffDisplayClient() {
        return (caller, staffCodes) -> List.of();
    }

    private static CallerIdentity iamServiceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_CENTER"), IAM_STAFF_READ_PERMISSIONS);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
