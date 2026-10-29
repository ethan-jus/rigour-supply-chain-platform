package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class HrCreationAuditMigrationTest {
    @Test
    void migrationAddsNullableCreationFactsWithoutInventingHistory() throws Exception {
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:audit_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE hr_payroll_component_version(id VARCHAR(36),created_by VARCHAR(50),created_time DATETIME)");
            jdbc.execute("CREATE TABLE hr_payroll(id VARCHAR(36),created_by VARCHAR(50),created_time DATETIME)");
            jdbc.execute("CREATE TABLE hr_attendance_rule(id VARCHAR(36),updated_by VARCHAR(50),updated_time DATETIME)");
            jdbc.update("INSERT INTO hr_attendance_rule VALUES('old','last-editor','2026-09-02 12:00:00')");
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V28__hr_rule_and_payroll_creation_audit.sql"))
                    .replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", "")
                    .replace(",\n ADD COLUMN", ";\nALTER TABLE hr_attendance_rule ADD COLUMN");
            for (String statement : migration.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            var old = jdbc.queryForMap("SELECT * FROM hr_attendance_rule WHERE id='old'");
            assertThat(old.get("created_by")).isNull();
            assertThat(old.get("created_by_name")).isNull();
            assertThat(old.get("created_time")).isNull();
            assertThat(old.get("updated_by")).isEqualTo("last-editor");
            jdbc.update("INSERT INTO hr_attendance_rule(id,created_by,created_by_name,created_time) VALUES('new','creator','创建人甲','2026-10-01 09:00:00')");
            jdbc.update("UPDATE hr_attendance_rule SET updated_by='editor' WHERE id='new'");
            assertThat(jdbc.queryForObject("SELECT created_by_name FROM hr_attendance_rule WHERE id='new'", String.class)).isEqualTo("创建人甲");
        } finally { ds.destroy(); }
    }
}
