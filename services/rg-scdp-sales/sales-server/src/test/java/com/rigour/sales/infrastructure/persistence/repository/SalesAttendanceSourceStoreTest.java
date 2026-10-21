package com.rigour.sales.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

public class SalesAttendanceSourceStoreTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusSalesAttendanceSourceStore store;
    final String tenant = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();

    public static byte[] uuid(String value) {
        var id = UUID.fromString(value);
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    public static Timestamp timezone(Timestamp timestamp, String from, String to) {
        return timestamp == null
                ? null
                : Timestamp.valueOf(timestamp.toLocalDateTime().plusHours(8));
    }

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:sales_attendance_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE ALIAS UUID_TO_BIN FOR"
                    + " 'com.rigour.sales.infrastructure.persistence.repository.SalesAttendanceSourceStoreTest.uuid'");
        jdbc.execute(
                "CREATE ALIAS CONVERT_TZ FOR"
                    + " 'com.rigour.sales.infrastructure.persistence.repository.SalesAttendanceSourceStoreTest.timezone'");
        jdbc.execute(
                "CREATE TABLE temp_sales_checkin_employee_link(tenant_id BINARY(16),salesperson_id"
                        + " VARCHAR(36),employee_code VARCHAR(50))");
        jdbc.execute(
                "CREATE TABLE temp_sales_checkin_submission(tenant_id BINARY(16),salesperson_id"
                        + " VARCHAR(36),submitted_at DATETIME,status VARCHAR(20),deletion_state"
                        + " VARCHAR(20))");
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(
                com.rigour.sales.infrastructure.persistence.mapper.SalesAttendanceSourceMapper
                        .class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(ds);
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        store =
                new MybatisPlusSalesAttendanceSourceStore(
                        session.getMapper(
                                com.rigour.sales.infrastructure.persistence.mapper
                                        .SalesAttendanceSourceMapper.class));
        jdbc.update(
                "INSERT INTO temp_sales_checkin_employee_link"
                    + " VALUES(UUID_TO_BIN(?),'S1','E1'),(UUID_TO_BIN(?),'S2','E2'),(UUID_TO_BIN(?),'S1','E1')",
                tenant,
                tenant,
                other);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    void punch(String tenantId, String time, String status, String deletion) {
        jdbc.update(
                "INSERT INTO temp_sales_checkin_submission VALUES(UUID_TO_BIN(?),'S1',?,?,?)",
                tenantId,
                Timestamp.valueOf(time),
                status,
                deletion);
    }

    @Test
    void countsSubmittedDatesOnceInShanghaiAndKeepsMissingLinksUnknown() {
        punch(tenant, "2026-08-31 15:59:59", "SUBMITTED", "NONE");
        punch(tenant, "2026-08-31 16:00:00", "SUBMITTED", "NONE");
        punch(tenant, "2026-09-01 01:00:00", "SUBMITTED", "NONE");
        punch(tenant, "2026-09-01 16:00:00", "DRAFT", "NONE");
        punch(tenant, "2026-09-02 01:00:00", "SUBMITTED", "DELETED");
        punch(tenant, "2026-09-30 16:00:00", "SUBMITTED", "NONE");
        punch(other, "2026-09-02 01:00:00", "SUBMITTED", "NONE");
        var rows =
                store.evidence(
                        tenant,
                        new Query(
                                List.of("E1", "E2", "E3"),
                                LocalDate.of(2026, 9, 1),
                                LocalDate.of(2026, 9, 30)));
        assertThat(rows)
                .containsExactly(
                        new Evidence("E1", true, List.of(LocalDate.of(2026, 9, 1))),
                        new Evidence("E2", true, List.of()),
                        new Evidence("E3", false, List.of()));
    }
}
