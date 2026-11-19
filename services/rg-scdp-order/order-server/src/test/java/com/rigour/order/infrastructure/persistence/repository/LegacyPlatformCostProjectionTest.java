package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.infrastructure.persistence.mapper.OrderLogisticsCostMapper;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class LegacyPlatformCostProjectionTest {
    final JsonMapper json = JsonMapper.builder().build();
    SingleConnectionDataSource ds;
    JdbcTemplate db;
    MybatisPlusCostAnalyticsSource source;

    @BeforeEach
    void setup() {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE order_logistics_import_order(tenant_id VARCHAR(64),cost_id"
                    + " VARCHAR(36),business_json LONGTEXT)");
        source = new MybatisPlusCostAnalyticsSource(mock(OrderLogisticsCostMapper.class), json);
        source.attribution(db);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    Map<String, String> row(String amount) {
        var row = new HashMap<String, String>();
        row.put("id", "C1");
        row.put("amount", amount);
        row.put("cost_date", "2026-11-10");
        row.put("source_system", "HUOLALA");
        row.put("status_code", "ACTIVE");
        row.put("data_json", "{}");
        row.put(
                "sources_json",
                """
                [{"documentNo":"SO1","sourceType":"SALES_ORDER","cityCode":"WH","documentAmount":6000,"employeeCode":"EMP1"},
                 {"documentNo":"SO2","sourceType":"SALES_ORDER","cityCode":"WH","documentAmount":4000,"employeeCode":"EMP2"}]
                """);
        return row;
    }

    @Test
    void oldSavedBusinessSharesAreProjectedWithoutRequiringConfirmationOrReadingAnotherTenant() {
        db.update(
                "INSERT INTO order_logistics_import_order VALUES('T','C1',?)",
                """
                {"transportType":"DELIVERY","orderAllocations":{"SO1":80,"SO2":20}}
                """);
        db.update(
                "INSERT INTO order_logistics_import_order VALUES('OTHER','C1',?)",
                """
                {"transportType":"DELIVERY","orderAllocations":{"SO1":1,"SO2":99}}
                """);
        var row = row("100.00");
        source.enrichSavedPlatformAllocations("T", List.of(row));
        var links = json.readTree(row.get("sources_json"));
        assertThat(links.get(0).path("allocations").get(0).path("amount").decimalValue())
                .isEqualByComparingTo("80");
        assertThat(links.get(1).path("allocations").get(0).path("amount").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(links.get(0).path("employeeCode").asText()).isEqualTo("EMP1");
        assertThat(row.get("status_code")).isEqualTo("ACTIVE");
        assertThat(
                        db.queryForObject(
                                "SELECT business_json FROM order_logistics_import_order WHERE"
                                    + " tenant_id='T'",
                                String.class))
                .contains("80");
    }

    @Test
    void brokenOldAllocationStaysUnassignedAndUnknownMoneyStaysUnknown() {
        db.update(
                "INSERT INTO order_logistics_import_order VALUES('T','C1',?)",
                """
                {"transportType":"DELIVERY","orderAllocations":{"SO1":1,"SO2":2}}
                """);
        var row = row("100.00");
        source.enrichSavedPlatformAllocations("T", List.of(row));
        assertThat(row.get("data_json")).isEqualTo("{}");
        assertThat(row.get("amount")).isEqualTo("100.00");
        db.update(
                "UPDATE order_logistics_import_order SET"
                    + " business_json='{\"transportType\":\"DELIVERY\"}' WHERE tenant_id='T'");
        var unknown = row(null);
        source.enrichSavedPlatformAllocations("T", List.of(unknown));
        assertThat(unknown.get("amount")).isNull();
        assertThat(
                        json.readTree(unknown.get("data_json"))
                                .path("ledger")
                                .path("feeItems")
                                .get(0)
                                .path("ourAmount")
                                .isNull())
                .isTrue();
    }
}
