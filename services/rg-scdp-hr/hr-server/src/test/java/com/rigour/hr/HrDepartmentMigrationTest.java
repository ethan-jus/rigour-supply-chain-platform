package com.rigour.hr;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class HrDepartmentMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("hr_upgrade");

    @Test
    void upgradePreservesExistingDepartmentsAndBackfillsOnlyTheirOwnActors() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO hr_department(id,tenant_id,department_code,department_name,created_time,updated_time) VALUES(1,'a','DEP1','已有部门','2020-01-02 03:04:05','2021-02-03 04:05:06'),(2,'a','DEP2','没有历史的部门','2020-01-02 03:04:05','2020-01-02 03:04:05')");
            sql.executeUpdate("INSERT INTO hr_organization_change(tenant_id,version,event_type,object_ref,actor_id) VALUES('a',1,'DEPARTMENT_CREATE','1','creator'),('a',2,'DEPARTMENT_UPDATE','1','editor'),('b',99,'DEPARTMENT_UPDATE','1','other-tenant')");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement(); var rs = sql.executeQuery("SELECT * FROM hr_department ORDER BY id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("department_name")).isEqualTo("已有部门");
            assertThat(rs.getString("created_time")).startsWith("2020-01-02 03:04:05");
            assertThat(rs.getString("updated_time")).startsWith("2021-02-03 04:05:06");
            assertThat(rs.getString("created_by")).isEqualTo("creator");
            assertThat(rs.getString("updated_by")).isEqualTo("editor");
            assertThat(rs.getString("leader_name")).isNull();
            assertThat(rs.getString("established_date")).isNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("created_by")).isNull();
            assertThat(rs.getString("updated_by")).isNull();
        }
    }
}
