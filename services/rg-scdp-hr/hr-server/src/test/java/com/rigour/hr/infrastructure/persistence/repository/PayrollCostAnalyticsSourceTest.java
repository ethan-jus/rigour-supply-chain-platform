package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrPayrollMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class PayrollCostAnalyticsSourceTest {
    @Test
    void finalizedOnlyExactComponentsPaginationTenantAndVersionRollback() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:payrollcost_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute(
                    "CREATE TABLE hr_payroll(id VARCHAR PRIMARY KEY, tenant_id VARCHAR,"
                        + " employee_code VARCHAR, employee_name VARCHAR, department_id BIGINT,"
                        + " department_name VARCHAR, city_name VARCHAR, payroll_month VARCHAR,"
                        + " status_code VARCHAR, revision INT, calculation_json VARCHAR, created_by"
                        + " VARCHAR, updated_by VARCHAR, created_time TIMESTAMP, updated_time"
                        + " TIMESTAMP)");
            jdbc.execute(
                    "CREATE TABLE hr_payroll_guard(tenant_id VARCHAR PRIMARY KEY, version BIGINT)");
            jdbc.update("INSERT INTO hr_payroll_guard VALUES('tenant',7)");
            jdbc.update("INSERT INTO hr_payroll_guard VALUES('other',22)");
            String body =
                    "{\"baseSalary\":4000.01,\"performance\":500,\"commission\":100.02,\"allowance\":100,\"correctionAmount\":-20,\"grossPay\":4680.03,\"netPay\":4500,\"bankAccount\":\"never-project\"}";
            for (int i = 1; i <= 1001; i++)
                jdbc.update(
                        "INSERT INTO"
                            + " hr_payroll(id,tenant_id,employee_code,employee_name,department_id,department_name,payroll_month,status_code,revision,calculation_json)"
                            + " VALUES(?, 'tenant','E1','员工',10,'武汉','2024-02',?,1,?)",
                        String.format("00000000-0000-0000-0000-%012d", i),
                        i % 2 == 0 ? "PAID" : "CONFIRMED",
                        body);
            for (String status : List.of("READY", "REVIEW", "DRAFT"))
                jdbc.update(
                        "INSERT INTO hr_payroll(id,tenant_id,status_code) VALUES(?,'tenant',?)",
                        UUID.randomUUID().toString(),
                        status);
            jdbc.update(
                    "INSERT INTO hr_payroll(id,tenant_id,status_code)"
                            + " VALUES(?,'other','CONFIRMED')",
                    UUID.randomUUID().toString());
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(HrPayrollMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setConfiguration(config);
            var mapper =
                    new SqlSessionTemplate(factory.getObject()).getMapper(HrPayrollMapper.class);
            var source =
                    new MybatisPlusPayrollCostAnalyticsSource(mapper, JsonMapper.builder().build());
            var page = source.page("tenant", "");
            assertThat(source.version("tenant")).isEqualTo("payroll-cost-v3:7");
            assertThat(page.version()).isEqualTo(source.version("tenant"));
            assertThat(source.version("other")).isEqualTo("payroll-cost-v3:22");
            assertThat(page.items()).hasSize(1000);
            var row = page.items().getFirst();
            assertThat(row.keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            MybatisPlusPayrollCostAnalyticsSource.COLUMNS);
            assertThat(row)
                    .containsEntry("amount", "4680.03")
                    .containsEntry("cost_date", "2024-02-29")
                    .containsEntry("status_code", "CONFIRMED")
                    .containsEntry("payment_status_code", "UNPAID");
            assertThat(page.items().get(1))
                    .containsEntry("status_code", "CONFIRMED")
                    .containsEntry("payment_status_code", "PAID");
            assertThat(row.get("components_json"))
                    .contains("100.02", "-20")
                    .doesNotContain("netPay", "bankAccount");
            assertThat(row.get("data_json")).isEqualTo("{}");
            assertThat(source.page("tenant", page.items().getLast().get("id")).items()).hasSize(1);
            assertThatThrownBy(() -> source.page("tenant", "invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(
                    status -> {
                        mapper.lock("tenant");
                        mapper.advanceAnalyticsVersion("tenant");
                        jdbc.update(
                                "UPDATE hr_payroll SET status_code='REVIEW' WHERE id=?",
                                row.get("id"));
                        assertThat(source.version("tenant")).isEqualTo("payroll-cost-v3:8");
                        assertThat(source.page("tenant", "").version())
                                .isEqualTo(source.version("tenant"));
                        assertThat(source.version("other")).isEqualTo("payroll-cost-v3:22");
                        status.setRollbackOnly();
                    });
            assertThat(source.version("tenant")).isEqualTo("payroll-cost-v3:7");
            assertThat(source.page("tenant", "").items().getFirst().get("id"))
                    .isEqualTo(row.get("id"));
            jdbc.update("UPDATE hr_payroll SET calculation_json='{}' WHERE id=?", row.get("id"));
            assertThatThrownBy(() -> source.page("tenant", "")).hasMessageContaining("缺少应发金额");
        } finally {
            ds.destroy();
        }
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "HEADQUARTERS|SALES|COST_HEADQUARTERS",
                "SALES|HEADQUARTERS|UNKNOWN",
                "CITY_MANAGER|HEADQUARTERS|UNKNOWN",
                "MISSING|HEADQUARTERS|COST_HEADQUARTERS",
                "NULL|HEADQUARTERS|COST_HEADQUARTERS",
                "STRING_NULL|HEADQUARTERS|COST_HEADQUARTERS",
                "EMPTY|HEADQUARTERS|UNKNOWN",
                "MISSING|SALES|UNKNOWN",
                "MISSING|MISSING|UNKNOWN"
            },
            delimiter = '|')
    void headquartersUsesOnlyFrozenMonthlyRoleAndPreservesAllMoney(
            String schemeRole, String salaryCategory, String expected) {
        var mapper = mock(HrPayrollMapper.class);
        var json = JsonMapper.builder().build();
        var calculation = json.createObjectNode();
        calculation.put("baseSalary", new java.math.BigDecimal("8000.01"));
        calculation.put("performance", new java.math.BigDecimal("1000.02"));
        calculation.put("commission", new java.math.BigDecimal("300.03"));
        calculation.put("allowance", new java.math.BigDecimal("100.04"));
        calculation.put("correctionAmount", new java.math.BigDecimal("-20.05"));
        calculation.put("grossPay", new java.math.BigDecimal("9380.05"));
        if (!"MISSING".equals(schemeRole)) {
            var definition =
                    calculation.putObject("scheme").putObject("scheme").putObject("definition");
            if ("NULL".equals(schemeRole)) definition.putNull("role");
            else
                definition.put(
                        "role",
                        "STRING_NULL".equals(schemeRole)
                                ? "null"
                                : "EMPTY".equals(schemeRole) ? "" : schemeRole);
        }
        if (!"MISSING".equals(salaryCategory))
            calculation.putObject("salarySource").put("payrollCategory", salaryCategory);
        var entity = new HrPayrollEntity();
        entity.setId("00000000-0000-0000-0000-000000000001");
        entity.setTenantId("tenant");
        entity.setEmployeeCode("EMP1");
        entity.setEmployeeName("员工");
        entity.setDepartmentId(30L);
        entity.setDepartmentName("研发部");
        entity.setCityName("武汉市");
        entity.setPayrollMonth("2026-10");
        entity.setRevision(3);
        entity.setStatusCode("CONFIRMED");
        entity.setCalculationJson(json.writeValueAsString(calculation));
        when(mapper.analyticsVersion("tenant")).thenReturn("7");
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(entity));
        var source = new MybatisPlusPayrollCostAnalyticsSource(mapper, json);
        var page = source.page("tenant", "");
        var row = page.items().getFirst();
        assertThat(row.get("city_code")).isEqualTo("UNKNOWN".equals(expected) ? null : expected);
        assertThat(row)
                .containsEntry("amount", "9380.05")
                .containsEntry("department_id", "30")
                .containsEntry("department_name", "研发部")
                .containsEntry("status_code", "CONFIRMED")
                .containsEntry("payment_status_code", "UNPAID")
                .containsEntry("data_json", "{}");
        var projectedComponents = json.readTree(row.get("components_json"));
        assertThat(projectedComponents.size()).isEqualTo(5);
        for (String key :
                List.of("baseSalary", "performance", "commission", "allowance", "correctionAmount"))
            assertThat(new java.math.BigDecimal(projectedComponents.path(key).asText()))
                    .isEqualByComparingTo(calculation.path(key).asText());
        assertThat(entity.getCalculationJson()).isEqualTo(json.writeValueAsString(calculation));
        assertThat(entity.getStatusCode()).isEqualTo("CONFIRMED");
        assertThat(page.version()).isEqualTo(source.version("tenant"));
        verify(mapper).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(mapper, times(2)).analyticsVersion("tenant");
        verifyNoMoreInteractions(mapper);
    }
}
