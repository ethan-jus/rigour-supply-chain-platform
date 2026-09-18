package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 复现已保存菜单覆盖全局隐藏设置的升级场景。 */
@Testcontainers
class HrPlaceholderMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void retiresOnlyPlaceholderAndPreservesEmployeeCustomizationAndGrants() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("106").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'hr-upgrade','HR升级测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='hr-upgrade'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,12)", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,icon_key,sort_order,visible)
                SELECT ?,?,r.id,r.id,'PAGE',IF(ui.route_key='supply.hr.employees','自定义员工资料','员工档案'),'User',42,1
                FROM iam_resource r JOIN iam_resource_ui ui ON ui.resource_id=r.id
                WHERE ui.route_key IN ('supply.hr.index','supply.hr.employees')
                """, tenant, app);
        jdbc.update("""
                INSERT INTO iam_tenant_menu_config(tenant_id,resource_id,visible,created_at,updated_at)
                SELECT ?,resource_id,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_resource_ui WHERE route_key='supply.hr.index'
                """, tenant);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'HR','人事')", tenant, app);
        jdbc.update("""
                INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
                SELECT role.tenant_id,role.application_id,role.id,node.id FROM iam_app_role role
                JOIN iam_app_menu_node node ON node.tenant_id=role.tenant_id AND node.application_id=role.application_id
                WHERE role.tenant_id=?
                """, tenant);
        var grants = jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id");
        var employee = jdbc.queryForList("""
                SELECT n.tenant_id,n.application_id,n.id,n.parent_id,n.resource_id,n.node_type,n.display_name,
                       n.icon_key,n.sort_order,n.visible,n.status,n.protected_node,n.version,
                       n.created_at,n.updated_at,n.deleted_at
                  FROM iam_app_menu_node n JOIN iam_resource_ui ui ON ui.resource_id=n.resource_id
                 WHERE ui.route_key='supply.hr.employees'
                """);

        Flyway.configure().dataSource(ds).load().migrate();

        assertThat(jdbc.queryForObject("SELECT r.status FROM iam_resource r JOIN iam_resource_ui ui ON ui.resource_id=r.id WHERE ui.route_key='supply.hr.index'", String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node n JOIN iam_resource_ui ui ON ui.resource_id=n.resource_id WHERE ui.route_key='supply.hr.index' AND (n.status='ACTIVE' OR n.visible=1)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_tenant_menu_config n JOIN iam_resource_ui ui ON ui.resource_id=n.resource_id WHERE ui.route_key='supply.hr.index' AND n.visible=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=?", Long.class, tenant)).isEqualTo(13);
        assertThat(jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id")).isEqualTo(grants);
        assertThat(jdbc.queryForList("""
                SELECT n.tenant_id,n.application_id,n.id,n.parent_id,n.resource_id,n.node_type,n.display_name,
                       n.icon_key,n.sort_order,n.visible,n.status,n.protected_node,n.version,
                       n.created_at,n.updated_at,n.deleted_at
                  FROM iam_app_menu_node n JOIN iam_resource_ui ui ON ui.resource_id=n.resource_id
                 WHERE ui.route_key='supply.hr.employees'
                """)).usingRecursiveComparison().isEqualTo(employee);
    }
}
