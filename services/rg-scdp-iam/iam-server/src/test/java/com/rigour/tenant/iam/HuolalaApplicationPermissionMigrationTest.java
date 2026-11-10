package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 验证应用内权限桥接及默认授权边界，不连接业务环境。 */
public class HuolalaApplicationPermissionMigrationTest {
    public static byte[] uuidBytes(String value) {
        var u = UUID.fromString(value);
        return ByteBuffer.allocate(16)
                .putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits())
                .array();
    }

    public static LocalDateTime utcNow(int precision) {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void addsEveryActionNodeAndOnlyGrantsExistingProtectedAdministrators() throws Exception {
        var ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE ALIAS UUID_TO_BIN FOR '" + getClass().getName() + ".uuidBytes'");
            jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR '" + getClass().getName() + ".utcNow'");
            jdbc.execute(
                    "CREATE TABLE iam_application(id BINARY(16) PRIMARY KEY,app_code"
                        + " VARCHAR(50),deleted_at TIMESTAMP)");
            jdbc.execute(
                    "CREATE TABLE iam_resource(id BINARY(16) PRIMARY KEY,application_id"
                        + " BINARY(16),parent_id BINARY(16),permission_code"
                        + " VARCHAR(100),display_name VARCHAR(100),sort_order INT,status"
                        + " VARCHAR(20),deleted_at TIMESTAMP)");
            jdbc.execute(
                    "CREATE TABLE iam_app_menu_node(tenant_id BINARY(16),application_id"
                        + " BINARY(16),id BINARY(16),parent_id BINARY(16),resource_id"
                        + " BINARY(16),node_type VARCHAR(20),display_name VARCHAR(100),sort_order"
                        + " INT,visible BOOLEAN,status VARCHAR(20),created_at TIMESTAMP,updated_at"
                        + " TIMESTAMP,deleted_at TIMESTAMP,PRIMARY"
                        + " KEY(tenant_id,application_id,id),UNIQUE(tenant_id,application_id,resource_id))");
            jdbc.execute(
                    "CREATE TABLE iam_app_role(tenant_id BINARY(16),application_id BINARY(16),id"
                        + " BINARY(16),role_code VARCHAR(100),protected_role BOOLEAN,status"
                        + " VARCHAR(20),deleted_at TIMESTAMP)");
            jdbc.execute(
                    "CREATE TABLE iam_app_role_grant(tenant_id BINARY(16),application_id"
                        + " BINARY(16),role_id BINARY(16),menu_node_id BINARY(16),PRIMARY"
                        + " KEY(tenant_id,application_id,role_id,menu_node_id))");
            jdbc.execute(
                    "CREATE TABLE iam_app_settings(tenant_id BINARY(16),application_id"
                        + " BINARY(16),version BIGINT,updated_at TIMESTAMP)");
            jdbc.execute(
                    "CREATE TABLE iam_tenant(id BINARY(16),policy_version BIGINT,version"
                        + " BIGINT,updated_at TIMESTAMP)");
            byte[] app = uuidBytes(UUID.randomUUID().toString()),
                    page = uuidBytes("691faa79-2d5d-45c3-9868-34d079500010");
            jdbc.update("INSERT INTO iam_application VALUES(?,'SUPPLY_CHAIN',NULL)", app);
            jdbc.update(
                    "INSERT INTO iam_resource VALUES(?,?,NULL,NULL,'货拉拉接入',1,'ACTIVE',NULL)",
                    page,
                    app);
            int index = 0;
            for (var permission :
                    new String[] {
                        "integration:huolala:read",
                        "integration:huolala:write",
                        "integration:huolala:sync",
                        "order:logistics-cost:manage-imports"
                    }) {
                jdbc.update(
                        "INSERT INTO iam_resource VALUES(?,?,?,?,?,?,'ACTIVE',NULL)",
                        uuidBytes(UUID.randomUUID().toString()),
                        app,
                        page,
                        permission,
                        permission,
                        ++index);
            }
            for (int n = 0; n < 2; n++) {
                byte[] tenant = uuidBytes(UUID.randomUUID().toString()),
                        admin = uuidBytes(UUID.randomUUID().toString()),
                        reader = uuidBytes(UUID.randomUUID().toString());
                jdbc.update("INSERT INTO iam_tenant VALUES(?,1,1,NULL)", tenant);
                jdbc.update("INSERT INTO iam_app_settings VALUES(?,?,1,NULL)", tenant, app);
                jdbc.update(
                        "INSERT INTO"
                            + " iam_app_menu_node(tenant_id,application_id,id,resource_id,node_type,display_name,sort_order,visible,status)"
                            + " VALUES(?,?,?,?,'PAGE','货拉拉接入',1,TRUE,'ACTIVE')",
                        tenant,
                        app,
                        page,
                        page);
                jdbc.update(
                        "INSERT INTO iam_app_role"
                            + " VALUES(?,?,?,'SUPPLY_BOOTSTRAP_ADMIN',TRUE,'ACTIVE',NULL)",
                        tenant,
                        app,
                        admin);
                jdbc.update(
                        "INSERT INTO iam_app_role VALUES(?,?,?,'COST_READER',FALSE,'ACTIVE',NULL)",
                        tenant,
                        app,
                        reader);
                jdbc.update(
                        "INSERT INTO iam_app_role_grant VALUES(?,?,?,?)",
                        tenant,
                        app,
                        reader,
                        page);
            }
            try (Connection connection = ds.getConnection()) {
                var resource =
                        new ClassPathResource(
                                "db/migration/V151__huolala_application_action_nodes.sql");
                ScriptUtils.executeSqlScript(connection, resource);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM iam_app_menu_node WHERE"
                                            + " node_type='BUTTON'",
                                        Long.class))
                        .isEqualTo(8);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM iam_app_role_grant g JOIN"
                                            + " iam_app_role r ON r.tenant_id=g.tenant_id AND"
                                            + " r.id=g.role_id WHERE"
                                            + " r.role_code='SUPPLY_BOOTSTRAP_ADMIN'",
                                        Long.class))
                        .isEqualTo(10);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM iam_app_role_grant g JOIN"
                                            + " iam_app_role r ON r.tenant_id=g.tenant_id AND"
                                            + " r.id=g.role_id JOIN iam_app_menu_node n ON"
                                            + " n.tenant_id=g.tenant_id AND n.id=g.menu_node_id"
                                            + " WHERE r.role_code='COST_READER' AND"
                                            + " n.node_type='BUTTON'",
                                        Long.class))
                        .isZero();
                ScriptUtils.executeSqlScript(connection, resource);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM iam_app_menu_node", Long.class))
                        .isEqualTo(10);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT MIN(version) FROM iam_app_settings", Long.class))
                        .isEqualTo(3);
            }
        } finally {
            ds.destroy();
        }
    }
}
