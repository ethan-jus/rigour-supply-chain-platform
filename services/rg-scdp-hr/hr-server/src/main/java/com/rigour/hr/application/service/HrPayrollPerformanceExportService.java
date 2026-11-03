package com.rigour.hr.application.service;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.TargetSettingsStore;
import com.rigour.order.api.v1.model.OrderPayrollLineView;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 业绩报表读取领域 API，不用提成明细替代销售或整单付清业绩，不保存、重算工资。 */
@Service
public final class HrPayrollPerformanceExportService {
    private final HrPayrollWorkflowService workflow;
    private final HrPayrollSource source;
    private final TargetSettingsStore targets;
    private final com.rigour.hr.application.port.out.HrPayrollStore payroll;
    private final com.rigour.hr.application.port.out.HrAttendanceStore attendance;
    public HrPayrollPerformanceExportService(HrPayrollWorkflowService workflow, HrPayrollSource source, TargetSettingsStore targets,
            com.rigour.hr.application.port.out.HrPayrollStore payroll,
            com.rigour.hr.application.port.out.HrAttendanceStore attendance) {
        this.workflow = workflow;
        this.source = source;
        this.targets = targets;
        this.payroll = payroll;
        this.attendance = attendance;
    }

    public List<PayrollPerformanceExportView> export(PayrollQuery query) {
        var actor = HrPayrollService.actor("hr:payroll:read");
        var rows = workflow.export(query); // Includes tenant/data-scope and exact selected-ID checks.
        if (rows.isEmpty()) return List.of();
        var tenant = actor.tenantId().toString();
        var categories = source.productCategories(tenant).stream()
                .collect(Collectors.toMap(ErpPayrollCategoryView::id, Function.identity()));
        var products = new HashMap<Long, ErpPayrollProductView>();
        var allTargets = targets.targets(tenant, query.month(), query.month());
        var subjects = targets.subjects(tenant);
        var monthlyTargets = allTargets.stream()
                .filter(t -> "SALES_OWNER".equals(t.dimensionType()))
                .collect(Collectors.toMap(t -> t.code() + ":" + t.metric(), TargetSettingsModels.Target::value));
        var result = new ArrayList<PayrollPerformanceExportView>();
        for (var row : rows) {
            var month = YearMonth.parse(row.month());
            var lines = source.payrollLines(tenant, row.employeeCode(), null, month.atEndOfMonth());
            if (lines.size() >= 20001) throw HrPayrollService.bad("订单来源超过完整读取上限，请缩小导出范围");
            var missing = lines.stream().map(OrderPayrollLineView::productVariantId)
                    .filter(Objects::nonNull).filter(id -> !products.containsKey(id)).distinct().toList();
            for (int begin = 0; begin < missing.size(); begin += 100)
                source.productsByVariants(tenant, missing.subList(begin, Math.min(begin + 100, missing.size())))
                        .forEach(p -> products.put(p.productVariantId(), p));
            var report = summarize(row, lines, products, categories,
                    row.calculation().performanceDetail() != null && row.calculation().performanceDetail().collectionTarget() != null
                            ? row.calculation().performanceDetail().collectionTarget()
                            : monthlyTargets.get(row.employeeCode() + ":FULLY_PAID_RECEIPT_BASIS"),
                    monthlyTargets.get(row.employeeCode() + ":SALES_AMOUNT"));
            var employee = payroll.employee(tenant, row.employeeCode(), "hr:payroll:read");
            var confirmed = attendance.employee(tenant, month, row.employeeCode(), "hr:payroll:read").confirmation();
            result.add(PayrollPerformanceReportFacts.enrich(row, report, employee, confirmed, allTargets, subjects));
        }
        return List.copyOf(result);
    }

    static PayrollPerformanceExportView summarize(PayrollDetailView row, List<OrderPayrollLineView> lines,
            Map<Long, ErpPayrollProductView> products,
            Map<Long, ErpPayrollCategoryView> categories, BigDecimal collectionTarget, BigDecimal salesTarget) {
        var month = YearMonth.parse(row.month());
        var issues = new LinkedHashSet<String>();
        var buckets = new LinkedHashMap<String, Amounts>();
        for (String code : List.of("NOODLES", "ACCESSORIES", "CLOTH", "OTHER")) buckets.put(code, new Amounts());
        var cloth = new LinkedHashMap<String, BigDecimal>();
        var quantities = new LinkedHashMap<String, Map<String, BigDecimal>>();
        for (String code : List.of("A300_A500", "A600_A800", "SNOOKER")) cloth.put(code, BigDecimal.ZERO);
        for (String code : cloth.keySet()) quantities.put(code, new TreeMap<>());
        var byOrder = lines.stream().collect(Collectors.groupingBy(OrderPayrollLineView::orderNo, LinkedHashMap::new, Collectors.toList()));
        BigDecimal totalReceipts = BigDecimal.ZERO, refunds = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;
        var seenLines = new HashSet<String>();
        for (var entry : byOrder.entrySet()) {
            var orderLines = entry.getValue();
            var first = orderLines.getFirst();
            boolean soldThisMonth = first.orderDate() != null && YearMonth.from(first.orderDate()).equals(month);
            boolean paidThisMonth = first.fullyPaidDate() != null && YearMonth.from(first.fullyPaidDate()).equals(month);
            if (!soldThisMonth && !paidThisMonth) continue;
            for (var line : orderLines) {
                if (!seenLines.add(line.lineId())) throw HrPayrollService.bad("销售订单行重复，无法完整导出");
                if (!row.employeeCode().equals(line.employeeCode())) throw HrPayrollService.bad("订单销售归属不一致");
                if (!"COMPLETE".equals(line.qualityStatus())) throw HrPayrollService.bad("订单资料待核定，无法完整导出业绩汇总");
                if (line.lineAmount() == null || line.lineAmount().signum() < 0)
                    throw HrPayrollService.bad("商品行金额缺失或异常，请先核对订单");
            }
            var weights = orderLines.stream().map(OrderPayrollLineView::lineAmount).toList();
            var sales = soldThisMonth ? allocate(first.orderAmount(), weights) : Collections.nCopies(orderLines.size(), BigDecimal.ZERO);
            var paid = allocate(paidThisMonth ? first.orderAmount() : BigDecimal.ZERO, weights);
            for (int i = 0; i < orderLines.size(); i++) {
                var line = orderLines.get(i);
                var product = products.get(line.productVariantId());
                String category = category(product == null ? null : product.categoryId(), categories);
                var bucket = buckets.get(category);
                bucket.sales = bucket.sales.add(sales.get(i));
                bucket.receipts = bucket.receipts.add(paid.get(i));
                if ("CLOTH".equals(category) && soldThisMonth) {
                    String group = clothGroup(product == null ? line.productName() : product.productName(), product == null ? null : product.specification());
                    if (group != null) {
                        cloth.merge(group, sales.get(i), BigDecimal::add);
                        if (line.quantity() == null || line.quantity().signum() < 0 || line.unitCode() == null || line.unitCode().isBlank())
                            throw HrPayrollService.bad("台呢订单数量或单位缺失，不能以销售额代替销量");
                        quantities.get(group).merge(line.unitCode(), line.quantity(), BigDecimal::add);
                    }
                    else if (sales.get(i).signum() != 0) issues.add("台呢含其他型号，销售总额包含该部分");
                }
                if (product == null) issues.add("部分商品未关联ERP分类，计入其他品类");
            }
            if (soldThisMonth) totalSales = totalSales.add(first.orderAmount());
            if (paidThisMonth) {
                totalReceipts = totalReceipts.add(first.orderAmount());
                if (first.refundedAmount() != null) refunds = refunds.add(first.refundedAmount());
            }
        }
        for (var detail : row.calculation().details()) {
            var product = detail.trace() == null ? null : detail.trace().product();
            String code = product == null ? "OTHER" : category(product.categoryId(), categories);
            var amount = buckets.get(code);
            if (detail.released() == null) amount.commissionPending = true;
            else amount.commission = amount.commission.add(detail.released());
        }
        if (row.calculation().commission() == null) issues.add("提成待核对，详见核对事项");
        else {
            var detailsTotal = buckets.values().stream().map(a -> a.commission).reduce(BigDecimal.ZERO, BigDecimal::add);
            var adjustment = row.calculation().commission().subtract(detailsTotal);
            if (adjustment.signum() != 0) issues.add("提成调整 " + money(adjustment).toPlainString() + " 元，已计入总提成，未分摊到品类");
        }
        if (buckets.get("OTHER").sales.signum() != 0 || buckets.get("OTHER").receipts.signum() != 0)
            issues.add("其他品类销售及回款包含在总额中，详见品类业绩明细");
        if (refunds.signum() != 0) issues.add("本期付清订单累计退款 " + money(refunds).toPlainString() + " 元，业绩影响待核定");
        if (collectionTarget == null) issues.add("回款指标未配置");
        // 普通销售按入职阶段考核回款；未单独设置销售目标不等于薪酬规则缺失。
        return new PayrollPerformanceExportView(row.id(), row.revision(), row.month(), row.employeeCode(), row.employeeName(), row.cityName(),
                buckets.entrySet().stream().map(e -> new PayrollPerformanceExportView.Category(e.getKey(), categoryName(e.getKey()),
                        money(e.getValue().sales), money(e.getValue().receipts), e.getValue().commissionPending ? null : money(e.getValue().commission))).toList(),
                cloth.entrySet().stream().map(e -> new PayrollPerformanceExportView.ClothSales(e.getKey(), clothName(e.getKey()), money(e.getValue()),
                        quantities.get(e.getKey()).entrySet().stream().map(q -> new PayrollPerformanceExportView.UnitQuantity(q.getKey(), q.getValue())).toList())).toList(),
                money(totalSales), money(totalReceipts), money(refunds), collectionTarget, salesTarget, row.calculation().commission(), List.copyOf(issues),
                null, null, null, null, null, List.of(), null);
    }

    /** Cumulative cent allocation preserves each order/receipt total, including small amounts. */
    static List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        if (amount == null) throw HrPayrollService.bad("订单金额缺失，无法汇总销售额");
        var total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            if (amount.signum() != 0) throw HrPayrollService.bad("订单商品金额为零，无法分摊回款或整单折扣");
            return Collections.nCopies(weights.size(), BigDecimal.ZERO);
        }
        var result = new ArrayList<BigDecimal>();
        BigDecimal cumulative = BigDecimal.ZERO, previous = BigDecimal.ZERO;
        for (var weight : weights) {
            cumulative = cumulative.add(weight);
            var current = amount.multiply(cumulative).divide(total, 2, RoundingMode.HALF_UP);
            result.add(current.subtract(previous));
            previous = current;
        }
        return result;
    }

    static String category(Long id, Map<Long, ErpPayrollCategoryView> categories) {
        var names = new HashSet<String>();
        var visited = new HashSet<Long>();
        while (id != null && visited.add(id)) {
            var category = categories.get(id);
            if (category == null) break;
            names.add(category.name()); id = category.parentId();
        }
        if (names.contains("台呢")) return "CLOTH";
        if (names.contains("方便面")) return "NOODLES";
        if (names.contains("台球周边")) return "ACCESSORIES";
        return "OTHER";
    }

    static String clothGroup(String name, String specification) {
        String value = ((name == null ? "" : name) + " " + (specification == null ? "" : specification)).toUpperCase(Locale.ROOT);
        if (value.contains("斯诺克")) return "SNOOKER";
        if (value.matches(".*\\bA(?:300|500B?)\\b.*")) return "A300_A500";
        if (value.matches(".*\\bA(?:600|700|800)\\b.*")) return "A600_A800";
        return null;
    }
    static String categoryName(String code) { return switch (code) { case "NOODLES" -> "方便面"; case "ACCESSORIES" -> "台球周边"; case "CLOTH" -> "台呢"; default -> "其他品类"; }; }
    static String clothName(String code) { return switch (code) { case "A300_A500" -> "A300/A500B"; case "A600_A800" -> "A600/A700/A800"; default -> "澳洋斯诺克"; }; }
    static BigDecimal money(BigDecimal n) { return n.setScale(2, RoundingMode.HALF_UP); }
    private static final class Amounts {
        BigDecimal sales = BigDecimal.ZERO, receipts = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        boolean commissionPending;
    }
}
