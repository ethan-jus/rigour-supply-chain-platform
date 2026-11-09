package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

class LogisticsImportPersistenceTest {
    JdbcTemplate jdbc;
    JdbcLogisticsImportStore store;

    @BeforeEach
    void setup() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                """
CREATE TABLE order_logistics_import_order(id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(36),enterprise_id VARCHAR(80),carrier_order_no VARCHAR(80),file_name VARCHAR(200),file_hash VARCHAR(64),source_json CLOB,state VARCHAR(20),cost_id VARCHAR(36),revision INT,created_by VARCHAR(50),created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,carrier_order_no))
""");
        jdbc.execute(
                "CREATE TABLE order_logistics_cost(tenant_id VARCHAR(36),carrier_code"
                    + " VARCHAR(40),tracking_no VARCHAR(100))");
        store = new JdbcLogisticsImportStore(jdbc, JsonMapper.builder().build());
    }

    Upload upload(String enterprise, String amount) {
        return new Upload(
                "货拉拉.xlsx",
                "a".repeat(64),
                List.of(
                        new CarrierOrder(
                                enterprise,
                                "3616894445226123730",
                                "已完成",
                                "2026-11-06",
                                new BigDecimal(amount),
                                Map.of("收货人信息", "星河门店"),
                                List.of(),
                                4)));
    }

    @Test
    void repeatUploadRetainsOriginalReceiptAndNeverDuplicatesAcrossAccountsWithinTenant() {
        var first = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        var repeated = store.stage("T1", "U1", upload("AC1", "99.00")).getFirst();
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.order().paidAmount()).isEqualByComparingTo("40.50");
        assertThat(repeated.order().orderNo()).isEqualTo("3616894445226123730");
        assertThat(store.stage("T1", "U2", upload("AC2", "99.00"))).isEmpty();
        assertThat(store.rows("T1", "U1", 0)).hasSize(1);
        assertThat(store.stage("T2", "U2", upload("AC1", "20"))).hasSize(1);
        assertThat(store.rows("T1", "U2", 0)).isEmpty();
    }

    @Test
    void actorTenantAndRevisionProtectLinkingAndGeneratedReceiptSurvivesReupload() {
        var first = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        assertThatThrownBy(() -> store.lock("T1", "U2", first.id())).hasMessageContaining("无权限");
        assertThatThrownBy(() -> store.lock("T2", "U1", first.id())).hasMessageContaining("无权限");
        store.generated("T1", first.id(), 1, "C1");
        assertThatThrownBy(() -> store.generated("T1", first.id(), 1, "C2"))
                .hasMessageContaining("已变化");
        var repeated = store.stage("T1", "U1", upload("AC1", "40.50")).getFirst();
        assertThat(repeated.costId()).isEqualTo("C1");
        assertThat(repeated.state()).isEqualTo("GENERATED");
        assertThat(repeated.revision()).isEqualTo(2);
    }
}
