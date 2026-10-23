package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/** 实际执行同城、任期和人员权限的 SQL 交集，不连接共享数据库。 */
public class HrExpenseEmployeeCityTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusHrPayrollRepository store;
    HrDataScope scope;

    public static Timestamp convertTz(Timestamp value, String from, String to) {
        return value;
    }

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:expense_city_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS CONVERT_TZ FOR"
                    + " 'com.rigour.hr.infrastructure.persistence.repository.HrExpenseEmployeeCityTest.convertTz'");
        jdbc.execute(
                "CREATE TABLE hr_employee(id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),employee_code"
                    + " VARCHAR(64),employee_name VARCHAR(120),department_id BIGINT,city_name"
                    + " VARCHAR(120),entry_date TIMESTAMP,leave_date TIMESTAMP,payroll_category"
                    + " VARCHAR(50),revision INT,deleted INT)");
        jdbc.execute(
                "CREATE TABLE hr_department(id BIGINT,tenant_id VARCHAR(64),department_name"
                    + " VARCHAR(120))");
        jdbc.execute(
                "CREATE TABLE hr_payroll_rule(id VARCHAR(64),tenant_id VARCHAR(64),request_id"
                    + " VARCHAR(64),kind_code VARCHAR(50),name VARCHAR(120),employee_code"
                    + " VARCHAR(64),effective_from DATE,effective_to DATE,enabled BOOLEAN,data_json"
                    + " CLOB,revision INT,created_by VARCHAR(64),updated_by"
                    + " VARCHAR(64),created_time TIMESTAMP,updated_time TIMESTAMP)");
        jdbc.update("INSERT INTO hr_department VALUES(1,'T1','武汉市'),(2,'T1','北京市')");
        employee(1, "T1", "WH", "武汉员工", "武汉市", 2, 0);
        employee(2, "T1", "WH_OLD_NAME", "武汉简称员工", "武汉", 1, 0);
        employee(3, "T1", "MANAGER", "多城负责人", "北京市", 2, 0);
        employee(4, "T1", "OTHER", "杭州员工武汉部门", "杭州市", 1, 0);
        employee(5, "T2", "OTHER_TENANT", "其他租户员工", "武汉市", 1, 0);
        employee(6, "T1", "DELETED", "已删除员工", "武汉市", 1, 1);
        manager("tenure", "T1", "MANAGER", "4201", "2026-10-01", "2026-10-31", true);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(HrPayrollReferenceMapper.class);
        config.addMapper(HrPayrollRuleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        scope = mock(HrDataScope.class);
        when(scope.employeePredicate("hr:expense:read", "e."))
                .thenReturn(new HrDataScope.Predicate("1=1", List.of()));
        store =
                new MybatisPlusHrPayrollRepository(
                        mock(HrPayrollMapper.class),
                        session.getMapper(HrPayrollRuleMapper.class),
                        session.getMapper(HrPayrollReferenceMapper.class),
                        mock(HrPayrollPersistenceSupport.class),
                        JsonMapper.builder().build(),
                        scope,
                        mock(HrPayrollPaymentMapper.class),
                        mock(HrPayrollCorrectionMapper.class),
                        mock(HrPayrollCommissionReleaseMapper.class));
    }

    void employee(
            long id,
            String tenant,
            String code,
            String name,
            String city,
            long department,
            int deleted) {
        jdbc.update(
                "INSERT INTO"
                    + " hr_employee(id,tenant_id,employee_code,employee_name,city_name,department_id,deleted,revision)"
                    + " VALUES(?,?,?,?,?,?,?,1)",
                id,
                tenant,
                code,
                name,
                city,
                department,
                deleted);
    }

    void manager(
            String id,
            String tenant,
            String code,
            String city,
            String from,
            String to,
            boolean enabled) {
        jdbc.update(
                "INSERT INTO"
                    + " hr_payroll_rule(id,tenant_id,employee_code,kind_code,enabled,effective_from,effective_to,data_json,revision)"
                    + " VALUES(?,?,?,'CITY_MANAGER',?,?,?,?,1)",
                id,
                tenant,
                code,
                enabled,
                from,
                to,
                "{\"cityCode\":\"" + city + "\"}");
    }

    List<String> codes(String date) {
        return store
                .expenseEmployees("T1", "hr:expense:read", "4201", "武汉市", LocalDate.parse(date))
                .stream()
                .map(PayrollEmployeeView::employeeCode)
                .toList();
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void cityUsesEmployeeProfileAndEffectiveManagementRatherThanDepartmentName() {
        assertThat(codes("2026-10-22")).containsExactly("MANAGER", "WH", "WH_OLD_NAME");
    }

    @Test
    void managementDatesAreInclusiveAndExpiredDisabledOtherTenantRulesDoNotApply() {
        manager("disabled", "T1", "OTHER", "4201", "2026-10-01", "2026-10-31", false);
        manager("foreign", "T2", "OTHER", "4201", "2026-10-01", "2026-10-31", true);
        assertThat(codes("2026-10-01")).contains("MANAGER").doesNotContain("OTHER");
        assertThat(codes("2026-10-31")).contains("MANAGER");
        assertThat(codes("2026-11-01")).doesNotContain("MANAGER");
        assertThat(codes("2026-09-30")).doesNotContain("MANAGER");
    }

    @Test
    void multiCityManagerIsSelectableInEachEffectiveCityWithoutDuplicates() {
        manager("other-city", "T1", "MANAGER", "3301", "2026-10-01", null, true);
        manager("same-city", "T1", "MANAGER", "4201", "2026-10-10", "2026-10-31", true);
        assertThat(codes("2026-10-22")).containsExactly("MANAGER", "WH", "WH_OLD_NAME");
        assertThat(
                        store
                                .expenseEmployees(
                                        "T1",
                                        "hr:expense:read",
                                        "3301",
                                        "杭州市",
                                        LocalDate.of(2026, 10, 22))
                                .stream()
                                .map(PayrollEmployeeView::employeeCode))
                .containsExactly("MANAGER", "OTHER");
    }

    @Test
    void scopedUserCannotReadOtherEmployeesEvenIfTheyManageTheCity() {
        when(scope.employeePredicate("hr:expense:read", "e."))
                .thenReturn(new HrDataScope.Predicate("e.employee_code=?", List.of("WH")));
        assertThat(codes("2026-10-22")).containsExactly("WH");
    }
}
