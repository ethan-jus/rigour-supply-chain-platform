package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.api.v1.model.PayrollCalculateCommand;
import com.rigour.hr.api.v1.model.PayrollCalculationView;
import com.rigour.hr.api.v1.model.PayrollCategoryView;
import com.rigour.hr.api.v1.model.PayrollCityCommissionPolicy;
import com.rigour.hr.api.v1.model.PayrollCityProductRule;
import com.rigour.hr.api.v1.model.PayrollCommissionPolicyView;
import com.rigour.hr.api.v1.model.PayrollDepartmentView;
import com.rigour.hr.api.v1.model.PayrollDetailView;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.api.v1.model.PayrollInputCommand;
import com.rigour.hr.api.v1.model.PayrollProductView;
import com.rigour.hr.api.v1.model.PayrollQuery;
import com.rigour.hr.api.v1.model.PayrollRuleCommand;
import com.rigour.hr.api.v1.model.PayrollRuleView;
import com.rigour.hr.api.v1.model.PayrollSummaryView;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HrPayrollService {
    private static final Logger log = LoggerFactory.getLogger(HrPayrollService.class);
    private final HrPayrollStore store;
    private final HrPayrollSource source;

    private final ConfiguredPayrollService configured;
    private com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore ruleReviews;
    private PayrollAttendanceGate attendanceGate;

    @org.springframework.beans.factory.annotation.Autowired
    void setAttendanceGate(PayrollAttendanceGate gate) {
        this.attendanceGate = gate;
    }

    private PayrollDetailView withPaymentGate(String tenant, PayrollDetailView row) {
        if (attendanceGate == null) throw new IllegalStateException("考勤发薪校验未初始化");
        return row.withPaymentBlockReason(
                attendanceGate.blockReason(tenant, row, "hr:payroll:read"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRuleReviews(
            com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore reviews) {
        this.ruleReviews = reviews;
    }

    public HrPayrollService(
            HrPayrollStore store, HrPayrollSource source, ConfiguredPayrollService configured) {
        this.store = store;
        this.source = source;
        this.configured = configured;
    }

    static CallerIdentity actor(String permission) {
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        return a;
    }

    public List<PayrollCommissionPolicyView> policies() {
        actor("hr:payroll-rule:read");
        return store.policies();
    }

    public List<PayrollProductView> products(
            String keyword, Long categoryId, Long productVariantId) {
        var a = actor("hr:payroll-rule:read");
        return source
                .products(a.tenantId().toString(), keyword, productVariantId, categoryId)
                .stream()
                .map(
                        p ->
                                new PayrollProductView(
                                        p.productId(),
                                        p.productVariantId(),
                                        p.productCode(),
                                        p.variantCode(),
                                        p.productName(),
                                        p.specification(),
                                        p.unitCode(),
                                        p.categoryId(),
                                        p.categoryName()))
                .toList();
    }

    public List<PayrollProductView> products(
            String keyword, Long categoryId, Long productVariantId, int begin) {
        if (begin < 0) throw bad("商品分页范围无效");
        if (begin == 0) return products(keyword, categoryId, productVariantId);
        var a = actor("hr:payroll-rule:read");
        return source
                .products(a.tenantId().toString(), keyword, productVariantId, categoryId, begin)
                .stream()
                .map(
                        p ->
                                new PayrollProductView(
                                        p.productId(),
                                        p.productVariantId(),
                                        p.productCode(),
                                        p.variantCode(),
                                        p.productName(),
                                        p.specification(),
                                        p.unitCode(),
                                        p.categoryId(),
                                        p.categoryName()))
                .toList();
    }

    public List<PayrollCategoryView> productCategories() {
        var a = actor("hr:payroll-rule:read");
        return source.productCategories(a.tenantId().toString()).stream()
                .map(c -> new PayrollCategoryView(c.id(), c.parentId(), c.name(), c.sortOrder()))
                .toList();
    }

    public List<PayrollDepartmentView> departments() {
        var a = actor("hr:payroll-rule:read");
        return store.departments(a.tenantId().toString(), "hr:payroll-rule:read");
    }

    public List<PayrollDepartmentView> payrollDepartments() {
        var a = actor("hr:payroll:read");
        return store.departments(a.tenantId().toString(), "hr:payroll:read");
    }

    public List<PayrollEmployeeView> ruleEmployees() {
        var a = actor("hr:payroll-rule:read");
        return store.employees(a.tenantId().toString(), "hr:payroll-rule:read");
    }

    public List<PayrollEmployeeView> employees() {
        var a = actor("hr:payroll:read");
        return store.employees(a.tenantId().toString(), "hr:payroll:read");
    }

    public HrPageView<PayrollRuleView> rules(int begin, int step, String kind, String keyword) {
        var a = actor("hr:payroll-rule:read");
        String t = a.tenantId().toString();
        kind(kind);
        var rows =
                store.rules(t).stream()
                        .filter(
                                r ->
                                        r.data().kindCode().equals(kind)
                                                && r.data()
                                                        .name()
                                                        .contains(keyword == null ? "" : keyword))
                        .filter(
                                r -> {
                                    try {
                                        store.requireRuleScope(t, r.data(), "hr:payroll-rule:read");
                                        return true;
                                    } catch (AuthorizationDeniedException | BusinessException e) {
                                        return false;
                                    }
                                })
                        .toList();
        int offset = Math.min(Math.max(0, begin), rows.size()),
                size = step <= 0 ? 20 : Math.min(200, step);
        var items = rows.subList(offset, Math.min(offset + size, rows.size()));
        var missing =
                items.stream()
                        .map(PayrollRuleView::data)
                        .filter(
                                c ->
                                        "COMMISSION".equals(c.kindCode())
                                                && c.categoryId() == null
                                                && c.productVariantId() != null)
                        .map(PayrollRuleCommand::productVariantId)
                        .distinct()
                        .toList();
        if (!missing.isEmpty()) {
            // 旧规则只保存 SKU；批量从 ERP 补充列表分类展示，不改写规则和历史快照。
            var products =
                    source.productsByVariants(t, missing).stream()
                            .collect(Collectors.toMap(p -> p.productVariantId(), p -> p));
            items =
                    items.stream()
                            .map(
                                    row -> {
                                        var c = row.data();
                                        var product = products.get(c.productVariantId());
                                        if (product == null
                                                || !Objects.equals(
                                                        c.productId(), product.productId()))
                                            return row;
                                        return new PayrollRuleView(
                                                row.id(),
                                                row.revision(),
                                                c.withProduct(
                                                        c.productName(),
                                                        product.categoryId(),
                                                        product.categoryName()),
                                                row.updatedTime());
                                    })
                            .toList();
        }
        return new HrPageView<>(rows.size(), offset, size, items);
    }

    @Transactional
    public List<PayrollRuleView> createCityManagers(List<PayrollRuleCommand> commands) {
        actor("hr:payroll-rule:write");
        if (commands == null || commands.isEmpty() || commands.size() > 100)
            throw bad("请选择 1 至 100 个管辖城市");
        var cities = new HashSet<String>();
        var requests = new HashSet<String>();
        for (var c : commands) {
            if (c == null || !"CITY_MANAGER".equals(c.kindCode()) || c.revision() != null)
                throw bad("仅支持新增城市总配置");
            if (!Objects.equals(c.employeeCode(), commands.getFirst().employeeCode())
                    || !cities.add(c.cityCode())
                    || !requests.add(c.requestId())) throw bad("请选择同一负责人，城市及请求编号不可重复");
        }
        // 所有城市共用事务和租户规则锁，失败连同审计回滚；沿用每条规则的幂等键。
        return commands.stream().map(c -> saveRule(null, c)).toList();
    }

    private HrPayrollComponentService components;

    @org.springframework.beans.factory.annotation.Autowired
    void setComponents(HrPayrollComponentService components) {
        this.components = components;
    }

    @Transactional
    public PayrollRuleView saveRule(String id, PayrollRuleCommand command) {
        var a = actor("hr:payroll-rule:write");
        String t = a.tenantId().toString();
        if (command == null) throw bad("请填写薪酬规则");
        if (components != null
                && components.unified(t, YearMonth.now(ZoneId.of("Asia/Shanghai")))
                && !"CITY_MANAGER".equals(command.kindCode()))
            throw bad("已启用统一薪酬配置，请在对应规则入口维护；旧规则仅用于历史查询");
        final var c = normalizeCityCommission(t, normalizeCommission(t, command));
        kind(c.kindCode());
        if ("BASE".equals(c.kindCode())) throw bad("底薪统一在员工档案维护，不再单独设置薪资标准");
        required(c.name(), 120, "规则名称");
        text(c.remark(), 500, "备注");
        if (c.effectiveFrom() == null
                || c.effectiveTo() == null
                || c.effectiveTo().isBefore(c.effectiveFrom())) throw bad("生效日期范围无效");
        if (id == null) request(c.requestId());
        else revision(c.revision());
        if (c.departmentIds() == null
                || c.departmentIds().size() > 100
                || c.departmentIds().stream().anyMatch(Objects::isNull)
                || c.departmentIds().stream().distinct().count() != c.departmentIds().size())
            throw bad("适用部门无效");
        if ("BASE".equals(c.kindCode())) {
            required(c.employeeCode(), 50, "员工");
            if (!c.departmentIds().isEmpty()) throw bad("底薪标准按员工配置");
        } else if (!"CITY_MANAGER".equals(c.kindCode()) && c.departmentIds().isEmpty())
            throw bad("请选择适用部门");
        if ("CITY_MANAGER".equals(c.kindCode())) {
            required(c.employeeCode(), 50, "城市总员工");
            required(c.cityCode(), 64, "所辖城市编码");
            required(c.cityName(), 120, "所辖城市");
            if (c.includeOwnSalesCommission() == null) throw bad("请明确本人销售是否同时计销售提成");
            if (!c.departmentIds().isEmpty()) throw bad("城市总按整座城市管辖，不按部门限定");
        }
        if ("COMMISSION".equals(c.kindCode())) {
            if (!Set.of("SALES", "CITY_MANAGER")
                    .contains(c.recipientRoleCode() == null ? "" : c.recipientRoleCode()))
                throw bad("请选择提成受益角色");
            if (c.groupCode() != null
                    && !c.groupCode().isBlank()
                    && !source.dictionary(t, "COMMISSION_PRODUCT_GROUP").contains(c.groupCode()))
                throw bad("请选择启用的提成商品组");
            if (c.productId() == null || c.productVariantId() == null) throw bad("请选择商品规格");
            required(c.unitCode(), 64, "计奖单位");
            if (!Set.of("QUANTITY", "PERCENT")
                    .contains(c.methodCode() == null ? "" : c.methodCode())) throw bad("未知提成方式");
            money(c.rate(), true);
            if ("PERCENT".equals(c.methodCode()) && c.rate().compareTo(new BigDecimal("100")) > 0)
                throw bad("比例不能超过100%");

        } else if (!"CITY_MANAGER".equals(c.kindCode()))
            money(
                    c.amount(),
                    !("PERFORMANCE".equals(c.kindCode())
                            && c.performancePolicy() != null
                            && "SALES_KPI".equals(c.performancePolicy().mode())));
        if ("PERFORMANCE".equals(c.kindCode()) && c.performancePolicy() != null) {
            var policy = c.performancePolicy();
            if (java.util.stream.Stream.of(
                            policy.vehicleAllowance(),
                            policy.phoneAllowance(),
                            policy.computerAllowance())
                    .anyMatch(value -> value != null && value.signum() != 0))
                throw bad("固定补贴请在其他薪资设置维护，不能保存在绩效规则中");
            if (!Set.of("FIXED", "SALES_KPI").contains(policy.mode() == null ? "" : policy.mode()))
                throw bad("未知绩效模式");
            if ("SALES_KPI".equals(policy.mode())) {
                money(policy.bronzeBase(), false);
                if (!Set.of("EXEMPT_ALL", "EXEMPT_COLLECTION")
                        .contains(policy.firstMonthMode() == null ? "" : policy.firstMonthMode()))
                    throw bad("请配置首月免考核口径");
                if (policy.processPercent() == null
                        || policy.processPercent() < 0
                        || policy.processPercent() > 100
                        || policy.dailyCheckins() == null
                        || policy.dailyCheckins() < 0
                        || policy.dailyCheckins() > 100
                        || policy.dailyVisits() == null
                        || policy.dailyVisits() < 0
                        || policy.dailyVisits() > 100
                        || policy.minimumSeconds() == null
                        || policy.minimumSeconds() < 1
                        || policy.minimumSeconds() > 86400) throw bad("过程考核指标无效");
                money(policy.checkinPenalty(), true);
                money(policy.visitPenalty(), true);
                money(policy.secondMonthTarget(), true);
                money(policy.laterMonthTarget(), true);
                if (policy.secondMonthTarget().signum() == 0
                        || policy.laterMonthTarget().signum() == 0) throw bad("回款指标必须大于0");
            }
        }
        if ("ALLOWANCE".equals(c.kindCode())) {
            required(c.allowanceTypeCode(), 64, "薪资类型");
            if (!source.dictionary(t, "PAYROLL_ALLOWANCE_TYPE").contains(c.allowanceTypeCode()))
                throw bad("请选择启用的薪资类型");
            if ("FIXED_ALLOWANCE".equals(c.allowanceTypeCode())) {
                var fixed = c.fixedAllowance();
                if (fixed == null) throw bad("请填写销售固定补贴明细");
                money(fixed.vehicleAllowance(), true);
                money(fixed.phoneAllowance(), true);
                money(fixed.computerAllowance(), true);
                var total =
                        fixed.vehicleAllowance()
                                .add(fixed.phoneAllowance())
                                .add(fixed.computerAllowance());
                if (total.compareTo(c.amount()) != 0) throw bad("固定补贴合计应等于交通、话费、电脑补贴之和");
            } else if (c.fixedAllowance() != null) throw bad("仅固定补贴类型可填写固定补贴明细");
        }
        store.requireRuleScope(t, c, "hr:payroll-rule:write");
        if ("CITY_MANAGER".equals(c.kindCode())
                && !"SALES"
                        .equals(
                                store.employee(t, c.employeeCode(), "hr:payroll-rule:write")
                                        .payrollCategory())) throw bad("城市总员工应在员工档案中设置为销售薪酬类别");
        if ("CITY_MANAGER".equals(c.kindCode())
                && !Set.of("城市总", "见习城市总")
                        .contains(
                                Objects.toString(
                                                store.employeePositionName(
                                                        t,
                                                        c.employeeCode(),
                                                        "hr:payroll-rule:write"),
                                                "")
                                        .strip())) throw bad("仅城市总和见习城市总可配置管辖城市，请先核对员工档案岗位");
        store.lock(t);
        for (var old : store.rules(t)) {
            var r = old.data();
            if (id == null && Objects.equals(c.requestId(), r.requestId()))
                return store.saveRule(t, null, c, a.principalId().toString());
            if (old.id().equals(id)
                    && "CITY_MANAGER".equals(r.kindCode())
                    && !r.effectiveFrom().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
                if (!Objects.equals(r.employeeCode(), c.employeeCode())
                        || !Objects.equals(r.cityCode(), c.cityCode())
                        || !Objects.equals(r.effectiveFrom(), c.effectiveFrom())
                        || r.enabled() != c.enabled())
                    throw bad("已开始的城市任职不能更换人员、城市、起始日期或停用；交接请填写任期结束并新增接任记录");
                if (!Objects.equals(r.effectiveTo(), c.effectiveTo())
                        && c.effectiveTo()
                                .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)))
                    throw bad("历史任期不能追溯修改，请通过工资更正核定历史差异");
            }
            if (old.id().equals(id)
                    || !r.enabled()
                    || !c.enabled()
                    || !r.kindCode().equals(c.kindCode())
                    || r.effectiveTo().isBefore(c.effectiveFrom())
                    || r.effectiveFrom().isAfter(c.effectiveTo())) continue;
            boolean same =
                    "BASE".equals(c.kindCode())
                            ? Objects.equals(c.employeeCode(), r.employeeCode())
                            : c.departmentIds().stream().anyMatch(r.departmentIds()::contains);
            if ("CITY_MANAGER".equals(c.kindCode()))
                same = Objects.equals(c.cityCode(), r.cityCode());
            if ("COMMISSION".equals(c.kindCode()))
                same &=
                        Objects.equals(c.recipientRoleCode(), r.recipientRoleCode())
                                && Objects.equals(c.productId(), r.productId())
                                && Objects.equals(c.productVariantId(), r.productVariantId());
            if ("ALLOWANCE".equals(c.kindCode()))
                same &=
                        "FIXED_ALLOWANCE".equals(c.allowanceTypeCode())
                                ? "FIXED_ALLOWANCE".equals(r.allowanceTypeCode())
                                : Objects.equals(c.allowanceTypeCode(), r.allowanceTypeCode())
                                        && c.name().equals(r.name());
            if (same)
                throw bad(
                        ("CITY_MANAGER".equals(c.kindCode()) ? c.cityName() + "：" : "")
                                + "同一适用对象的启用规则存在重叠，请调整生效期间");
        }
        return store.saveRule(t, id, c, a.principalId().toString());
    }

    private PayrollRuleCommand normalizeCityCommission(String tenant, PayrollRuleCommand c) {
        var policy = c.cityCommission();
        if (policy == null) return c;
        if (!"CITY_MANAGER".equals(c.kindCode())) throw bad("仅城市总可设置团队提成和独立商品标准");
        money(policy.teamPercent(), true);
        if (policy.teamPercent().compareTo(new BigDecimal("100")) > 0)
            throw bad("团队计入比例应在 0 至 100% 之间");
        if (policy.products() == null) return c;
        if (policy.products().size() > 100) throw bad("独立商品标准最多 100 条");
        var variants = new HashSet<Long>();
        var products = new ArrayList<PayrollCityProductRule>();
        for (var item : policy.products()) {
            if (item == null
                    || item.productId() == null
                    || item.productVariantId() == null
                    || !variants.add(item.productVariantId())) throw bad("请选择商品型号，同一型号不可重复");
            if (!Set.of("PERCENT", "QUANTITY")
                    .contains(item.methodCode() == null ? "" : item.methodCode()))
                throw bad("请选择商品提成计算方式");
            money(item.rate(), true);
            if ("PERCENT".equals(item.methodCode())
                    && item.rate().compareTo(new BigDecimal("100")) > 0)
                throw bad("商品提成比例不能超过 100%");
            required(item.unitCode(), 64, "商品计量单位");
            var p =
                    source.requireProduct(
                            tenant, item.productId(), item.productVariantId(), item.unitCode());
            if (p.categoryId() == null || !Objects.equals(p.categoryId(), item.categoryId()))
                throw bad("商品分类已变化，请重新选择型号");
            products.add(
                    new PayrollCityProductRule(
                            p.productId(),
                            p.productVariantId(),
                            p.productName()
                                    + (p.specification() == null || p.specification().isBlank()
                                            ? ""
                                            : " · " + p.specification()),
                            p.unitCode(),
                            p.categoryId(),
                            p.categoryName(),
                            item.methodCode(),
                            item.rate()));
        }
        return c.withCityCommission(
                new PayrollCityCommissionPolicy(policy.teamPercent(), products));
    }

    private PayrollRuleCommand normalizeCommission(String tenant, PayrollRuleCommand c) {
        if (!"COMMISSION".equals(c.kindCode())) return c;
        if (c.productId() == null || c.productVariantId() == null) throw bad("请选择商品规格型号");
        var p = source.requireProduct(tenant, c.productId(), c.productVariantId(), c.unitCode());
        if (p.categoryId() == null || p.categoryName() == null) throw bad("商品未关联有效分类，请先完善商品资料");
        if (c.categoryId() != null && !c.categoryId().equals(p.categoryId()))
            throw bad("商品分类已变化，请重新选择规格型号");
        return c.withProduct(
                p.productName()
                        + (p.specification() == null || p.specification().isBlank()
                                ? ""
                                : " · " + p.specification()),
                p.categoryId(),
                p.categoryName());
    }

    public HrPageView<PayrollDetailView> payroll(PayrollQuery q) {
        var a = actor("hr:payroll:read");
        if (q.month() != null && !q.month().isBlank()) month(q.month());
        String tenant = a.tenantId().toString();
        var page = store.payroll(tenant, q, "hr:payroll:read");
        return new HrPageView<>(
                page.total(),
                page.begin(),
                page.step(),
                page.items().stream().map(row -> withPaymentGate(tenant, row)).toList());
    }

    public PayrollDetailView detail(String id) {
        var a = actor("hr:payroll:read");
        String tenant = a.tenantId().toString();
        return withPaymentGate(tenant, store.detail(tenant, id, "hr:payroll:read"));
    }

    public PayrollSummaryView summary(PayrollQuery query) {
        var a = actor("hr:payroll:read");
        month(query.month());
        return store.summary(a.tenantId().toString(), query, "hr:payroll:read");
    }

    public List<PayrollAuditView> history(String id) {
        var p = detail(id);
        return store.history(AuthorizationContext.requireCurrent().tenantId().toString(), p.id());
    }

    public PayrollInputCommand input(String month, String employee) {
        var a = actor("hr:payroll:read");
        month(month);
        String t = a.tenantId().toString();
        store.employee(t, employee, "hr:payroll:read");
        return store.input(t, employee, month);
    }

    @Transactional
    public PayrollInputCommand saveInput(String month, String employee, PayrollInputCommand c) {
        var a = actor("hr:payroll:calculate");
        month(month);
        if (c == null || c.revision() == null || c.revision() < 0) throw bad("缺少月度输入版本");
        money(c.baseOverride(), false);
        money(c.performanceOverride(), false);
        money(c.allowanceOverride(), false);
        money(c.deduction(), true);
        required(c.reason(), 500, "调整原因");
        String t = a.tenantId().toString();
        store.employee(t, employee, "hr:payroll:calculate");
        if (c.departmentOverrideId() != null
                && store.departments(t, "hr:payroll:calculate").stream()
                        .noneMatch(d -> d.id().equals(c.departmentOverrideId())))
            throw bad("核定部门不存在、已停用或无权限");
        return store.saveInput(t, employee, month, c, a.principalId().toString());
    }

    @Transactional
    public List<PayrollDetailView> calculate(PayrollCalculateCommand c) {
        var a = actor("hr:payroll:calculate");
        if (c == null) throw bad("缺少核算参数");
        var m = month(c.month());
        if (c.employeeCodes() == null
                || c.employeeCodes().isEmpty()
                || c.employeeCodes().size() > 100
                || c.employeeCodes().stream().anyMatch(Objects::isNull))
            throw bad("每次请选择1至100名员工核算");
        String t = a.tenantId().toString();
        store.lock(t);
        List<PayrollDetailView> result = new ArrayList<>();
        for (var code : new LinkedHashSet<>(c.employeeCodes())) {
            var e = store.employee(t, code, "hr:payroll:calculate");
            if (store.byMonth(t, code, c.month())
                    .map(p -> Set.of("CONFIRMED", "PAID").contains(p.statusCode()))
                    .orElse(false)) continue;
            var value = calculation(t, e, m);
            result.add(
                    store.saveCalculation(
                            t, code, c.month(), e, value, a.principalId().toString()));
        }
        return List.copyOf(result);
    }

    PayrollCalculationView calculation(String t, PayrollEmployeeView e, YearMonth m) {
        if (configured == null) throw new IllegalStateException("薪资核算服务未初始化");
        long started = System.nanoTime();
        try {
            var result =
                    configured
                            .calculateIfConfigured(t, e, m)
                            .orElseThrow(() -> new IllegalStateException("薪资核算未返回结果"));
            log.info(
                    "Payroll calculated tenant={} employee={} month={} issueCount={} elapsedMs={}",
                    t,
                    e.employeeCode(),
                    m,
                    result.issues().size(),
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException failure) {
            log.error(
                    "Payroll calculation failed tenant={} employee={} month={} elapsedMs={}",
                    t,
                    e.employeeCode(),
                    m,
                    (System.nanoTime() - started) / 1_000_000,
                    failure);
            throw failure;
        }
    }

    @Transactional
    public PayrollDetailView confirm(String id, PayrollActionCommand command) {
        var a = actor("hr:payroll:confirm");
        checkAction(command);
        String t = a.tenantId().toString();
        store.lock(t);
        var old = store.detail(t, id, "hr:payroll:confirm");
        if (!"READY".equals(old.statusCode()) || old.revision() != command.revision())
            throw bad("请先完成核算并处理待核对事项");
        if (!month(old.month())
                .atEndOfMonth()
                .isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
            throw bad("月份尚未结束，当前结果仅供预核算");
        if (attendanceGate == null) throw new IllegalStateException("考勤发薪校验未初始化");
        attendanceGate.require(t, old, "hr:payroll:confirm");
        var employee = store.employee(t, old.employeeCode(), "hr:payroll:confirm");
        var fresh = calculation(t, employee, month(old.month()));
        if (!fresh.issues().isEmpty()
                || !fresh.sourceFingerprint().equals(old.calculation().sourceFingerprint()))
            throw new BusinessException(ErrorCode.CONFLICT, "规则、员工或订单回款已变化，请重新核算", List.of());
        var confirmed = store.transition(t, id, command, "CONFIRMED", a.principalId().toString());
        if (ruleReviews != null)
            ruleReviews.resolveSubject(
                    t, "PAYROLL", id, "按最新规则重新核算并确认薪酬", a.principalId().toString());
        log.info(
                "Payroll confirmed tenant={} actor={} payrollId={} revision={}",
                t,
                a.principalId(),
                id,
                confirmed.revision());
        return confirmed;
    }

    static YearMonth month(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}"))
                throw new IllegalArgumentException();
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw bad("月份格式应为YYYY-MM");
        }
    }

    static void checkAction(PayrollActionCommand c) {
        if (c == null) throw bad("缺少操作参数");
        revision(c.revision());
        text(c.reason(), 500, "操作原因");
    }

    private static void kind(String value) {
        if (!Set.of("BASE", "COMMISSION", "PERFORMANCE", "ALLOWANCE", "CITY_MANAGER")
                .contains(value == null ? "" : value)) throw bad("未知薪酬规则类型");
    }

    static void money(BigDecimal value, boolean required) {
        if (value == null) {
            if (required) throw bad("请填写金额");
            return;
        }
        if (value.signum() < 0 || value.scale() > 2 || value.precision() - value.scale() > 14)
            throw bad("金额须非负且最多两位小数");
    }

    static void revision(Integer n) {
        if (n == null || n < 1) throw bad("缺少当前版本，请刷新");
    }

    static void request(String value) {
        try {
            UUID.fromString(value);
        } catch (Exception e) {
            throw bad("requestId无效");
        }
    }

    static void required(String value, int max, String name) {
        if (value == null || value.isBlank()) throw bad("请填写" + name);
        text(value, max, name);
    }

    static void text(String value, int max, String name) {
        if (value != null && value.length() > max) throw bad(name + "长度不能超过" + max);
    }

    static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
