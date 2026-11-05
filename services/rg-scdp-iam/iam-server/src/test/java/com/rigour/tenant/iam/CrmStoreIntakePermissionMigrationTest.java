package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CrmStoreIntakePermissionMigrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");

    @Test void preservesIntakeGrantsAndOnlyExtendsExplicitVisitOperatorsWithTheirScope() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        IamTestMigrations.configuration(ds).target("148").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN(UUID()),'crm-intake','门店新增权限测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var tenant=jdbc.queryForObject("SELECT id FROM iam_tenant WHERE tenant_code='crm-intake'",byte[].class);
        var app=jdbc.queryForObject("SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN'",byte[].class);
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,version) VALUES(?,?,10)",tenant,app);
        jdbc.update("""
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible)
SELECT ?,?,UUID_TO_BIN(UUID()),id,'PAGE',display_name,sort_order,1 FROM iam_resource
WHERE resource_code IN ('SUPPLY_CHAIN.PAGE.CRM_STORE_PUBLIC','SUPPLY_CHAIN.PAGE.CRM_STORE_MINE','SUPPLY_CHAIN.PAGE.CRM_STORE_LEADS','SUPPLY_CHAIN.PAGE.CRM_STORE_LIBRARY')
""",tenant,app);
        jdbc.update("""
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible)
SELECT p.tenant_id,p.application_id,UUID_TO_BIN(UUID()),p.id,b.id,'BUTTON',b.display_name,b.sort_order,0
FROM iam_app_menu_node p JOIN iam_resource b ON b.parent_id=p.resource_id
WHERE p.tenant_id=? AND b.permission_code IN ('crm:lead:create','crm:store:visit-write')
""",tenant);
        // Simulate the pre-migration customized intake node living under Store Submissions.
        jdbc.update("""
UPDATE iam_app_menu_node b JOIN iam_app_menu_node p ON p.tenant_id=b.tenant_id AND p.application_id=b.application_id
JOIN iam_resource br ON br.id=b.resource_id JOIN iam_resource pr ON pr.id=p.resource_id
SET b.parent_id=p.id,b.display_name='自定义新增入口'
WHERE b.tenant_id=? AND br.permission_code='crm:lead:create' AND pr.permission_code='crm:lead:read'
""",tenant);
        var intakeId=jdbc.queryForObject("SELECT n.id FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id WHERE n.tenant_id=? AND r.permission_code='crm:lead:create'",byte[].class,tenant);
        var visitId=jdbc.queryForObject("SELECT n.id FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id WHERE n.tenant_id=? AND r.permission_code='crm:store:visit-write'",byte[].class,tenant);
        for(String role:new String[]{"VISITOR","READER","SUBMITTER"})
            jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name) VALUES(?,?,UUID_TO_BIN(UUID()),?,?)",tenant,app,role,role);
        jdbc.update("INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id) SELECT tenant_id,application_id,id,? FROM iam_app_role WHERE tenant_id=? AND role_code='VISITOR'",visitId,tenant);
        jdbc.update("INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id) SELECT tenant_id,application_id,id,? FROM iam_app_role WHERE tenant_id=? AND role_code='SUBMITTER'",intakeId,tenant);
        jdbc.update("""
INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT r.tenant_id,r.application_id,r.id,n.id FROM iam_app_role r JOIN iam_app_menu_node n ON n.tenant_id=r.tenant_id AND n.application_id=r.application_id
JOIN iam_resource p ON p.id=n.resource_id WHERE r.tenant_id=? AND r.role_code='READER' AND p.permission_code IN ('crm:pool:public-read','crm:pool:private-read','crm:store:library-read')
""",tenant);
        jdbc.update("""
INSERT INTO iam_app_scope_rule(tenant_id,application_id,id,role_id,action_code,object_type,scope_mode,department_mode,include_descendants)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),id,'crm:store:visit-write','CUSTOMER','DEPARTMENT','SPECIFIED',TRUE FROM iam_app_role WHERE tenant_id=? AND role_code='VISITOR'
""",tenant);
        jdbc.update("INSERT INTO iam_app_scope_reference(tenant_id,application_id,scope_rule_id,dimension,reference_key) SELECT tenant_id,application_id,id,'DEPARTMENT','HZ-11' FROM iam_app_scope_rule WHERE tenant_id=?",tenant);

        IamTestMigrations.configuration(ds).target("149").load().migrate();
        assertThat(permissions(jdbc,tenant,"VISITOR")).containsExactlyInAnyOrder("crm:store:visit-write","crm:store:status-write");
        assertThat(permissions(jdbc,tenant,"READER")).containsExactlyInAnyOrder("crm:pool:public-read","crm:pool:private-read","crm:store:library-read");
        assertThat(permissions(jdbc,tenant,"SUBMITTER")).containsExactly("crm:lead:create");
        assertThat(jdbc.queryForObject("""
SELECT n.id FROM iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id WHERE n.tenant_id=? AND r.permission_code='crm:lead:create'
""",byte[].class,tenant)).isEqualTo(intakeId);
        assertThat(jdbc.queryForObject("""
SELECT p.permission_code FROM iam_app_menu_node b JOIN iam_app_menu_node n ON n.tenant_id=b.tenant_id AND n.application_id=b.application_id AND n.id=b.parent_id
JOIN iam_resource p ON p.id=n.resource_id WHERE b.tenant_id=? AND b.id=?
""",String.class,tenant,intakeId)).isEqualTo("crm:pool:public-read");
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_app_scope_rule s JOIN iam_app_scope_reference r ON r.tenant_id=s.tenant_id AND r.application_id=s.application_id AND r.scope_rule_id=s.id
WHERE s.tenant_id=? AND s.action_code IN ('crm:store:visit-write','crm:store:status-write') AND s.object_type='CUSTOMER' AND s.scope_mode='DEPARTMENT' AND s.department_mode='SPECIFIED' AND s.include_descendants=1 AND r.reference_key='HZ-11'
""",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
SELECT COUNT(*) FROM iam_app_menu_node b JOIN iam_app_menu_node n ON n.tenant_id=b.tenant_id AND n.application_id=b.application_id AND n.id=b.parent_id
JOIN iam_resource br ON br.id=b.resource_id JOIN iam_resource pr ON pr.id=n.resource_id
WHERE b.tenant_id=? AND br.permission_code='crm:store:status-write' AND pr.permission_code='crm:pool:private-read' AND b.visible=0
""",Integer.class,tenant)).isEqualTo(1);
        assertThat(IamTestMigrations.configuration(ds).target("149").load().migrate().migrationsExecuted).isZero();
    }
    private java.util.List<String> permissions(JdbcTemplate jdbc,byte[] tenant,String role) {
        return jdbc.queryForList("""
SELECT res.permission_code FROM iam_app_role_grant g JOIN iam_app_role r ON r.tenant_id=g.tenant_id AND r.application_id=g.application_id AND r.id=g.role_id
JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=g.application_id AND n.id=g.menu_node_id
JOIN iam_resource res ON res.id=n.resource_id WHERE r.tenant_id=? AND r.role_code=? ORDER BY res.permission_code
""",String.class,tenant,role);
    }
}
