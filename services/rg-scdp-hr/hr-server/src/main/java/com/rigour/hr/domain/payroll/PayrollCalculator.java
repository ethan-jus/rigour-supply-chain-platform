package com.rigour.hr.domain.payroll;

import java.math.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 月度管理薪酬：规则和来源都必须明确，未知值不能折成零，确认后的结果由快照保存。 */
public final class PayrollCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private PayrollCalculator() {}

    public record Rule(
            String id,
            int revision,
            String name,
            String kind,
            String employee,
            List<Long> departments,
            Long productId,
            Long variantId,
            String unit,
            String recipientRole,
            String method,
            BigDecimal rate,
            BigDecimal amount,
            LocalDate from,
            LocalDate to,
            boolean enabled) {}

    public record Fact(
            String id,
            String recipientRole,
            String orderNo,
            String productName,
            Long productId,
            Long variantId,
            String unit,
            LocalDate date,
            Long departmentId,
            BigDecimal quantity,
            BigDecimal lineAmount,
            BigDecimal orderLineTotal,
            BigDecimal orderAmount,
            BigDecimal paidAmount,
            LocalDate paymentDate,
            boolean refund,
            String quality) {}

    public record Input(
            BigDecimal baseOverride,
            BigDecimal performanceOverride,
            BigDecimal deduction,
            BigDecimal allowanceOverride) {
        public Input(
                BigDecimal baseOverride, BigDecimal performanceOverride, BigDecimal deduction) {
            this(baseOverride, performanceOverride, deduction, null);
        }
    }

    public record Detail(
            String lineId,
            String recipientRole,
            String orderNo,
            String productName,
            String unit,
            BigDecimal quantity,
            String ruleId,
            String ruleName,
            int ruleRevision,
            BigDecimal accrued,
            BigDecimal released,
            String message) {}

    public record Result(
            BigDecimal base,
            BigDecimal performance,
            BigDecimal commission,
            BigDecimal held,
            BigDecimal allowance,
            BigDecimal deduction,
            BigDecimal gross,
            BigDecimal net,
            List<String> issues,
            List<Detail> details) {}

    public static Result calculate(
            String employee,
            Long department,
            YearMonth month,
            LocalDate entry,
            LocalDate leave,
            List<Rule> all,
            Input input,
            List<Fact> facts,
            Set<String> released) {
        List<String> issues = new ArrayList<>();
        List<Detail> details = new ArrayList<>();
        var first = month.atDay(1);
        var last = month.atEndOfMonth();
        List<Rule> active = all.stream().filter(Rule::enabled).toList();
        BigDecimal base = input.baseOverride();
        if (base == null) issues.add("当月底薪待核对，请完善员工薪资档案或填写月度调整");
        BigDecimal performance = input.performanceOverride();
        if (performance == null) {
            var perf =
                    active.stream()
                            .filter(
                                    r ->
                                            "PERFORMANCE".equals(r.kind())
                                                    && r.departments().contains(department)
                                                    && overlap(r, first, last))
                            .toList();
            if (perf.size() != 1 || !covers(perf.getFirst(), first, last))
                issues.add("绩效规则缺失、冲突或月内调整，请填写当月绩效");
            else performance = perf.getFirst().amount();
        }
        BigDecimal allowance = ZERO, commission = ZERO, held = ZERO;
        if (input.allowanceOverride() != null) allowance = input.allowanceOverride();
        else
            for (var r : active)
                if ("ALLOWANCE".equals(r.kind())
                        && r.departments().contains(department)
                        && overlap(r, first, last)) {
                    if (!covers(r, first, last)) issues.add("补贴规则未覆盖整月：" + r.name());
                    else allowance = allowance.add(r.amount());
                }
        var commissionRules = active.stream().filter(r -> "COMMISSION".equals(r.kind())).toList();
        for (var f : facts) {
            if (released.contains(f.id() + "|" + f.recipientRole())) continue;
            var candidates =
                    commissionRules.stream()
                            .filter(
                                    r ->
                                            Objects.equals(r.recipientRole(), f.recipientRole())
                                                    && Objects.equals(r.productId(), f.productId())
                                                    && Objects.equals(r.variantId(), f.variantId())
                                                    && !f.date().isBefore(r.from())
                                                    && !f.date().isAfter(r.to()))
                            .toList();
            if (candidates.isEmpty()) continue; // 未设置提成规则的商品不计提。
            String issue = null;
            Rule rule = null;
            if (f.departmentId() == null) issue = "订单发生时的部门归属待核对";
            else {
                var matches =
                        candidates.stream()
                                .filter(r -> r.departments().contains(f.departmentId()))
                                .toList();
                if (matches.isEmpty()) continue;
                if (matches.size() != 1) issue = "商品提成规则冲突";
                else rule = matches.getFirst();
            }
            if (f.refund()) issue = "存在退款，需核对净销售与冲回";
            if (!"COMPLETE".equals(f.quality())) issue = "订单资料待完善";
            // 金额比例不依赖箱/桶等计量单位；按数量计奖必须保留订单单位校验。
            if (rule != null
                    && "QUANTITY".equals(rule.method())
                    && !Objects.equals(rule.unit(), f.unit())) issue = "商品计奖单位不一致";
            if (f.quantity() == null
                    || f.quantity().signum() < 0
                    || f.lineAmount() == null
                    || f.lineAmount().signum() < 0) issue = "订单数量或金额异常";
            BigDecimal accrued = null, releasedAmount = ZERO;
            if (issue == null && rule != null) {
                if ("QUANTITY".equals(rule.method()))
                    accrued = money(f.quantity().multiply(rule.rate()));
                else if ("PERCENT".equals(rule.method())) {
                    if (f.orderLineTotal() == null
                            || f.orderLineTotal().signum() <= 0
                            || f.orderAmount() == null
                            || f.orderAmount().signum() < 0) issue = "订单净额分配依据缺失";
                    else
                        accrued =
                                money(
                                        f.lineAmount()
                                                .multiply(f.orderAmount())
                                                .divide(
                                                        f.orderLineTotal(),
                                                        12,
                                                        RoundingMode.HALF_UP)
                                                .multiply(rule.rate())
                                                .divide(
                                                        new BigDecimal("100"),
                                                        12,
                                                        RoundingMode.HALF_UP));
                } else issue = "未支持的提成计算方式";
                if (issue == null) {
                    boolean paid =
                            f.orderAmount() != null
                                    && f.orderAmount().signum() > 0
                                    && f.paidAmount() != null
                                    && f.paidAmount().compareTo(f.orderAmount()) >= 0
                                    && f.paymentDate() != null
                                    && !f.paymentDate().isAfter(last);
                    if (paid) {
                        releasedAmount = accrued;
                        commission = commission.add(accrued);
                    } else held = held.add(accrued);
                }
            }
            if (issue != null) issues.add(f.orderNo() + "：" + issue);
            details.add(
                    new Detail(
                            f.id(),
                            f.recipientRole(),
                            f.orderNo(),
                            f.productName(),
                            f.unit(),
                            f.quantity(),
                            rule == null ? null : rule.id(),
                            rule == null ? null : rule.name(),
                            rule == null ? 0 : rule.revision(),
                            accrued,
                            releasedAmount,
                            issue == null
                                    ? (releasedAmount.signum() > 0 ? "全额回款，可发放" : "待全额回款")
                                    : issue));
        }
        BigDecimal deduction = input.deduction() == null ? ZERO : input.deduction();
        BigDecimal gross =
                base == null || performance == null
                        ? null
                        : money(base.add(performance).add(commission).add(allowance));
        BigDecimal net = gross == null ? null : money(gross.subtract(deduction));
        if (net != null && net.signum() < 0) issues.add("扣款超过应发金额，请核对");
        return new Result(
                base,
                performance,
                commission,
                held,
                allowance,
                deduction,
                gross,
                net,
                List.copyOf(new LinkedHashSet<>(issues)),
                List.copyOf(details));
    }

    private static boolean overlap(Rule r, LocalDate a, LocalDate b) {
        return !r.to().isBefore(a) && !r.from().isAfter(b);
    }

    private static boolean covers(Rule r, LocalDate a, LocalDate b) {
        return !r.from().isAfter(a) && !r.to().isBefore(b);
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
