package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;

import java.math.BigDecimal;
import java.util.*;

/** 草稿可不完整；只有通过完整校验的方案才能发布或试算。 */
final class PayrollSchemeValidator {
    private PayrollSchemeValidator() {}

    static PayrollSchemeCheckView check(PayrollSchemeDefinition d) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (d == null) return new PayrollSchemeCheckView(List.of("方案内容不能为空"), List.of());
        if (hasNullRows(d)) return new PayrollSchemeCheckView(List.of("配置列表不能包含空项目"), List.of());
        if (d.name() == null || d.name().isBlank() || d.name().length() > 120)
            errors.add("方案名称须为1至120字");
        choice(
                d.role(),
                Set.of("SALES", "CITY_MANAGER", "TRAINEE_MANAGER", "HEADQUARTERS"),
                "人员身份",
                errors);
        if (empty(d.departmentIds()) && empty(d.employeeCodes())) errors.add("请选择适用部门或员工");
        if (!empty(d.departmentIds()) && !empty(d.employeeCodes())) errors.add("部门方案和员工专项方案请分别建立");
        if (d.effectiveFrom() == null
                || d.effectiveTo() == null
                || d.effectiveFrom().isAfter(d.effectiveTo())) errors.add("生效期间无效");
        else if (d.effectiveFrom().getDayOfMonth() != 1
                || d.effectiveTo().getDayOfMonth() != d.effectiveTo().lengthOfMonth())
            errors.add("薪酬方案须覆盖完整自然月，任职期间由折算政策处理");
        var base = d.baseSalary();
        if (base == null) errors.add("请配置底薪");
        else {
            choice(base.mode(), Set.of("FIXED", "TIERS"), "底薪模式", errors);
            if ("FIXED".equals(base.mode())) nonnegative(base.fixedAmount(), "固定底薪", errors);
            if ("TIERS".equals(base.mode())) {
                if (empty(base.tiers())) errors.add("请配置底薪档位");
                else {
                    unique(
                            base.tiers().stream().map(PayrollSalaryTier::code).toList(),
                            "底薪档位编码",
                            errors);
                    List<PayrollSchemeInterval> intervals = new ArrayList<>();
                    for (var tier : base.tiers()) {
                        if (tier.name() == null || tier.name().isBlank()) errors.add("请填写档位名称");
                        nonnegative(tier.amount(), "档位底薪", errors);
                        nonnegative(tier.performanceBase(), "档位绩效基数", errors);
                        intervals.add(
                                new PayrollSchemeInterval(
                                        tier.lower(),
                                        tier.upper(),
                                        tier.lowerInclusive(),
                                        tier.upperInclusive()));
                    }
                    intervals(intervals, "底薪档位", errors);
                    if (base.firstMonthTier() == null
                            || base.tiers().stream()
                                    .noneMatch(
                                            t -> Objects.equals(t.code(), base.firstMonthTier())))
                        errors.add("请选择首月底薪档位");
                }
            }
        }
        if (base == null || !"TIERS".equals(base.mode()))
            nonnegative(d.performanceBase(), "绩效基数", errors);
        choice(d.performanceMode(), Set.of("NONE", "FIXED", "SALES", "MANAGER"), "绩效模式", errors);
        if ("SALES".equals(d.performanceMode())) sales(d.sales(), errors);
        if ("MANAGER".equals(d.performanceMode())) manager(d.manager(), errors);
        if ("SALES".equals(d.role()) && "MANAGER".equals(d.performanceMode())
                || !"SALES".equals(d.role()) && "SALES".equals(d.performanceMode()))
            errors.add("人员身份与绩效模板不匹配");
        personal(d.personalPolicy(), d.baseSalary(), errors);
        calendar(d.calendar(), errors);
        if (d.calendar() != null
                && "SALES".equals(d.performanceMode())
                && empty(d.calendar().weekdays())
                && empty(d.calendar().workDays())) errors.add("销售过程考核须配置工作日，不能用月度应出勤总数代替每日考核日期");
        if (d.proration() == null) errors.add("请配置底薪、绩效和补贴的折算政策");
        else
            for (String mode :
                    Arrays.asList(
                            d.proration().base(),
                            d.proration().performance(),
                            d.proration().allowance()))
                choice(
                        mode,
                        Set.of("NONE", "CALENDAR_DAYS", "WORK_DAYS", "CONFIRMED_ATTENDANCE"),
                        "折算方式",
                        errors);
        if (d.allowances() == null) errors.add("请明确补贴项目，无补贴使用空列表");
        else {
            unique(
                    d.allowances().stream().map(PayrollSchemeAllowance::code).toList(),
                    "补贴编码",
                    errors);
            d.allowances().forEach(a -> nonnegative(a.amount(), "补贴金额", errors));
            if (!"SALES".equals(d.role()) && !d.allowances().isEmpty())
                errors.add("当前业务仅销售人员享受固定补贴，城市总及总部方案不能配置销售补贴");
        }
        commission(d.commission(), errors);
        if (Set.of("CITY_MANAGER", "TRAINEE_MANAGER").contains(d.role() == null ? "" : d.role())
                && d.commission() != null
                && !empty(d.commission().rules())) {
            var roles =
                    d.commission().rules().stream()
                            .map(PayrollSchemeCommissionRule::recipientRole)
                            .toList();
            if (!roles.contains("SALES") || !roles.contains("CITY_MANAGER"))
                errors.add("城市总本人订单享受双提成，请分别配置销售与城市总规则；不计提商品须明确排除");
        }
        return new PayrollSchemeCheckView(List.copyOf(errors), List.copyOf(warnings));
    }

    static PayrollSchemeCheckView component(PayrollComponentDefinition d) {
        var salary = d.salary();
        var performance = d.performance();
        var definition =
                new PayrollSchemeDefinition(
                        d.name(),
                        "ALL".equals(d.role()) ? "SALES" : d.role(),
                        d.departmentIds(),
                        d.employeeCodes() == null ? List.of() : d.employeeCodes(),
                        d.effectiveFrom(),
                        d.effectiveTo(),
                        salary == null
                                ? new PayrollSchemeBaseSalary(
                                        "FIXED", BigDecimal.ZERO, List.of(), null)
                                : salary.baseSalary(),
                        salary == null ? BigDecimal.ZERO : salary.performanceBase(),
                        performance == null ? "NONE" : performance.mode(),
                        performance == null ? null : performance.sales(),
                        performance == null ? null : performance.manager(),
                        d.calendar() == null
                                ? new PayrollWorkCalendar(
                                        List.of(1, 2, 3, 4, 5),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        new PayrollWorkWindow(
                                                java.time.LocalTime.of(9, 0),
                                                java.time.LocalTime.of(6, 0),
                                                true,
                                                "IGNORE"),
                                        List.of(),
                                        null)
                                : d.calendar(),
                        salary == null
                                ? new PayrollProrationPolicy(
                                        "NONE",
                                        "NONE",
                                        d.allowance() == null ? "NONE" : d.allowance().proration())
                                : salary.proration(),
                        d.allowance() == null ? List.of() : d.allowance().items(),
                        d.commission() == null
                                ? new PayrollSchemeCommissionPolicy(
                                        "ORDER_DATE", "ORDER_DATE", List.of())
                                : d.commission(),
                        "");
        var result = check(definition);
        var errors = new ArrayList<>(result.errors());
        if (salary != null) personal(salary.personalPolicy(), salary.baseSalary(), errors);
        if (performance != null && "SALES".equals(performance.mode())) {
            var grades = performance.grades();
            if (grades == null
                    || grades.size() != 3
                    || grades.stream().anyMatch(Objects::isNull)
                    || !new HashSet<>(grades.stream().map(PayrollGradeBand::code).toList())
                            .equals(Set.of("BRONZE", "SILVER", "GOLD")))
                errors.add("请分别维护铜牌、银牌、金牌业绩区间");
            else
                intervals(
                        grades.stream()
                                .map(
                                        g ->
                                                new PayrollSchemeInterval(
                                                        g.lower(),
                                                        g.upper(),
                                                        g.lowerInclusive(),
                                                        g.upperInclusive()))
                                .toList(),
                        "业绩等级",
                        errors);
        }
        return new PayrollSchemeCheckView(List.copyOf(errors), result.warnings());
    }

    private static void personal(
            PayrollPersonalPolicy personal, PayrollSchemeBaseSalary base, List<String> errors) {
        if (personal == null) return;
        if (personal.exemptDays().size() > 366
                || personal.exemptDays().stream().anyMatch(Objects::isNull))
            errors.add("个人免考核日期最多366天，且不能包含空日期");
        if (personal.probationUntil() != null
                && (personal.probationBasePercent() == null
                        || personal.probationBasePercent().signum() < 0
                        || personal.probationBasePercent().compareTo(new BigDecimal("100")) > 0))
            errors.add("请配置试用期底薪比例，范围为0至100%");
        if (personal.salaryChangeDate() != null) {
            nonnegative(personal.baseAfterChange(), "调整后底薪", errors);
            nonnegative(personal.performanceAfterChange(), "调整后绩效基数", errors);
            if (base != null && "TIERS".equals(base.mode()))
                errors.add("档位薪资请通过版本调整，月内金额切换仅适用固定薪资");
        }
    }

    private static void sales(PayrollSalesPolicy p, List<String> errors) {
        if (p == null) {
            errors.add("请配置销售绩效");
            return;
        }
        if (p.processProtectionDays() != null
                && (p.processProtectionDays() < 0 || p.processProtectionDays() > 366))
            errors.add("销售打卡拜访保护期应为0至366个自然日");
        range(p.processPercent(), BigDecimal.ZERO, new BigDecimal("100"), "过程权重", errors);
        if (p.dailyCheckins() < 0
                || p.dailyCheckins() > 1000
                || p.dailyVisits() < 0
                || p.dailyVisits() > 1000
                || p.minimumSeconds() < 0
                || p.minimumSeconds() > 86400) errors.add("每日任务或拜访时长无效");
        nonnegative(p.checkinPenalty(), "缺卡扣减", errors);
        nonnegative(p.visitPenalty(), "缺拜访扣减", errors);
        if (p.resultCap() == null || p.resultCap().compareTo(BigDecimal.ONE) != 0)
            errors.add("销售回款达标绩效最高按100%发放，升档使用对应档位绩效基数");
        choice(p.checkinCountMode(), Set.of("UNIQUE_CUSTOMER", "RECORD"), "打卡计数口径", errors);
        if (empty(p.targets())) errors.add("请配置入职阶段目标");
        else {
            int next = p.firstMonthExempt() ? 2 : 1;
            for (int i = 0; i < p.targets().size(); i++) {
                var stage = p.targets().get(i);
                if (stage.fromMonth() != next
                        || stage.toMonth() != null && stage.toMonth() < stage.fromMonth())
                    errors.add("入职阶段须连续且不重叠");
                if (stage.toMonth() == null && i != p.targets().size() - 1
                        || stage.toMonth() != null && i == p.targets().size() - 1)
                    errors.add("最后一个入职阶段须长期有效");
                positive(stage.target(), "阶段目标", errors);
                next = stage.toMonth() == null ? Integer.MAX_VALUE : stage.toMonth() + 1;
            }
        }
    }

    private static void manager(PayrollManagerPolicy p, List<String> errors) {
        if (p == null) {
            errors.add("请配置城市总绩效");
            return;
        }
        choice(p.calculationMode(), Set.of("ACTUAL_COMPLETION", "TIERED"), "城市总绩效计算口径", errors);
        if ("TIERED".equals(p.calculationMode())) positive(p.cap(), "综合封顶系数", errors);
        var protection = p.protection();
        if (protection == null) errors.add("请明确城市总保护期");
        else {
            choice(protection.unit(), Set.of("DAYS", "MONTHS"), "保护期单位", errors);
            if (protection.count() < 0 || protection.count() > 366) errors.add("保护期长度无效");
        }
        if (empty(p.metrics())) {
            errors.add("请配置绩效指标");
            return;
        }
        unique(p.metrics().stream().map(PayrollMetricDefinition::code).toList(), "指标编码", errors);
        BigDecimal weight = BigDecimal.ZERO;
        for (var metric : p.metrics()) {
            if (metric.cityMetric() != null
                    && !metric.cityMetric().isBlank()
                    && !ManagerCityTargetResolver.METRICS.containsKey(metric.cityMetric()))
                errors.add("关联的城市指标无效");
            if (metric.name() == null || metric.name().isBlank()) errors.add("请填写指标名称");
            // Missing mode keeps historical rules on actual completion, even if old bands exist.
            if (metric.scoringMode() != null)
                choice(metric.scoringMode(), Set.of("ACTUAL", "TIERED"), "单项指标计分方式", errors);
            if ("TIERED".equals(metric.scoringMode()))
                steps(metric.steps(), metric.name() + "计分区间", errors);
            choice(
                    metric.source(),
                    Set.of(
                            "MANUAL_CONFIRMED",
                            "FULLY_PAID_RECEIPTS",
                            "ORDER_SALES",
                            "CITY_RECEIPTS"),
                    "指标数据源",
                    errors);
            // Null means the monthly target is owned by Target Settings; historical snapshots
            // retain fixed targets.
            if (metric.target() != null) positive(metric.target(), "指标目标", errors);
            if (metric.code() == null || !metric.code().matches("[A-Za-z0-9_-]{1,64}"))
                errors.add("指标编码须为1至64位字母、数字、下划线或连字符");
            nonnegative(metric.weight(), "指标权重", errors);
            if (metric.weight() != null) weight = weight.add(metric.weight());
        }
        if ("TIERED".equals(p.calculationMode())) steps(p.steps(), "综合完成率系数", errors);
        if (weight.compareTo(new BigDecimal("100")) != 0) errors.add("指标权重合计必须为100%");
    }

    private static void steps(List<PayrollMetricStep> values, String label, List<String> errors) {
        if (empty(values) || values.stream().anyMatch(Objects::isNull)) {
            errors.add("请配置" + label);
            return;
        }
        intervals(
                values.stream()
                        .map(
                                s ->
                                        new PayrollSchemeInterval(
                                                s.lower(),
                                                s.upper(),
                                                s.lowerInclusive(),
                                                s.upperInclusive()))
                        .toList(),
                label,
                errors);
        for (var step : values) {
            choice(step.mode(), Set.of("FIXED", "LINEAR"), label + "计算方式", errors);
            nonnegative(step.coefficient(), label, errors);
        }
    }

    static void calendar(PayrollWorkCalendar c, List<String> errors) {
        if (c == null
                || c.weekdays() == null
                || c.workDays() == null
                || c.restDays() == null
                || c.exemptDays() == null) {
            errors.add("请完整配置工作日历");
            return;
        }
        if (c.weekdays().stream().anyMatch(d -> d == null || d < 1 || d > 7))
            errors.add("星期应在1至7之间");
        if (c.source() != null
                && !Set.of("MANUAL", "STATUTORY", "STATUTORY_SNAPSHOT").contains(c.source()))
            errors.add("工作日历来源无效");
        if (!empty(c.alternateWeekdays())
                && (c.alternateAnchor() == null
                        || c.alternateWeekdays().stream()
                                .anyMatch(d -> d == null || d < 1 || d > 7)))
            errors.add("大小周须配置起始周和有效星期");
        if (!Collections.disjoint(c.workDays(), c.restDays())) errors.add("同一天不能同时设为工作日和休息日");
        var w = c.window();
        if (w == null || w.start() == null || w.end() == null) errors.add("请配置工作日时间窗口");
        else {
            if (w.nextDay() ? w.end().isAfter(w.start()) : !w.end().isAfter(w.start()))
                errors.add("时间窗口须大于0且不超过24小时");
            choice(w.outside(), Set.of("IGNORE", "CALENDAR_DAY"), "窗口外打卡处理", errors);
        }
    }

    private static void commission(PayrollSchemeCommissionPolicy p, List<String> errors) {
        if (p == null || p.rules() == null) {
            errors.add("请明确提成规则，无提成使用空列表");
            return;
        }
        choice(p.managerAttribution(), Set.of("ORDER_DATE", "FULLY_PAID_DATE"), "城市总归属", errors);
        choice(p.standardDate(), Set.of("ORDER_DATE", "FULLY_PAID_DATE"), "提成标准时点", errors);
        unique(
                p.rules().stream().map(PayrollSchemeCommissionRule::code).toList(),
                "提成规则编码",
                errors);
        Map<String, List<PayrollSchemeCommissionRule>> paired = new HashMap<>();
        for (var rule : p.rules())
            if (rule.rowCode() != null && !rule.rowCode().isBlank())
                paired.computeIfAbsent(rule.rowCode(), ignored -> new ArrayList<>()).add(rule);
        for (var pair : paired.values()) {
            if (pair.size() != 2
                    || Objects.equals(
                            pair.getFirst().recipientRole(), pair.getLast().recipientRole())) {
                errors.add("同一商品标准须分别配置销售与城市总提成");
                continue;
            }
            var a = pair.getFirst();
            var b = pair.getLast();
            if (!sameScope(a.categoryIds(), b.categoryIds())
                    || !sameScope(a.excludedCategoryIds(), b.excludedCategoryIds())
                    || !sameScope(a.variantIds(), b.variantIds())
                    || !sameScope(a.excludedVariantIds(), b.excludedVariantIds()))
                errors.add("同一标准行的销售与城市总商品范围必须一致");
        }
        for (int i = 0; i < p.rules().size(); i++)
            for (int j = i + 1; j < p.rules().size(); j++) {
                var a = p.rules().get(i);
                var b = p.rules().get(j);
                if (Objects.equals(a.recipientRole(), b.recipientRole())
                        && a.variantIds() != null
                        && b.variantIds() != null
                        && a.categoryIds() != null
                        && b.categoryIds() != null
                        && (!Collections.disjoint(a.variantIds(), b.variantIds())
                                || !Collections.disjoint(a.categoryIds(), b.categoryIds())))
                    errors.add("同一受益角色存在同级商品范围交叉：" + a.name() + " / " + b.name());
            }
        for (var rule : p.rules()) {
            if (rule.name() == null || rule.name().isBlank()) errors.add("请填写商品规则名称");
            choice(rule.recipientRole(), Set.of("SALES", "CITY_MANAGER"), "提成受益角色", errors);
            if (empty(rule.categoryIds()) && empty(rule.variantIds()))
                errors.add("请选择提成商品范围：" + rule.name());
            if (rule.categoryIds() == null
                    || rule.excludedCategoryIds() == null
                    || rule.variantIds() == null
                    || rule.excludedVariantIds() == null) errors.add("商品包含／排除范围必须明确");
            choice(rule.method(), Set.of("PERCENT", "QUANTITY"), "提成方式", errors);
            if (rule.excluded()) continue;
            nonnegative(rule.value(), "提成比例／单价", errors);
            if ("PERCENT".equals(rule.method())
                    && rule.value() != null
                    && rule.value().compareTo(new BigDecimal("100")) > 0)
                errors.add("提成比例不能超过100%");
            if ("QUANTITY".equals(rule.method())
                    && (rule.unitCode() == null || rule.unitCode().isBlank()))
                errors.add("按数量提成须选择计价单位");
        }
    }

    private static boolean sameScope(List<Long> first, List<Long> second) {
        return first != null
                && second != null
                && new HashSet<>(first).equals(new HashSet<>(second));
    }

    private static void intervals(
            List<PayrollSchemeInterval> rows, String name, List<String> errors) {
        BigDecimal previous = BigDecimal.ZERO;
        boolean previousIncluded = false;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.lower() == null
                    || previous == null
                    || row.lower().compareTo(previous) != 0
                    || row.lowerInclusive() == previousIncluded)
                errors.add(name + "须从0开始，连续无重叠，端点只能属于一个区间");
            if (row.upper() != null
                    && (row.lower() == null || row.upper().compareTo(row.lower()) <= 0))
                errors.add(name + "上界须大于下界");
            if ((row.upper() == null) != (i == rows.size() - 1)) errors.add(name + "仅最后一个区间不设上限");
            previous = row.upper();
            previousIncluded = row.upperInclusive();
        }
    }

    private static boolean hasNullRows(PayrollSchemeDefinition d) {
        List<Collection<?>> lists =
                new ArrayList<>(
                        Arrays.asList(d.departmentIds(), d.employeeCodes(), d.allowances()));
        if (d.baseSalary() != null) lists.add(d.baseSalary().tiers());
        if (d.sales() != null) lists.add(d.sales().targets());
        if (d.manager() != null) lists.add(d.manager().metrics());
        if (d.commission() != null) lists.add(d.commission().rules());
        if (lists.stream().anyMatch(c -> c != null && c.stream().anyMatch(Objects::isNull)))
            return true;
        if (d.manager() != null
                && d.manager().metrics() != null
                && d.manager().metrics().stream()
                        .anyMatch(
                                m ->
                                        m.steps() != null
                                                && m.steps().stream().anyMatch(Objects::isNull)))
            return true;
        return d.commission() != null
                && d.commission().rules() != null
                && d.commission().rules().stream()
                        .anyMatch(
                                r ->
                                        Arrays.<Collection<?>>asList(
                                                        r.categoryIds(),
                                                        r.excludedCategoryIds(),
                                                        r.variantIds(),
                                                        r.excludedVariantIds())
                                                .stream()
                                                .anyMatch(
                                                        c ->
                                                                c != null
                                                                        && c.stream()
                                                                                .anyMatch(
                                                                                        Objects
                                                                                                ::isNull)));
    }

    private static boolean empty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private static void unique(List<String> values, String label, List<String> errors) {
        if (values.stream().anyMatch(v -> v == null || v.isBlank())
                || new HashSet<>(values).size() != values.size()) errors.add(label + "不能为空或重复");
    }

    private static void choice(
            String value, Set<String> values, String label, List<String> errors) {
        if (value == null || !values.contains(value)) errors.add(label + "无效");
    }

    private static void nonnegative(BigDecimal value, String label, List<String> errors) {
        if (value == null || value.signum() < 0 || value.precision() > 18 || value.scale() > 6)
            errors.add(label + "须为最多6位小数的非负数");
    }

    private static void positive(BigDecimal value, String label, List<String> errors) {
        nonnegative(value, label, errors);
        if (value != null && value.signum() == 0) errors.add(label + "必须大于0");
    }

    private static void range(
            BigDecimal value, BigDecimal min, BigDecimal max, String label, List<String> errors) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0)
            errors.add(label + "超出允许范围");
    }
}
