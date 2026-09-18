package com.rigour.hr;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class HrPositionMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("hr_positions");
    @Test void normalizesCatalogAndEmployeeReferencesWithoutKeepingLegacyDimensions() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).locations("classpath:db/migration").target("5").load().migrate();
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO hr_position(tenant_id,position_code,position_name,position_type,status_code) VALUES('a','SALE','销售','JOB_CATEGORY','ACTIVE'),('a','SELLER','销售员','JOB_TITLE','ACTIVE'),('a','KEY_ACCOUNT','大客户经理','JOB_TITLE','ACTIVE')");
            s.executeUpdate("INSERT INTO hr_employee(tenant_id,employee_code,employee_name,primary_position_code,employment_status) VALUES('a','E1','测试员工','KEY_ACCOUNT','ACTIVE')");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); var s = c.createStatement()) {
            try(var r=s.executeQuery("SELECT COUNT(*) FROM hr_position WHERE tenant_id='a' AND deleted=0")) {r.next();assertThat(r.getInt(1)).isEqualTo(11);}
            try(var r=s.executeQuery("SELECT p.position_name,e.primary_position_name_snapshot FROM hr_employee e JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code WHERE e.employee_code='E1' AND p.deleted=0")) {assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("业务员");assertThat(r.getString(2)).isEqualTo("业务员");}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='hr_position' AND column_name IN ('position_type','source_system')")) {r.next();assertThat(r.getInt(1)).isZero();}
        }
    }
}
