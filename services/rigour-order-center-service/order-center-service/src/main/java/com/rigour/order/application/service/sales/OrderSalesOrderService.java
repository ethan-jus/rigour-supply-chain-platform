package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.api.v1.model.FundDocumentAttachmentView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderSourceProjectionCommand;
import com.rigour.order.api.v1.model.SalesOrderSourceStatusCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutCommand;
import com.rigour.order.api.v1.model.SalesOrderStockOutResult;
import com.rigour.order.api.v1.model.SalesOrderSummaryView;
import com.rigour.order.api.v1.model.SalesOrderTotalsView;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.order.application.port.out.ErpSalesStockOutClient;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutLine;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutRequest;
import com.rigour.order.application.port.out.ErpSalesStockOutClient.SalesStockOutResult;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderSalesOrderStore;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderLineWrite;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSearchCriteria;
import com.rigour.order.application.port.out.OrderSalesOrderStore.SalesOrderSourceProjectionWrite;
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
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Order 自研销售订单用例；订单头与明细作为一个聚合保存。 */
@Service
public final class OrderSalesOrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderSalesOrderService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final String ERP_STOCK_OUT_PERMISSION = "erp:supply:write";
    private static final String SOURCE_SYSTEM_DINGHUOBAO = ExternalSourceCodes.DOMAIN_DINGHUOBAO;
    private static final String SOURCE_SYSTEM_FEISHU = "FEISHU";
    private static final String DATA_QUALITY_COMPLETE = "COMPLETE";
    private static final String DATA_QUALITY_NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> HR_EMPLOYEE_READ_PERMISSIONS = Set.of("hr:employee:read");
    private static final Set<String> CRM_CUSTOMER_READ_PERMISSIONS = Set.of("crm:customer:read");

    private final OrderSalesOrderStore store;
    private final ErpSalesStockOutClient erpStockOutClient;
    private final HrEmployeeDisplayClient hrEmployeeDisplayClient;
    private final CrmCustomerAreaDisplayClient crmCustomerAreaDisplayClient;
    private final FundAttachmentUrlResolver fundAttachmentUrlResolver;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public OrderSalesOrderService(OrderSalesOrderStore store, ErpSalesStockOutClient erpStockOutClient,
                                  HrEmployeeDisplayClient hrEmployeeDisplayClient,
                                  CrmCustomerAreaDisplayClient crmCustomerAreaDisplayClient,
                                  ObjectProvider<FundAttachmentUrlResolver> fundAttachmentUrlResolverProvider) {
        this(store, erpStockOutClient, hrEmployeeDisplayClient, crmCustomerAreaDisplayClient, new BusinessCodeGenerator(),
                fundAttachmentUrlResolverProvider.getIfAvailable(() -> FundAttachmentUrlResolver.NONE));
    }

    OrderSalesOrderService(OrderSalesOrderStore store, BusinessCodeGenerator codeGenerator) {
        this(store, unsupportedErpClient(), unsupportedHrEmployeeDisplayClient(), unsupportedCrmCustomerAreaDisplayClient(),
                codeGenerator,
                FundAttachmentUrlResolver.NONE);
    }

    OrderSalesOrderService(OrderSalesOrderStore store,
                           ErpSalesStockOutClient erpStockOutClient,
                           HrEmployeeDisplayClient hrEmployeeDisplayClient,
                           BusinessCodeGenerator codeGenerator) {
        this(store, erpStockOutClient, hrEmployeeDisplayClient, unsupportedCrmCustomerAreaDisplayClient(),
                codeGenerator, FundAttachmentUrlResolver.NONE);
    }

    OrderSalesOrderService(OrderSalesOrderStore store,
                           ErpSalesStockOutClient erpStockOutClient,
                           HrEmployeeDisplayClient hrEmployeeDisplayClient,
                           BusinessCodeGenerator codeGenerator,
                           FundAttachmentUrlResolver fundAttachmentUrlResolver) {
        this(store, erpStockOutClient, hrEmployeeDisplayClient, unsupportedCrmCustomerAreaDisplayClient(),
                codeGenerator, fundAttachmentUrlResolver);
    }

    OrderSalesOrderService(OrderSalesOrderStore store,
                           ErpSalesStockOutClient erpStockOutClient,
                           HrEmployeeDisplayClient hrEmployeeDisplayClient,
                           CrmCustomerAreaDisplayClient crmCustomerAreaDisplayClient,
                           BusinessCodeGenerator codeGenerator,
                           FundAttachmentUrlResolver fundAttachmentUrlResolver) {
        this.store = Objects.requireNonNull(store, "store");
        this.erpStockOutClient = Objects.requireNonNull(erpStockOutClient, "erpStockOutClient");
        this.hrEmployeeDisplayClient = Objects.requireNonNull(hrEmployeeDisplayClient, "hrEmployeeDisplayClient");
        this.crmCustomerAreaDisplayClient = Objects.requireNonNull(crmCustomerAreaDisplayClient, "crmCustomerAreaDisplayClient");
        this.fundAttachmentUrlResolver = Objects.requireNonNull(fundAttachmentUrlResolver, "fundAttachmentUrlResolver");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String customerName, String contactPhone,
            String regionCode, String ownerSalesUserId, String ownerEmployeeCode, String orderStatusCode,
            String paymentStatusCode, String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        return salesOrders(begin, step, orderNo, null, null, null, customerName, contactPhone,
                regionCode, ownerSalesUserId, ownerEmployeeCode, orderStatusCode,
                paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo,
                null, null, null, null, null, null);
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String sourceOrderNo, String customerName, String contactPhone,
            String regionCode, String ownerSalesUserId, String ownerEmployeeCode, String orderStatusCode,
            String paymentStatusCode, String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        return salesOrders(begin, step, orderNo, sourceOrderNo, null, null, customerName, contactPhone,
                regionCode, ownerSalesUserId, ownerEmployeeCode, orderStatusCode,
                paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo,
                null, null, null, null, null, null);
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String sourceOrderNo, String sourceStatusCode,
            String customerName, String contactPhone, String regionCode, String ownerSalesUserId,
            String ownerEmployeeCode, String orderStatusCode, String paymentStatusCode,
            String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo) {
        return salesOrders(begin, step, orderNo, sourceOrderNo, sourceStatusCode,
                null, customerName, contactPhone, regionCode, ownerSalesUserId, ownerEmployeeCode, orderStatusCode,
                paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo,
                null, null, null, null, null, null);
    }

    public OrderPageView<SalesOrderSummaryView> salesOrders(
            int begin, int step, String orderNo, String sourceOrderNo, String sourceStatusCode,
            String dataQualityStatusCode, String customerName, String contactPhone, String regionCode,
            String ownerSalesUserId, String ownerEmployeeCode, String orderStatusCode, String paymentStatusCode,
            String outboundStatusCode, Instant orderDateFrom, Instant orderDateTo,
            Long productId, Long productVariantId, String productCodeSnapshot, String skuCodeSnapshot,
            String productNameSnapshot, String specificationSnapshot) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderSearchCriteria criteria = salesOrderCriteria(orderNo, sourceOrderNo, sourceStatusCode,
                dataQualityStatusCode, customerName, contactPhone, regionCode, ownerSalesUserId, ownerEmployeeCode,
                orderStatusCode, paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo, productId,
                productVariantId, productCodeSnapshot, skuCodeSnapshot, productNameSnapshot, specificationSnapshot);
        OrderPageView<SalesOrderSummaryView> result = store.salesOrders(
                tenantId, pageBegin(begin), pageStep(step), criteria);
        result = withRegionNames(actor, result);
        result = withEmployeeNames(actor, result);
        log.debug("Order销售订单列表查询完成 tenantId={} orderNo={} customerName={} regionCode={} orderStatusCode={} count={} total={}",
                tenantId, value(criteria.orderNo()), value(criteria.customerName()),
                value(criteria.regionCode()), value(criteria.orderStatusCode()),
                result.items().size(), result.total());
        return result;
    }

    public SalesOrderTotalsView salesOrderTotals(
            String orderNo, String sourceOrderNo, String sourceStatusCode, String dataQualityStatusCode,
            String customerName, String contactPhone, String regionCode, String ownerSalesUserId,
            String ownerEmployeeCode, String orderStatusCode, String paymentStatusCode, String outboundStatusCode,
            Instant orderDateFrom, Instant orderDateTo, Long productId, Long productVariantId,
            String productCodeSnapshot, String skuCodeSnapshot, String productNameSnapshot,
            String specificationSnapshot) {
        CallerIdentity actor = actor(READ_PERMISSION);
        SalesOrderSearchCriteria criteria = salesOrderCriteria(orderNo, sourceOrderNo, sourceStatusCode,
                dataQualityStatusCode, customerName, contactPhone, regionCode, ownerSalesUserId, ownerEmployeeCode,
                orderStatusCode, paymentStatusCode, outboundStatusCode, orderDateFrom, orderDateTo, productId,
                productVariantId, productCodeSnapshot, skuCodeSnapshot, productNameSnapshot, specificationSnapshot);
        SalesOrderTotalsView result = store.salesOrderTotals(actor.tenantId().toString(), criteria);
        log.debug("Order销售订单汇总查询完成 tenantId={} orderNo={} customerName={} regionCode={} total={}",
                actor.tenantId(), value(criteria.orderNo()), value(criteria.customerName()),
                value(criteria.regionCode()), result.total());
        return result;
    }

    private static SalesOrderSearchCriteria salesOrderCriteria(
            String orderNo, String sourceOrderNo, String sourceStatusCode, String dataQualityStatusCode,
            String customerName, String contactPhone, String regionCode, String ownerSalesUserId,
            String ownerEmployeeCode, String orderStatusCode, String paymentStatusCode, String outboundStatusCode,
            Instant orderDateFrom, Instant orderDateTo, Long productId, Long productVariantId,
            String productCodeSnapshot, String skuCodeSnapshot, String productNameSnapshot,
            String specificationSnapshot) {
        if (orderDateFrom != null && orderDateTo != null && orderDateFrom.isAfter(orderDateTo)) {
            throw badRequest("orderDateFrom不能晚于orderDateTo");
        }
        return new SalesOrderSearchCriteria(
                text(orderNo, 50, "orderNo"),
                text(sourceOrderNo, 80, "sourceOrderNo"),
                text(sourceStatusCode, 64, "sourceStatusCode"),
                dataQualityStatus(dataQualityStatusCode, false),
                text(customerName, 200, "customerName"),
                text(contactPhone, 50, "contactPhone"),
                code(regionCode, "regionCode", false),
                text(ownerSalesUserId, 64, "ownerSalesUserId"),
                text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                salesOrderStatus(orderStatusCode, false),
                paymentStatus(paymentStatusCode, false),
                outboundStatus(outboundStatusCode, false),
                orderDateFrom,
                orderDateTo,
                optionalId(productId, "productId无效"),
                optionalId(productVariantId, "productVariantId无效"),
                text(productCodeSnapshot, 128, "productCodeSnapshot"),
                text(skuCodeSnapshot, 128, "skuCodeSnapshot"),
                text(productNameSnapshot, 200, "productNameSnapshot"),
                text(specificationSnapshot, 500, "specificationSnapshot"));
    }

    public SalesOrderDetailView salesOrder(Long id) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView result = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        result = withDetailEnrichment(actor, result);
        log.debug("Order销售订单详情查询完成 tenantId={} salesOrderId={} orderNo={}",
                tenantId, result.id(), result.orderNo());
        return result;
    }

    public SalesOrderDetailView create(SalesOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        String sourceSystemCode = command == null ? null : code(command.sourceSystemCode(), "sourceSystemCode", false);
        SalesOrderWrite normalized = normalize(command, false, isFeishuSource(sourceSystemCode));
        requireExternalProjectionAllowed(actor, normalized.sourceSystemCode());
        String orderNo = codeGenerator.generateUnique(OrderBusinessCodeRules.SALES_ORDER,
                orderCodeBusinessTime(normalized), candidate -> !store.existsByNo(tenantId, candidate));
        SalesOrderDetailView created = store.create(
                tenantId, orderNo, normalized, OrderAuditActors.writeActor(actor));
        created = withDetailEnrichment(actor, created);
        log.info("Order销售订单创建完成 tenantId={} salesOrderId={} orderNo={} orderStatusCode={} payableAmount={} actorId={}",
                tenantId, created.id(), created.orderNo(), created.orderStatusCode(),
                created.payableAmount(), actor.principalId());
        return created;
    }

    private static Instant orderCodeBusinessTime(SalesOrderWrite command) {
        if (command == null || !sourceSystemUsesBusinessTime(command.sourceSystemCode())) {
            return null;
        }
        return command.orderDate();
    }

    private static boolean sourceSystemUsesBusinessTime(String sourceSystemCode) {
        return SOURCE_SYSTEM_DINGHUOBAO.equalsIgnoreCase(sourceSystemCode)
                || SOURCE_SYSTEM_FEISHU.equalsIgnoreCase(sourceSystemCode);
    }

    public SalesOrderDetailView update(Long id, SalesOrderCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        boolean externalProjectionUpdate = serviceCaller(actor) && externalSource(current.sourceSystemCode());
        if (!externalProjectionUpdate) {
            requireManualExternalMutationAllowed(actor, current);
        }
        SalesOrderWrite normalized = normalize(command, true, editableFeishuOrder(current));
        if (externalProjectionUpdate) {
            requireSameExternalIdentity(current, normalized);
            SalesOrderDetailView updated = store.updateExternalProjection(
                    tenantId, current.id(), normalized, OrderAuditActors.writeActor(actor));
            updated = withDetailEnrichment(actor, updated);
            log.info("Order销售订单来源投影内容更新完成 tenantId={} salesOrderId={} orderNo={} "
                            + "sourceSystemCode={} sourceOrderNo={} orderStatusCode={} revision={} actorId={}",
                    tenantId, updated.id(), updated.orderNo(), value(updated.sourceSystemCode()),
                    value(updated.sourceOrderNo()), updated.orderStatusCode(), updated.revision(), actor.principalId());
            return updated;
        }
        if (editableFeishuOrder(current) && !serviceCaller(actor)) {
            normalized = preserveExternalIdentity(normalized, current);
        } else {
            requireExternalProjectionAllowed(actor, normalized.sourceSystemCode());
        }
        SalesOrderDetailView updated = store.update(
                tenantId, current.id(), normalized, OrderAuditActors.writeActor(actor));
        updated = withDetailEnrichment(actor, updated);
        log.info("Order销售订单修改完成 tenantId={} salesOrderId={} orderNo={} orderStatusCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.orderNo(), updated.orderStatusCode(),
                updated.revision(), actor.principalId());
        return updated;
    }

    public SalesOrderDetailView updateSourceStatus(Long id, SalesOrderSourceStatusCommand command) {
        CallerIdentity actor = serviceActor();
        if (command == null) throw badRequest("销售订单来源状态参数不能为空");
        checkRevision(command.revision(), true);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (!externalSource(current.sourceSystemCode())) {
            throw conflict("只有外部来源销售订单允许更新来源状态");
        }
        String sourceStatusCode = text(command.sourceStatusCode(), 64, "sourceStatusCode");
        if (Objects.equals(current.sourceStatusCode(), sourceStatusCode)) {
            return withDetailEnrichment(actor, current);
        }
        SalesOrderDetailView updated = store.updateSourceStatus(
                tenantId, current.id(), sourceStatusCode, command.revision(),
                OrderAuditActors.writeActor(actor));
        updated = withDetailEnrichment(actor, updated);
        log.info("Order销售订单来源状态更新完成 tenantId={} salesOrderId={} orderNo={} sourceStatusCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.orderNo(), value(updated.sourceStatusCode()),
                updated.revision(), actor.principalId());
        return updated;
    }

    public SalesOrderDetailView updateSourceProjection(Long id, SalesOrderSourceProjectionCommand command) {
        CallerIdentity actor = serviceActor();
        if (command == null) throw badRequest("销售订单来源投影参数不能为空");
        checkRevision(command.revision(), true);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (!externalSource(current.sourceSystemCode())) {
            throw conflict("只有外部来源销售订单允许更新来源投影资料");
        }
        SalesOrderSourceProjectionWrite normalized = normalizeSourceProjection(command, current);
        if (sourceProjectionSame(current, normalized)) {
            return withDetailEnrichment(actor, current);
        }
        SalesOrderDetailView updated = store.updateSourceProjection(
                tenantId, current.id(), normalized, OrderAuditActors.writeActor(actor));
        updated = withDetailEnrichment(actor, updated);
        log.info("Order销售订单来源投影资料更新完成 tenantId={} salesOrderId={} orderNo={} "
                        + "sourceStatusCode={} sourceCreatorName={} ownerEmployeeCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.orderNo(), value(updated.sourceStatusCode()),
                value(updated.sourceCreatorName()), value(updated.ownerEmployeeCode()),
                updated.revision(), actor.principalId());
        return updated;
    }

    public SalesOrderDetailView submit(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        requireManualExternalMutationAllowed(actor, current);
        requireCompleteForBusinessFlow(current);
        SalesOrderDetailView submitted = store.submit(
                tenantId, current.id(), revision, OrderAuditActors.writeActor(actor));
        submitted = withDetailEnrichment(actor, submitted);
        log.info("Order销售订单提交完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, submitted.id(), submitted.orderNo(), submitted.revision(), actor.principalId());
        return submitted;
    }

    public SalesOrderDetailView cancel(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        requireManualExternalMutationAllowed(actor, current);
        SalesOrderDetailView cancelled = store.cancel(
                tenantId, current.id(), revision, OrderAuditActors.writeActor(actor));
        cancelled = withDetailEnrichment(actor, cancelled);
        log.info("Order销售订单取消完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, cancelled.id(), cancelled.orderNo(), cancelled.revision(), actor.principalId());
        return cancelled;
    }

    public SalesOrderDetailView cancelBySource(Long id, int revision) {
        CallerIdentity actor = serviceActor();
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        if (!externalSource(current.sourceSystemCode())) {
            throw conflict("只有外部来源销售订单允许按来源取消");
        }
        SalesOrderDetailView cancelled = store.cancelBySource(
                tenantId, current.id(), revision, OrderAuditActors.writeActor(actor));
        cancelled = withDetailEnrichment(actor, cancelled);
        log.info("Order销售订单来源取消完成 tenantId={} salesOrderId={} orderNo={} revision={} actorId={}",
                tenantId, cancelled.id(), cancelled.orderNo(), cancelled.revision(), actor.principalId());
        return cancelled;
    }

    public SalesOrderDetailView confirmOutbound(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        SalesOrderDetailView current = store.salesOrder(tenantId, requireId(id, "销售订单ID无效"))
                .orElseThrow(() -> notFound("销售订单不存在"));
        requireManualExternalMutationAllowed(actor, current);
        requireCompleteForBusinessFlow(current);
        SalesOrderDetailView confirmed = store.confirmOutbound(
                tenantId, current.id(), revision, Instant.now(), OrderAuditActors.writeActor(actor));
        confirmed = withDetailEnrichment(actor, confirmed);
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
        requireManualExternalMutationAllowed(actor, current);
        requireCompleteForBusinessFlow(current);
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
                    tenantId, orderId, command.revision(), stockOutTime, OrderAuditActors.writeActor(actor));
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
                withDetailEnrichment(actor, confirmed));
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        requireRevision(revision);
        String tenantId = actor.tenantId().toString();
        Long orderId = requireId(id, "销售订单ID无效");
        SalesOrderDetailView current = store.salesOrder(tenantId, orderId)
                .orElseThrow(() -> notFound("销售订单不存在"));
        requireManualExternalMutationAllowed(actor, current);
        store.delete(tenantId, orderId, revision, OrderAuditActors.writeActor(actor));
        log.info("Order销售订单逻辑删除完成 tenantId={} salesOrderId={} revision={} actorId={}",
                tenantId, orderId, revision, actor.principalId());
    }

    private OrderPageView<SalesOrderSummaryView> withEmployeeNames(
            CallerIdentity actor, OrderPageView<SalesOrderSummaryView> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<String> employeeCodes = new LinkedHashSet<>();
        for (SalesOrderSummaryView item : page.items()) {
            if (item.ownerEmployeeCode() != null && !item.ownerEmployeeCode().isBlank()) {
                employeeCodes.add(item.ownerEmployeeCode().strip());
            }
        }
        Map<String, String> employeeNames = employeeNames(actor, employeeCodes);
        if (employeeNames.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream()
                        .map(item -> withEmployeeName(item, employeeNames))
                        .toList());
    }

    private SalesOrderDetailView withDetailEnrichment(CallerIdentity actor, SalesOrderDetailView detail) {
        return withResolvedPaymentAttachments(actor, withRegionName(actor, withEmployeeName(actor, detail)));
    }

    private SalesOrderDetailView withEmployeeName(CallerIdentity actor, SalesOrderDetailView detail) {
        if (detail == null || detail.ownerEmployeeCode() == null || detail.ownerEmployeeCode().isBlank()) {
            return detail;
        }
        Map<String, String> employeeNames = employeeNames(actor, Set.of(detail.ownerEmployeeCode().strip()));
        String employeeName = employeeNames.get(detail.ownerEmployeeCode().strip());
        return employeeName == null ? detail : withEmployeeName(detail, employeeName);
    }

    private SalesOrderSummaryView withEmployeeName(SalesOrderSummaryView item, Map<String, String> employeeNames) {
        if (item.ownerEmployeeCode() == null || item.ownerEmployeeCode().isBlank()) return item;
        String employeeName = employeeNames.get(item.ownerEmployeeCode().strip());
        if (employeeName == null || employeeName.isBlank()) return item;
        return new SalesOrderSummaryView(item.id(), item.orderNo(),
                item.sourceSystemCode(), item.sourceOrderNo(), item.sourceStatusCode(),
                item.sourceCreatorId(), item.sourceCreatorStaffCode(), item.sourceCreatorName(),
                item.dataQualityStatusCode(), item.dataQualityMessage(),
                item.customerId(), item.customerNameSnapshot(),
                item.contactPhoneSnapshot(), item.regionCode(), item.regionName(), item.ownerSalesUserId(),
                item.ownerSalesName(), item.ownerEmployeeCode(), employeeName,
                item.orderDate(), item.paymentTime(), item.shipmentTime(),
                item.shipmentStatusCode(),
                item.orderStatusCode(), item.paymentStatusCode(),
                item.outboundStatusCode(), item.totalQuantity(),
                item.originalAmount(), item.discountAmount(), item.payableAmount(),
                item.paidAmount(), item.unpaidAmount(), item.revision(), item.updatedTime());
    }

    private SalesOrderDetailView withEmployeeName(SalesOrderDetailView detail, String employeeName) {
        return copySalesOrderDetail(detail, employeeName, detail.paymentAttachments());
    }

    private OrderPageView<SalesOrderSummaryView> withRegionNames(
            CallerIdentity actor, OrderPageView<SalesOrderSummaryView> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<String> regionCodes = new LinkedHashSet<>();
        for (SalesOrderSummaryView item : page.items()) {
            if (item.regionCode() != null && !item.regionCode().isBlank()) {
                regionCodes.add(item.regionCode().strip());
            }
        }
        Map<String, String> areaNames = customerAreaNames(actor, regionCodes);
        if (areaNames.isEmpty()) return page;
        return new OrderPageView<>(page.total(), page.begin(), page.step(),
                page.items().stream()
                        .map(item -> withRegionName(item, areaNames))
                        .toList());
    }

    private SalesOrderDetailView withRegionName(CallerIdentity actor, SalesOrderDetailView detail) {
        if (detail == null || detail.regionCode() == null || detail.regionCode().isBlank()) {
            return detail;
        }
        String areaName = customerAreaNames(actor, Set.of(detail.regionCode().strip()))
                .get(detail.regionCode().strip());
        return areaName == null || areaName.isBlank() ? detail : copySalesOrderDetail(
                detail, areaName, detail.ownerEmployeeNameSnapshot(), detail.paymentAttachments());
    }

    private SalesOrderSummaryView withRegionName(SalesOrderSummaryView item, Map<String, String> areaNames) {
        if (item.regionCode() == null || item.regionCode().isBlank()) return item;
        String areaName = areaNames.get(item.regionCode().strip());
        if (areaName == null || areaName.isBlank()) return item;
        return new SalesOrderSummaryView(item.id(), item.orderNo(),
                item.sourceSystemCode(), item.sourceOrderNo(), item.sourceStatusCode(),
                item.sourceCreatorId(), item.sourceCreatorStaffCode(), item.sourceCreatorName(),
                item.dataQualityStatusCode(), item.dataQualityMessage(),
                item.customerId(), item.customerNameSnapshot(),
                item.contactPhoneSnapshot(), item.regionCode(), areaName, item.ownerSalesUserId(),
                item.ownerSalesName(), item.ownerEmployeeCode(), item.ownerEmployeeNameSnapshot(),
                item.orderDate(), item.paymentTime(), item.shipmentTime(),
                item.shipmentStatusCode(),
                item.orderStatusCode(), item.paymentStatusCode(),
                item.outboundStatusCode(), item.totalQuantity(),
                item.originalAmount(), item.discountAmount(), item.payableAmount(),
                item.paidAmount(), item.unpaidAmount(), item.revision(), item.updatedTime());
    }

    private SalesOrderDetailView withResolvedPaymentAttachments(CallerIdentity actor, SalesOrderDetailView detail) {
        if (detail == null) return null;
        List<FundDocumentAttachmentView> attachments =
                paymentAttachmentViews(actor.tenantId().toString(), detail.paymentVoucherKeys());
        if (attachments.equals(detail.paymentAttachments())) return detail;
        return copySalesOrderDetail(detail, detail.ownerEmployeeNameSnapshot(), attachments);
    }

    private SalesOrderDetailView copySalesOrderDetail(
            SalesOrderDetailView detail, String ownerEmployeeName,
            List<FundDocumentAttachmentView> paymentAttachments) {
        return copySalesOrderDetail(detail, detail.regionName(), ownerEmployeeName, paymentAttachments);
    }

    private SalesOrderDetailView copySalesOrderDetail(
            SalesOrderDetailView detail, String regionName, String ownerEmployeeName,
            List<FundDocumentAttachmentView> paymentAttachments) {
        return new SalesOrderDetailView(detail.id(), detail.orderNo(),
                detail.sourceSystemCode(), detail.sourceOrderNo(), detail.sourceStatusCode(),
                detail.sourceCreatorId(), detail.sourceCreatorStaffCode(), detail.sourceCreatorName(),
                detail.dataQualityStatusCode(), detail.dataQualityMessage(),
                detail.customerId(), detail.customerCodeSnapshot(),
                detail.customerNameSnapshot(), detail.contactNameSnapshot(), detail.contactPhoneSnapshot(),
                detail.regionCode(), regionName, detail.ownerSalesUserId(), detail.ownerSalesName(),
                detail.ownerEmployeeCode(), ownerEmployeeName, detail.orderDate(),
                detail.paymentTime(), detail.shipmentTime(),
                detail.shipmentStatusCode(),
                detail.orderStatusCode(), detail.orderTypeCode(), detail.paymentMethodCode(),
                detail.paymentStatusCode(), detail.outboundStatusCode(), detail.totalQuantity(),
                detail.originalAmount(), detail.discountRate(), detail.discountAmount(),
                detail.payableAmount(), detail.paidAmount(), detail.unpaidAmount(), detail.remark(),
                detail.revision(), detail.createdBy(), detail.createdTime(), detail.updatedBy(),
                detail.updatedTime(), detail.paymentVoucherKeys(), paymentAttachments, detail.lines());
    }

    private List<FundDocumentAttachmentView> paymentAttachmentViews(String tenantId, List<String> voucherKeys) {
        List<FundDocumentAttachmentView> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (voucherKeys != null) keys.addAll(voucherKeys);
        for (String key : keys) {
            String objectKey = plainAttachmentKey(key);
            if (objectKey == null) continue;
            result.add(new FundDocumentAttachmentView(
                    objectKey, attachmentFileName(objectKey), temporaryFundAttachmentUrl(tenantId, objectKey)));
        }
        return List.copyOf(result);
    }

    private String temporaryFundAttachmentUrl(String tenantId, String objectKey) {
        if (!objectKey.startsWith(tenantId + "/")) return null;
        try {
            return fundAttachmentUrlResolver.temporaryUrl(tenantId, objectKey);
        } catch (RuntimeException exception) {
            log.debug("销售订单付款凭证临时URL生成失败 tenantId={} objectKey={} errorType={}",
                    tenantId, safeLogObjectKey(objectKey), exception.getClass().getSimpleName());
            return null;
        }
    }

    private static String plainAttachmentKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")
                || normalized.startsWith("file:")) {
            return null;
        }
        return normalized;
    }

    private static String attachmentFileName(String objectKey) {
        String value = objectKey == null ? "" : objectKey.strip();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    private static String safeLogObjectKey(String objectKey) {
        if (objectKey == null) return "-";
        return objectKey.length() <= 96 ? objectKey : objectKey.substring(0, 96) + "...";
    }

    private Map<String, String> employeeNames(CallerIdentity actor, Set<String> employeeCodes) {
        if (employeeCodes == null || employeeCodes.isEmpty()) return Map.of();
        CallerIdentity serviceCaller = hrServiceCaller(actor.tenantId());
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (HrEmployeeDisplayClient.EmployeeDisplay item : hrEmployeeDisplayClient.resolve(serviceCaller, employeeCodes)) {
                if (item == null || item.employeeCode() == null || item.employeeCode().isBlank()
                        || item.employeeName() == null || item.employeeName().isBlank()) {
                    continue;
                }
                result.put(item.employeeCode().strip(), item.employeeName().strip());
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("HR员工展示名查询失败，Order返回员工姓名快照 tenantId={} employeeCount={} reason={}",
                    actor.tenantId(), employeeCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> customerAreaNames(CallerIdentity actor, Set<String> areaCodes) {
        if (areaCodes == null || areaCodes.isEmpty()) return Map.of();
        CallerIdentity serviceCaller = crmServiceCaller(actor.tenantId());
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (CrmCustomerAreaDisplayClient.CustomerAreaDisplay item
                    : crmCustomerAreaDisplayClient.resolve(serviceCaller, areaCodes)) {
                if (item == null || item.areaCode() == null || item.areaCode().isBlank()
                        || item.areaName() == null || item.areaName().isBlank()) {
                    continue;
                }
                result.put(item.areaCode().strip(), item.areaName().strip());
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("CRM客户归属地区展示名查询失败，Order返回地区编码 tenantId={} areaCount={} reason={}",
                    actor.tenantId(), areaCodes.size(), exception.getMessage());
            return Map.of();
        }
    }

    private SalesOrderWrite normalize(SalesOrderCommand command, boolean update, boolean allowIncompleteFeishuOrder) {
        if (command == null) throw badRequest("销售订单参数不能为空");
        checkRevision(command.revision(), update);
        String sourceSystemCode = code(command.sourceSystemCode(), "sourceSystemCode", false);
        List<SalesOrderLineWrite> lines = lines(command.lines(), allowIncompleteFeishuOrder);
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
        BigDecimal sourceUnpaidAmount = sourceUnpaidAmount(sourceSystemCode, command.sourceUnpaidAmount());
        Instant orderDate = orderBusinessDate(sourceSystemCode, command.orderDate());
        Long customerId = allowIncompleteFeishuOrder
                ? optionalId(command.customerId(), "customerId无效")
                : requireId(command.customerId(), "customerId无效");
        String customerName = allowIncompleteFeishuOrder
                ? text(command.customerNameSnapshot(), 200, "customerNameSnapshot")
                : required(command.customerNameSnapshot(), "customerNameSnapshot不能为空", 200);
        DataQuality dataQuality = dataQuality(customerId, customerName, lines);
        if (Boolean.TRUE.equals(command.submit()) && !dataQuality.complete()) {
            throw conflict("销售订单数据待完善，补齐客户、商品、规格和单位后才能提交");
        }
        return new SalesOrderWrite(
                customerId,
                sourceSystemCode,
                text(command.sourceOrderNo(), 80, "sourceOrderNo"),
                text(command.sourceStatusCode(), 64, "sourceStatusCode"),
                text(command.sourceCreatorId(), 80, "sourceCreatorId"),
                text(command.sourceCreatorStaffCode(), 50, "sourceCreatorStaffCode"),
                text(command.sourceCreatorName(), 100, "sourceCreatorName"),
                dataQuality.statusCode(),
                dataQuality.message(),
                text(command.customerCodeSnapshot(), 50, "customerCodeSnapshot"),
                customerName,
                text(command.contactNameSnapshot(), 100, "contactNameSnapshot"),
                text(command.contactPhoneSnapshot(), 50, "contactPhoneSnapshot"),
                code(command.regionCode(), "regionCode", false),
                text(command.ownerSalesUserId(), 64, "ownerSalesUserId"),
                text(command.ownerSalesName(), 100, "ownerSalesName"),
                text(command.ownerEmployeeCode(), 50, "ownerEmployeeCode"),
                text(first(command.ownerEmployeeNameSnapshot(), command.ownerSalesName()), 100,
                        "ownerEmployeeNameSnapshot"),
                orderDate,
                initialOrderStatus(sourceSystemCode, command.submit(), dataQuality),
                code(command.orderTypeCode(), "orderTypeCode", false),
                code(command.paymentMethodCode(), "paymentMethodCode", false),
                voucherKeys(command.paymentVoucherKeys()),
                sourceUnpaidAmount,
                totalQuantity,
                originalAmount,
                orderDiscountRate,
                orderDiscountAmount,
                payableAmount,
                lines,
                text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
    }

    private static Instant orderBusinessDate(String sourceSystemCode, Instant orderDate) {
        if (externalSource(sourceSystemCode) && orderDate == null) {
            throw badRequest("外部来源销售订单orderDate必须使用来源下单/创建时间");
        }
        return orderDate == null ? Instant.now() : orderDate;
    }

    private SalesOrderSourceProjectionWrite normalizeSourceProjection(
            SalesOrderSourceProjectionCommand command, SalesOrderDetailView current) {
        String sourceStatusCode = first(text(command.sourceStatusCode(), 64, "sourceStatusCode"),
                current.sourceStatusCode());
        boolean hasCreator = hasAny(command.sourceCreatorId(), command.sourceCreatorStaffCode(),
                command.sourceCreatorName());
        boolean hasOwner = hasAny(command.ownerSalesUserId(), command.ownerSalesName(),
                command.ownerEmployeeCode(), command.ownerEmployeeNameSnapshot());
        return new SalesOrderSourceProjectionWrite(
                sourceStatusCode,
                hasCreator ? text(command.sourceCreatorId(), 80, "sourceCreatorId")
                        : current.sourceCreatorId(),
                hasCreator ? text(command.sourceCreatorStaffCode(), 50, "sourceCreatorStaffCode")
                        : current.sourceCreatorStaffCode(),
                hasCreator ? text(command.sourceCreatorName(), 100, "sourceCreatorName")
                        : current.sourceCreatorName(),
                hasOwner ? text(command.ownerSalesUserId(), 64, "ownerSalesUserId")
                        : current.ownerSalesUserId(),
                hasOwner ? text(command.ownerSalesName(), 100, "ownerSalesName")
                        : current.ownerSalesName(),
                hasOwner ? text(command.ownerEmployeeCode(), 50, "ownerEmployeeCode")
                        : current.ownerEmployeeCode(),
                hasOwner ? text(first(command.ownerEmployeeNameSnapshot(), command.ownerSalesName()), 100,
                        "ownerEmployeeNameSnapshot") : current.ownerEmployeeNameSnapshot(),
                command.revision());
    }

    private static boolean sourceProjectionSame(
            SalesOrderDetailView current, SalesOrderSourceProjectionWrite expected) {
        return Objects.equals(current.sourceStatusCode(), expected.sourceStatusCode())
                && Objects.equals(current.sourceCreatorId(), expected.sourceCreatorId())
                && Objects.equals(current.sourceCreatorStaffCode(), expected.sourceCreatorStaffCode())
                && Objects.equals(current.sourceCreatorName(), expected.sourceCreatorName())
                && Objects.equals(current.ownerSalesUserId(), expected.ownerSalesUserId())
                && Objects.equals(current.ownerSalesName(), expected.ownerSalesName())
                && Objects.equals(current.ownerEmployeeCode(), expected.ownerEmployeeCode())
                && Objects.equals(current.ownerEmployeeNameSnapshot(), expected.ownerEmployeeNameSnapshot());
    }

    private List<SalesOrderLineWrite> lines(List<SalesOrderLineCommand> source, boolean allowIncompleteFeishuOrder) {
        if (source == null || source.isEmpty()) {
            if (allowIncompleteFeishuOrder) return List.of();
            throw badRequest("销售订单至少需要一条商品明细");
        }
        if (source.size() > 200) throw badRequest("销售订单明细不能超过200条");
        Set<String> duplicateGuard = new LinkedHashSet<>();
        ArrayList<SalesOrderLineWrite> result = new ArrayList<>();
        for (SalesOrderLineCommand item : source) {
            if (item == null) continue;
            Long productId = allowIncompleteFeishuOrder
                    ? optionalId(item.productId(), "productId无效")
                    : requireId(item.productId(), "productId无效");
            Long variantId = allowIncompleteFeishuOrder
                    ? optionalId(item.productVariantId(), "productVariantId无效")
                    : requireId(item.productVariantId(), "productVariantId无效");
            String duplicateKey = productId != null && variantId != null
                    ? productId + "::" + variantId
                    : "INCOMPLETE::" + result.size();
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
                    allowIncompleteFeishuOrder
                            ? text(item.productNameSnapshot(), 200, "productNameSnapshot")
                            : required(item.productNameSnapshot(), "productNameSnapshot不能为空", 200),
                    text(item.specificationSnapshot(), 500, "specificationSnapshot"),
                    code(item.unitCode(), "unitCode", !allowIncompleteFeishuOrder),
                    quantity, unitPrice, lineDiscountRate, lineDiscountAmount,
                    originalLineAmount.subtract(lineDiscountAmount),
                    text(item.remark(), 1000, "lineRemark")));
        }
        if (result.isEmpty()) {
            if (allowIncompleteFeishuOrder) return List.of();
            throw badRequest("销售订单至少需要一条有效商品明细");
        }
        return List.copyOf(result);
    }

    private static List<String> voucherKeys(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String key = text(value, 500, "paymentVoucherKey");
            if (key != null) result.add(key);
        }
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

    private static void requireExternalProjectionAllowed(CallerIdentity actor, String sourceSystemCode) {
        if (externalSource(sourceSystemCode) && !serviceCaller(actor)) {
            throw conflict("外部来源销售订单只能由同步服务写入");
        }
    }

    private static void requireManualExternalMutationAllowed(CallerIdentity actor, SalesOrderDetailView order) {
        if (isFeishuSource(order.sourceSystemCode())) {
            if (SalesOrderStatus.DRAFT.code().equals(order.orderStatusCode())) return;
            throw conflict("飞书完整销售订单由来源投影维护，只有待补充草稿允许业务修改或删除");
        }
        if (externalSource(order.sourceSystemCode()) && !serviceCaller(actor)) {
            throw conflict("外部来源销售订单已按来源单据同步，无需在Order重复人工操作");
        }
    }

    private static SalesOrderWrite preserveExternalIdentity(SalesOrderWrite command, SalesOrderDetailView current) {
        return new SalesOrderWrite(command.customerId(), current.sourceSystemCode(), current.sourceOrderNo(),
                current.sourceStatusCode(), current.sourceCreatorId(), current.sourceCreatorStaffCode(),
                current.sourceCreatorName(), command.dataQualityStatusCode(), command.dataQualityMessage(),
                command.customerCodeSnapshot(), command.customerNameSnapshot(), command.contactNameSnapshot(),
                command.contactPhoneSnapshot(), command.regionCode(), command.ownerSalesUserId(),
                command.ownerSalesName(), command.ownerEmployeeCode(), command.ownerEmployeeNameSnapshot(),
                command.orderDate(), command.orderStatusCode(), command.orderTypeCode(), command.paymentMethodCode(),
                command.paymentVoucherKeys(), command.sourceUnpaidAmount(), command.totalQuantity(),
                command.originalAmount(), command.discountRate(), command.discountAmount(), command.payableAmount(),
                command.lines(), command.remark(),
                command.revision());
    }

    private static void requireSameExternalIdentity(SalesOrderDetailView current, SalesOrderWrite command) {
        if (!externalSource(current.sourceSystemCode())) {
            throw conflict("只有外部来源销售订单允许按来源投影更新");
        }
        if (!current.sourceSystemCode().equalsIgnoreCase(command.sourceSystemCode())
                || !Objects.equals(trimmed(current.sourceOrderNo()), trimmed(command.sourceOrderNo()))) {
            throw conflict("外部来源销售订单投影不允许变更来源身份");
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireCompleteForBusinessFlow(SalesOrderDetailView order) {
        if (order == null) throw badRequest("销售订单不能为空");
        if (DATA_QUALITY_NEEDS_REVIEW.equals(order.dataQualityStatusCode())) {
            throw conflict("销售订单数据待完善，补齐客户、商品、规格和单位后才能继续");
        }
        List<String> missing = new ArrayList<>();
        if (order.customerId() == null) missing.add("客户映射");
        if (order.customerNameSnapshot() == null || order.customerNameSnapshot().isBlank()) missing.add("客户名称");
        if (order.lines() == null || order.lines().isEmpty()) {
            missing.add("商品明细");
        } else {
            for (SalesOrderLineView line : order.lines()) {
                if (line.productId() == null) missing.add("商品映射");
                if (line.productVariantId() == null) missing.add("规格映射");
                if (line.unitCode() == null || line.unitCode().isBlank()) missing.add("单位映射");
                if (line.productNameSnapshot() == null || line.productNameSnapshot().isBlank()) missing.add("商品名称");
            }
        }
        if (!missing.isEmpty()) {
            throw conflict("销售订单数据待完善：" + String.join("、", new LinkedHashSet<>(missing)));
        }
    }

    private static DataQuality dataQuality(Long customerId, String customerName, List<SalesOrderLineWrite> lines) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (customerId == null) missing.add("客户映射");
        if (customerName == null || customerName.isBlank()) missing.add("客户名称");
        if (lines == null || lines.isEmpty()) {
            missing.add("商品明细");
        } else {
            for (SalesOrderLineWrite line : lines) {
                if (line.productId() == null) missing.add("商品映射");
                if (line.productVariantId() == null) missing.add("规格映射");
                if (line.unitCode() == null || line.unitCode().isBlank()) missing.add("单位映射");
                if (line.productNameSnapshot() == null || line.productNameSnapshot().isBlank()) missing.add("商品名称");
            }
        }
        if (missing.isEmpty()) return new DataQuality(DATA_QUALITY_COMPLETE, null);
        return new DataQuality(DATA_QUALITY_NEEDS_REVIEW, "待补齐：" + String.join("、", missing));
    }

    private static boolean editableFeishuOrder(SalesOrderDetailView order) {
        return order != null && isFeishuSource(order.sourceSystemCode());
    }

    private static boolean isFeishuSource(String sourceSystemCode) {
        return SOURCE_SYSTEM_FEISHU.equalsIgnoreCase(sourceSystemCode);
    }

    private static boolean externalSource(String sourceSystemCode) {
        return sourceSystemCode != null && !sourceSystemCode.isBlank();
    }

    private static boolean serviceCaller(CallerIdentity actor) {
        return actor != null && "SERVICE".equals(actor.principalScope());
    }

    private static String salesOrderStatus(String value, boolean required) {
        String normalized = code(value, "orderStatusCode", required);
        if (normalized == null) return null;
        if (!SalesOrderStatus.supports(normalized)) throw badRequest("orderStatusCode无效");
        return normalized;
    }

    private static String dataQualityStatus(String value, boolean required) {
        String normalized = code(value, "dataQualityStatusCode", required);
        if (normalized == null) return null;
        if (!DATA_QUALITY_COMPLETE.equals(normalized) && !DATA_QUALITY_NEEDS_REVIEW.equals(normalized)) {
            throw badRequest("dataQualityStatusCode无效");
        }
        return normalized;
    }

    private static String initialOrderStatus(String sourceSystemCode, Boolean submit, DataQuality dataQuality) {
        if (Boolean.TRUE.equals(submit)) return SalesOrderStatus.SUBMITTED.code();
        if (isFeishuSource(sourceSystemCode) && dataQuality != null && dataQuality.complete()) {
            return SalesOrderStatus.COMPLETED.code();
        }
        return SalesOrderStatus.DRAFT.code();
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

    private static BigDecimal sourceUnpaidAmount(String sourceSystemCode, BigDecimal value) {
        if (value == null) return null;
        return isFeishuSource(sourceSystemCode) ? value : null;
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

    private static CallerIdentity serviceActor() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        if (!serviceCaller(caller)) throw new AuthorizationDeniedException("service-caller");
        return caller;
    }

    private static Long requireId(Long id, String message) {
        if (id == null || id < 1) throw badRequest(message);
        return id;
    }

    private static Long optionalId(Long id, String message) {
        if (id == null) return null;
        if (id < 1) throw badRequest(message);
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

    private static boolean hasAny(String... values) {
        if (values == null) return false;
        for (String value : values) {
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static ErpSalesStockOutClient unsupportedErpClient() {
        return (caller, request) -> {
            throw new IllegalStateException("ERP销售出库客户端未装配");
        };
    }

    private static HrEmployeeDisplayClient unsupportedHrEmployeeDisplayClient() {
        return (caller, employeeCodes) -> List.of();
    }

    private static CrmCustomerAreaDisplayClient unsupportedCrmCustomerAreaDisplayClient() {
        return (caller, areaCodes) -> List.of();
    }

    private record DataQuality(String statusCode, String message) {
        private boolean complete() {
            return DATA_QUALITY_COMPLETE.equals(statusCode);
        }
    }

    private static CallerIdentity hrServiceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_CENTER"), HR_EMPLOYEE_READ_PERMISSIONS);
    }

    private static CallerIdentity crmServiceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_CENTER"), CRM_CUSTOMER_READ_PERMISSIONS);
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
