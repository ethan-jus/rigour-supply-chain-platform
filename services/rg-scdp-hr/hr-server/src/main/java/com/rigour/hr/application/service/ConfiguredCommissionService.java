package com.rigour.hr.application.service;

import com.rigour.erp.api.v1.model.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.domain.payroll.SalarySchemeCalculator;
import com.rigour.order.api.v1.model.OrderPayrollLineView;

import org.springframework.stereotype.Service;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfiguredCommissionService {
    private final HrPayrollStore payroll;
    private final HrPayrollSource source;
    private PayrollRuleResolver rules;

    @org.springframework.beans.factory.annotation.Autowired
    void setRules(PayrollRuleResolver rules) {
        this.rules = rules;
    }

    public ConfiguredCommissionService(HrPayrollStore payroll, HrPayrollSource source) {
        this.payroll = payroll;
        this.source = source;
    }

    ConfiguredCommissionResult calculate(
            String tenant,
            PayrollEmployeeView employee,
            YearMonth month,
            PayrollSchemeVersionView selected,
            List<PayrollSchemeVersionView> versions,
            List<OrderPayrollLineView> facts,
            List<PayrollRuleView> assignments) {
        List<String> issues = new ArrayList<>();
        List<PayrollCommissionDetailView> details = new ArrayList<>();
        var released =
                payroll.releasedForCalculation(tenant, employee.employeeCode(), month.toString());
        // 只有已整单付清的订单进入计提，未付清商品也不要求提前匹配经济标准。
        facts =
                facts.stream()
                        .filter(
                                f ->
                                        f.fullyPaidDate() != null
                                                && YearMonth.from(f.fullyPaidDate()).equals(month))
                        .toList();
        if (facts.isEmpty())
            return new ConfiguredCommissionResult(
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of());
        var variants =
                facts.stream()
                        .map(OrderPayrollLineView::productVariantId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, ErpPayrollProductView> products = new HashMap<>();
        for (int i = 0; i < variants.size(); i += 100)
            source.productsByVariants(
                            tenant, variants.subList(i, Math.min(i + 100, variants.size())))
                    .forEach(p -> products.put(p.productVariantId(), p));
        var categories =
                source.productCategories(tenant).stream()
                        .collect(Collectors.toMap(ErpPayrollCategoryView::id, c -> c));
        for (var fact : facts) {
            if (Objects.equals(employee.employeeCode(), fact.employeeCode())) {
                var execution =
                        execution(
                                tenant,
                                employee.employeeCode(),
                                fact.departmentId() != null
                                        ? fact.departmentId()
                                        : payroll.departmentAt(
                                                tenant, employee.employeeCode(), fact.orderDate()),
                                fact.orderDate(),
                                selected,
                                versions,
                                issues,
                                fact.orderNo());
                add(
                        tenant,
                        employee,
                        month,
                        "SALES",
                        fact,
                        execution,
                        versions,
                        products,
                        categories,
                        released,
                        details,
                        issues);
            }
            var atOrder = assignmentsAt(assignments, fact.cityCode(), fact.orderDate());
            if (atOrder.size() > 1) {
                issues.add(fact.orderNo() + "：订单日城市总任职重叠");
                continue;
            }
            if (atOrder.isEmpty()) continue;
            String original = atOrder.getFirst().data().employeeCode();
            var execution =
                    execution(
                            tenant,
                            original,
                            payroll.departmentAt(tenant, original, fact.orderDate()),
                            fact.orderDate(),
                            selected,
                            versions,
                            issues,
                            fact.orderNo());
            if (execution == null) {
                if (original.equals(employee.employeeCode())) {
                    issues.add(fact.orderNo() + "：订单发生时城市总提成规则未配置，需核定存量订单政策");
                    pending(fact, "CITY_MANAGER", "订单发生时城市总提成规则未配置", details);
                }
                continue;
            }
            var date =
                    "FULLY_PAID_DATE"
                                    .equals(
                                            execution
                                                    .definition()
                                                    .commission()
                                                    .managerAttribution())
                            ? fact.fullyPaidDate()
                            : fact.orderDate();
            if (date == null) continue;
            var recipients = assignmentsAt(assignments, fact.cityCode(), date);
            if (recipients.size() != 1) {
                issues.add(fact.orderNo() + "：归属时点城市总任职缺失或重叠");
                continue;
            }
            if (employee.employeeCode().equals(recipients.getFirst().data().employeeCode()))
                add(
                        tenant,
                        employee,
                        month,
                        "CITY_MANAGER",
                        fact,
                        execution,
                        versions,
                        products,
                        categories,
                        released,
                        details,
                        issues);
        }
        var metadata =
                rules == null ? Map.<String, PayrollAppliedRuleView>of() : rules.metadata(tenant);
        var byLine =
                facts.stream()
                        .collect(
                                Collectors.toMap(
                                        OrderPayrollLineView::lineId, f -> f, (a, b) -> a));
        details.replaceAll(
                d -> {
                    var t = d.trace();
                    var p = products.get(t.variantId());
                    var product =
                            p == null
                                    ? null
                                    : new PayrollProductSnapshotView(
                                            p.productId(),
                                            p.productVariantId(),
                                            p.productCode(),
                                            p.variantCode(),
                                            p.specification(),
                                            p.categoryId(),
                                            p.categoryName(),
                                            categoryPath(p.categoryId(), categories));
                    var trace =
                            new PayrollCommissionTraceView(
                                    t.orderId(),
                                    t.cityCode(),
                                    t.variantId(),
                                    t.baseAmount(),
                                    t.rate(),
                                    t.method(),
                                    t.fullyPaidDate(),
                                    t.refundedAmount(),
                                    t.ruleCode(),
                                    t.schemeVersionId(),
                                    t.originalLineId(),
                                    product,
                                    t.schemeVersionId() == null
                                            ? null
                                            : metadata.get(t.schemeVersionId()),
                                    byLine.get(d.sourceLineId()).orderDate(),
                                    new com.rigour.hr.api.v1.model.PayrollOrderSettlementView(
                                            byLine.get(d.sourceLineId()).orderAmount(),
                                            byLine.get(d.sourceLineId()).paidAmount(),
                                            byLine.get(d.sourceLineId()).refundedAmount(),
                                            month.atEndOfMonth()),
                                    // Fully-paid goods receipts are allocated by line value, even
                                    // when the commission itself is based on quantity.
                                    baseAmount(
                                            byLine.get(d.sourceLineId()), null, new ArrayList<>()));
                    return new PayrollCommissionDetailView(
                            d.sourceLineId(),
                            d.recipientRoleCode(),
                            d.orderNo(),
                            d.productName(),
                            d.unitCode(),
                            d.quantity(),
                            d.ruleId(),
                            d.ruleName(),
                            d.ruleRevision(),
                            d.accrued(),
                            d.released(),
                            d.message(),
                            d.sourceEmployeeCode(),
                            trace);
                });
        return new ConfiguredCommissionResult(
                SalarySchemeCalculator.money(
                        details.stream()
                                .map(PayrollCommissionDetailView::released)
                                .filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                BigDecimal.ZERO,
                List.copyOf(details),
                List.copyOf(new LinkedHashSet<>(issues)));
    }

    private PayrollSchemeVersionView execution(
            String tenant,
            String employee,
            Long department,
            LocalDate date,
            PayrollSchemeVersionView template,
            List<PayrollSchemeVersionView> versions,
            List<String> issues,
            String orderNo) {
        if (rules == null || !"RESOLVED".equals(template.status()))
            return PayrollSchemeSelector.select(versions, employee, department, date).orElse(null);
        try {
            var row = rules.commission(tenant, department, date).orElse(null);
            if (row == null) return null;
            var d = template.definition();
            var c = row.definition();
            var snapshot =
                    new PayrollSchemeDefinition(
                            c.name(),
                            d.role(),
                            List.of(),
                            List.of(employee),
                            c.effectiveFrom(),
                            c.effectiveTo(),
                            d.baseSalary(),
                            d.performanceBase(),
                            d.performanceMode(),
                            d.sales(),
                            d.manager(),
                            d.calendar(),
                            d.proration(),
                            d.allowances(),
                            c.commission(),
                            "按提成规则生效日期匹配",
                            new PayrollRuleReferences(null, null, row.id(), null, null));
            return new PayrollSchemeVersionView(
                    row.id(),
                    row.ruleId(),
                    row.version(),
                    row.version(),
                    "RESOLVED",
                    snapshot,
                    row.reason(),
                    row.createdBy(),
                    null,
                    row.createdTime(),
                    null);
        } catch (IllegalArgumentException conflict) {
            issues.add(orderNo + "：" + conflict.getMessage());
            return null;
        }
    }

    private void add(
            String tenant,
            PayrollEmployeeView employee,
            YearMonth month,
            String role,
            OrderPayrollLineView f,
            PayrollSchemeVersionView execution,
            List<PayrollSchemeVersionView> versions,
            Map<Long, ErpPayrollProductView> products,
            Map<Long, ErpPayrollCategoryView> categories,
            Set<String> released,
            List<PayrollCommissionDetailView> details,
            List<String> issues) {
        int before = details.size(), issueCount = issues.size();
        addResolved(
                tenant,
                employee,
                month,
                role,
                f,
                execution,
                versions,
                products,
                categories,
                released,
                details,
                issues);
        if (details.size() == before && issues.size() > issueCount)
            pending(f, role, String.join("；", issues.subList(issueCount, issues.size())), details);
    }

    private static void pending(
            OrderPayrollLineView f,
            String role,
            String message,
            List<PayrollCommissionDetailView> details) {
        details.add(
                new PayrollCommissionDetailView(
                        f.lineId(),
                        role,
                        f.orderNo(),
                        f.productName(),
                        f.unitCode(),
                        f.quantity(),
                        null,
                        null,
                        0,
                        null,
                        null,
                        message,
                        f.employeeCode(),
                        new PayrollCommissionTraceView(
                                f.orderId(),
                                f.cityCode(),
                                f.productVariantId(),
                                null,
                                null,
                                null,
                                f.fullyPaidDate(),
                                f.refundedAmount(),
                                null,
                                null,
                                f.lineId())));
    }

    private static String categoryPath(Long id, Map<Long, ErpPayrollCategoryView> categories) {
        var names = new ArrayList<String>();
        var visited = new HashSet<Long>();
        while (id != null && visited.add(id)) {
            var category = categories.get(id);
            if (category == null) break;
            names.addFirst(category.name());
            id = category.parentId();
        }
        return String.join(" / ", names);
    }

    private void addResolved(
            String tenant,
            PayrollEmployeeView employee,
            YearMonth month,
            String role,
            OrderPayrollLineView f,
            PayrollSchemeVersionView execution,
            List<PayrollSchemeVersionView> versions,
            Map<Long, ErpPayrollProductView> products,
            Map<Long, ErpPayrollCategoryView> categories,
            Set<String> released,
            List<PayrollCommissionDetailView> details,
            List<String> issues) {
        if (released.contains(f.lineId() + "|" + role)) return;
        if (execution == null) {
            issues.add(f.orderNo() + "：历史提成规则待配置");
            return;
        }
        var policy = execution.definition().commission();
        LocalDate standardDate =
                "FULLY_PAID_DATE".equals(policy.standardDate()) ? f.fullyPaidDate() : f.orderDate();
        if (standardDate == null) return;
        // Match the beneficiary policy owner, not a team salesperson whose HR history may differ.
        var standard =
                rules != null && "RESOLVED".equals(execution.status())
                        ? execution(
                                tenant,
                                execution.definition().employeeCodes().getFirst(),
                                Objects.equals(
                                                        execution
                                                                .definition()
                                                                .employeeCodes()
                                                                .getFirst(),
                                                        f.employeeCode())
                                                && standardDate.equals(f.orderDate())
                                                && f.departmentId() != null
                                        ? f.departmentId()
                                        : payroll.departmentAt(
                                                tenant,
                                                execution.definition().employeeCodes().getFirst(),
                                                standardDate),
                                standardDate,
                                execution,
                                versions,
                                issues,
                                f.orderNo())
                        : PayrollSchemeSelector.standard(versions, execution, standardDate);
        if (standard == null) {
            issues.add(f.orderNo() + "：标准日期未匹配提成规则");
            return;
        }
        if (standard.definition().commission().rules().stream()
                .noneMatch(r -> role.equals(r.recipientRole()))) return;
        var product = products.get(f.productVariantId());
        if (product == null) {
            issues.add(f.orderNo() + "：商品未关联ERP规格");
            return;
        }
        List<PayrollSchemeCommissionRule> candidates = new ArrayList<>();
        int best = Integer.MAX_VALUE;
        boolean explicitlyExcluded = false;
        boolean unmatchedUnit = false;
        for (var rule : standard.definition().commission().rules()) {
            if (!role.equals(rule.recipientRole())) continue;
            int priority;
            if (!rule.variantIds().isEmpty()) {
                // Selected models restrict this row; its category must not act as a fallback.
                if (!rule.variantIds().contains(f.productVariantId())) continue;
                priority = 0;
            } else {
                int categoryDistance =
                        distance(product.categoryId(), rule.categoryIds(), categories);
                if (categoryDistance < 0) continue;
                priority = categoryDistance + 1;
            }
            if ("QUANTITY".equals(rule.method())
                    && (!rule.excluded() || rule.unitCode() != null && !rule.unitCode().isBlank())
                    && !Objects.equals(rule.unitCode(), f.unitCode())) {
                unmatchedUnit = true;
                continue;
            }
            if (rule.excludedVariantIds().contains(f.productVariantId())
                    || distance(product.categoryId(), rule.excludedCategoryIds(), categories)
                            >= 0) {
                explicitlyExcluded = true;
                continue;
            }
            if (priority < best) {
                candidates.clear();
                best = priority;
            }
            if (priority == best) candidates.add(rule);
        }
        // An explicit exception is a configured zero, not a missing commission standard.
        // A separate applicable SKU standard can still take precedence over a category exception.
        if (candidates.isEmpty() && explicitlyExcluded) return;
        if (candidates.size() != 1) {
            issues.add(
                    f.orderNo()
                            + " / "
                            + f.productName()
                            + "："
                            + (candidates.isEmpty()
                                    ? unmatchedUnit ? "计奖单位未匹配，请维护该订单单位的提成标准" : "提成标准未配置"
                                    : "同级商品规则冲突"));
            return;
        }
        var rule = candidates.getFirst();
        if (rule.excluded()) return;
        if (!"COMPLETE".equals(f.qualityStatus())) {
            issues.add(f.orderNo() + "：订单资料待核对");
            return;
        }
        if (f.refundedAmount() != null && f.refundedAmount().signum() > 0) {
            issues.add(f.orderNo() + "：存在退款，请通过工资更正按原人原标准核定");
            return;
        }
        var economic = standard.definition().commission();
        BigDecimal base;
        if ("QUANTITY".equals(rule.method())) {
            if (!Objects.equals(rule.unitCode(), f.unitCode()) || f.quantity() == null) {
                issues.add(f.orderNo() + "：计奖单位或数量待核对");
                return;
            }
            base = f.quantity();
        } else {
            base = baseAmount(f, economic, issues);
            if (base == null) return;
        }
        BigDecimal amount =
                SalarySchemeCalculator.money(
                        base.multiply(rule.value())
                                .divide(
                                        "PERCENT".equals(rule.method())
                                                ? new BigDecimal("100")
                                                : BigDecimal.ONE));
        BigDecimal pay =
                f.fullyPaidDate() != null && !f.fullyPaidDate().isAfter(month.atEndOfMonth())
                        ? amount
                        : BigDecimal.ZERO;
        var trace =
                new PayrollCommissionTraceView(
                        f.orderId(),
                        f.cityCode(),
                        f.productVariantId(),
                        base,
                        rule.value(),
                        rule.method(),
                        f.fullyPaidDate(),
                        f.refundedAmount(),
                        rule.code(),
                        standard.id(),
                        f.lineId());
        String name =
                f.productName()
                        + (product.specification() == null ? "" : " / " + product.specification());
        details.add(
                new PayrollCommissionDetailView(
                        f.lineId(),
                        role,
                        f.orderNo(),
                        name,
                        f.unitCode(),
                        f.quantity(),
                        standard.id() + ":" + rule.code(),
                        rule.name(),
                        standard.version(),
                        amount,
                        pay,
                        "规则 "
                                + standard.definition().name()
                                + " v"
                                + standard.version()
                                + "；"
                                + (f.fullyPaidDate() == null
                                        ? "整单未付清，暂不发放"
                                        : "整单付清 " + f.fullyPaidDate()),
                        f.employeeCode(),
                        trace));
    }

    static List<PayrollRuleView> assignmentsAt(
            List<PayrollRuleView> assignments, String city, LocalDate date) {
        return assignments.stream()
                .filter(
                        r ->
                                Objects.equals(city, r.data().cityCode())
                                        && !date.isBefore(r.data().effectiveFrom())
                                        && !date.isAfter(r.data().effectiveTo()))
                .toList();
    }

    private static int distance(
            Long category, List<Long> selected, Map<Long, ErpPayrollCategoryView> categories) {
        Set<Long> visited = new HashSet<>();
        int depth = 0;
        while (category != null && visited.add(category)) {
            if (selected.contains(category)) return depth;
            var node = categories.get(category);
            category = node == null ? null : node.parentId();
            depth++;
        }
        return -1;
    }

    static BigDecimal baseAmount(
            OrderPayrollLineView f, PayrollSchemeCommissionPolicy p, List<String> issues) {
        if (f.orderAmount() == null
                || f.orderLineTotal() == null
                || f.orderLineTotal().signum() <= 0
                || f.lineAmount() == null
                || f.orderAmount().signum() < 0
                || f.lineAmount().signum() < 0) {
            issues.add(f.orderNo() + "：商品回款分摊依据缺失");
            return null;
        }
        return f.orderAmount()
                .multiply(f.lineAmount())
                .divide(f.orderLineTotal(), 12, RoundingMode.HALF_UP);
    }
}
