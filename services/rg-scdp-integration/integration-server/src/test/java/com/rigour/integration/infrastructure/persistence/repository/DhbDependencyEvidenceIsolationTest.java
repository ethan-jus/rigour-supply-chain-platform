package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.rigour.integration.infrastructure.persistence.IntegrationUuidCodec;
import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

/** 执行真实Mapper SQL，验证同号发货不能串来源或租户。 */
class DhbDependencyEvidenceIsolationTest {
    @Test
    void exactConnectorFindsItsOlderIssueEvenWhenAnotherSourceHasNewerSameNumber() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE integration_sync_task(id BINARY(16),tenant_id BINARY(16),connector_id"
                    + " BINARY(16))");
        jdbc.execute(
                "CREATE TABLE integration_sync_run(id BINARY(16),tenant_id BINARY(16),task_id"
                    + " BINARY(16))");
        jdbc.execute(
                "CREATE TABLE integration_dead_letter(id BINARY(16),tenant_id BINARY(16),run_id"
                    + " BINARY(16),source_object_type VARCHAR(64),source_id VARCHAR(128),status"
                    + " VARCHAR(16),updated_at TIMESTAMP,created_at TIMESTAMP)");
        UUID tenant = UUID.randomUUID(),
                otherTenant = UUID.randomUUID(),
                a = UUID.randomUUID(),
                b = UUID.randomUUID();
        UUID expected = seed(jdbc, tenant, a, "SALES_SHIPMENT", "OPEN", 1);
        seed(jdbc, tenant, b, "SALES_SHIPMENT", "OPEN", 3);
        seed(jdbc, otherTenant, a, "SALES_SHIPMENT", "OPEN", 4);
        seed(jdbc, tenant, a, "ERP_STOCK_OUT", "OPEN", 5);
        seed(jdbc, tenant, a, "SALES_SHIPMENT", "RESOLVED", 6);
        var cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.addMapper(IntegrationDeadLetterMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession()) {
            var mapper = session.getMapper(IntegrationDeadLetterMapper.class);
            var result = mapper.findOpenForSource(bin(tenant), bin(a), "SALES_SHIPMENT", "FH-SAME");
            assertThat(IntegrationUuidCodec.decode(result.id)).isEqualTo(expected);
            assertThat(
                            mapper.findOpenForSource(
                                    bin(tenant),
                                    bin(UUID.randomUUID()),
                                    "SALES_SHIPMENT",
                                    "FH-SAME"))
                    .isNull();
            assertThat(
                            mapper.findOpenForSource(
                                    bin(UUID.randomUUID()), bin(a), "SALES_SHIPMENT", "FH-SAME"))
                    .isNull();
        }
    }

    static UUID seed(
            JdbcTemplate jdbc, UUID tenant, UUID connector, String type, String status, int day) {
        UUID task = UUID.randomUUID(), run = UUID.randomUUID(), issue = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO integration_sync_task VALUES(?,?,?)",
                bin(task),
                bin(tenant),
                bin(connector));
        jdbc.update(
                "INSERT INTO integration_sync_run VALUES(?,?,?)", bin(run), bin(tenant), bin(task));
        jdbc.update(
                "INSERT INTO integration_dead_letter VALUES(?,?,?,?,?,?,?,?)",
                bin(issue),
                bin(tenant),
                bin(run),
                type,
                "FH-SAME",
                status,
                LocalDateTime.of(2026, 11, day, 0, 0),
                LocalDateTime.of(2026, 11, day, 0, 0));
        return issue;
    }

    static byte[] bin(UUID id) {
        return IntegrationUuidCodec.encode(id);
    }
}
