package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.util.*;

/** 单连接临时表遮蔽同名表，验证历史归属读取；不写真实 HR 资料。 */
@EnabledIfEnvironmentVariable(named = "DHB_MYSQL_TEST_URL", matches = ".+")
class HrHistoricalDepartmentMySqlTest {
    @Test
    void tenantBoundSnapshotsAndAssignmentsIncludeLeftAndPendingWithoutModifyingEmployees()
            throws Exception {
        var connection =
                DriverManager.getConnection(
                        System.getenv("DHB_MYSQL_TEST_URL"),
                        System.getenv("DHB_MYSQL_TEST_USER"),
                        System.getenv("DHB_MYSQL_TEST_PASSWORD"));
        var dataSource = new SingleConnectionDataSource(connection, true);
        try {
            var db = new JdbcTemplate(dataSource);
            db.execute(
                    "CREATE TEMPORARY TABLE hr_employee (tenant_id VARCHAR(16),employee_code"
                            + " VARCHAR(16),employee_name VARCHAR(50),department_id"
                            + " BIGINT,department_name_snapshot VARCHAR(128),deleted"
                            + " INT,employment_status VARCHAR(16))");
            db.execute(
                    "CREATE TEMPORARY TABLE hr_department (tenant_id VARCHAR(16),id"
                            + " BIGINT,department_name VARCHAR(128))");
            db.execute(
                    "CREATE TEMPORARY TABLE hr_employee_assignment (id BIGINT,tenant_id"
                            + " VARCHAR(16),employee_code VARCHAR(16),department_id"
                            + " BIGINT,department_name_snapshot VARCHAR(128),effective_from"
                            + " DATETIME(6),effective_to DATETIME(6))");
            db.update(
                    "INSERT INTO hr_employee VALUES"
                        + " ('T1','E1','离职员工',6,'旧部门',0,'LEFT'),('T1','E2','待完善员工',7,'导入部门',0,'PENDING'),('T2','E1','另一租户',99,'其他部门',0,'ACTIVE')");
            db.update("INSERT INTO hr_department VALUES ('T1',6,'已改名部门'),('T2',6,'跨租户同ID部门')");
            db.update(
                    "INSERT INTO hr_employee_assignment VALUES"
                        + " (1,'T1','E1',6,'历史部门','2026-07-01','2026-10-01'),(2,'T2','E1',99,'另一租户历史部门','2026-07-01',NULL)");
            var store = new JdbcHrHistoricalDepartmentStore(db);
            assertThat(store.employees("T1", Set.of("E1", "E2")))
                    .extracting(e -> e.departmentName())
                    .containsExactlyInAnyOrder("旧部门", "导入部门");
            assertThat(store.assignments("T1", Set.of("E1", "E2")))
                    .hasSize(1)
                    .allSatisfy(a -> assertThat(a.departmentName()).isEqualTo("历史部门"));
            assertThat(store.employees("T1", Set.of("E1' OR 1=1 --"))).isEmpty();
            assertThat(
                            db.queryForList(
                                    "SELECT employment_status FROM hr_employee WHERE tenant_id='T1'"
                                            + " ORDER BY employee_code",
                                    String.class))
                    .containsExactly("LEFT", "PENDING");
        } finally {
            dataSource.destroy();
        }
    }
}
