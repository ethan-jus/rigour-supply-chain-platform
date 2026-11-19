package com.rigour.tenant.iam;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import com.rigour.tenant.iam.infrastructure.persistence.migration.IamV139MigrationResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class IamSchemaMigrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private Flyway migration(boolean includeBaselines, String target) {
        String[] locations = includeBaselines
                ? new String[] {"classpath:db/migration", "classpath:db/bootstrap"}
                : new String[] {"classpath:db/migration"};
        var configuration = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(locations).target(target).baselineOnMigrate(false).outOfOrder(false)
                .cleanDisabled(false); // Only the disposable container owned by this test.
        IamV139MigrationResources.configure(configuration);
        return configuration.load();
    }

    @BeforeEach
    void clearDisposableSchema() {
        migration(true, "latest").clean();
    }

    @Test
    void emptySchemaUsesNewBaselineAndCompletesAllFollowingMigrations() throws Exception {
        Flyway flyway = migration(true, "latest");
        flyway.migrate();
        flyway.validate();
        assertEquals("160", flyway.info().current().getVersion().getVersion());
        assertEquals(1, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='120' AND type='SQL_BASELINE'"));
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT table_name,column_name,collation_name FROM information_schema.columns"
                     + " WHERE table_schema=DATABASE() AND collation_name IS NOT NULL AND collation_name <> 'utf8mb4_general_ci'")) {
            var unexpected = new java.util.ArrayList<String>();
            while (result.next()) unexpected.add(result.getString(1) + "." + result.getString(2) + ": " + result.getString(3));
            assertEquals(java.util.List.of(), unexpected);
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM iam_resource WHERE display_name='拜访申诉' AND resource_code='SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND permission_code IN ('sales:risk:read','sales:risk:write')"));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void existingVersionedDatabaseKeepsHistoryAndDataThroughEveryFollowingMigration() throws Exception {
        migration(false, "132").migrate();
        long checksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version='120'");
        long historyCount = scalar("SELECT COUNT(*) FROM flyway_schema_history");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_sentinel(id INT PRIMARY KEY)");
            statement.execute("INSERT INTO migration_sentinel VALUES(42)");
        }
        Flyway upgraded = migration(true, "latest");
        assertEquals(28, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(historyCount + 28, scalar("SELECT COUNT(*) FROM flyway_schema_history"));
        assertEquals("160", upgraded.info().current().getVersion().getVersion());
        assertEquals(checksum, scalar("SELECT checksum FROM flyway_schema_history WHERE version='120'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL_BASELINE'"));
        assertEquals(42, scalar("SELECT id FROM migration_sentinel"));
        assertEquals(0, upgraded.migrate().migrationsExecuted);
    }

    @Test
    void v120AddsMissingActionsAndPreservesTenantChoices() throws Exception {
        migration(false, "119").migrate();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("ALTER DATABASE test COLLATE utf8mb4_general_ci");
        jdbc.update("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'v120','V120 租户','ACTIVE',NOW(6),NOW(6))");
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='v120'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("""
                INSERT INTO iam_tenant_subscription(id,tenant_id,package_version_id,effective_from,effective_to,user_limit,status,created_at,updated_at)
                VALUES(UUID_TO_BIN(UUID()),?,UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 YEAR),100,'ACTIVE',NOW(6),NOW(6))
                """, tenant);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,authorization_mode,version) VALUES(?,?,'ACTIVE',7)", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name)
                SELECT ?,?,r.id,r.id,'PAGE',r.display_name FROM iam_resource r JOIN iam_resource_ui ui ON ui.resource_id=r.id
                WHERE r.application_id=? AND ui.route_key IN ('supply.order.sales-orders','supply.order.sales-payments','supply.crm.customers.profiles',
                    'supply.erp.master-data.prices','supply.erp.master-data.products','supply.erp.index')
                """, tenant, app, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,status,deleted_at)
                SELECT ?,?,id,id,'BUTTON','企业自定义权限名称',777,'DISABLED',IF(permission_code='erp:product:read',NOW(6),NULL)
                FROM iam_resource WHERE application_id=? AND permission_code IN ('crm:customer:read','erp:product:read')
                """, tenant, app, app);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'SALES','销售')", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
                SELECT ?,?,role.id,ui.resource_id FROM iam_app_role role JOIN iam_resource_ui ui ON ui.route_key='supply.order.sales-orders'
                WHERE role.tenant_id=? AND role.application_id=?
                """, tenant, app, tenant, app);
        var savedGrants = jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant WHERE tenant_id=?", tenant);
        migration(false, "120").migrate();
        // Replaying the frozen statement must keep the operator's choices and the version.
        try (var conn = ds.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn,
                    new org.springframework.core.io.ClassPathResource("db/migration/V120__iam_complete_supply_permission_nodes.sql"));
        }
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND node_type='BUTTON' AND deleted_at IS NULL", Integer.class, tenant));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND deleted_at IS NULL AND display_name='企业自定义权限名称' AND sort_order=777 AND status='DISABLED'", Integer.class, tenant));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND deleted_at IS NOT NULL", Integer.class, tenant));
        assertEquals(8L, jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=? AND authorization_mode='ACTIVE'", Long.class, tenant));
        assertEquals(savedGrants, jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant WHERE tenant_id=?", tenant));
    }

    private long scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
