package com.rigour.tenant.iam.infrastructure.persistence.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 执行完整 V87 SQL，验证两个人工同步权限归属及普通角色、套餐和停用应用边界。 */
class DhbSyncPermissionMigrationTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcIdentityAccessReader reader;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET TIME ZONE 'UTC'");
        jdbc.execute(
                "CREATE TABLE iam_application (id BINARY(16) PRIMARY KEY, app_code VARCHAR(80)"
                    + " UNIQUE, app_scope VARCHAR(20), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_resource (id BINARY(16) PRIMARY KEY, application_id BINARY(16),"
                    + " parent_id BINARY(16), resource_code VARCHAR(100) UNIQUE, permission_code"
                    + " VARCHAR(80), resource_type VARCHAR(20), display_name VARCHAR(100) DEFAULT"
                    + " 'original', status VARCHAR(20), deleted_at TIMESTAMP, updated_at TIMESTAMP,"
                    + " version INT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE iam_resource_ui (resource_id BINARY(16), route_key VARCHAR(80),"
                    + " visible INT)");
        jdbc.execute(
                "CREATE TABLE iam_tenant (id BINARY(16) PRIMARY KEY, company_name VARCHAR(80),"
                    + " status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_user (id BINARY(16), tenant_id BINARY(16), username VARCHAR(80),"
                    + " display_name VARCHAR(80), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_role (id BINARY(16), tenant_id BINARY(16), role_code VARCHAR(80),"
                    + " role_type VARCHAR(20), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_user_role (tenant_id BINARY(16), user_id BINARY(16), role_id"
                    + " BINARY(16), status VARCHAR(20), effective_from TIMESTAMP, effective_to"
                    + " TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_role_resource (tenant_id BINARY(16), role_id BINARY(16),"
                    + " resource_id BINARY(16), status VARCHAR(20), created_at TIMESTAMP,"
                    + " created_by BINARY(16), updated_at TIMESTAMP, updated_by BINARY(16), PRIMARY"
                    + " KEY(tenant_id,role_id,resource_id))");
        jdbc.execute(
                "CREATE TABLE iam_tenant_subscription (tenant_id BINARY(16), package_version_id"
                    + " BINARY(16), status VARCHAR(20), effective_from TIMESTAMP, effective_to"
                    + " TIMESTAMP, deleted_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE iam_package_resource (package_version_id BINARY(16), resource_id"
                    + " BINARY(16), PRIMARY KEY(package_version_id,resource_id))");
        jdbc.update(
                "INSERT INTO iam_application VALUES (?, 'SUPPLY_CHAIN', 'TENANT', 'ACTIVE', NULL)",
                bin("supply"));
        jdbc.update(
                "INSERT INTO iam_application VALUES (?, 'DHB_INTEGRATION', 'TENANT', 'DISABLED',"
                    + " NULL)",
                bin("old"));
        resource("page", "supply", null, "SUPPLY_CHAIN.PAGE.DINGHUOBAO_OVERVIEW", null, "PAGE");
        resource("root", "old", null, "DHB_INTEGRATION.ROOT", null, "APPLICATION");
        resource("read", "old", "root", "DHB_INTEGRATION.API.READ", "integration:dhb:read", "API");
        resource(
                "write",
                "old",
                "root",
                "DHB_INTEGRATION.API.WRITE",
                "integration:dhb:write",
                "API");
        resource("service", "old", "root", "TEST.SERVICE.LEASE", "integration:lease:write", "API");
        resource(
                "order-read", "supply", "page", "SUPPLY_CHAIN.API.ORDER_READ", "order:read", "API");
        resource(
                "product-write",
                "supply",
                "page",
                "SUPPLY_CHAIN.API.ERP_PRODUCT_WRITE",
                "erp:product:write",
                "API");
        resource(
                "supply-write",
                "supply",
                "page",
                "SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE",
                "erp:supply:write",
                "API");
        resource(
                "customer-write",
                "supply",
                "page",
                "SUPPLY_CHAIN.API.CRM_CUSTOMER_WRITE",
                "crm:customer:write",
                "API");
        resource("hr-sync", "supply", "page", "TEST.HR.SYNC", "hr:employee:sync", "API");
        jdbc.update(
                "INSERT INTO iam_resource_ui VALUES (?, 'supply.integration.overview', 1)",
                bin("page"));
        jdbc.update(
                "INSERT INTO iam_resource_ui VALUES (?, 'retired.integration', 0)", bin("root"));
        tenantUser("tenant", "admin", true, "package");
        tenantUser("tenant", "sales", false, null);
        tenantUser("other", "other-admin", true, "other-package");
        for (String resource : List.of("page", "root", "read", "write", "service")) {
            jdbc.update(
                    "INSERT INTO iam_package_resource VALUES (?, ?)",
                    bin("package"),
                    bin(resource));
        }
        jdbc.update(
                "INSERT INTO iam_role_resource (tenant_id,role_id,resource_id,status) VALUES (?, ?,"
                    + " ?, 'ACTIVE')",
                bin("tenant"),
                bin("sales"),
                bin("read"));
        jdbc.update(
                "INSERT INTO iam_role_resource (tenant_id,role_id,resource_id,status) VALUES (?, ?,"
                    + " ?, 'INACTIVE')",
                bin("tenant"),
                bin("sales"),
                bin("write"));
        String v86 =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V86__iam_tenant_admin_effective_resources.sql"));
        jdbc.execute(
                v86.substring(v86.indexOf("CREATE VIEW"), v86.indexOf("-- END EFFECTIVE ROLE VIEW"))
                        .replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP"));
        reader =
                new JdbcIdentityAccessReader(
                        jdbc,
                        org.mockito.Mockito.mock(
                                com.rigour.tenant.iam.application.port.out.AppSettingsStore.class));
    }

    @AfterEach
    void close() {
        dataSource.destroy();
    }

    @Test
    void movesOnlyTheTwoHumanApisAndKeepsLegacyApplicationAndRoutesDisabled() throws Exception {
        migrate();
        for (String resource : List.of("read", "write")) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT application_id FROM iam_resource WHERE id=?",
                                    byte[].class,
                                    bin(resource)))
                    .isEqualTo(bin("supply"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT parent_id FROM iam_resource WHERE id=?",
                                    byte[].class,
                                    bin(resource)))
                    .isEqualTo(bin("page"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM iam_application WHERE id=?",
                                String.class,
                                bin("old")))
                .isEqualTo("DISABLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT application_id FROM iam_resource WHERE id=?",
                                byte[].class,
                                bin("service")))
                .isEqualTo(bin("old"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT visible FROM iam_resource_ui WHERE resource_id=?",
                                Integer.class,
                                bin("root")))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_resource_ui", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_package_resource", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void grantsEntitledSystemAdministratorButPreservesEveryOrdinaryGrant() throws Exception {
        assertThat(reader.readCurrentUser(query("admin", "tenant")).permissions()).isEmpty();
        migrate();
        assertThat(reader.readCurrentUser(query("admin", "tenant")).permissions())
                .containsExactlyInAnyOrder("integration:dhb:read", "integration:dhb:write");
        assertThat(reader.readCurrentUser(query("sales", "tenant")).permissions())
                .containsExactly("integration:dhb:read");
        assertThat(reader.readCurrentUser(query("other-admin", "other")).permissions()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM iam_role_resource WHERE role_id=? AND"
                                    + " status='ACTIVE'",
                                Integer.class,
                                bin("admin")))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM iam_role_resource WHERE role_id=? ORDER BY"
                                    + " status",
                                String.class,
                                bin("sales")))
                .containsExactly("ACTIVE", "INACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM iam_role_resource WHERE tenant_id=?",
                                Integer.class,
                                bin("other")))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_user_role", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void canReapplyWithoutAddingGrantsOrChangingResourceVersionsAgain() throws Exception {
        migrate();
        migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_role_resource", Integer.class))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM iam_resource WHERE id=?",
                                Integer.class,
                                bin("read")))
                .isEqualTo(1);
    }

    @Test
    void correctsOnlyFourBusinessLabelsWithoutChangingTheirPermissionsOrGrants() throws Exception {
        List<String> before =
                jdbc.queryForList(
                        "SELECT permission_code FROM iam_resource WHERE permission_code IS NOT NULL"
                            + " ORDER BY permission_code",
                        String.class);
        migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM iam_resource WHERE id=?",
                                String.class,
                                bin("order-read")))
                .isEqualTo("查询订单、发货与收退款");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM iam_resource WHERE id=?",
                                String.class,
                                bin("product-write")))
                .isEqualTo("维护商品、分类、品牌与规格");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM iam_resource WHERE id=?",
                                String.class,
                                bin("supply-write")))
                .isEqualTo("维护采购、库存与供应商");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM iam_resource WHERE id=?",
                                String.class,
                                bin("customer-write")))
                .isEqualTo("维护客户与客户基础数据");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT display_name FROM iam_resource WHERE id=?",
                                String.class,
                                bin("hr-sync")))
                .isEqualTo("original");
        assertThat(
                        jdbc.queryForList(
                                "SELECT permission_code FROM iam_resource WHERE permission_code IS"
                                    + " NOT NULL ORDER BY permission_code",
                                String.class))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM iam_role_resource WHERE resource_id IN (?, ?,"
                                    + " ?, ?, ?)",
                                Integer.class,
                                bin("order-read"),
                                bin("product-write"),
                                bin("supply-write"),
                                bin("customer-write"),
                                bin("hr-sync")))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE iam_application SET status='DISABLED' WHERE app_code='SUPPLY_CHAIN'",
                "UPDATE iam_resource SET status='DISABLED' WHERE resource_type='PAGE'",
                "DELETE FROM iam_package_resource",
                "UPDATE iam_tenant_subscription SET effective_to=TIMESTAMP '2000-01-01 00:00:00'"
            })
    void unavailableDestinationOrEntitlementCannotExpandAdministratorPermissions(String sql)
            throws Exception {
        jdbc.execute(sql);
        migrate();
        assertThat(reader.readCurrentUser(query("admin", "tenant")).permissions()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM iam_role_resource WHERE role_id=?",
                                Integer.class,
                                bin("admin")))
                .isZero();
    }

    private void migrate() throws Exception {
        String source =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V87__iam_dhb_sync_permissions_supply_application.sql"));
        ScriptUtils.executeSqlScript(
                dataSource.getConnection(),
                new ByteArrayResource(
                        source.replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP")
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private void resource(
            String key, String app, String parent, String code, String permission, String type) {
        jdbc.update(
                "INSERT INTO iam_resource"
                    + " (id,application_id,parent_id,resource_code,permission_code,resource_type,status)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')",
                bin(key),
                bin(app),
                parent == null ? null : bin(parent),
                code,
                permission,
                type);
    }

    private void tenantUser(String tenant, String user, boolean administrator, String pack) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_tenant WHERE id=?", Integer.class, bin(tenant))
                == 0) {
            jdbc.update(
                    "INSERT INTO iam_tenant VALUES (?, ?, 'ACTIVE', NULL)", bin(tenant), tenant);
        }
        jdbc.update(
                "INSERT INTO iam_user VALUES (?, ?, ?, ?, 'ACTIVE', NULL)",
                bin(user),
                bin(tenant),
                user,
                user);
        jdbc.update(
                "INSERT INTO iam_role VALUES (?, ?, ?, ?, 'ACTIVE', NULL)",
                bin(user),
                bin(tenant),
                administrator ? "TENANT_SUPER_ADMIN" : "SALES",
                administrator ? "SYSTEM" : "CUSTOM");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO iam_user_role VALUES (?, ?, ?, 'ACTIVE', ?, NULL)",
                bin(tenant),
                bin(user),
                bin(user),
                now.minusDays(1));
        if (pack != null)
            jdbc.update(
                    "INSERT INTO iam_tenant_subscription VALUES (?, ?, 'ACTIVE', ?, ?, NULL)",
                    bin(tenant),
                    bin(pack),
                    now.minusDays(1),
                    now.plusDays(1));
    }

    private static IdentityAccessQuery query(String user, String tenant) {
        return new IdentityAccessQuery("TENANT", id(user), id(tenant));
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bin(String key) {
        return UuidBinaryCodec.encode(id(key));
    }
}
