package com.rigour.merchant.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StorePoolMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void v28CreatesOnlyNewTablesAndPreservesLegacyDataRelationsAndHistory(boolean bootstrap) {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        String[] locations = bootstrap ? new String[]{"classpath:db/migration", "classpath:db/bootstrap"}
                : new String[]{"classpath:db/migration"};
        var previous = Flyway.configure().dataSource(dataSource).locations(locations).target("27")
                .cleanDisabled(false).baselineOnMigrate(false).outOfOrder(false).load();
        previous.clean(); // Disposable Testcontainers schema only.
        previous.migrate();
        var jdbc = new JdbcTemplate(dataSource);
        seedLegacy(jdbc);
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name", String.class);
        Map<String, List<String>> dataBefore = new LinkedHashMap<>();
        Map<String, String> ddlBefore = new LinkedHashMap<>();
        for (String table : tables) {
            dataBefore.put(table, snapshot(jdbc, table));
            ddlBefore.put(table, jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table").toString());
        }
        var upgraded = Flyway.configure().dataSource(dataSource).locations(locations).target("28")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("28");
        for (String table : tables) {
            if (table.equals("flyway_schema_history")) {
                var history = snapshot(jdbc, table);
                assertThat(history).containsAll(dataBefore.get(table)).hasSize(dataBefore.get(table).size() + 1);
            } else {
                assertThat(snapshot(jdbc, table)).as("legacy rows of %s", table).isEqualTo(dataBefore.get(table));
                assertThat(jdbc.queryForMap("SHOW CREATE TABLE `" + table + "`").get("Create Table"))
                        .as("legacy schema and foreign keys of %s", table).isEqualTo(ddlBefore.get(table));
            }
        }
        for (String table : List.of("crm_store_master", "crm_store_identity", "crm_store_lead", "crm_pool_policy",
                "crm_segment_policy", "crm_store_ownership", "crm_store_summary", "crm_store_event")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT referenced_table_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('crm_contact','crm_address','crm_sales_assignment') AND referenced_table_name LIKE 'crm_store%'", String.class))
                .containsExactly("crm_store");
        assertThat(jdbc.queryForList("SELECT DISTINCT referenced_table_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name IN ('crm_store_identity','crm_store_ownership','crm_store_summary') AND referenced_table_name LIKE 'crm_store_m%'", String.class))
                .containsExactly("crm_store_master");
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }

    private static List<String> snapshot(JdbcTemplate jdbc, String table) {
        return jdbc.query("SELECT * FROM `" + table + "`", (rs, n) -> {
            var row = new ArrayList<String>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                Object value = rs.getObject(i);
                row.add(value == null ? "<NULL>" : value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value.toString());
            }
            return row.toString();
        }).stream().sorted().toList();
    }

    private static void seedLegacy(JdbcTemplate jdbc) {
        String tenant = "00000000-0000-0000-0000-000000000001";
        String party = "00000000-0000-0000-0000-000000000002";
        String store = "00000000-0000-0000-0000-000000000003";
        String contact = "00000000-0000-0000-0000-000000000004";
        String address = "00000000-0000-0000-0000-000000000005";
        String assignment = "00000000-0000-0000-0000-000000000006";
        String binding = "00000000-0000-0000-0000-000000000007";
        String alias = "00000000-0000-0000-0000-000000000008";
        String connector = "00000000-0000-0000-0000-000000000009";
        jdbc.update("INSERT INTO crm_party(id,tenant_id,party_code,display_name,revision,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'LEGACY','保留经营主体',7,'2024-01-01','2025-02-02')", party, tenant);
        jdbc.update("INSERT INTO crm_customer(id,tenant_id,customer_code,party_id,customer_name,owner_sales_user_id,revision,created_time,updated_time) VALUES(17,?,'LEGACY-CUSTOMER',UUID_TO_BIN(?),'保留合作客户','employee-7',4,'2024-01-01','2025-02-02')", tenant, party);
        jdbc.update("INSERT INTO crm_customer_profile(party_id,tenant_id,login_account,revision,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'legacy-account',3,'2024-01-01','2025-02-02')", party, tenant);
        jdbc.update("INSERT INTO crm_store(id,tenant_id,party_id,store_code,store_name,revision,created_by,updated_by,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'LEGACY-STORE','保留旧门店',6,'old-creator','old-updater','2024-01-01','2025-02-02')", store, tenant, party);
        jdbc.update("INSERT INTO crm_contact(id,tenant_id,party_id,store_id,contact_name,phone,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'原联系人','test-phone','2024-01-01','2025-02-02')", contact, tenant, party, store);
        jdbc.update("INSERT INTO crm_address(id,tenant_id,party_id,store_id,contact_id,address_type,full_address,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'SHIPPING','原收货地址','2024-01-01','2025-02-02')", address, tenant, party, store, contact);
        jdbc.update("INSERT INTO crm_sales_assignment(id,tenant_id,store_id,assignee_type,source,effective_from,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'SALES_PROFILE','MANUAL','2024-01-01','2024-01-01','2025-02-02')", assignment, tenant, store);
        jdbc.update("INSERT INTO crm_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,source_fields_json,source_payload_hash,synced_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER','original-source-id','PARTY',UUID_TO_BIN(?),JSON_OBJECT('original','keep'),REPEAT('a',64),'2025-02-02','2024-01-01','2025-02-02')", binding, tenant, connector, party);
        jdbc.update("INSERT INTO crm_source_identity_alias(id,tenant_id,binding_id,connector_id,source_system,source_object_type,alias_type,alias_value,first_seen_at,last_seen_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER','ACCOUNT','original-account','2024-01-01','2025-02-02','2024-01-01','2025-02-02')", alias, tenant, binding, connector);
    }
}
