package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostLedger;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.application.port.out.LogisticsImportStore;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** 使用实际平台持久化适配器，验证金额保护、分摊、幂等与租户边界。 */
class TransportSavedCostPersistenceTest {
    final JsonMapper json = JsonMapper.builder().build();
    SingleConnectionDataSource ds;
    JdbcTemplate db;
    JdbcPlatformTransportStore store;

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
                "CREATE TABLE order_logistics_cost(tenant_id VARCHAR(64),id VARCHAR(36),status_code"
                    + " VARCHAR(20),deleted INT DEFAULT 0,revision INT DEFAULT"
                    + " 1,amount_manually_edited BOOLEAN DEFAULT FALSE,amount"
                    + " DECIMAL(16,2),cost_date DATE,data_json LONGTEXT,sources_json LONGTEXT"
                    + " DEFAULT '[]',updated_time TIMESTAMP,PRIMARY KEY(tenant_id,id))");
        db.execute(
                "CREATE TABLE order_logistics_cost_guard(tenant_id VARCHAR(64) PRIMARY KEY,version"
                    + " BIGINT)");
        db.execute(
                "CREATE TABLE order_logistics_cost_audit(tenant_id VARCHAR(64),entry_id"
                    + " VARCHAR(36),action_code VARCHAR(40),actor_id VARCHAR(36),reason"
                    + " VARCHAR(500),snapshot_json LONGTEXT)");
        db.update("INSERT INTO order_logistics_cost_guard VALUES('T',0),('OTHER',0)");
        store = new JdbcPlatformTransportStore(db, json, mock(LogisticsImportStore.class));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    void seed(String tenant, String state, boolean manual) {
        String body =
                """
                {"amount":100.01,"costDate":"2026-11-10","ledger":{"schemaVersion":2,
                  "feeItems":[{"feeKey":"freight","bearerCode":"COMPANY","allocationMethodCode":"AMOUNT","feeAmount":100.01,"ourAmount":100.01}],
                  "sources":[
                    {"orderTypeCode":"SALES_ORDER","orderCode":"SO1","linkRoleCode":"COST","documentAmount":6000,
                     "allocations":[{"feeKey":"freight","receivingPointCode":"TO1","cityCode":"WH","basis":6000,"amount":60.01}]},
                    {"orderTypeCode":"SALES_ORDER","orderCode":"SO2","linkRoleCode":"COST","documentAmount":4000,
                     "allocations":[{"feeKey":"freight","receivingPointCode":"TO2","cityCode":"WH","basis":4000,"amount":40.00}]}]}}
                """;
        var data = (ObjectNode) json.readTree(body);
        var sources = ((ObjectNode) data.path("ledger")).remove("sources");
        db.update(
                "INSERT INTO"
                    + " order_logistics_cost(tenant_id,id,status_code,amount_manually_edited,amount,cost_date,data_json,sources_json)"
                    + " VALUES(?,'C',?,?,100.01,'2026-11-10',?,?)",
                tenant,
                state,
                manual ? 1 : 0,
                json.writeValueAsString(data),
                json.writeValueAsString(sources));
    }

    Row incoming(String amount) {
        var order =
                json.readValue(
                        "{\"enterpriseId\":\"E\",\"orderNo\":\"HL1\",\"status\":\"已完成\",\"useTime\":\"2026-11-10"
                            + " 10:00:00\",\"line\":0,\"raw\":{},\"facts\":{\"billAmount\":"
                                + amount
                                + "}}",
                        CarrierOrder.class);
        return new Row(
                "R", 1, "GENERATED", "C", "API", order, "API", false, order, null, null, null);
    }

    LogisticsCostCommand saved(String tenant) {
        return json.readValue(
                db.queryForObject(
                        "SELECT data_json FROM order_logistics_cost WHERE tenant_id=? AND id='C'",
                        String.class,
                        tenant),
                LogisticsCostCommand.class);
    }

    LogisticsCostLedger.Source[] savedSources(String tenant) {
        return json.readValue(
                db.queryForObject(
                        "SELECT sources_json FROM order_logistics_cost WHERE tenant_id=? AND id='C'",
                        String.class,
                        tenant),
                LogisticsCostLedger.Source[].class);
    }

    @Test
    void uneditedCostRefreshesAndReallocatesExactlyOnceWithoutCrossingTenant() {
        seed("T", "ACTIVE", false);
        seed("OTHER", "ACTIVE", false);
        store.materialize("T", "U", incoming("137.64"));
        var result = saved("T");
        assertThat(result.amount()).isEqualByComparingTo("137.64");
        assertThat(result.ledger().feeItems().getFirst().ourAmount())
                .isEqualByComparingTo("137.64");
        var sources = savedSources("T");
        assertThat(sources).hasSize(2);
        assertThat(sources[0].allocations().getFirst().amount())
                .isEqualByComparingTo("82.58");
        assertThat(sources[1].allocations().getFirst().amount())
                .isEqualByComparingTo("55.06");
        store.materialize("T", "U", incoming("137.64"));
        assertThat(
                        db.queryForObject(
                                "SELECT revision FROM order_logistics_cost WHERE tenant_id='T'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_logistics_cost_audit", Integer.class))
                .isEqualTo(1);
        assertThat(saved("OTHER").amount()).isEqualByComparingTo("100.01");
    }

    @Test
    void manualMoneyAndAllocationsSurviveEveryRepeatedPlatformRefresh() {
        seed("T", "ACTIVE", true);
        for (int i = 0; i < 3; i++) store.materialize("T", "U", incoming("142.64"));
        assertThat(saved("T").amount()).isEqualByComparingTo("100.01");
        assertThat(savedSources("T")[0].allocations().getFirst().amount())
                .isEqualByComparingTo("60.01");
        assertThat(db.queryForObject("SELECT revision FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void numericDriverFlagsAlsoProtectManuallyEditedCosts() {
        db.execute(
                "ALTER TABLE order_logistics_cost ALTER COLUMN amount_manually_edited INT DEFAULT"
                    + " 0");
        seed("T", "ACTIVE", true);
        store.materialize("T", "U", incoming("142.64"));
        assertThat(saved("T").amount()).isEqualByComparingTo("100.01");
        assertThat(db.queryForObject("SELECT revision FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> JdbcFlags.isSet(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JdbcFlags.isSet("false"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void voidCostIsNeverReactivatedAndUnknownMoneyIsNeverChangedToZero() {
        seed("T", "VOID", false);
        store.materialize("T", "U", incoming("137.64"));
        assertThat(saved("T").amount()).isEqualByComparingTo("100.01");
        db.update("UPDATE order_logistics_cost SET status_code='ACTIVE' WHERE tenant_id='T'");
        store.materialize("T", "U", incoming("null"));
        assertThat(saved("T").amount()).isNull();
        assertThat(saved("T").ledger().feeItems().getFirst().ourAmount()).isNull();
    }
}
