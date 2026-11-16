package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static com.rigour.tenant.iam.infrastructure.persistence.settings.JdbcAppSettingsStore.bin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL on isolated H2; version publication, rollback and node isolation are exercised without DEV writes. */
class IamAuthorizationDefinitionCacheTest {
    final UUID tenant = UUID.randomUUID(), app = UUID.randomUUID(), user = UUID.randomUUID(), role = UUID.randomUUID(), node = UUID.randomUUID();
    final Actor actor = new Actor("TENANT", user, tenant);
    JdbcTemplate jdbc;
    JdbcAppSettingsStore settings;
    JdbcAppRoleStore roles;
    DataSourceTransactionManager transactions;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = spy(new JdbcTemplate(ds)); transactions = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE iam_application(id BINARY(16), app_code VARCHAR(40), status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE iam_app_settings(tenant_id BINARY(16), application_id BINARY(16), version BIGINT)");
        jdbc.execute("CREATE TABLE iam_app_role(tenant_id BINARY(16), application_id BINARY(16), id BINARY(16), status VARCHAR(20), deleted_at TIMESTAMP, data_scope_mode VARCHAR(20), data_scope_departments VARCHAR(1000))");
        jdbc.execute("CREATE TABLE iam_app_member_role(tenant_id BINARY(16), application_id BINARY(16), user_id BINARY(16), role_id BINARY(16))");
        jdbc.execute("CREATE TABLE iam_app_role_grant(tenant_id BINARY(16), application_id BINARY(16), role_id BINARY(16), menu_node_id BINARY(16))");
        jdbc.update("INSERT INTO iam_application VALUES(?,'SUPPLY_CHAIN','ACTIVE',NULL)", bin(app));
        jdbc.update("INSERT INTO iam_app_settings VALUES(?,?,1)", bin(tenant), bin(app));
        jdbc.update("INSERT INTO iam_app_role VALUES(?,?,?,'ACTIVE',NULL,'SELF','[]')", bin(tenant), bin(app), bin(role));
        jdbc.update("INSERT INTO iam_app_member_role VALUES(?,?,?,?)", bin(tenant), bin(app), bin(user), bin(role));
        jdbc.update("INSERT INTO iam_app_role_grant VALUES(?,?,?,?)", bin(tenant), bin(app), bin(role), bin(node));
        settings = new JdbcAppSettingsStore(jdbc, transactions, mock(AppEmployeeClient.class));
        roles = new JdbcAppRoleStore(jdbc, settings, transactions, mock(AppReferenceValidator.class), mock(AppEmployeeClient.class));
        clearInvocations(jdbc);
    }

    @Test void warmAuthorizationReadsOnlyVersionsAndOnlyUsesAssignedRoles() {
        jdbc.update("INSERT INTO iam_app_role VALUES(?,?,?,'ACTIVE',NULL,'ALL','[]')", bin(tenant), bin(app), bin(UUID.randomUUID()));
        var cold = settings.authorizationRead(() -> roles.authorizationRoles(actor));
        assertThat(cold).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(role); assertThat(r.menuNodeIds()).containsExactly(node); assertThat(r.dataScope().mode()).isEqualTo("SELF");
        });
        clearInvocations(jdbc);
        assertThat(settings.authorizationRead(() -> roles.authorizationRoles(actor))).isEqualTo(cold);
        var sql = mockingDetails(jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().startsWith("query"))
                .filter(i -> i.getArgument(0) instanceof String).map(i -> i.getArgument(0).toString()).distinct().toList();
        assertThat(sql).hasSize(2).allMatch(s -> !s.contains("iam_app_role"));
        assertThat(settings.authorizationRead(() -> roles.authorizationRoles(new Actor("TENANT", UUID.randomUUID(), tenant)))).isEmpty();
        var otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO iam_app_settings VALUES(?,?,1)", bin(otherTenant), bin(app));
        assertThat(settings.authorizationRead(() -> roles.authorizationRoles(new Actor("TENANT", user, otherTenant)))).isEmpty();
        assertThatThrownBy(() -> cold.getFirst().menuNodeIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> cold.getFirst().dataScope().departmentIds().add("7")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void committedVersionInvalidatesEveryInstanceWithoutTrustingTtl() {
        var otherSettings = new JdbcAppSettingsStore(jdbc, transactions, mock(AppEmployeeClient.class));
        var otherRoles = new JdbcAppRoleStore(jdbc, otherSettings, transactions, mock(AppReferenceValidator.class), mock(AppEmployeeClient.class));
        settings.authorizationRead(() -> roles.authorizationRoles(actor));
        otherSettings.authorizationRead(() -> otherRoles.authorizationRoles(actor));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM iam_app_role_grant WHERE tenant_id=?", bin(tenant));
            jdbc.update("UPDATE iam_app_role SET data_scope_mode='CUSTOM',data_scope_departments='[\"7\"]' WHERE tenant_id=?", bin(tenant));
            jdbc.update("UPDATE iam_app_settings SET version=version+1 WHERE tenant_id=?", bin(tenant));
        });
        for (var result : List.of(settings.authorizationRead(() -> roles.authorizationRoles(actor)),
                otherSettings.authorizationRead(() -> otherRoles.authorizationRoles(actor)))) {
            assertThat(result.getFirst().menuNodeIds()).isEmpty();
            assertThat(result.getFirst().dataScope().departmentIds()).containsExactly("7");
        }
    }

    @Test void writeTransactionBypassesWarmCacheAndRollbackCannotPublishItsValues() {
        settings.authorizationRead(() -> roles.authorizationRoles(actor));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("UPDATE iam_app_role SET data_scope_mode='ALL' WHERE tenant_id=?", bin(tenant));
            jdbc.update("UPDATE iam_app_settings SET version=version+1 WHERE tenant_id=?", bin(tenant));
            assertThat(settings.authorizationRead(() -> roles.authorizationRoles(actor)).getFirst().dataScope().mode()).isEqualTo("ALL");
            tx.setRollbackOnly();
        });
        assertThat(settings.authorizationRead(() -> roles.authorizationRoles(actor)).getFirst().dataScope().mode()).isEqualTo("SELF");
        jdbc.update("UPDATE iam_app_settings SET version=version+1 WHERE tenant_id=?", bin(tenant)); // Same version number as the rolled-back transaction must still load committed SELF.
        assertThat(settings.authorizationRead(() -> roles.authorizationRoles(actor)).getFirst().dataScope().mode()).isEqualTo("SELF");
    }

    @Test void concurrentConfigurationChangeAbortsTheSnapshotAndDoesNotCacheMixedVersions() {
        var cache = new VersionedIamReadCache<JdbcAppSettingsStore.AuthorizationKey, String>();
        assertThatThrownBy(() -> settings.authorizationRead(() -> settings.authorizationDefinition(actor, cache, () -> {
            jdbc.update("UPDATE iam_app_settings SET version=version+1"); return "stale";
        }))).hasMessageContaining("授权配置已变化");
        assertThat(settings.authorizationRead(() -> settings.authorizationDefinition(actor, cache, () -> "current"))).isEqualTo("current");
    }

    @Test void readOnlyTransactionsMayReuseOnlyCommittedDefinitions() {
        var tx = new TransactionTemplate(transactions); tx.setReadOnly(true);
        var first = tx.execute(status -> settings.authorizationRead(() -> roles.authorizationRoles(actor)));
        clearInvocations(jdbc);
        var next = tx.execute(status -> settings.authorizationRead(() -> roles.authorizationRoles(actor)));
        assertThat(next).isEqualTo(first);
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i -> i.getArguments().length > 0 && i.getArgument(0) instanceof String s && s.contains("iam_app_role"));
    }
}
