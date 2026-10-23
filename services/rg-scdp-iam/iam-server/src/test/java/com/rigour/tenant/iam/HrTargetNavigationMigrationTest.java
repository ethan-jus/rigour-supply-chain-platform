package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HrTargetNavigationMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void restoresDeletedTargetsWithoutRevivingDefaultsOrChangingOtherGrants() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        IamTestMigrations.configuration(ds).target("128").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'hr-target-restore','指标恢复测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='hr-target-restore'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,12)", tenant, app);
        // HR 目录使用租户自定义节点 ID，不能假定节点 ID 等于资源 ID。
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible)
                SELECT ?,?,UUID_TO_BIN(UUID()),r.id,'MENU','人事自定义目录',42,1
                FROM iam_resource r WHERE r.resource_code='SUPPLY_CHAIN.MENU.HR'
                """, tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible)
                SELECT n.tenant_id,n.application_id,r.id,n.id,r.id,'PAGE',r.display_name,35,1
                FROM iam_app_menu_node n JOIN iam_resource hr ON hr.id=n.resource_id
                JOIN iam_resource r ON r.resource_code='SUPPLY_CHAIN.PAGE.HR_TARGET_SETTINGS'
                WHERE n.tenant_id=? AND hr.resource_code='SUPPLY_CHAIN.MENU.HR'
                """, tenant);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'HR','人事')", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
                SELECT r.tenant_id,r.application_id,r.id,n.id FROM iam_app_role r
                JOIN iam_app_menu_node n ON n.tenant_id=r.tenant_id AND n.application_id=r.application_id
                WHERE r.tenant_id=?
                """, tenant);

        IamTestMigrations.configuration(ds).target("130").load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_resource WHERE permission_code LIKE 'hr:targets:%'", Integer.class)).isZero();
        var grants = jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id");
        var hr = jdbc.queryForList("SELECT * FROM iam_app_menu_node WHERE tenant_id=?", tenant);
        long version = jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=?", Long.class, tenant);
        long policy = jdbc.queryForObject("SELECT policy_version FROM iam_tenant WHERE id=?", Long.class, tenant);

        IamTestMigrations.configuration(ds).target("131").load().migrate();

        assertThat(jdbc.queryForList("SELECT permission_code FROM iam_resource WHERE permission_code LIKE 'hr:targets:%' ORDER BY permission_code", String.class))
                .containsExactly("hr:targets:read", "hr:targets:write");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_app_menu_node p JOIN iam_app_menu_node hr
                  ON hr.tenant_id=p.tenant_id AND hr.application_id=p.application_id AND hr.id=p.parent_id
                JOIN iam_resource_ui ui ON ui.resource_id=p.resource_id
                WHERE p.tenant_id=? AND p.node_type='PAGE' AND p.status='ACTIVE' AND p.visible=1
                  AND hr.display_name='人事自定义目录' AND ui.route_path='/supply-chain/hr/target-settings'
                """, Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_app_menu_node w JOIN iam_app_menu_node p
                  ON p.tenant_id=w.tenant_id AND p.application_id=w.application_id AND p.id=w.parent_id
                JOIN iam_resource r ON r.id=w.resource_id
                WHERE w.tenant_id=? AND w.node_type='BUTTON' AND w.visible=0 AND w.status='ACTIVE'
                  AND r.permission_code='hr:targets:write' AND p.display_name='指标设置'
                """, Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.API.BI_TARGET_DEFAULTS'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_package_resource hr JOIN iam_resource h ON h.id=hr.resource_id
                JOIN iam_resource r ON r.permission_code IN ('hr:targets:read','hr:targets:write')
                LEFT JOIN iam_package_resource p ON p.package_version_id=hr.package_version_id AND p.resource_id=r.id
                WHERE h.resource_code='SUPPLY_CHAIN.MENU.HR' AND p.resource_id IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id")).isEqualTo(grants);
        assertThat(jdbc.queryForList("SELECT * FROM iam_app_menu_node WHERE tenant_id=? AND display_name='人事自定义目录'", tenant))
                .usingRecursiveComparison().isEqualTo(hr);
        assertThat(jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=?", Long.class, tenant)).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("SELECT policy_version FROM iam_tenant WHERE id=?", Long.class, tenant)).isEqualTo(policy + 1);
        assertThat(IamTestMigrations.configuration(ds).target("131").load().migrate().migrationsExecuted).isZero();
    }
}
