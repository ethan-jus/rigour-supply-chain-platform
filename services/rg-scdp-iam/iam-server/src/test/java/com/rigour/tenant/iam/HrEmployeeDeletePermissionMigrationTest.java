package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HrEmployeeDeletePermissionMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void registersDeleteUnderCustomEmployeePageWithoutGrantingOrdinaryRoles() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        IamTestMigrations.configuration(ds).target("146").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'employee-delete','删除测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var tenant = jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='employee-delete'", byte[].class);
        var app = jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'", byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,10)", tenant, app);
        jdbc.update("""
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible)
SELECT ?,?,UUID_TO_BIN(UUID()),id,'PAGE','员工档案',1,1 FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_EMPLOYEE'
""", tenant, app);
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),'HR','人事')", tenant, app);
        jdbc.update("""
INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT r.tenant_id,r.application_id,r.id,n.id FROM iam_app_role r
JOIN iam_app_menu_node n ON n.tenant_id=r.tenant_id AND n.application_id=r.application_id WHERE r.tenant_id=?
""", tenant);
        var grants = jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id");
        IamTestMigrations.configuration(ds).target("147").load().migrate();
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_app_menu_node b JOIN iam_app_menu_node p
 ON p.tenant_id=b.tenant_id AND p.application_id=b.application_id AND p.id=b.parent_id
JOIN iam_resource r ON r.id=b.resource_id
WHERE b.tenant_id=? AND r.permission_code='hr:employee:delete' AND b.node_type='BUTTON'
 AND b.visible=0 AND p.display_name='员工档案'
""", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_package_resource p JOIN iam_resource parent ON p.resource_id=parent.id
JOIN iam_resource r ON r.permission_code='hr:employee:delete'
LEFT JOIN iam_package_resource b ON b.package_version_id=p.package_version_id AND b.resource_id=r.id
WHERE parent.resource_code='SUPPLY_CHAIN.PAGE.HR_EMPLOYEE' AND b.resource_id IS NULL
""", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT HEX(role_id),HEX(menu_node_id) FROM iam_app_role_grant ORDER BY menu_node_id")).isEqualTo(grants);
        assertThat(jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=?", Long.class, tenant)).isEqualTo(11);
        assertThat(IamTestMigrations.configuration(ds).target("147").load().migrate().migrationsExecuted).isZero();
    }
}
