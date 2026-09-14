package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.api.controller.management.IamManagementController;
import com.rigour.tenant.iam.application.service.management.IamManagementService;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 使用真实迁移视图、JDBC 查询与 MyBatis 权限 SQL 验证租户管理员边界。 */
class TenantAdministratorEffectivePermissionsTest {
    private static final String MIGRATION = "src/main/resources/db/migration/V86__iam_tenant_admin_effective_resources.sql";
    private static final String READ = "analytics:dashboard:read";
    private static final String WRITE = "analytics:targets:write";
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcPortalAccessReader reader;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET TIME ZONE 'UTC'");
        jdbc.execute("CREATE TABLE iam_tenant (id BINARY(16) PRIMARY KEY, company_name VARCHAR(80), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_user (id BINARY(16), tenant_id BINARY(16), username VARCHAR(80), display_name VARCHAR(80), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_platform_user (id BINARY(16), username VARCHAR(80), display_name VARCHAR(80), platform_role VARCHAR(40), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_role (id BINARY(16), tenant_id BINARY(16), role_code VARCHAR(80), role_type VARCHAR(20), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_user_role (tenant_id BINARY(16), user_id BINARY(16), role_id BINARY(16), status VARCHAR(20), effective_from TIMESTAMP, effective_to TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_role_resource (tenant_id BINARY(16), role_id BINARY(16), resource_id BINARY(16), status VARCHAR(20), created_at TIMESTAMP, created_by BINARY(16), updated_at TIMESTAMP, updated_by BINARY(16), PRIMARY KEY(tenant_id, role_id, resource_id))");
        jdbc.execute("CREATE TABLE iam_application (id BINARY(16), app_scope VARCHAR(20), status VARCHAR(20), deleted_at TIMESTAMP, app_code VARCHAR(80), app_name VARCHAR(80), icon_key VARCHAR(80), launch_mode VARCHAR(20), target_uri VARCHAR(80), sort_order INT)");
        jdbc.execute("CREATE TABLE iam_resource (id BINARY(16), application_id BINARY(16), permission_code VARCHAR(80), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_tenant_subscription (tenant_id BINARY(16), package_version_id BINARY(16), status VARCHAR(20), effective_from TIMESTAMP, effective_to TIMESTAMP, deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_package_resource (package_version_id BINARY(16), resource_id BINARY(16))");
        jdbc.execute(migrationSection("EFFECTIVE ROLE VIEW"));
        tenant("tenant");
        tenant("other");
        subscription("tenant", "package");
        subscription("other", "other-package");
        application("bi", "TENANT", "ACTIVE");
        application("platform", "PLATFORM", "ACTIVE");
        role("admin", "tenant", "TENANT_SUPER_ADMIN", "SYSTEM");
        role("sales", "tenant", "SALES_CUSTOM", "CUSTOM");
        user("admin", "tenant", "admin");
        user("sales", "tenant", "sales");
        resource("read", "bi", READ, "package");
        resource("write", "bi", WRITE, "package");
        grant("sales", "read", "ACTIVE");
        reader = new JdbcPortalAccessReader(jdbc);
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        dataSource.destroy();
    }

    @Test
    void administratorGetsEntitledCatalogDynamicallyWithoutWideningOrdinaryRoles() {
        assertThat(permissions("admin")).containsExactlyInAnyOrder(READ, WRITE);
        assertThat(permissions("sales")).containsExactly(READ);
        resource("operations", "bi", "analytics:operations:write", "package");
        resource("reconciliation", "bi", "analytics:reconciliation:write", "package");
        assertThat(permissions("admin")).containsExactlyInAnyOrder(READ, WRITE,
                "analytics:operations:write", "analytics:reconciliation:write");
        assertThat(permissions("sales")).containsExactly(READ);
        grant("sales", "write", "ACTIVE");
        assertThat(permissions("sales")).containsExactlyInAnyOrder(READ, WRITE);
        jdbc.update("UPDATE iam_role_resource SET status='INACTIVE' WHERE role_id=? AND resource_id=?", bin("sales"), bin("write"));
        assertThat(permissions("sales")).containsExactly(READ);
    }

    @Test
    void persistsOnlyCurrentSystemAdministratorGrantsAndKeepsFuturePermissionsDynamic() throws Exception {
        grant("admin", "write", "INACTIVE");
        resource("other-resource", "bi", "other:private:read", "other-package");
        jdbc.execute(migrationSection("ADMIN GRANT BACKFILL"));
        jdbc.execute(migrationSection("ADMIN GRANT BACKFILL"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_role_resource WHERE role_id=? AND status='ACTIVE'", Integer.class, bin("admin"))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_role_resource WHERE role_id=?", Integer.class, bin("sales"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_user_role", Integer.class)).isEqualTo(2);
        resource("new", "bi", "analytics:operations:write", "package");
        assertThat(permissions("admin")).contains("analytics:operations:write").doesNotContain("other:private:read");
        jdbc.update("DELETE FROM iam_package_resource WHERE resource_id=?", bin("write"));
        assertThat(permissions("admin")).doesNotContain(WRITE);
    }

    @Test
    void missingMenuConfigurationIsPersistedWithoutOverridingHiddenMenusOrLeakingPackages() throws Exception {
        jdbc.execute("ALTER TABLE iam_resource ADD resource_type VARCHAR(20) DEFAULT 'API'");
        jdbc.execute("CREATE TABLE iam_resource_ui (resource_id BINARY(16), visible INT)");
        jdbc.execute("CREATE TABLE iam_tenant_menu_config (tenant_id BINARY(16), resource_id BINARY(16), visible INT, version INT, created_at TIMESTAMP, created_by BINARY(16), updated_at TIMESTAMP, updated_by BINARY(16), PRIMARY KEY(tenant_id,resource_id))");
        for (String page : List.of("visible-page", "hidden-page", "hidden-by-tenant", "other-page")) {
            resource(page, "bi", page + ":read", "other-page".equals(page) ? "other-package" : "package");
            jdbc.update("UPDATE iam_resource SET resource_type='PAGE' WHERE id=?", bin(page));
            jdbc.update("INSERT INTO iam_resource_ui VALUES (?, ?)", bin(page), "hidden-page".equals(page) ? 0 : 1);
        }
        jdbc.update("INSERT INTO iam_tenant_menu_config (tenant_id,resource_id,visible,version) VALUES (?, ?, 0, 7)", bin("tenant"), bin("hidden-by-tenant"));
        jdbc.execute(migrationSection("MISSING MENU CONFIG"));
        jdbc.execute(migrationSection("MISSING MENU CONFIG"));
        assertThat(jdbc.queryForObject("SELECT visible FROM iam_tenant_menu_config WHERE tenant_id=? AND resource_id=?", Integer.class, bin("tenant"), bin("visible-page"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT visible FROM iam_tenant_menu_config WHERE tenant_id=? AND resource_id=?", Integer.class, bin("tenant"), bin("hidden-page"))).isZero();
        assertThat(jdbc.queryForObject("SELECT visible FROM iam_tenant_menu_config WHERE tenant_id=? AND resource_id=?", Integer.class, bin("tenant"), bin("hidden-by-tenant"))).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM iam_tenant_menu_config WHERE tenant_id=? AND resource_id=?", Integer.class, bin("tenant"), bin("hidden-by-tenant"))).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_tenant_menu_config WHERE tenant_id=?", Integer.class, bin("tenant"))).isEqualTo(3);
    }

    @Test
    void excludesOtherTenantUnsubscribedDisabledDeletedAndPlatformResources() {
        resource("other-resource", "bi", "other:private:read", "other-package");
        resource("unsubscribed", "bi", "not:entitled:read", "no-subscription");
        resource("platform-resource", "platform", "platform:private:read", "package");
        application("disabled-app", "TENANT", "DISABLED");
        application("deleted-app", "TENANT", "ACTIVE");
        jdbc.update("UPDATE iam_application SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", bin("deleted-app"));
        resource("disabled-app-resource", "disabled-app", "disabled:app:read", "package");
        resource("deleted-app-resource", "deleted-app", "deleted:app:read", "package");
        resource("disabled", "bi", "disabled:resource:read", "package");
        resource("deleted", "bi", "deleted:resource:read", "package");
        jdbc.update("UPDATE iam_resource SET status='DISABLED' WHERE id=?", bin("disabled"));
        jdbc.update("UPDATE iam_resource SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", bin("deleted"));
        assertThat(permissions("admin")).containsExactlyInAnyOrder(READ, WRITE);
        assertThat(reader.readGrantedApplications(query("admin"))).extracting(app -> app.code()).containsExactly("bi");
        assertThatThrownBy(() -> reader.readCurrentUser(new PortalAccessQuery("TENANT", id("admin"), id("other"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE iam_tenant_subscription SET status='CANCELLED'",
            "UPDATE iam_tenant_subscription SET deleted_at=CURRENT_TIMESTAMP",
            "UPDATE iam_tenant_subscription SET effective_to=TIMESTAMP '2000-01-01 00:00:00'",
            "UPDATE iam_tenant_subscription SET effective_from=TIMESTAMP '2999-01-01 00:00:00'",
            "UPDATE iam_role SET status='DISABLED'",
            "UPDATE iam_role SET deleted_at=CURRENT_TIMESTAMP",
            "UPDATE iam_user_role SET status='INACTIVE'",
            "UPDATE iam_user_role SET effective_to=TIMESTAMP '2000-01-01 00:00:00'",
            "UPDATE iam_user_role SET effective_from=TIMESTAMP '2999-01-01 00:00:00'",
            "UPDATE iam_application SET status='DISABLED'"
    })
    void entitlementAndRoleRevocationsTakeEffectImmediately(String sql) {
        jdbc.execute(sql);
        assertThat(permissions("admin")).isEmpty();
        assertThat(permissions("sales")).isEmpty();
        assertThat(reader.readGrantedApplications(query("admin"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE iam_user SET status='DISABLED'", "UPDATE iam_tenant SET status='DISABLED'",
            "UPDATE iam_user SET deleted_at=CURRENT_TIMESTAMP", "UPDATE iam_tenant SET deleted_at=CURRENT_TIMESTAMP"})
    void inactivePrincipalsCannotObtainPermissionsOrApplications(String sql) {
        jdbc.execute(sql);
        assertThatThrownBy(() -> permissions("admin")).isInstanceOf(IllegalStateException.class);
        assertThat(reader.readGrantedApplications(query("admin"))).isEmpty();
    }

    @Test
    void customRoleCannotImpersonateSystemAdministratorByCode() {
        jdbc.update("UPDATE iam_role SET role_code='TENANT_SUPER_ADMIN' WHERE id=?", bin("sales"));
        assertThat(permissions("sales")).containsExactly(READ);
        assertThat(reader.readCurrentUser(query("sales")).roles()).doesNotContain("TENANT_SUPER_ADMIN");
    }

    @Test
    void platformAdministratorSemanticsAreUnchanged() {
        jdbc.update("INSERT INTO iam_platform_user VALUES (?, 'root', 'root', 'SUPER_ADMIN', 'ACTIVE', NULL)", bin("root"));
        PortalAccessQuery platform = new PortalAccessQuery("PLATFORM", id("root"), null);
        assertThat(reader.readCurrentUser(platform).permissions()).containsExactly("*:*:*");
        assertThat(reader.readGrantedApplications(platform)).extracting(app -> app.code()).containsExactly("platform");
        assertThat(permissions("admin")).doesNotContain("*:*:*");
    }

    @Test
    void gatewayCurrentTokenAndMeUseSameLivePermissionsInsteadOfJwtPermissionClaims() {
        Jwt token = Jwt.withTokenValue("same-validated-token").header("alg", "RS256")
                .claim("principalScope", "TENANT").claim("principalId", id("admin").toString())
                .claim("tenantId", id("tenant").toString()).claim("permissions", List.of("*:*:*")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of()));
        PortalAccessService access = new PortalAccessService(reader);
        IamManagementController controller = new IamManagementController(mock(IamManagementService.class), access);
        assertThat(controller.validateCurrentToken().permissions()).isEqualTo(access.currentUser(query("admin")).permissions());
        resource("operations", "bi", "analytics:operations:write", "package");
        assertThat(controller.validateCurrentToken().permissions()).contains("analytics:operations:write").doesNotContain("*:*:*");
        jdbc.update("UPDATE iam_resource SET status='DISABLED' WHERE id=?", bin("operations"));
        assertThat(controller.validateCurrentToken().permissions()).isEqualTo(access.currentUser(query("admin")).permissions())
                .doesNotContain("analytics:operations:write");
    }

    @ParameterizedTest
    @ValueSource(strings = {"role/RolePermissionMapper", "staff/StaffManagementMapper"})
    void managementPermissionGuardsExecuteTheSameEffectiveScopeSql(String mapper) throws Exception {
        String resource = "mapper/" + mapper + ".xml";
        Configuration configuration = new Configuration();
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String mapperName = mapper.substring(mapper.indexOf('/') + 1);
        var statement = configuration.getMappedStatement("com.rigour.tenant.iam.infrastructure.persistence.mapper." + mapperName + ".countTenantPermission");
        for (String user : List.of("admin", "sales")) {
            Map<String, Object> parameters = Map.of("tenantId", bin("tenant"), "userId", bin(user), "permission", WRITE);
            var boundSql = statement.getBoundSql(parameters);
            Object[] bindings = boundSql.getParameterMappings().stream().map(p -> parameters.get(p.getProperty())).toArray();
            Integer count = jdbc.queryForObject(boundSql.getSql().replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP"), Integer.class, bindings);
            assertThat(count).isEqualTo("admin".equals(user) ? 1 : 0);
        }
        jdbc.execute("UPDATE iam_user SET status='DISABLED'");
        Map<String, Object> parameters = Map.of("tenantId", bin("tenant"), "userId", bin("admin"), "permission", WRITE);
        var bound = statement.getBoundSql(parameters);
        assertThat(jdbc.queryForObject(bound.getSql().replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP"), Integer.class,
                bound.getParameterMappings().stream().map(p -> parameters.get(p.getProperty())).toArray())).isZero();
    }

    private Set<String> permissions(String user) { return reader.readCurrentUser(query(user)).permissions(); }
    private PortalAccessQuery query(String user) { return new PortalAccessQuery("TENANT", id(user), id("tenant")); }
    private void tenant(String tenant) { jdbc.update("INSERT INTO iam_tenant VALUES (?, ?, 'ACTIVE', NULL)", bin(tenant), tenant); }
    private void subscription(String tenant, String pack) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO iam_tenant_subscription VALUES (?, ?, 'ACTIVE', ?, ?, NULL)", bin(tenant), bin(pack), now.minusDays(1), now.plusDays(1));
    }
    private void application(String app, String scope, String status) {
        jdbc.update("INSERT INTO iam_application VALUES (?, ?, ?, NULL, ?, ?, NULL, 'EMBEDDED', '/', 1)", bin(app), scope, status, app, app);
    }
    private void role(String role, String tenant, String code, String type) {
        jdbc.update("INSERT INTO iam_role VALUES (?, ?, ?, ?, 'ACTIVE', NULL)", bin(role), bin(tenant), code, type);
    }
    private void user(String user, String tenant, String role) {
        jdbc.update("INSERT INTO iam_user VALUES (?, ?, ?, ?, 'ACTIVE', NULL)", bin(user), bin(tenant), user, user);
        jdbc.update("INSERT INTO iam_user_role VALUES (?, ?, ?, 'ACTIVE', ?, NULL)", bin(tenant), bin(user), bin(role), LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }
    private void resource(String resource, String app, String permission, String pack) {
        jdbc.update("INSERT INTO iam_resource (id,application_id,permission_code,status,deleted_at) VALUES (?, ?, ?, 'ACTIVE', NULL)", bin(resource), bin(app), permission);
        jdbc.update("INSERT INTO iam_package_resource VALUES (?, ?)", bin(pack), bin(resource));
    }
    private void grant(String role, String resource, String status) {
        jdbc.update("INSERT INTO iam_role_resource (tenant_id,role_id,resource_id,status) VALUES (?, ?, ?, ?)", bin("tenant"), bin(role), bin(resource), status);
    }
    private static String migrationSection(String name) throws Exception {
        String source = Files.readString(Path.of(MIGRATION));
        return source.substring(source.indexOf("-- BEGIN " + name) + ("-- BEGIN " + name).length(), source.indexOf("-- END " + name))
                .replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP");
    }
    private static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] bin(String key) { return UuidBinaryCodec.encode(id(key)); }
}
