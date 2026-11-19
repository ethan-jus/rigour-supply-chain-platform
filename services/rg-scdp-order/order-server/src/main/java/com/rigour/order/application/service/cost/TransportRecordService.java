package com.rigour.order.application.service.cost;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsCostLedger.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 产品主列表复用原成本事实，自动来源保持只读；人工金额在服务端精确分摊。 */
@Service
public class TransportRecordService {
    private final OrderLogisticsCostService costs;
    private final OrderLogisticsLedgerService ledgers;
    private final OrderLogisticsReferences refs;
    private final PlatformTransportStore platform;
    private final CostAttachmentStorage attachments;
    private CostAttachmentService evidence;
    private ShipmentTransportStore shipments;

    @org.springframework.beans.factory.annotation.Autowired
    void shipmentStore(ShipmentTransportStore store) {
        this.shipments = store;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void evidenceService(CostAttachmentService service) {
        this.evidence = service;
    }

    public TransportRecordService(
            OrderLogisticsCostService costs,
            OrderLogisticsLedgerService ledgers,
            OrderLogisticsReferences refs,
            PlatformTransportStore platform,
            CostAttachmentStorage attachments) {
        this.costs = costs;
        this.ledgers = ledgers;
        this.refs = refs;
        this.platform = platform;
        this.attachments = attachments;
    }

    private String tenant() {
        var c = AuthorizationContext.requireCurrent();
        if (c.tenantId() == null) throw new AuthorizationDeniedException("tenant");
        return c.tenantId().toString();
    }

    public Page page(LogisticsCostQuery q, boolean export) {
        var p = export ? costs.export("logistics", q) : costs.entries("logistics", q);
        return new Page(
                p,
                platform.byCosts(
                        tenant(), p.items().stream().map(LogisticsCostDetailView::id).toList()),
                shipmentRecords(p.items().stream().map(LogisticsCostDetailView::id).toList()));
    }

    public List<Detail> selected(List<String> ids) {
        var rows =
                costs.withBusinessStatuses(
                        costs.exportSelected(ids), "order:logistics-cost:export");
        var sources = platform.byCosts(tenant(), ids);
        var shipping = shipmentRecords(ids);
        return rows.stream()
                .map(
                        r ->
                                TransportAllocationPlan.detail(
                                        r,
                                        sources.get(r.id()),
                                        shipping.getOrDefault(r.id(), List.of())))
                .toList();
    }

    public Detail detail(String id) {
        var row = costs.entry("logistics", id);
        return TransportAllocationPlan.detail(
                row,
                platform.detail(tenant(), id),
                shipmentRecords(List.of(id)).getOrDefault(id, List.of()));
    }

    private Map<String, List<ShipmentTransportModels.Source>> shipmentRecords(List<String> ids) {
        return shipments == null ? Map.of() : shipments.byCosts(tenant(), ids);
    }

    public String voucher(String id, String voucherId) {
        return voucher(id, voucherId, false);
    }

    public String voucher(String id, String voucherId, boolean download) {
        var v = authorizedVoucher(id, voucherId);
        return download ? attachments.downloadUrl(v.fileKey(), v.name()) : attachments.url(v.fileKey());
    }

    public CostAttachmentService.FileContent voucherContent(String id, String voucherId) {
        var v = authorizedVoucher(id, voucherId);
        return new CostAttachmentService.FileContent(v.name(), attachments.read(v.fileKey()));
    }

    private com.rigour.order.api.v1.model.LogisticsImportModels.Voucher authorizedVoucher(
            String id, String voucherId) {
        var d = detail(id);
        if (d.platform() == null) throw bad("平台凭证不存在");
        var source = d.platform().source();
        var order = source.latestOrder() == null ? source.order() : source.latestOrder();
        if (order.facts() == null) throw bad("平台凭证尚未取得");
        var vouchers = new ArrayList<com.rigour.order.api.v1.model.LogisticsImportModels.Voucher>();
        for (var fee : order.facts().fees())
            if (fee.vouchers() != null) vouchers.addAll(fee.vouchers());
        if (order.facts().receipts() != null) vouchers.addAll(order.facts().receipts());
        if (order.facts().billVouchers() != null) vouchers.addAll(order.facts().billVouchers());
        for (var point : order.facts().stopPhotos())
            if (point.vouchers() != null) vouchers.addAll(point.vouchers());
        for (var v : vouchers)
            if (voucherId.equals(v.id())
                    && "READY".equals(v.state())
                    && v.fileKey() != null
                    && v.fileKey().startsWith(tenant() + "/integration-huolala-vouchers/")
                    && !v.fileKey().contains(".."))
                return v;
        throw bad("凭证正在获取，请稍后重试");
    }

    @Transactional
    public Detail save(String id, Draft d) {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        var old = id == null ? null : detail(id);
        if (old == null || old.platform() == null) {
            if (id == null || evidence.list("logistics", id).isEmpty())
                throw bad("手工运输记录至少上传一份业务凭证，请连同凭证一起保存");
        }
        return saveWithPendingEvidence(id, d);
    }

    /** 仅供同一事务内的整单保存使用；调用方在提交前核验最终附件集合。 */
    Detail saveWithPendingEvidence(String id, Draft d) {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        validate(d);
        var old = id == null ? null : detail(id);
        if (old != null && !"ACTIVE".equals(old.record().statusCode())) throw bad("作废记录不可修改");
        if (old != null && !Objects.equals(old.record().revision(), d.revision()))
            throw bad("记录已变化，请刷新");
        if (old != null && old.platform() == null && !old.shipments().isEmpty()) {
            var original = old.record().data().ledger();
            if (!"DELIVERY".equals(d.transportType())
                    || !Objects.equals(original.carrierCode(), d.carrierCode())
                    || !Objects.equals(original.platformOrderNo(), d.platformOrderNo())
                    || !Objects.equals(original.sender().address(), d.senderAddress())
                    || !Objects.equals(original.sender().displayName(), d.senderName())
                    || !Objects.equals(old.record().data().costDate(), d.date())
                    || d.receivingPoints() != null
                            && !Objects.equals(original.receivingPoints(), d.receivingPoints())
                    || !new HashSet<>(original.sources().stream().map(Source::orderCode).toList())
                            .equals(new HashSet<>(d.orderCodes())))
                throw bad("订货宝发货及原销售关系只读，请在原单更正后同步");
            if (!TransportAllocationPlan.complex(original)) return saveShipmentFinancials(old, d);
        }
        if (old == null)
            try {
                UUID.fromString(d.requestId());
            } catch (Exception e) {
                throw bad("保存标识无效，请重新打开新增页面");
            }
        if (old != null && TransportAllocationPlan.complex(old.record().data().ledger()))
            throw bad("此记录有多项费用或特殊分摊，请使用费用明细编辑，避免覆盖原分摊");
        var sources = documents(d, "order:logistics-cost:write");
        if (old != null && old.platform() != null) {
            var source = old.platform().source();
            var order = source.latestOrder() == null ? source.order() : source.latestOrder();
            var f = order.facts();
            if (d.manualBillItems() != null && !d.manualBillItems().isEmpty())
                throw bad("平台账单明细只读");
            if (d.paymentStatusCode() != null
                    && !Objects.equals(
                            d.paymentStatusCode(), old.record().data().paymentStatusCode()))
                throw bad("平台付款信息只读");
            if (!"HLL".equals(d.carrierCode())
                    || !Objects.equals(d.platformOrderNo(), order.orderNo())
                    || !Objects.equals(
                            blankToNull(d.trackingNo()),
                            blankToNull(order.raw() == null ? null : order.raw().get("运单号")))
                    || !Objects.equals(d.date().toString(), order.useTime().substring(0, 10)))
                throw bad("平台物流公司、订单号和日期只读");
            // 未关联也能维护负责人、备注，暂不要求抄录平台未提供的地址。
            var plan = TransportAllocationPlan.calculate(d.amount(), sources, d.orderAllocations());
            // 关联业务后立即固化同一份成本与分摊；平台账单仍独立只读。
            if (!sources.isEmpty()) {
                var saved =
                        costs.save(
                                "logistics",
                                id,
                                command(
                                        old.record(),
                                        d,
                                        sources,
                                        "order:logistics-cost:write",
                                        "DOOR_DELIVERY"));
                platform.saveAssociations(
                        tenant(),
                        id,
                        d,
                        sources,
                        AuthorizationContext.requireCurrent().principalId().toString());
                return detail(saved.id());
            }
            platform.saveDraft(
                    tenant(),
                    id,
                    sources.isEmpty()
                            ? d
                            : d.withAllocations(d.amount() == null ? null : plan.shares()),
                    sources,
                    AuthorizationContext.requireCurrent().principalId().toString());
            return detail(id);
        }
        if (sources.isEmpty()) throw bad("请选择关联业务订单");
        validateManual(d);
        var saved =
                costs.save(
                        "logistics",
                        id,
                        command(
                                old == null ? null : old.record(),
                                d,
                                sources,
                                "order:logistics-cost:write",
                                first(
                                        d.receivingMethodCode(),
                                        old == null || old.record().data().ledger() == null
                                                ? null
                                                : old.record().data().ledger().receivingMethodCode())));
        return detail(saved.id());
    }

    private Detail saveShipmentFinancials(Detail old, Draft d) {
        var data = old.record().data();
        var ledger = data.ledger();
        var items = d.manualBillItems() == null ? ledger.manualBillItems() : d.manualBillItems();
        BigDecimal amount =
                items == null || items.isEmpty()
                        ? d.amount()
                        : com.rigour.order.domain.cost.LogisticsLedgerRules.manualBillTotal(items);
        if (amount != null
                && (amount.signum() < 0
                        || amount.scale() > 2
                        || amount.compareTo(new BigDecimal("999999999999.99")) > 0))
            throw bad("运费须为非负金额，最多两位小数");
        if (amount != null
                && amount.signum() == 0
                && (d.zeroReason() == null || d.zeroReason().isBlank())) throw bad("请填写零元运费原因");
        String payment = first(d.paymentStatusCode(), data.paymentStatusCode());
        if (!refs.validCodes(tenant(), "COST_PAYMENT_STATUS").contains(payment))
            throw bad("请选择有效付款状态");
        var frozen =
                ledger.sources().stream()
                        .map(
                                link ->
                                        new LogisticsCostSourceView(
                                                        null,
                                                        link.orderCode(),
                                                        link.orderTypeCode(),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null)
                                                .withBusinessSnapshot(
                                                        link.documentAmount(), link.linkedAt()))
                        .toList();
        var plan = TransportAllocationPlan.calculate(amount, frozen, d.orderAllocations());
        var links =
                ledger.sources().stream()
                        .map(
                                link -> {
                                    var a = link.allocations().getFirst();
                                    return new Source(
                                            link.orderTypeCode(),
                                            link.orderCode(),
                                            link.linkRoleCode(),
                                            List.of(
                                                    new Allocation(
                                                            a.feeKey(),
                                                            a.receivingPointCode(),
                                                            a.cityCode(),
                                                            plan.bases().get(link.orderCode()),
                                                            amount == null
                                                                    ? BigDecimal.ZERO
                                                                    : plan.shares()
                                                                            .get(
                                                                                    link
                                                                                            .orderCode()))),
                                            link.noSourceReason(),
                                            link.employeeCode(),
                                            link.employeeName(),
                                            link.departmentId(),
                                            link.departmentName(),
                                            link.sourceWarehouseCode(),
                                            link.documentAmount(),
                                            link.documentDate(),
                                            link.linkedAt());
                                })
                        .toList();
        var f = ledger.feeItems().getFirst();
        ledger =
                ledger.withSources(links)
                        .withFeeItems(
                                List.of(
                                        new Fee(
                                                f.feeKey(),
                                                f.feeTypeCode(),
                                                amount,
                                                f.bearerCode(),
                                                amount,
                                                plan.method(),
                                                d.zeroReason(),
                                                f.remark(),
                                                f.basisUnitCode())))
                        .withManualBillItems(items);
        Long department = data.departmentId();
        String employeeName = old.record().ownerEmployeeName();
        if (!Objects.equals(d.ownerEmployeeCode(), data.ownerEmployeeCode())) {
            var employee =
                    d.ownerEmployeeCode() == null || d.ownerEmployeeCode().isBlank()
                            ? null
                            : refs.employees("order:logistics-cost:write").stream()
                                    .filter(e -> d.ownerEmployeeCode().equals(e.employeeCode()))
                                    .findFirst()
                                    .orElseThrow(() -> bad("费用负责人不存在或无权限"));
            department = employee == null ? null : employee.departmentId();
            employeeName = employee == null ? null : employee.employeeName();
        }
        var command =
                new LogisticsCostCommand(
                        data.requestId(),
                        old.record().revision(),
                        data.title(),
                        data.typeCode(),
                        data.cityCode(),
                        data.cityName(),
                        department,
                        d.ownerEmployeeCode(),
                        data.costDate(),
                        amount,
                        payment,
                        data.logistics(),
                        d.remark(),
                        d.reason(),
                        ledger);
        return detail(costs.saveFinancialSnapshot(old.record(), command, employeeName).id());
    }

    /** 旧客户端获得明确提示，不能继续创建确认状态。 */
    public Detail confirm(String id, LogisticsCostActionCommand action) {
        AuthorizationContext.requirePermission("order:logistics-cost:write");
        throw bad("运输记录保存即生效，无需确认");
    }

    private List<LogisticsCostSourceView> documents(Draft d, String action) {
        var types =
                Map.of(
                        "DELIVERY",
                        "SALES_ORDER",
                        "TRANSFER",
                        "TRANSFER_ORDER",
                        "OTHER",
                        "OTHER_OUT");
        if (d.orderCodes() == null
                || d.orderCodes().size() > 50
                || new HashSet<>(d.orderCodes()).size() != d.orderCodes().size())
            throw bad("关联订单最多50张且不可重复");
        if (d.orderCodes().isEmpty()) return List.of();
        if (!types.containsKey(Objects.toString(d.transportType(), ""))) throw bad("请先选择运输类型");
        if (d.orderCodes().stream()
                .anyMatch(code -> code == null || code.isBlank() || code.length() > 100))
            throw bad("业务单号无效");
        if ("DELIVERY".equals(d.transportType())) {
            var rows = ledgers.salesSources(d.orderCodes(), action, false);
            if (!new HashSet<>(rows.stream().map(LogisticsCostSourceView::documentNo).toList())
                    .equals(new HashSet<>(d.orderCodes()))) throw bad("部分业务单据不存在、状态无效或无权限，请重新核对");
            return rows.stream()
                    .sorted(Comparator.comparing(LogisticsCostSourceView::documentNo))
                    .toList();
        }
        var result = new ArrayList<LogisticsCostSourceView>();
        for (String code : d.orderCodes()) {
            if (code == null || code.length() > 100) throw bad("业务单号无效");
            var q =
                    new LogisticsLedgerSourceQuery(
                            types.get(d.transportType()),
                            code,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            0,
                            true,
                            false);
            result.add(
                    ledgers.sources(q, action).stream()
                            .filter(r -> code.equals(r.documentNo()))
                            .findFirst()
                            .orElseThrow(() -> bad("业务单据不存在或无权限：" + code)));
        }
        return result.stream()
                .sorted(Comparator.comparing(LogisticsCostSourceView::documentNo))
                .toList();
    }

    private LogisticsCostCommand command(
            LogisticsCostDetailView old,
            Draft d,
            List<LogisticsCostSourceView> rows,
            String action,
            String receivingMethod) {
        var billItems =
                d.manualBillItems() == null && old != null && old.data().ledger() != null
                        ? old.data().ledger().manualBillItems()
                        : d.manualBillItems();
        BigDecimal amount =
                billItems == null || billItems.isEmpty()
                        ? d.amount()
                        : com.rigour.order.domain.cost.LogisticsLedgerRules.manualBillTotal(
                                billItems);
        if (rows.isEmpty()) throw bad("请先关联业务订单");
        String city;
        if ("DELIVERY".equals(d.transportType()) || "TRANSFER".equals(d.transportType())) {
            var original = old == null ? null : old.data().ledger();
            var originalCodes = original == null ? Set.<String>of()
                    : original.sources().stream().map(Source::orderCode).collect(java.util.stream.Collectors.toSet());
            boolean sameOrders = original != null
                    && d.transportType().equals(original.transportPurposeCode())
                    && originalCodes.equals(new HashSet<>(d.orderCodes()));
            // 无关字段的修改保留已登记的汇总归属；新增或重新关联时使用业务单据归属。
            city = sameOrders
                    ? first(old.data().cityCode(), rows.getFirst().cityCode())
                    : rows.getFirst().cityCode();
        } else {
            city = first(d.cityCode(), rows.getFirst().cityCode());
        }
        if (city == null) throw bad("请维护业务单据的归属城市后保存");
        String cityName = refs.city(city, action);
        Party sender;
        if (d.warehouseCode() != null && !d.warehouseCode().isBlank()) {
            var w =
                    ledgers
                            .parties("WAREHOUSE", d.warehouseCode(), null, null, null, 0, action)
                            .stream()
                            .filter(p -> d.warehouseCode().equals(p.code()))
                            .findFirst()
                            .orElseThrow(() -> bad("发货仓库不存在或无权限"));
            sender =
                    new Party(
                            "FROM",
                            "WAREHOUSE",
                            w.code(),
                            d.senderName(),
                            d.senderName(),
                            d.senderContact(),
                            d.senderPhone(),
                            d.senderAddress(),
                            w.cityCode());
        } else
            sender =
                    new Party(
                            "FROM",
                            "OTHER",
                            null,
                            d.senderName(),
                            d.senderName(),
                            d.senderContact(),
                            d.senderPhone(),
                            d.senderAddress(),
                            city);
        var points = new ArrayList<Party>();
        var sources = new ArrayList<Source>();
        var allocation = TransportAllocationPlan.calculate(amount, rows, d.orderAllocations());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String point = "TO" + i;
            points.add(
                    new Party(
                            point,
                            "CUSTOMER",
                            row.receiverCode(),
                            null,
                            row.receiverName(),
                            row.contactName(),
                            row.contactPhone(),
                            row.destinationAddress(),
                            row.cityCode()));
            if (!"SALES_ORDER".equals(row.sourceType())) {
                String kind = "TRANSFER_ORDER".equals(row.sourceType()) ? "WAREHOUSE" : "OTHER";
                points.set(
                        i,
                        new Party(
                                point,
                                kind,
                                "WAREHOUSE".equals(kind) ? row.receiverCode() : null,
                                row.receiverName(),
                                row.receiverName(),
                                row.contactName(),
                                row.contactPhone(),
                                row.destinationAddress(),
                                row.cityCode()));
            }
            if (d.receivingPoints() != null) {
                var actual =
                        d.receivingPoints().stream()
                                .filter(p -> point.equals(p.pointCode()))
                                .findFirst()
                                .orElseThrow(() -> bad("实际收货信息与所选订单不一致，请重新核对"));
                if (!Objects.equals(actual.partyCode(), row.receiverCode()))
                    throw bad("实际收货方必须属于关联销售订单客户");
                points.set(
                        i,
                        new Party(
                                point,
                                points.get(i).partyTypeCode(),
                                row.receiverCode(),
                                actual.manualName(),
                                first(actual.manualName(), actual.displayName()),
                                actual.contactName(),
                                actual.phone(),
                                actual.address(),
                                row.cityCode()));
            }
            sources.add(
                    new Source(
                                    row.sourceType(),
                                    row.documentNo(),
                                    "COST",
                                    List.of(
                                            new Allocation(
                                                    "freight",
                                                    point,
                                                    row.cityCode(),
                                                    allocation.bases().get(row.documentNo()),
                                                    Objects.requireNonNullElse(
                                                            allocation
                                                                    .shares()
                                                                    .get(row.documentNo()),
                                                            BigDecimal.ZERO))),
                                    null)
                            .attributed(row));
        }
        Long department = null;
        if (d.ownerEmployeeCode() != null && !d.ownerEmployeeCode().isBlank())
            department =
                    refs.employees(action).stream()
                            .filter(e -> d.ownerEmployeeCode().equals(e.employeeCode()))
                            .findFirst()
                            .orElseThrow(() -> bad("费用负责人不存在"))
                            .departmentId();
        var ledger =
                new LogisticsCostLedger(
                                2,
                                d.transportType(),
                                first(
                                        d.deliveryModeCode(),
                                        "SELF".equals(d.carrierCode()) ? "SELF" : "THIRD_PARTY"),
                                d.carrierCode(),
                                d.date().toString().substring(0, 7),
                                d.trackingNo(),
                                null,
                                sender,
                                points,
                                List.of(
                                        new Fee(
                                                "freight",
                                                "FREIGHT",
                                                amount,
                                                "COMPANY",
                                                amount,
                                                allocation.method(),
                                                d.zeroReason(),
                                                null)),
                                sources,
                                "OTHER".equals(d.transportType())
                                        ? first(d.remark(), "其他订单运输")
                                        : null,
                                "实际无运单号",
                                null,
                                List.of(),
                                List.of())
                        .withPlatformOrderNo(d.platformOrderNo())
                        .withManualBillItems(billItems)
                        .withDeliveryDetails(
                                d.shippingTime() != null
                                        ? d.shippingTime()
                                        : old != null
                                                        && old.data().ledger() != null
                                                        && Objects.equals(
                                                                old.data().costDate(), d.date())
                                                ? old.data().ledger().shippingTime()
                                                : null,
                                receivingMethod);
        return new LogisticsCostCommand(
                old == null ? d.requestId() : old.data().requestId(),
                old == null ? null : old.revision(),
                "物流运输",
                d.transportType(),
                city,
                cityName,
                department,
                d.ownerEmployeeCode(),
                d.date(),
                amount,
                first(
                        d.paymentStatusCode(),
                        old == null ? "UNKNOWN" : old.data().paymentStatusCode()),
                null,
                d.remark(),
                d.reason(),
                ledger);
    }

    private void validateManual(Draft d) {
        String mode =
                first(
                        d.deliveryModeCode(),
                        "SELF".equals(d.carrierCode()) ? "SELF" : "THIRD_PARTY");
        if (!refs.validCodes(tenant(), "COST_DELIVERY_MODE").contains(mode))
            throw bad("请选择启用的配送方式");
        if (!"SELF".equals(mode)) {
            required(d.carrierCode(), 64, "物流公司");
            required(d.platformOrderNo(), 80, "快递单号");
        }
        required(d.senderName(), 100, "实际发货方");
        required(d.senderAddress(), 500, "发货地址");
        phone(d.senderPhone(), false);
        if (d.receivingPoints() == null || d.receivingPoints().isEmpty()) throw bad("请填写实际收货信息");
        if (d.receivingPoints() != null) {
            if (d.receivingPoints().size() != d.orderCodes().size()) throw bad("实际收货信息与关联订单数量不一致");
            for (var point : d.receivingPoints()) {
                required(first(point.manualName(), point.displayName()), 100, "收货单位");
                required(point.contactName(), 100, "收货联系人");
                required(point.address(), 500, "收货地址");
                phone(point.phone(), true);
            }
        }
    }

    private static void required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) throw bad("请填写有效的" + name);
    }

    private static void phone(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw bad("请填写收货联系电话");
            return;
        }
        if (value.length() > 50 || !value.matches("[+0-9()（） \\-#]{5,50}")) throw bad("联系电话格式无效");
    }

    private void validate(Draft d) {
        if (d == null
                || d.date() == null
                || d.remark() != null && d.remark().length() > 1000
                || d.reason() != null && d.reason().length() > 500) throw bad("运输日期、备注或原因无效");
        if (d.shippingTime() != null
                && (!d.shippingTime().toLocalDate().equals(d.date())
                        || d.shippingTime().getNano() != 0)) throw bad("发货时间须与运输日期一致，精确到秒");
        for (String value :
                Arrays.asList(
                        d.carrierCode(),
                        d.warehouseCode(),
                        d.cityCode(),
                        d.ownerEmployeeCode(),
                        d.paymentStatusCode()))
            if (value != null && value.length() > 64) throw bad("档案编码过长");
        if (d.platformOrderNo() != null && d.platformOrderNo().length() > 80) throw bad("第三方订单号过长");
        for (String value : Arrays.asList(d.trackingNo(), d.senderName(), d.senderContact()))
            if (value != null && value.length() > 100) throw bad("单号或名称过长");
        if (d.senderPhone() != null && d.senderPhone().length() > 50
                || d.senderAddress() != null && d.senderAddress().length() > 500)
            throw bad("发货联系方式或地址过长");
        try {
            if (d.manualBillItems() != null && !d.manualBillItems().isEmpty())
                com.rigour.order.domain.cost.LogisticsLedgerRules.manualBillTotal(
                        d.manualBillItems());
            else com.rigour.order.domain.cost.LogisticsCostRules.money(d.amount(), false);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
    }

    private static String first(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
