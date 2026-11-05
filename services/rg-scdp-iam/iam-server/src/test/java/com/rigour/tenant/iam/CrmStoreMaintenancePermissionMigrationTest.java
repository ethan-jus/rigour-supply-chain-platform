package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CrmStoreMaintenancePermissionMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void separatesReadAndWritePreservingManagersAndTheirRegionalLimits() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        IamTestMigrations.configuration(ds).target("147").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'crm-maintain','门店权限测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var tenant=jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='crm-maintain'",byte[].class);
        var app=jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'",byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,10)",tenant,app);
        jdbc.update("""
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible)
SELECT ?,?,UUID_TO_BIN(UUID()),id,'PAGE','商家库管理',1,1 FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.CRM_STORE_LIBRARY'
""",tenant,app);
        for(String role:new String[]{"MANAGER","SALES"})
            jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),?,?)",tenant,app,role,role);
        jdbc.update("""
INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT r.tenant_id,r.application_id,r.id,n.id FROM iam_app_role r JOIN iam_app_menu_node n ON n.tenant_id=r.tenant_id AND n.application_id=r.application_id
WHERE r.tenant_id=? AND r.role_code='MANAGER'
""",tenant);
        jdbc.update("""
INSERT INTO iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,include_descendants)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),id,'crm:store:manage','CUSTOMER','DEPARTMENT','SPECIFIED',TRUE FROM iam_app_role WHERE tenant_id=? AND role_code='MANAGER'
""",tenant);
        jdbc.update("INSERT INTO iam_app_scope_reference(tenant_id,application_id,scope_rule_id,dimension,reference_key) SELECT tenant_id,application_id,id,'DEPARTMENT','HZ-10' FROM iam_app_scope_rule WHERE tenant_id=?",tenant);
        IamTestMigrations.configuration(ds).target("148").load().migrate();
        assertThat(jdbc.queryForList("""
SELECT res.permission_code FROM iam_app_role_grant g JOIN iam_app_role r ON r.tenant_id=g.tenant_id AND r.application_id=g.application_id AND r.id=g.role_id
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=g.application_id AND n.id=g.menu_node_id
JOIN iam_resource res ON res.id=n.resource_id WHERE r.tenant_id=? AND r.role_code='MANAGER' ORDER BY res.permission_code
""",String.class,tenant)).containsExactly("crm:store:library-read","crm:store:manage");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_role_grant g JOIN iam_app_role r ON r.id=g.role_id WHERE r.tenant_id=? AND r.role_code='SALES'",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_app_scope_rule s JOIN iam_app_scope_reference r ON r.tenant_id=s.tenant_id AND r.application_id=s.application_id AND r.scope_rule_id=s.id
WHERE s.tenant_id=? AND s.action_code IN ('crm:store:library-read','crm:store:manage') AND s.scope_mode='DEPARTMENT' AND s.department_mode='SPECIFIED' AND s.include_descendants=1 AND r.reference_key='HZ-10'
""",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_app_menu_node b JOIN iam_app_menu_node p ON p.tenant_id=b.tenant_id AND p.application_id=b.application_id AND p.id=b.parent_id
JOIN iam_resource r ON r.id=b.resource_id WHERE b.tenant_id=? AND r.permission_code='crm:store:manage' AND b.node_type='BUTTON' AND b.visible=0 AND p.display_name='商家库管理'
""",Integer.class,tenant)).isEqualTo(1);
        assertThat(IamTestMigrations.configuration(ds).target("148").load().migrate().migrationsExecuted).isZero();
    }
}
