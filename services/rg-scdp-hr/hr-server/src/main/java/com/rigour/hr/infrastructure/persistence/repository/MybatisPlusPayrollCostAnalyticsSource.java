package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.hr.application.port.out.AnalyticsSourceSnapshotStore.Page;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrPayrollMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/** 已确认工资的最小成本投影；不把薪酬规则、银行卡、绩效证据或逐单提成复制到 BI。 */
@Repository
public class MybatisPlusPayrollCostAnalyticsSource {
    public static final String DATASET = "HR_PAYROLL_COST";
    private static final String PROJECTION_VERSION = "payroll-cost-v2:";
    public static final List<String> COMPONENTS =
            List.of("baseSalary", "performance", "commission", "allowance", "correctionAmount");
    public static final List<String> COLUMNS =
            List.of(
                    "id",
                    "tenant_id",
                    "document_no",
                    "title",
                    "type_code",
                    "city_code",
                    "department_id",
                    "cost_date",
                    "amount",
                    "status_code",
                    "payment_status_code",
                    "data_json",
                    "revision",
                    "created_by",
                    "updated_time",
                    "employee_code",
                    "employee_name",
                    "department_name",
                    "category_code",
                    "components_json");
    private final HrPayrollMapper mapper;
    private final ObjectMapper json;

    public MybatisPlusPayrollCostAnalyticsSource(HrPayrollMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public String version(String tenant) {
        String version = mapper.analyticsVersion(tenant);
        if (version == null || !version.matches("[0-9]+"))
            throw new IllegalStateException("薪酬成本来源版本无效");
        return PROJECTION_VERSION + version;
    }

    @Transactional(readOnly = true)
    public Page page(String tenant, String after) {
        String cursor = after == null ? "" : after.toLowerCase(Locale.ROOT);
        if (!cursor.isEmpty()
                && !cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("TEXT_UUID源游标无效");
        String version = version(tenant);
        var rows =
                mapper.selectList(
                        new QueryWrapper<HrPayrollEntity>()
                                .eq("tenant_id", tenant)
                                .in("status_code", "CONFIRMED", "PAID")
                                .gt(!cursor.isEmpty(), "id", cursor)
                                .orderByAsc("id")
                                .last("LIMIT 1000"));
        return new Page(version, rows.stream().map(this::row).toList());
    }

    private Map<String, String> row(HrPayrollEntity r) {
        var calculation = json.readTree(r.getCalculationJson());
        var gross = calculation.path("grossPay");
        if (!gross.isNumber()) throw new IllegalStateException("已确认工资缺少应发金额");
        BigDecimal amount =
                new BigDecimal(gross.asText()).setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (amount.signum() < 0) throw new IllegalStateException("已确认工资应发金额为负");
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        for (String key : COMPONENTS) {
            var value = calculation.path(key);
            if (value.isNumber()) components.put(key, new BigDecimal(value.asText()));
        }
        // 旧工资可能缺分项，保留未知；已具备全部分项时必须与确认快照应发金额核平。
        if (components.size() == COMPONENTS.size()
                && components.values().stream()
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .compareTo(amount)
                        != 0) throw new IllegalStateException("已确认工资分项与应发金额不一致");
        Map<String, String> values = new LinkedHashMap<>();
        COLUMNS.forEach(k -> values.put(k, null));
        values.put("id", r.getId());
        values.put("tenant_id", r.getTenantId());
        values.put("document_no", r.getPayrollMonth() + "/" + r.getEmployeeCode());
        values.put("title", r.getPayrollMonth() + " " + r.getEmployeeName() + " 月度薪酬");
        values.put("category_code", "PAYROLL");
        values.put("type_code", "PAYROLL");
        // 与工资列表的冻结分类优先级相同；不查询员工现状，不将缺少城市的工资猜作总部。
        String role =
                frozenRole(
                        calculation.path("scheme").path("scheme").path("definition").path("role"));
        if (role == null)
            role = frozenRole(calculation.path("salarySource").path("payrollCategory"));
        if ("HEADQUARTERS".equals(role))
            values.put("city_code", HrCostCityReference.HEADQUARTERS_CODE);
        values.put("department_id", Objects.toString(r.getDepartmentId(), null));
        values.put("cost_date", YearMonth.parse(r.getPayrollMonth()).atEndOfMonth().toString());
        values.put("amount", amount.toPlainString());
        values.put("status_code", "CONFIRMED");
        // 工资应发成本与实发欠款不是同一口径，付款分析留在工资单，避免部分发放被算成全部未付。
        values.put("payment_status_code", "UNKNOWN");
        values.put("data_json", "{}");
        values.put("revision", Objects.toString(r.getRevision()));
        values.put("created_by", r.getCreatedBy());
        values.put("updated_time", Objects.toString(r.getUpdatedTime(), null));
        values.put("employee_code", r.getEmployeeCode());
        values.put("employee_name", r.getEmployeeName());
        values.put("department_name", r.getDepartmentName());
        values.put("components_json", json.writeValueAsString(components));
        return values;
    }

    private static String frozenRole(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        String role = value.asText();
        return "null".equals(role) ? null : role;
    }
}
