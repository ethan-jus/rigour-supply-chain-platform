package com.rigour.tenant.iam;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** 在有旧客户端和平台会话的库上升级，而非仅验证空库建表。 */
@Testcontainers
class ScdpSingleProductMigrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void retiresOldEntryWithoutOverwritingExistingScdpClientOrBusinessGrants() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("105").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        for (String client : java.util.List.of("rigour-portal-browser", "rigour-portal-desktop", "rigour-scdp-desktop")) {
            jdbc.update("""
                INSERT INTO iam_oauth_client(id,client_id,client_id_issued_at,client_name,client_type,require_pkce,
                    require_consent,authorization_code_ttl_seconds,access_token_ttl_seconds,refresh_token_ttl_seconds,
                    id_token_signature_algorithm,status,created_at,updated_at)
                VALUES(UUID_TO_BIN(UUID()),?,UTC_TIMESTAMP(6),'existing','PUBLIC',1,0,300,900,604800,'RS256','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, client);
        }
        String oldId = jdbc.queryForObject("SELECT HEX(id) FROM iam_oauth_client WHERE client_id='rigour-portal-browser'", String.class);
        String existingId = jdbc.queryForObject("SELECT HEX(id) FROM iam_oauth_client WHERE client_id='rigour-scdp-desktop'", String.class);
        jdbc.update("""
            INSERT INTO iam_platform_user(id,username,display_name,platform_role,status,created_at,updated_at)
            VALUES(UUID_TO_BIN('019facff-0000-7000-8000-000000000001'),'old-admin','old','SUPER_ADMIN','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """);
        jdbc.update("""
            INSERT INTO iam_auth_session(id,principal_scope,principal_id,client_type,issued_at,last_seen_at,expires_at,status)
            VALUES(UUID_TO_BIN(UUID()),'PLATFORM',UUID_TO_BIN('019facff-0000-7000-8000-000000000001'),'WEB',
                UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'ACTIVE')
            """);
        jdbc.execute("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),'upgrade','升级测试','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.execute("INSERT INTO iam_role(id,tenant_id,role_code,role_name,role_type,status,created_at,updated_at) VALUES(UUID_TO_BIN('019facff-0000-7000-8000-000000000003'),UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),'SALES','业务员','CUSTOM','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.execute("INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at) SELECT UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),UUID_TO_BIN('019facff-0000-7000-8000-000000000003'),id,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_resource WHERE permission_code LIKE 'sales:%' AND status='ACTIVE'");
        var grantsBefore = jdbc.queryForList("SELECT HEX(tenant_id) tenant,HEX(role_id) role_id,HEX(resource_id) resource_id,status FROM iam_role_resource ORDER BY tenant_id,role_id,resource_id");
        assertThat(grantsBefore).isNotEmpty();
        jdbc.execute("INSERT INTO iam_app_settings(tenant_id,application_id) SELECT UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),id FROM iam_application WHERE app_code='SUPPLY_CHAIN'");
        jdbc.execute("INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name) SELECT UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),application_id,id,id,'PAGE','销售菜单保留名称' FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.SALES_DASHBOARD'");
        jdbc.execute("INSERT INTO iam_tenant_subscription(id,tenant_id,package_version_id,status,effective_from,effective_to,user_limit,created_at,updated_at) SELECT UUID_TO_BIN(UUID()),UUID_TO_BIN('019facff-0000-7000-8000-000000000002'),id,'ACTIVE',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 YEAR),100,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_tenant_package_version WHERE id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')");
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForObject("SELECT HEX(id) FROM iam_oauth_client WHERE client_id='rigour-scdp-browser'", String.class)).isEqualTo(oldId);
        assertThat(jdbc.queryForObject("SELECT HEX(id) FROM iam_oauth_client WHERE client_id='rigour-scdp-desktop'", String.class)).isEqualTo(existingId);
        assertThat(jdbc.queryForObject("SELECT status FROM iam_oauth_client WHERE client_id='rigour-portal-desktop'", String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT status FROM iam_platform_user WHERE username='old-admin'", String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_auth_session WHERE principal_scope='PLATFORM' AND status='ACTIVE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_application WHERE status='ACTIVE' AND app_code<>'SUPPLY_CHAIN'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT HEX(tenant_id) tenant,HEX(role_id) role_id,HEX(resource_id) resource_id,status FROM iam_role_resource ORDER BY tenant_id,role_id,resource_id")).isEqualTo(grantsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_resource r JOIN iam_application a ON a.id=r.application_id WHERE a.app_code='SUPPLY_CHAIN' AND r.permission_code LIKE 'sales:%' AND r.status='ACTIVE'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_menu_node WHERE node_type='BUTTON'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_role_grant", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_settings WHERE authorization_mode='ACTIVE'", Integer.class)).isZero();
    }
}
