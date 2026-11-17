package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.infrastructure.persistence.mapper.IntegrationDeadLetterMapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.*;

class DhbShipmentIssuePagingTest {
    SingleConnectionDataSource ds;
    JdbcTemplate db;

    @BeforeEach
    void setup() {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE integration_dead_letter(id INT,tenant_id VARCHAR,run_id INT,source_id"
                        + " VARCHAR,source_object_type VARCHAR,status VARCHAR,updated_at"
                        + " TIMESTAMP,created_at TIMESTAMP)");
        db.execute("CREATE TABLE integration_sync_run(id INT,tenant_id VARCHAR,task_id INT)");
        db.execute(
                "CREATE TABLE integration_sync_task(id INT,tenant_id VARCHAR,connector_id"
                        + " VARCHAR)");
        db.update(
                "INSERT INTO integration_sync_task"
                    + " VALUES(1,'TENANT','CONNECTOR'),(2,'TENANT','OTHER'),(3,'OTHER','CONNECTOR')");
        db.update(
                "INSERT INTO integration_sync_run"
                        + " VALUES(1,'TENANT',1),(2,'TENANT',1),(3,'TENANT',2),(4,'OTHER',3)");
        for (int i = 0; i < 25; i++)
            db.update(
                    "INSERT INTO integration_dead_letter"
                            + " VALUES(?,'TENANT',1,?,'SALES_SHIPMENT','OPEN','2026-11-17"
                            + " 13:00:00','2026-11-17 13:00:00')",
                    i,
                    "FH" + String.format("%02d", i));
        db.update(
                "INSERT INTO integration_dead_letter"
                    + " VALUES(100,'TENANT',2,'FH00','SALES_SHIPMENT','OPEN','2026-11-17"
                    + " 14:00:00','2026-11-17"
                    + " 14:00:00'),(101,'TENANT',1,'CLOSED','SALES_SHIPMENT','RESOLVED','2026-11-17"
                    + " 15:00:00','2026-11-17"
                    + " 15:00:00'),(102,'TENANT',3,'OTHER','SALES_SHIPMENT','OPEN','2026-11-17"
                    + " 15:00:00','2026-11-17"
                    + " 15:00:00'),(103,'OTHER',4,'OTHER-TENANT','SALES_SHIPMENT','OPEN','2026-11-17"
                    + " 15:00:00','2026-11-17"
                    + " 15:00:00'),(104,'TENANT',1,'TRANSFER','ERP_STOCK_OUT','OPEN','2026-11-17"
                    + " 15:00:00','2026-11-17 15:00:00')");
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void pagesCountUniqueShipmentsAndNeverMixTenantsConnectionsOrTransferIssues() throws Exception {
        var method =
                IntegrationDeadLetterMapper.class.getMethod(
                        "shipmentIssues", byte[].class, byte[].class, int.class, int.class);
        String sql =
                method.getAnnotation(Select.class)
                        .value()[0]
                        .replace("#{tenant}", "?")
                        .replace("#{connector}", "?")
                        .replace("#{limit}", "?")
                        .replace("#{offset}", "?");
        var first = db.queryForList(sql, "TENANT", "CONNECTOR", 20, 0);
        var second = db.queryForList(sql, "TENANT", "CONNECTOR", 20, 20);
        assertThat(first).hasSize(20);
        assertThat(second).hasSize(5);
        assertThat(first.getFirst().get("id")).isEqualTo(100);
        var ids = new HashSet<Object>();
        for (var row : first) ids.add(row.get("source_id"));
        for (var row : second) assertThat(ids.add(row.get("source_id"))).isTrue();
        assertThat(ids).hasSize(25).doesNotContain("CLOSED", "OTHER", "OTHER-TENANT", "TRANSFER");
        var count =
                IntegrationDeadLetterMapper.class
                        .getMethod("shipmentIssueCount", byte[].class, byte[].class)
                        .getAnnotation(Select.class)
                        .value()[0]
                        .replace("#{tenant}", "?")
                        .replace("#{connector}", "?");
        assertThat(db.queryForObject(count, Long.class, "TENANT", "CONNECTOR")).isEqualTo(25);
    }
}
