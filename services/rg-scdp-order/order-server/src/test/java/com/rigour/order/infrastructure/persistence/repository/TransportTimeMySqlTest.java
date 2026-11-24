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
                  SELECT 'T' tenant_id,'hll' id,'HUOLALA' source_system,DATE '2026-11-01' cost_date,
                    CAST(NULL AS CHAR) data_json,CAST('2026-11-18 20:00:00' AS DATETIME) created_time
                  UNION ALL SELECT 'T','hll-reused','HUOLALA',DATE '2026-11-01',NULL,'2026-11-17 20:00:00'
                  UNION ALL SELECT 'T','dhb-tie','DHB',DATE '2026-11-01',NULL,'2026-11-18 20:00:00'
                  UNION ALL SELECT 'T','dhb','DHB',DATE '2026-11-01',NULL,'2026-11-19 20:00:00'
                  UNION ALL SELECT 'T','manual','MANUAL_ENTRY',DATE '2026-11-16','{"ledger":{"shippingTime":"2026-11-16T08:59:58"}}','2026-11-20 20:00:00'
                  UNION ALL SELECT 'T','date-only','MANUAL_ENTRY',DATE '2026-11-15',NULL,'2026-11-21 20:00:00'
                  UNION ALL SELECT 'T','unknown','HUOLALA',NULL,NULL,'2026-11-22 20:00:00'),
                order_logistics_import_order AS (
                  SELECT 'T' tenant_id,'hll' id,'hll' cost_id,
                    '{"raw":{"下单时间":"2026-11-16 10:30:00"}}' source_json,
                    CAST(NULL AS CHAR) latest_source_json,'API' source_channel
                  UNION ALL SELECT 'T','different-source-id','hll-reused',
                    '{"raw":{"下单时间":"2026-11-16 08:00:00"}}',
                    '{"raw":{"下单时间":" 2026-11-16 12:05:00 "}}','API'
                  UNION ALL SELECT 'T','manual','manual','{"raw":{"下单时间":"2027-01-01 00:00:00"}}',NULL,'EXCEL'
                  UNION ALL SELECT 'OTHER','hll-reused','hll-reused','{"raw":{"下单时间":"2027-01-01 00:00:00"}}',NULL,'API'),
                order_transport_shipment_source AS (
                  SELECT 'T' tenant_id,'dhb' cost_id,'{"shipTime":"2026-11-15T17:00:00Z"}' latest_json
                  UNION ALL SELECT 'T','dhb','{"shipTime":"2026-11-16T03:45:00Z"}'
                  UNION ALL SELECT 'T','dhb-tie','{"shipTime":"2026-11-16T02:30:00Z"}'
                  UNION ALL SELECT 'T','hll-reused','{"shipTime":"2026-11-16T00:00:00Z"}'
                  UNION ALL SELECT 'OTHER','manual','{"shipTime":"2027-01-01T00:00:00Z"}')
                SELECT id,%s AS event_time FROM order_logistics_cost ORDER BY event_time DESC,created_time DESC,id DESC
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
                    if ("hll-reused".equals(r.getString(1)))
                        assertThat(r.getString(2)).isEqualTo("2026-11-16 12:05:00");
                    if ("manual".equals(r.getString(1)))
                        assertThat(r.getString(2)).isEqualTo("2026-11-16 08:59:58");
                    if ("date-only".equals(r.getString(1)))
                        assertThat(r.getString(2)).isEqualTo("2026-11-15 00:00:00");
                    if ("unknown".equals(r.getString(1))) assertThat(r.getString(2)).isNull();
                }
            }
            assertThat(ids).containsExactly(
                    "hll-reused", "hll", "dhb-tie", "manual", "dhb", "date-only", "unknown");
            // 相同时刻跨页时沿用创建时间、ID 次序；翻完各页应与一次性全量查询完全一致。
            for (int repeat = 0; repeat < 2; repeat++) {
                var paged = new ArrayList<String>();
                for (int offset = 0; offset < ids.size(); offset += 2) {
                    try (var r = s.executeQuery(fixture + " LIMIT 2 OFFSET " + offset)) {
                        while (r.next()) paged.add(r.getString(1));
                    }
                }
                assertThat(paged).containsExactlyElementsOf(ids).doesNotHaveDuplicates();
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
