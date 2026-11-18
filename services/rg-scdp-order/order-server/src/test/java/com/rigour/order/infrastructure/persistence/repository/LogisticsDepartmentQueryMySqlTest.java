package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

/** 使用当前 MySQL 的单连接临时表验证生产候选筛选，不修改业务表。 */
@EnabledIfEnvironmentVariable(named = "DHB_MYSQL_TEST_URL", matches = ".+")
class LogisticsDepartmentQueryMySqlTest {
    @Test
    void findsOnlyActiveMissingDepartmentsAndKeepsTenantAndPageBoundaries() throws Exception {
        var connection =
                DriverManager.getConnection(
                        System.getenv("DHB_MYSQL_TEST_URL"),
                        System.getenv("DHB_MYSQL_TEST_USER"),
                        System.getenv("DHB_MYSQL_TEST_PASSWORD"));
        var source = new SingleConnectionDataSource(connection, true);
        try {
            var db = new JdbcTemplate(source);
            db.execute(
                    "CREATE TEMPORARY TABLE order_logistics_cost (id VARCHAR(16),tenant_id"
                        + " VARCHAR(16),category_code VARCHAR(24),status_code VARCHAR(24),deleted"
                        + " INT,sources_json JSON)");
            for (String id : new String[] {"A1", "A2", "OTHER", "VOID", "DELETED"})
                db.update(
                        "INSERT INTO order_logistics_cost VALUES(?,?,'LOGISTICS',?,?,?)",
                        id,
                        "OTHER".equals(id) ? "T2" : "T1",
                        "VOID".equals(id) ? "VOID" : "ACTIVE",
                        "DELETED".equals(id) ? 1 : 0,
                        "[{\"employeeCode\":\"EMP1\",\"departmentId\":null,\"departmentName\":null}]");
            db.update(
                    "INSERT INTO order_logistics_cost"
                        + " VALUES('COMPLETE','T1','LOGISTICS','ACTIVE',0,?)",
                    "[{\"employeeCode\":\"EMP1\",\"departmentId\":6,\"departmentName\":\"北京市\"}]");
            db.update(
                    "INSERT INTO order_logistics_cost"
                        + " VALUES('NO_EMP','T1','LOGISTICS','ACTIVE',0,?)",
                    "[{\"employeeCode\":null,\"departmentId\":null}]");
            var method =
                    MybatisPlusOrderLogisticsCostRepository.class.getDeclaredMethod(
                            "missingDepartmentSql");
            method.setAccessible(true);
            var predicate = (String) method.invoke(null);
            var where =
                    " FROM order_logistics_cost WHERE category_code='LOGISTICS'"
                            + " AND status_code='ACTIVE' AND deleted=0 AND "
                            + predicate;
            assertThat(
                            db.queryForList(
                                    "SELECT DISTINCT tenant_id" + where + " ORDER BY tenant_id",
                                    String.class))
                    .containsExactly("T1", "T2");
            assertThat(
                            db.queryForList(
                                    "SELECT id"
                                            + where
                                            + " AND tenant_id=? AND id>? ORDER BY id LIMIT 100",
                                    String.class,
                                    "T1",
                                    "A1"))
                    .containsExactly("A2");
        } finally {
            source.destroy();
        }
    }
}
