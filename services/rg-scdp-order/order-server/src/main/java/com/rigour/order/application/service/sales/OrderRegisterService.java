package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.FundDocumentAttachmentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.NumberMappingView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingResult;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PaymentCheckCommand;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodRow;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.order.application.port.out.OrderRegisterStore.ApplyNumberMapping;
import com.rigour.order.application.port.out.OrderRegisterStore.LineCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.NumberMappingCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.OrderCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PaymentCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.PeriodCriteria;
import com.rigour.order.application.port.out.OrderRegisterStore.ReceivablesCriteria;
import com.rigour.order.domain.invoice.OrderInvoiceStatus;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 订单登记读取用例；归属和金额口径由仓储统一实现，服务层只做权限、校验和展示名补齐。 */
@Service
public class OrderRegisterService {
    private static final Logger log = LoggerFactory.getLogger(OrderRegisterService.class);
    private static final String READ_PERMISSION = "order:read";
    private static final String WRITE_PERMISSION = "order:write";
    private static final String CHECK_PERMISSION = "order:payment:check";
    private static final UUID SERVICE_PRINCIPAL_ID =
            UUID.nameUUIDFromBytes(
                    "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> CRM_CUSTOMER_READ_PERMISSIONS = Set.of("crm:customer:read");
    private static final Set<String> HR_EMPLOYEE_READ_PERMISSIONS = Set.of("hr:employee:read");

    private final OrderRegisterStore store;
    private final CrmCustomerAreaDisplayClient crmAreaDisplayClient;
    private final HrEmployeeDisplayClient hrEmployeeDisplayClient;
    private final OrderInvoiceStore invoiceStore;
    private final FundAttachmentUrlResolver fundAttachmentUrlResolver;

    public OrderRegisterService(
            OrderRegisterStore store,
            CrmCustomerAreaDisplayClient crmAreaDisplayClient,
            HrEmployeeDisplayClient hrEmployeeDisplayClient,
            OrderInvoiceStore invoiceStore,
            ObjectProvider<FundAttachmentUrlResolver> fundAttachmentUrlResolverProvider) {
        this.store = store;
        this.crmAreaDisplayClient = crmAreaDisplayClient;
        this.hrEmployeeDisplayClient = hrEmployeeDisplayClient;
        this.invoiceStore = invoiceStore;
        this.fundAttachmentUrlResolver =
                fundAttachmentUrlResolverProvider.getIfAvailable(() -> FundAttachmentUrlResolver.NONE);
    }

    public OrderRegisterPage<OrderRegisterOrderView> orders(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentStatusCode,
            Boolean hasUnpaid,
            String invoiceStatusCode) {
        CallerIdentity actor = actor(READ_PERMISSION);
        var criteria =
                new OrderCriteria(
                        text(orderNo, 80, "orderNo"),
                        optionalId(customerId, "customerId无效"),
                        text(customerName, 200, "customerName"),
                        text(customerCode, 64, "customerCode"),
                        text(regionCode, 128, "regionCode"),
                        text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                        departmentScopeIds(actor, departmentId, includeSubDepartments),
                        orderDateFrom,
                        orderDateTo,
                        text(orderStatusCode, 64, "orderStatusCode"),
                        text(paymentStatusCode, 64, "paymentStatusCode"),
                        hasUnpaid,
                        invoiceStatusCode(invoiceStatusCode));
        requireRange(orderDateFrom, orderDateTo, "orderDateFrom不能晚于orderDateTo");
        var result = store.orders(actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
        return withInvoiceStatuses(
                actor, withDepartmentNames(actor, withRegionNames(actor, result)));
    }

    /** 商品筛选集合由前端分类解析或商品下拉给出，限制上限避免超长 SQL。 */
    private static List<Long> filterProductIds(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null) continue;
            if (value < 1) throw badRequest("productIds包含无效商品ID");
            ids.add(value);
        }
        if (ids.isEmpty()) return null;
        if (ids.size() > 500) throw badRequest("productIds单次最多筛选500个商品");
        return List.copyOf(ids);
    }

    /** 发票状态筛选只接受列表展示口径：未申请、待开票、已开票。 */
    /** 发票状态筛选只接受白名单；非法值返回 400，避免被当成“不筛选”放大成全量。 */
    private static String invoiceStatusCode(String value) {
        String code = text(value, 32, "invoiceStatusCode");
        if (code == null) return null;
        if (!Set.of("NOT_APPLIED", "PENDING", "INVOICED").contains(code)) {
            throw badRequest("invoiceStatusCode无效");
        }
        return code;
    }

    /** 财务核对回款：与银行流水核对后写入交易单号（凭证验重 + 方便对账）并标记已核对。 */
    public OrderRegisterPaymentView checkPayment(Long id, PaymentCheckCommand command) {
        CallerIdentity actor = actor(CHECK_PERMISSION);
        if (id == null || id < 1) throw badRequest("回款ID无效");
        if (command == null) throw badRequest("核对参数不能为空");
        String transactionNo = text(command.transactionNo(), 128, "transactionNo");
        if (transactionNo == null) throw badRequest("交易单号不能为空");
        if (command.revision() == null || command.revision() < 1) {
            throw badRequest("回款版本不能为空");
        }
        return store.checkPayment(
                actor.tenantId().toString(),
                id,
                transactionNo,
                command.revision(),
                actor.principalId() == null ? null : actor.principalId().toString(),
                Instant.now());
    }

    public OrderRegisterPage<OrderRegisterLineView> lines(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String productKeyword,
            String productCode,
            List<Long> productIds) {
        CallerIdentity actor = actor(READ_PERMISSION);
        requireRange(orderDateFrom, orderDateTo, "orderDateFrom不能晚于orderDateTo");
        var criteria =
                new LineCriteria(
                        text(orderNo, 80, "orderNo"),
                        optionalId(customerId, "customerId无效"),
                        text(customerName, 200, "customerName"),
                        text(customerCode, 64, "customerCode"),
                        text(regionCode, 128, "regionCode"),
                        text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                        departmentScopeIds(actor, departmentId, includeSubDepartments),
                        orderDateFrom,
                        orderDateTo,
                        text(orderStatusCode, 64, "orderStatusCode"),
                        text(productKeyword, 200, "productKeyword"),
                        text(productCode, 128, "productCode"),
                        filterProductIds(productIds));
        var result = store.lines(actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
        return withDepartmentNames(actor, withRegionNames(actor, result));
    }

    public OrderRegisterPage<OrderRegisterPaymentView> payments(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentNo,
            String transactionNo,
            String paymentStatusCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo,
            String sortBy,
            String sortDirection) {
        CallerIdentity actor = actor(READ_PERMISSION);
        requireRange(orderDateFrom, orderDateTo, "orderDateFrom不能晚于orderDateTo");
        requireRange(paymentTimeFrom, paymentTimeTo, "paymentTimeFrom不能晚于paymentTimeTo");
        var criteria =
                new PaymentCriteria(
                        text(orderNo, 80, "orderNo"),
                        optionalId(customerId, "customerId无效"),
                        text(customerName, 200, "customerName"),
                        text(customerCode, 64, "customerCode"),
                        text(regionCode, 128, "regionCode"),
                        text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                        departmentScopeIds(actor, departmentId, includeSubDepartments),
                        orderDateFrom,
                        orderDateTo,
                        text(orderStatusCode, 64, "orderStatusCode"),
                        text(paymentNo, 64, "paymentNo"),
                        text(transactionNo, 128, "transactionNo"),
                        text(paymentStatusCode, 32, "paymentStatusCode"),
                        paymentTimeFrom,
                        paymentTimeTo,
                        text(sortBy, 32, "sortBy"),
                        text(sortDirection, 8, "sortDirection"));
        var result = store.payments(actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
        return withDepartmentNames(actor, withAttachmentViews(actor, withRegionNames(actor, result)));
    }

    public PeriodStatisticsView periodStatistics(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String customerName,
            String customerCode) {
        CallerIdentity actor = actor(READ_PERMISSION);
        if (dateFrom == null || dateTo == null) throw badRequest("统计日期范围不能为空");
        String normalizedGroup = text(groupBy, 16, "groupBy");
        if (normalizedGroup != null
                && !Set.of("region", "customer", "employee").contains(normalizedGroup.toLowerCase())) {
            throw badRequest("groupBy只支持region、customer、employee");
        }
        var criteria =
                new PeriodCriteria(
                        dateFrom,
                        dateTo,
                        normalizedGroup,
                        text(regionCode, 128, "regionCode"),
                        text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                        departmentScopeIds(actor, departmentId, includeSubDepartments),
                        optionalId(customerId, "customerId无效"),
                        text(customerName, 200, "customerName"),
                        text(customerCode, 64, "customerCode"));
        return withPeriodLabels(actor, store.periodStatistics(actor.tenantId().toString(), criteria));
    }

    /** 创建人下拉数据源；返回订单真实创建人（来源创建人优先），去空、去重、稳定排序。 */
    public List<String> creators() {
        CallerIdentity actor = actor(READ_PERMISSION);
        return store.creators(actor.tenantId().toString());
    }

    public OrderRegisterPage<ReceivablesView> receivables(
            int begin,
            int step,
            LocalDate asOfDate,
            boolean hasUnpaid,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String orderNo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        if (asOfDate == null) throw badRequest("asOfDate不能为空");
        var criteria =
                new ReceivablesCriteria(
                        asOfDate,
                        hasUnpaid,
                        text(regionCode, 128, "regionCode"),
                        text(ownerEmployeeCode, 50, "ownerEmployeeCode"),
                        departmentScopeIds(actor, departmentId, includeSubDepartments),
                        optionalId(customerId, "customerId无效"),
                        text(orderNo, 80, "orderNo"));
        var result =
                store.receivables(actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
        return withRegionNames(actor, result);
    }

    public OrderRegisterPage<NumberMappingView> numberMappings(
            int begin, int step, String state, String internalOrderNo, String dhbOrderNo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        var criteria =
                new NumberMappingCriteria(
                        text(state, 32, "state"),
                        text(internalOrderNo, 80, "internalOrderNo"),
                        text(dhbOrderNo, 128, "dhbOrderNo"));
        return store.numberMappings(
                actor.tenantId().toString(), pageBegin(begin), pageStep(step), criteria);
    }

    public OrderNumberMappingResult mapOrderNumber(
            String connectorId,
            String sourceObjectType,
            String sourceObjectId,
            String internalOrderNo,
            String dhbOrderNo,
            String evidence,
            Integer revision) {
        CallerIdentity actor = action(WRITE_PERMISSION);
        if (!"SERVICE".equals(actor.principalScope()))
            throw new AuthorizationDeniedException("service-caller-required");
        var command =
                new ApplyNumberMapping(
                        text(connectorId, 36, "connectorId"),
                        text(sourceObjectType, 64, "sourceObjectType"),
                        text(sourceObjectId, 128, "sourceObjectId"),
                        text(internalOrderNo, 80, "internalOrderNo"),
                        text(dhbOrderNo, 128, "dhbOrderNo"),
                        text(evidence, 1000, "evidence"),
                        revision);
        if (command.internalOrderNo() == null || command.dhbOrderNo() == null) {
            throw badRequest("旧内部订单号和订货宝单号不能为空");
        }
        if (command.evidence() == null || command.evidence().length() < 5) {
            throw badRequest("请填写至少5字的核对依据");
        }
        return store.applyNumberMapping(
                actor.tenantId().toString(), command, actor.principalId().toString());
    }

    /**
     * 期间统计分组标签补齐：region 用 CRM 客户归属地区主档展示名，其余分组用仓储已解析的名称。
     * 补齐失败只回退到编码，不影响统计金额。
     */
    private PeriodStatisticsView withPeriodLabels(
            CallerIdentity actor, PeriodStatisticsView view) {
        if (view == null || view.rows().isEmpty()) return view;
        Map<String, String> names = new LinkedHashMap<>();
        if ("region".equalsIgnoreCase(view.groupBy())) {
            Set<String> regionCodes = new LinkedHashSet<>();
            for (PeriodRow row : view.rows()) {
                if (row != null && row.key() != null && !row.key().isBlank())
                    regionCodes.add(row.key().strip());
            }
            if (!regionCodes.isEmpty()) {
                try {
                    for (CrmCustomerAreaDisplayClient.CustomerAreaDisplay display :
                            crmAreaDisplayClient.resolve(
                                    crmServiceCaller(actor.tenantId()), regionCodes)) {
                        if (display == null
                                || display.areaCode() == null
                                || display.areaName() == null) continue;
                        names.put(display.areaCode().strip(), display.areaName().strip());
                    }
                } catch (RuntimeException ignored) {
                    // 展示名补齐失败不阻断期间统计；地区编码仍可正常展示与筛选。
                }
            }
        }
        List<PeriodRow> rows =
                view.rows().stream()
                        .map(row -> withPeriodLabel(row, names))
                        .toList();
        return new PeriodStatisticsView(
                view.dateFrom(),
                view.dateTo(),
                view.groupBy(),
                view.totals(),
                rows,
                view.coverage());
    }

    private static PeriodRow withPeriodLabel(PeriodRow row, Map<String, String> names) {
        if (row == null) return null;
        String key = row.key() == null ? null : row.key().strip();
        String label = row.label();
        String resolved = key == null || key.isEmpty() ? null : names.get(key);
        if (resolved != null && !resolved.isBlank()) label = resolved;
        if (label == null || label.isBlank()) label = key == null || key.isEmpty() ? "未分配" : key;
        return new PeriodRow(
                row.key(),
                label,
                row.periodOrderAmount(),
                row.periodReceivedAmount(),
                row.periodRefundAmount(),
                row.periodNetReceivedAmount(),
                row.endingUnpaidAmount());
    }

    private <T> OrderRegisterPage<T> withRegionNames(
            CallerIdentity actor, OrderRegisterPage<T> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<String> regionCodes = new LinkedHashSet<>();
        for (T item : page.items()) {
            String code = regionCodeOf(item);
            if (code != null && !code.isBlank()) regionCodes.add(code.strip());
        }
        if (regionCodes.isEmpty()) return page;
        Map<String, String> names = new LinkedHashMap<>();
        try {
            for (CrmCustomerAreaDisplayClient.CustomerAreaDisplay display :
                    crmAreaDisplayClient.resolve(crmServiceCaller(actor.tenantId()), regionCodes)) {
                if (display == null || display.areaCode() == null || display.areaName() == null)
                    continue;
                names.put(display.areaCode().strip(), display.areaName().strip());
            }
        } catch (RuntimeException ignored) {
            // 展示名补齐失败不阻断登记列表；地区编码仍可正常展示与筛选。
        }
        if (names.isEmpty()) return page;
        List<T> items =
                page.items().stream().map(item -> withRegionName(item, names)).toList();
        return new OrderRegisterPage<>(
                page.total(), page.begin(), page.step(), items, page.totals(), page.coverage());
    }

    @SuppressWarnings("unchecked")
    private static <T> T withRegionName(T item, Map<String, String> names) {
        String code = regionCodeOf(item);
        String name = code == null ? null : names.get(code.strip());
        if (name == null || name.isBlank()) return item;
        if (item instanceof OrderRegisterOrderView v) {
            return (T)
                    new OrderRegisterOrderView(
                            v.id(), v.orderNo(), v.legacyOrderNo(), v.sourceSystemCode(),
                            v.sourceOrderNo(), v.dhbOrderNo(), v.orderNumberState(), v.customerId(), v.customerCode(),
                            v.customerName(), v.regionCode(), name, v.ownerEmployeeCode(),
                            v.ownerEmployeeName(), v.departmentId(), v.departmentName(),
                            v.orderStatusCode(), v.paymentStatusCode(), v.dataQualityStatusCode(),
                            v.invoiceStatusCode(), v.invoiceStatusName(), v.originalAmount(),
                            v.payableAmount(), v.paidAmount(), v.unpaidAmount(), v.checkedAmount(),
                            v.orderDate(), v.shipmentTime(), v.createdBy(), v.createdTime(),
                            v.updatedBy(), v.updatedTime(), v.syncedBy(), v.syncedAt(), v.revision());
        }
        if (item instanceof OrderRegisterLineView v) {
            return (T)
                    new OrderRegisterLineView(
                            v.id(), v.orderId(), v.lineNo(), v.sourceLineId(), v.productId(),
                            v.productVariantId(), v.productCode(), v.skuCode(), v.productName(),
                            v.specification(), v.unitCode(), v.quantity(), v.unitPrice(),
                            v.lineAmount(), v.orderNo(), v.sourceOrderNo(), v.dhbOrderNo(), v.customerId(),
                            v.customerCode(), v.customerName(), v.regionCode(), name,
                            v.ownerEmployeeCode(),
                            v.ownerEmployeeName(), v.departmentId(), v.departmentName(),
                            v.orderStatusCode(), v.orderDate(), v.revision(),
                            v.createdBy(), v.createdTime(), v.updatedBy(), v.updatedTime(),
                            v.syncedBy(), v.syncedAt());
        }
        if (item instanceof OrderRegisterPaymentView v) {
            return (T)
                    new OrderRegisterPaymentView(
                            v.id(), v.paymentNo(), v.sourceRecordId(), v.orderId(), v.orderNo(), v.dhbOrderNo(),
                            v.customerId(), v.customerCode(), v.customerName(), v.regionCode(), name,
                            v.ownerEmployeeCode(), v.ownerEmployeeName(), v.departmentId(),
                            v.departmentName(), v.orderDate(), v.orderAmount(), v.paidAmount(),
                            v.paymentStatusCode(), v.paymentTime(), v.transactionNo(),
                            v.attachments(), v.attachmentViews(), v.createdBy(), v.createdTime(),
                            v.updatedBy(),
                            v.updatedTime(), v.syncedBy(), v.syncedAt(), v.checkedBy(),
                            v.checkedAt(), v.revision());
        }
        if (item instanceof ReceivablesView v) {
            return (T)
                    new ReceivablesView(
                            v.orderId(), v.orderNo(), v.sourceSystemCode(), v.customerId(),
                            v.customerCode(), v.customerName(), v.regionCode(), name,
                            v.ownerEmployeeCode(), v.ownerEmployeeName(), v.departmentName(),
                            v.orderDate(), v.receivableAmount(), v.netReceivedAmount(),
                            v.unpaidAmount(), v.overpaidAmount(), v.historyComplete(),
                            v.coverageFrom());
        }
        return item;
    }

    /** 用业务员当前 HR 员工档案补齐部门名；归属快照缺失时列表仍可展示部门。 */
    private <T> OrderRegisterPage<T> withDepartmentNames(
            CallerIdentity actor, OrderRegisterPage<T> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<String> employeeCodes = new LinkedHashSet<>();
        for (T item : page.items()) {
            String code = employeeCodeOf(item);
            if (code != null && !code.isBlank() && departmentNameOf(item) == null) {
                employeeCodes.add(code.strip());
            }
        }
        if (employeeCodes.isEmpty()) return page;
        Map<String, String> departments = new LinkedHashMap<>();
        try {
            for (HrEmployeeDisplayClient.EmployeeDisplay display :
                    hrEmployeeDisplayClient.resolve(hrServiceCaller(actor.tenantId()), employeeCodes)) {
                if (display == null || display.employeeCode() == null) continue;
                if (display.departmentName() == null || display.departmentName().isBlank()) continue;
                departments.put(display.employeeCode().strip(), display.departmentName().strip());
            }
        } catch (RuntimeException ignored) {
            // 部门展示名补齐失败不阻断登记列表；业务员和地区仍可正常展示。
        }
        if (departments.isEmpty()) return page;
        List<T> items = page.items().stream().map(item -> withDepartmentName(item, departments)).toList();
        return new OrderRegisterPage<>(
                page.total(), page.begin(), page.step(), items, page.totals(), page.coverage());
    }

    @SuppressWarnings("unchecked")
    private static <T> T withDepartmentName(T item, Map<String, String> departments) {
        String code = employeeCodeOf(item);
        String department = code == null ? null : departments.get(code.strip());
        if (department == null || department.isBlank()) return item;
        if (item instanceof OrderRegisterOrderView v) {
            return (T)
                    new OrderRegisterOrderView(
                            v.id(), v.orderNo(), v.legacyOrderNo(), v.sourceSystemCode(),
                            v.sourceOrderNo(), v.dhbOrderNo(), v.orderNumberState(), v.customerId(), v.customerCode(),
                            v.customerName(), v.regionCode(), v.regionName(), v.ownerEmployeeCode(),
                            v.ownerEmployeeName(), v.departmentId(), department, v.orderStatusCode(),
                            v.paymentStatusCode(), v.dataQualityStatusCode(), v.invoiceStatusCode(),
                            v.invoiceStatusName(),
                            v.originalAmount(), v.payableAmount(), v.paidAmount(),
                            v.unpaidAmount(), v.checkedAmount(), v.orderDate(), v.shipmentTime(),
                            v.createdBy(), v.createdTime(), v.updatedBy(), v.updatedTime(),
                            v.syncedBy(), v.syncedAt(), v.revision());
        }
        if (item instanceof OrderRegisterLineView v) {
            return (T)
                    new OrderRegisterLineView(
                            v.id(), v.orderId(), v.lineNo(), v.sourceLineId(), v.productId(),
                            v.productVariantId(), v.productCode(), v.skuCode(), v.productName(),
                            v.specification(), v.unitCode(), v.quantity(), v.unitPrice(),
                            v.lineAmount(), v.orderNo(), v.sourceOrderNo(), v.dhbOrderNo(), v.customerId(),
                            v.customerCode(), v.customerName(), v.regionCode(), v.regionName(),
                            v.ownerEmployeeCode(),
                            v.ownerEmployeeName(), v.departmentId(), department, v.orderStatusCode(),
                            v.orderDate(), v.revision(),
                            v.createdBy(), v.createdTime(), v.updatedBy(), v.updatedTime(),
                            v.syncedBy(), v.syncedAt());
        }
        if (item instanceof OrderRegisterPaymentView v) {
            return (T)
                    new OrderRegisterPaymentView(
                            v.id(), v.paymentNo(), v.sourceRecordId(), v.orderId(), v.orderNo(), v.dhbOrderNo(),
                            v.customerId(), v.customerCode(), v.customerName(), v.regionCode(),
                            v.regionName(), v.ownerEmployeeCode(), v.ownerEmployeeName(),
                            v.departmentId(), department, v.orderDate(), v.orderAmount(),
                            v.paidAmount(), v.paymentStatusCode(), v.paymentTime(), v.transactionNo(),
                            v.attachments(), v.attachmentViews(), v.createdBy(), v.createdTime(),
                            v.updatedBy(), v.updatedTime(), v.syncedBy(), v.syncedAt(), v.checkedBy(),
                            v.checkedAt(), v.revision());
        }
        return item;
    }

    /** 批量回填开票状态；未申请/已撤回没有发票行，列表按未申请展示。 */
    private <T> OrderRegisterPage<T> withInvoiceStatuses(CallerIdentity actor, OrderRegisterPage<T> page) {
        if (page == null || page.items().isEmpty()) return page;
        Set<Long> orderIds = new LinkedHashSet<>();
        for (T item : page.items()) {
            if (item instanceof OrderRegisterOrderView view && view.id() != null) orderIds.add(view.id());
        }
        if (orderIds.isEmpty()) return page;
        Map<Long, String> statuses;
        try {
            statuses = invoiceStore.statusesByOrderIds(actor.tenantId().toString(), orderIds);
        } catch (RuntimeException exception) {
            log.warn(
                    "开票状态批量回填失败，列表仍可展示其他字段 tenantId={} errorType={}",
                    actor.tenantId(),
                    exception.getClass().getSimpleName());
            return page;
        }
        if (statuses.isEmpty()) return page;
        List<T> items = page.items().stream().map(item -> withInvoiceStatus(item, statuses)).toList();
        return new OrderRegisterPage<>(
                page.total(), page.begin(), page.step(), items, page.totals(), page.coverage());
    }

    @SuppressWarnings("unchecked")
    private static <T> T withInvoiceStatus(T item, Map<Long, String> statuses) {
        if (!(item instanceof OrderRegisterOrderView v) || v.id() == null) return item;
        String status = statuses.get(v.id());
        if (status == null) return item;
        return (T)
                new OrderRegisterOrderView(
                        v.id(), v.orderNo(), v.legacyOrderNo(), v.sourceSystemCode(),
                        v.sourceOrderNo(), v.dhbOrderNo(), v.orderNumberState(), v.customerId(), v.customerCode(),
                        v.customerName(), v.regionCode(), v.regionName(), v.ownerEmployeeCode(),
                        v.ownerEmployeeName(), v.departmentId(), v.departmentName(),
                        v.orderStatusCode(), v.paymentStatusCode(), v.dataQualityStatusCode(), status,
                        OrderInvoiceStatus.displayNameOf(status), v.originalAmount(),
                        v.payableAmount(), v.paidAmount(), v.unpaidAmount(), v.checkedAmount(),
                        v.orderDate(), v.shipmentTime(), v.createdBy(), v.createdTime(),
                        v.updatedBy(), v.updatedTime(), v.syncedBy(), v.syncedAt(), v.revision());
    }

    private static String employeeCodeOf(Object item) {
        if (item instanceof OrderRegisterOrderView v) return v.ownerEmployeeCode();
        if (item instanceof OrderRegisterLineView v) return v.ownerEmployeeCode();
        if (item instanceof OrderRegisterPaymentView v) return v.ownerEmployeeCode();
        return null;
    }

    private static String departmentNameOf(Object item) {
        if (item instanceof OrderRegisterOrderView v) return v.departmentName();
        if (item instanceof OrderRegisterLineView v) return v.departmentName();
        if (item instanceof OrderRegisterPaymentView v) return v.departmentName();
        return null;
    }

    /** 以服务身份读取 HR 员工档案，避免依赖当前用户的 HR 权限。 */
    private static CallerIdentity hrServiceCaller(UUID tenantId) {
        return new CallerIdentity(
                "SERVICE",
                SERVICE_PRINCIPAL_ID,
                tenantId,
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of(),
                HR_EMPLOYEE_READ_PERMISSIONS);
    }

    /**
     * 部门筛选按订单归属快照的部门口径匹配；HR 只用来展开子部门，不参与历史归属判断。
     * HR 不可用时抛依赖错误，避免把“查不到”当成“没有数据”。
     */
    private Set<Long> departmentScopeIds(
            CallerIdentity actor, Long departmentId, Boolean includeSubDepartments) {
        Long departmentKey = optionalId(departmentId, "departmentId无效");
        if (departmentKey == null) return null;
        try {
            Set<Long> scope = hrEmployeeDisplayClient.departmentIdsInScope(
                    hrServiceCaller(actor.tenantId()), departmentKey, includeSubDepartments);
            log.info(
                    "部门筛选解析完成 tenantId={} departmentId={} includeSubDepartments={} departmentCount={}",
                    actor.tenantId(),
                    departmentKey,
                    includeSubDepartments,
                    scope.size());
            return scope;
        } catch (RuntimeException exception) {
            log.warn(
                    "部门筛选解析部门范围失败 tenantId={} departmentId={} errorType={}",
                    actor.tenantId(),
                    departmentKey,
                    exception.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "部门范围暂时无法解析，请稍后重试", List.of());
        }
    }

    private static String regionCodeOf(Object item) {
        if (item instanceof OrderRegisterOrderView v) return v.regionCode();
        if (item instanceof OrderRegisterLineView v) return v.regionCode();
        if (item instanceof OrderRegisterPaymentView v) return v.regionCode();
        if (item instanceof ReceivablesView v) return v.regionCode();
        return null;
    }

    /** 给回款列表行签发凭证短时预览地址；签名不可用或失败时保持原始对象键。 */
    private OrderRegisterPage<OrderRegisterPaymentView> withAttachmentViews(
            CallerIdentity actor, OrderRegisterPage<OrderRegisterPaymentView> page) {
        if (page == null || page.items().isEmpty()) return page;
        String tenantId = actor.tenantId().toString();
        List<OrderRegisterPaymentView> items =
                page.items().stream()
                        .map(item -> item.attachments().isEmpty() ? item : withAttachmentViews(tenantId, item))
                        .toList();
        return new OrderRegisterPage<>(
                page.total(), page.begin(), page.step(), items, page.totals(), page.coverage());
    }

    private OrderRegisterPaymentView withAttachmentViews(String tenantId, OrderRegisterPaymentView item) {
        List<FundDocumentAttachmentView> views = new ArrayList<>();
        for (String key : new LinkedHashSet<>(item.attachments())) {
            String objectKey = plainAttachmentKey(key);
            if (objectKey == null) continue;
            views.add(
                    new FundDocumentAttachmentView(
                            objectKey,
                            attachmentFileName(objectKey),
                            temporaryFundAttachmentUrl(tenantId, objectKey)));
        }
        if (views.isEmpty()) return item;
        return new OrderRegisterPaymentView(
                item.id(), item.paymentNo(), item.sourceRecordId(), item.orderId(), item.orderNo(),
                item.dhbOrderNo(), item.customerId(), item.customerCode(), item.customerName(), item.regionCode(),
                item.regionName(), item.ownerEmployeeCode(), item.ownerEmployeeName(),
                item.departmentId(), item.departmentName(), item.orderDate(), item.orderAmount(),
                item.paidAmount(), item.paymentStatusCode(), item.paymentTime(), item.transactionNo(),
                item.attachments(), views, item.createdBy(), item.createdTime(), item.updatedBy(),
                item.updatedTime(), item.syncedBy(), item.syncedAt(), item.checkedBy(),
                item.checkedAt(), item.revision());
    }

    private String temporaryFundAttachmentUrl(String tenantId, String objectKey) {
        if (!objectKey.startsWith(tenantId + "/")) return null;
        try {
            return fundAttachmentUrlResolver.temporaryUrl(tenantId, objectKey);
        } catch (RuntimeException exception) {
            log.debug(
                    "回款凭证临时URL生成失败 tenantId={} objectKey={} errorType={}",
                    tenantId,
                    objectKey.length() <= 96 ? objectKey : objectKey.substring(0, 96) + "...",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private static String plainAttachmentKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.startsWith("http://")
                || normalized.startsWith("https://")
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

    private static CallerIdentity actor(String permission) {
        if (READ_PERMISSION.equals(permission))
            com.rigour.tenant.iam.client.SupplyAuthorizationContext.observe(permission, permission);
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static CallerIdentity action(String permission) {
        com.rigour.tenant.iam.client.SupplyAuthorizationContext.observe(permission, WRITE_PERMISSION);
        return actor(permission);
    }

    private static CallerIdentity crmServiceCaller(UUID tenantId) {
        return new CallerIdentity(
                "SERVICE",
                SERVICE_PRINCIPAL_ID,
                tenantId,
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("ORDER_CENTER"),
                CRM_CUSTOMER_READ_PERMISSIONS);
    }

    private static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
    }

    private static Long optionalId(Long id, String message) {
        if (id == null) return null;
        if (id < 1) throw badRequest(message);
        return id;
    }

    private static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static void requireRange(Instant from, Instant to, String message) {
        if (from != null && to != null && !from.isBefore(to)) throw badRequest(message);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
