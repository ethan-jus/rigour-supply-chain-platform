package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/** MySQL 的时间类型转换和 TINYINT(1) 驱动返回值不能只由 H2 代替验证。 */
@Testcontainers(disabledWithoutDocker = true)
class TransportTimeMySqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Test
    void mixedSourceTimesSortBeforePaginationAndNeverUseOtherTenantShipment() throws Exception {
        String fixture =
                """
                WITH order_logistics_cost AS (
                  SELECT 'T' tenant_id,'hll' id,'HUOLALA' source_system,DATE '2026-11-01' cost_date,CAST(NULL AS CHAR) data_json
                  UNION ALL SELECT 'T','dhb','DHB',DATE '2026-11-01',NULL
                  UNION ALL SELECT 'T','manual','MANUAL_ENTRY',DATE '2026-11-16','{"ledger":{"shippingTime":"2026-11-16T08:59:58"}}'
                  UNION ALL SELECT 'T','unknown','HUOLALA',NULL,NULL),
                order_logistics_import_order AS (
                  SELECT 'T' tenant_id,'hll' id,'hll' cost_id,
                    '{"raw":{"下单时间":"2026-11-16 10:30:00"}}' source_json,
                    CAST(NULL AS CHAR) latest_source_json),
                order_transport_shipment_source AS (
                  SELECT 'T' tenant_id,'dhb' cost_id,'{"shipTime":"2026-11-15T17:00:00Z"}' latest_json
                  UNION ALL SELECT 'T','dhb','{"shipTime":"2026-11-16T03:45:00Z"}'
                  UNION ALL SELECT 'OTHER','manual','{"shipTime":"2027-01-01T00:00:00Z"}')
                SELECT id,%s AS event_time FROM order_logistics_cost ORDER BY event_time DESC,id DESC
                """
                        .formatted(MybatisPlusOrderLogisticsCostRepository.transportTimeSql());
        try (var c =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var s = c.createStatement()) {
            var ids = new ArrayList<String>();
            try (var r = s.executeQuery(fixture)) {
                while (r.next()) {
                    ids.add(r.getString(1));
                    if ("dhb".equals(r.getString(1)))
                        assertThat(r.getString(2)).isEqualTo("2026-11-16 01:00:00");
                    if ("unknown".equals(r.getString(1))) assertThat(r.getString(2)).isNull();
                }
            }
            assertThat(ids).containsExactly("hll", "manual", "dhb", "unknown");
            try (var r = s.executeQuery(fixture + " LIMIT 2 OFFSET 1")) {
                var secondPage = new ArrayList<String>();
                while (r.next()) secondPage.add(r.getString(1));
                assertThat(secondPage).containsExactly("manual", "dhb");
            }
        }
    }

    @Test
    void nativeBooleanFlagsProtectManuallyMaintainedAmounts() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var s = c.createStatement()) {
            s.execute("CREATE TEMPORARY TABLE transport_flags(value TINYINT(1) NOT NULL)");
            s.execute("INSERT INTO transport_flags VALUES(0),(1)");
            try (var r = s.executeQuery("SELECT value FROM transport_flags ORDER BY value")) {
                for (boolean expected : List.of(false, true)) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getObject(1)).isInstanceOf(Boolean.class);
                    assertThat(JdbcFlags.isSet(r.getObject(1))).isEqualTo(expected);
                }
            }
        }
    }
}
