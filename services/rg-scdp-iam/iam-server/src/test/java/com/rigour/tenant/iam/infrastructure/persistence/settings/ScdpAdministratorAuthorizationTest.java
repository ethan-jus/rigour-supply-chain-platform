package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 MySQL 验证动态管理员权限、数据库菜单配置及企业边界；不连接共享 DEV。 */
@Testcontainers
class ScdpAdministratorAuthorizationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    JdbcAppSettingsStore settings;
    JdbcAppAuthorizationStore authorization;
    JdbcAppMemberStore members;
    Actor admin, ordinary;
    UUID app, role, page, button;
    String action;

    @BeforeAll static void migrate() {
        ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
    }

    @BeforeEach void seed() {
        jdbc = new JdbcTemplate(ds);
        var employee = mock(AppEmployeeClient.class);
        settings = new JdbcAppSettingsStore(jdbc, new DataSourceTransactionManager(ds), employee);
        members = mock(JdbcAppMemberStore.class);
        authorization = new JdbcAppAuthorizationStore(jdbc, settings, mock(JdbcAppRoleStore.class), members, employee);
        app = settings.applicationId();
        UUID tenant = tenant();
        admin = new Actor("TENANT", user(tenant, "admin", true), tenant);
        ordinary = new Actor("TENANT", user(tenant, "ordinary", false), tenant);
        role = settings.protectedAdministratorRole(admin, null);
        page = UUID.randomUUID();
        byte[] resource = jdbc.queryForObject("SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles'", byte[].class);
        jdbc.update("INSERT INTO iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name) VALUES(?,?,?,?,'PAGE','自定义客户页面')",
                bin(tenant), bin(app), bin(page), resource);
        // 在管理员账号与角色创建后新增功能，完全不写 iam_app_role_grant / iam_app_scope_rule。
        button = UUID.randomUUID();
        action = "crm:customer:test-" + button.toString().substring(0, 8);
        jdbc.update("INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,status,created_at,updated_at) VALUES(?,?,?,?, 'BUTTON',?,'新增动作','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                bin(button), bin(app), resource, "TEST." + button, action);
        jdbc.update("INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) VALUES(UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),?,UTC_TIMESTAMP(6))", bin(button));
        jdbc.update("INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name) VALUES(?,?,?,?,?,'BUTTON','新增动作')",
                bin(tenant), bin(app), bin(button), bin(page), bin(button));
    }

    UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", bin(id), id.toString().substring(0, 12), "测试企业");
        jdbc.update("INSERT INTO iam_tenant_subscription(id,tenant_id,package_version_id,status,effective_from,effective_to,user_limit,created_at,updated_at) VALUES(?,?,UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),'ACTIVE',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 YEAR),100,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", bin(UUID.randomUUID()), bin(id));
        jdbc.update("INSERT INTO iam_app_settings(tenant_id,application_id,authorization_mode) VALUES(?,?,'ACTIVE')", bin(id), bin(app));
        return id;
    }

    UUID user(UUID tenant, String name, boolean protectedRole) {
        UUID user = UUID.randomUUID(), assignedRole = UUID.randomUUID();
        jdbc.update("INSERT INTO iam_user(id,tenant_id,username,display_name,status,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", bin(user), bin(tenant), name, name);
        jdbc.update("INSERT INTO iam_app_member(tenant_id,application_id,user_id,member_kind,status) VALUES(?,?,?,'PROTECTED','ACTIVE')", bin(tenant), bin(app), bin(user));
        jdbc.update("INSERT INTO iam_app_role(tenant_id,application_id,id,role_code,role_name,protected_role) VALUES(?,?,?,?,'管理员',?)", bin(tenant), bin(app), bin(assignedRole), name, protectedRole);
        jdbc.update("INSERT INTO iam_app_member_role(tenant_id,application_id,user_id,role_id) VALUES(?,?,?,?)", bin(tenant), bin(app), bin(user), bin(assignedRole));
        return user;
    }

    @Test void newFunctionsAreAutomaticForBuiltInAdministratorOnly() {
        assertThat(settings.permissions(admin)).containsExactly(action);
        assertThat(settings.permissions(ordinary)).isEmpty();
        assertThat(settings.protectedAdministrator(ordinary)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_app_role_grant WHERE tenant_id=?", Integer.class, bin(admin.tenantId()))).isZero();
        assertThat(settings.permissionsExcludingRole(admin, role)).isEmpty();
    }

    @Test void navigationReadsSavedNamesHierarchyOrderAndVisibility() {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO iam_app_menu_node(tenant_id,application_id,id,node_type,display_name,sort_order) VALUES(?,?,?,'MENU','华东业务',7)", bin(admin.tenantId()), bin(app), bin(group));
        jdbc.update("UPDATE iam_app_menu_node SET parent_id=?,display_name='杭州客户',sort_order=23,icon_key='User' WHERE tenant_id=? AND id=?", bin(group), bin(admin.tenantId()), bin(page));
        var tree = settings.navigation(admin);
        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().displayName()).isEqualTo("华东业务");
        var child = tree.getFirst().children().getFirst();
        assertThat(child.displayName()).isEqualTo("杭州客户");
        assertThat(child.sortOrder()).isEqualTo(23);
        assertThat(child.iconKey()).isEqualTo("User");
        assertThat(child.routePath()).isEqualTo("/supply-chain/crm/customers/profiles");
        jdbc.update("UPDATE iam_app_menu_node SET visible=0 WHERE tenant_id=? AND id=?", bin(admin.tenantId()), bin(group));
        assertThat(settings.navigation(admin).getFirst().children().getFirst().visible()).isFalse();
        assertThat(settings.permissions(admin)).contains(action); // 隐藏不等于禁用。
        assertThat(settings.navigation(ordinary)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"crm:customer,CUSTOMER", "erp:inventory,INVENTORY", "order:sales,ORDER", "hr:employee,EMPLOYEE", "analytics:dashboard,ANALYTICS"})
    void administratorDataScopeCoversCurrentTenantWithoutPerActionSeedRules(String prefix, String objectType) {
        action = prefix + ":test-" + button.toString().substring(0, 8);
        jdbc.update("UPDATE iam_resource SET permission_code=? WHERE id=?", action, bin(button));
        var snapshot = authorization.proposed(admin, action);
        assertThat(snapshot.tenantId()).isEqualTo(admin.tenantId());
        assertThat(snapshot.functionAllowed()).isTrue();
        assertThat(snapshot.clauses()).singleElement().satisfies(clause -> {
            assertThat(clause.roleId()).isEqualTo(role);
            assertThat(clause.objectType()).isEqualTo(objectType);
            assertThat(clause.scopeMode()).isEqualTo("ALL");
            assertThat(clause.departments().mode()).isEqualTo("ALL");
            assertThat(clause.regions().mode()).isEqualTo("ALL");
            assertThat(clause.warehouses().mode()).isEqualTo("ALL");
        });
        assertThat(snapshot.regionLimit().mode()).isEqualTo("ALL");
        assertThat(snapshot.warehouseLimit().mode()).isEqualTo("ALL");
        verifyNoInteractions(members);
        var unknown = authorization.proposed(admin, "crm:customer:unknown");
        assertThat(unknown.functionAllowed()).isFalse();
        assertThat(unknown.clauses()).isEmpty();
    }

    @Test void noOtherTenantOrUnsubscribedOrDisabledFunctionAccess() {
        UUID otherTenant = tenant();
        Actor wrongTenant = new Actor("TENANT", admin.principalId(), otherTenant);
        assertThat(settings.permissions(wrongTenant)).isEmpty();
        assertThatThrownBy(() -> settings.navigation(wrongTenant)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("UPDATE iam_app_menu_node SET status='DISABLED' WHERE tenant_id=? AND id=?", bin(admin.tenantId()), bin(page));
        assertThat(settings.permissions(admin)).doesNotContain(action);
        jdbc.update("UPDATE iam_app_menu_node SET status='ACTIVE' WHERE tenant_id=? AND id=?", bin(admin.tenantId()), bin(page));
        jdbc.update("DELETE FROM iam_package_resource WHERE resource_id=?", bin(button));
        assertThat(settings.permissions(admin)).doesNotContain(action);
    }

    @ParameterizedTest @ValueSource(strings={"iam_user", "iam_app_member", "iam_app_role"})
    void disabledIdentityOrRoleImmediatelyLosesAdministratorAccess(String table) {
        String key = table.equals("iam_app_member") ? "user_id" : "id";
        UUID id = table.equals("iam_app_role") ? role : admin.principalId();
        jdbc.update("UPDATE " + table + " SET status='DISABLED' WHERE tenant_id=? AND " + key + "=?", bin(admin.tenantId()), bin(id));
        assertThat(settings.protectedAdministrator(admin)).isFalse();
        assertThat(settings.permissions(admin)).isEmpty();
        assertThatThrownBy(() -> authorization.proposed(admin, action)).isInstanceOf(AccessDeniedException.class);
    }
}
