package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.util.*;

@Testcontainers(disabledWithoutDocker = true)
class TransportBusinessReadinessMySqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    static String fixture() {
        return """
        WITH order_logistics_cost AS (
          SELECT 'T' tenant_id,'hll-linked' id,10 amount,'ACTIVE' status_code,
            '[{"orderCode":"SO1","linkRoleCode":"COST","employeeCode":"E1","departmentId":7}]' sources_json
          UNION ALL SELECT 'T','hll-unlinked',10,'ACTIVE','[]'
          UNION ALL SELECT 'T','missing-dept',10,'ACTIVE','[{"orderCode":"SO1","employeeCode":"E1"}]'
          UNION ALL SELECT 'T','manual-proof',10,'ACTIVE','[{"orderCode":"SO1","employeeCode":"E1","departmentId":7}]'
          UNION ALL SELECT 'T','manual-no-proof',10,'ACTIVE','[{"orderCode":"SO1","employeeCode":"E1","departmentId":7}]'
          UNION ALL SELECT 'T','missing-fee',NULL,'ACTIVE','[{"orderCode":"SO1","employeeCode":"E1","departmentId":7}]'
          UNION ALL SELECT 'T','void',10,'VOID','[{"orderCode":"SO1","employeeCode":"E1","departmentId":7}]'
        ), order_logistics_import_order AS (
          SELECT 'T' tenant_id,'hll-linked' cost_id,'API' source_channel
          UNION ALL SELECT 'T','hll-unlinked','API'
          UNION ALL SELECT 'T','missing-dept','API'
        ), order_transport_shipment_source AS (SELECT 'T' tenant_id,'missing-fee' cost_id),
        order_logistics_cost_attachment AS (
          SELECT 'T' tenant_id,'manual-proof' entry_id,0 deleted
          UNION ALL SELECT 'OTHER','manual-no-proof',0
          UNION ALL SELECT 'T','void',0)
        SELECT id,CASE WHEN status_code='VOID' THEN 'VOID' WHEN %s THEN 'COMPLETE' ELSE 'INCOMPLETE' END readiness
        FROM order_logistics_cost
        """
                .formatted(MybatisPlusOrderLogisticsCostRepository.completeBusinessSql());
    }

    static void assertStates(java.sql.Connection c) throws Exception {
        var result = new HashMap<String, String>();
        try (var s = c.createStatement();
                var r = s.executeQuery(fixture())) {
            while (r.next()) result.put(r.getString(1), r.getString(2));
        }
        assertThat(result)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "hll-linked",
                                "COMPLETE",
                                "hll-unlinked",
                                "INCOMPLETE",
                                "missing-dept",
                                "INCOMPLETE",
                                "manual-proof",
                                "COMPLETE",
                                "manual-no-proof",
                                "INCOMPLETE",
                                "missing-fee",
                                "INCOMPLETE",
                                "void",
                                "VOID"));
    }

    @Test
    void automaticCompletenessUsesAllFieldsAndOwnTenantEvidence() throws Exception {
        try (var c =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertStates(c);
        }
    }
}
