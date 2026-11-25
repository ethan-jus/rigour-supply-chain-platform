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
        assertEquals("166", flyway.info().current().getVersion().getVersion());
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
        assertEquals(1, scalar("SELECT COUNT(*) FROM iam_resource WHERE resource_type='PAGE' AND resource_code='SUPPLY_CHAIN.PAGE.SALES_DASHBOARD' AND permission_code='sales:fieldwork:read'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM iam_resource WHERE resource_type='PAGE' AND resource_code='SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS' AND permission_code='sales:visit-record:read'"));
        assertEquals(3, scalar("""
                SELECT COUNT(*) FROM iam_resource WHERE resource_type='PAGE' AND
                  (resource_code,permission_code) IN (
                  ('SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS','order:register-orders:read'),
                  ('SUPPLY_CHAIN.PAGE.ORDER_SALES_LINES','order:register-lines:read'),
                  ('SUPPLY_CHAIN.PAGE.ORDER_SALES_PAYMENTS','order:register-payments:read'))
                """));
        assertEquals(11, scalar("""
                SELECT COUNT(*) FROM iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id
                WHERE r.resource_code LIKE 'SUPPLY_CHAIN.%SALES%' AND u.icon_key IN
                    ('Route','TrendLine','Storefront','ShieldAlert','CalendarOutline','FileText','Inbox','Sliders','Target','Receipt')
                """));
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
        assertEquals(34, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(historyCount + 34, scalar("SELECT COUNT(*) FROM flyway_schema_history"));
        assertEquals("166", upgraded.info().current().getVersion().getVersion());
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

    @Test
    void groupedNavigationKeepsCustomNamesAndLimitsInheritedAccessToTheOriginalRole() {
        migration(true, "160").migrate();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.update("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'nav-review','菜单验收','ACTIVE',NOW(6),NOW(6))");
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='nav-review'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id) VALUES(?,?)", tenant, app);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'WORKER','本人作业')", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible,status)
                SELECT ?,?,UUID_TO_BIN(UUID()),r.id,IF(r.resource_type='API','BUTTON',r.resource_type),
                       IF(u.route_key='supply.order.sales-orders','客户订单',r.display_name),r.sort_order,COALESCE(u.visible,0),r.status
                FROM iam_resource r LEFT JOIN iam_resource_ui u ON u.resource_id=r.id
                WHERE r.application_id=? AND r.deleted_at IS NULL AND (r.resource_type IN ('MENU','PAGE','BUTTON')
                      OR r.permission_code IN ('sales:context:read','sales:visit:own:read','sales:visit:own:write'))
                """, tenant, app, app);
        jdbc.update("""
                UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id JOIN iam_app_menu_node parent
                  ON parent.tenant_id=n.tenant_id AND parent.application_id=n.application_id AND parent.resource_id=r.parent_id
                SET n.parent_id=parent.id WHERE n.tenant_id=?
                """, tenant);
        jdbc.update("""
                INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
                SELECT n.tenant_id,n.application_id,role.id,n.id FROM iam_app_menu_node n
                JOIN iam_app_role role ON role.tenant_id=n.tenant_id AND role.application_id=n.application_id
                JOIN iam_resource r ON r.id=n.resource_id LEFT JOIN iam_resource_ui u ON u.resource_id=r.id
                WHERE n.tenant_id=? AND (u.route_key IN ('supply.order.sales-orders','supply.bi.payment-risk') OR
                      r.permission_code IN ('sales:context:read','sales:visit:own:read','sales:visit:own:write'))
                """, tenant);

        migration(true, "163").migrate();
        var grants = jdbc.queryForList("""
                SELECT COALESCE(u.route_key,r.permission_code) FROM iam_app_role_grant g
                JOIN iam_app_menu_node n ON n.id=g.menu_node_id AND n.application_id=g.application_id AND n.tenant_id=g.tenant_id
                JOIN iam_resource r ON r.id=n.resource_id LEFT JOIN iam_resource_ui u ON u.resource_id=r.id
                WHERE g.tenant_id=?
                """, String.class, tenant);
        org.assertj.core.api.Assertions.assertThat(grants)
                .contains("supply.order.sales-orders", "supply.bi.receivables", "supply.sales.pay-statements",
                        "sales:followup:self:read", "sales:followup:self:respond")
                .doesNotContain("supply.sales.overview", "supply.hr.sales-payroll", "supply.sales.visits.records",
                        "hr:payroll:publish", "analytics:receivables:archive", "order:receivable-plan:write");
        assertEquals("客户订单", jdbc.queryForObject("""
                SELECT n.display_name FROM iam_app_menu_node n JOIN iam_resource_ui u ON u.resource_id=n.resource_id
                WHERE n.tenant_id=? AND u.route_key='supply.order.sales-orders'
                """, String.class, tenant));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_app_menu_node n LEFT JOIN iam_app_menu_node p
                  ON p.id=n.parent_id AND p.application_id=n.application_id AND p.tenant_id=n.tenant_id AND p.deleted_at IS NULL
                WHERE n.tenant_id=? AND n.deleted_at IS NULL AND n.parent_id IS NOT NULL AND p.id IS NULL
                """, Integer.class, tenant));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_resource r WHERE r.application_id=? AND r.resource_type='MENU' AND r.deleted_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM iam_resource c WHERE c.parent_id=r.id)
                """, Integer.class, app));
    }

    @Test
    void salesIconsUpgradeDefaultsWithoutChangingNavigationAndPermissions() {
        migration(true, "163").migrate();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at)
                VALUES(UUID_TO_BIN(UUID()),'sales-icons-qa','菜单图标验收','ACTIVE',NOW(6),NOW(6))
                """);
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='sales-icons-qa'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,7)", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,icon_key,sort_order,visible,status,version)
                SELECT ?,?,r.id,r.id,r.resource_type,r.display_name,
                       CASE r.resource_code WHEN 'SUPPLY_CHAIN.MENU.SALES' THEN 'Location'
                           WHEN 'SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN' THEN 'Folder' END,
                       r.sort_order, r.resource_code<>'SUPPLY_CHAIN.PAGE.SALES_RISK_CASES',
                       IF(r.resource_code='SUPPLY_CHAIN.PAGE.SALES_RISK_CASES','DISABLED','ACTIVE'), 3
                FROM iam_resource r WHERE r.application_id=? AND r.resource_code IN
                    ('SUPPLY_CHAIN.MENU.SALES','SUPPLY_CHAIN.PAGE.SALES_OVERVIEW','SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN',
                     'SUPPLY_CHAIN.PAGE.SALES_RISK_CASES','SUPPLY_CHAIN.MENU.ORDER')
                """,tenant,app,app);
        jdbc.update("""
                UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
                JOIN iam_resource p ON p.application_id=r.application_id AND p.resource_code='SUPPLY_CHAIN.MENU.SALES'
                SET n.parent_id=p.id WHERE n.tenant_id=? AND r.resource_type='PAGE'
                """,tenant);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'WORKER','销售')",tenant,app);
        jdbc.update("""
                INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
                SELECT role.tenant_id,role.application_id,role.id,n.id
                FROM iam_app_role role JOIN iam_app_menu_node n
                  ON n.tenant_id=role.tenant_id AND n.application_id=role.application_id
                JOIN iam_resource r ON r.id=n.resource_id
                WHERE role.tenant_id=? AND r.resource_code='SUPPLY_CHAIN.PAGE.SALES_OVERVIEW'
                """,tenant);
        String menus = """
                SELECT HEX(n.id) id,HEX(n.parent_id) parent_id,n.display_name,n.sort_order,n.status,n.visible
                FROM iam_app_menu_node n WHERE n.tenant_id=? ORDER BY n.id
                """;
        String grants = """
                SELECT HEX(role_id) role_id,HEX(menu_node_id) menu_node_id
                FROM iam_app_role_grant WHERE tenant_id=? ORDER BY role_id,menu_node_id
                """;
        var originalMenus = jdbc.queryForList(menus,tenant);
        var originalGrants = jdbc.queryForList(grants,tenant);

        migration(true,"164").migrate();

        var icons = jdbc.query("""
                SELECT r.resource_code,n.icon_key,n.version FROM iam_app_menu_node n
                JOIN iam_resource r ON r.id=n.resource_id WHERE n.tenant_id=?
                """, (rs, row) -> rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getInt(3),tenant);
        org.assertj.core.api.Assertions.assertThat(icons).containsExactlyInAnyOrder(
                "SUPPLY_CHAIN.MENU.SALES:Route:4",
                "SUPPLY_CHAIN.PAGE.SALES_OVERVIEW:TrendLine:4",
                "SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN:Folder:3",
                "SUPPLY_CHAIN.PAGE.SALES_RISK_CASES:ShieldAlert:4",
                "SUPPLY_CHAIN.MENU.ORDER:null:3");
        assertEquals(originalMenus,jdbc.queryForList(menus,tenant));
        assertEquals(originalGrants,jdbc.queryForList(grants,tenant));
        assertEquals(8,jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=? AND application_id=?",Integer.class,tenant,app));
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
