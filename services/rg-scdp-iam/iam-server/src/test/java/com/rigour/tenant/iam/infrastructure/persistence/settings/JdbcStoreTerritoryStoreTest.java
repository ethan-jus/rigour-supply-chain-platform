package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot.Area;
import com.rigour.tenant.iam.application.model.settings.StoreTerritoryModels.Update;
import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class JdbcStoreTerritoryStoreTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static final UUID APP = UUID.randomUUID();
    static final AdminDivisionCatalog CATALOG = new AdminDivisionCatalog("TEST-2026", "test fixture", "test-only", List.of(
            new AdminDivisionCatalog.Node("330000", "浙江省", "PROVINCE", null, "EAST"),
            new AdminDivisionCatalog.Node("330100", "杭州市", "PREFECTURE", "330000", null),
            new AdminDivisionCatalog.Node("330105", "拱墅区", "COUNTY", "330100", null),
            new AdminDivisionCatalog.Node("620000", "甘肃省", "PROVINCE", null, "NORTHWEST"),
            new AdminDivisionCatalog.Node("620100", "兰州市", "PREFECTURE", "620000", null)));
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    JdbcStoreTerritoryStore store;
    JdbcAppSettingsStore settings;
    AppReferenceClient references;
    StoreDivisionClient divisions;
    Actor actor;

    @BeforeAll static void schema() {
        ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE iam_app_member_scope_limit(dimension VARCHAR(32),CONSTRAINT ck_app_limit_dimension CHECK(dimension IN ('REGION','WAREHOUSE')))");
        jdbc.execute("CREATE TABLE iam_app_settings(tenant_id BINARY(16),application_id BINARY(16),version BIGINT,PRIMARY KEY(tenant_id,application_id))");
        jdbc.execute("CREATE TABLE territory_test_audit(tenant VARCHAR(64),action VARCHAR(64),target VARCHAR(64),summary VARCHAR(1000))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V142__crm_store_territories.sql")).execute(ds);
    }

    @BeforeEach void setup() {
        actor = new Actor("TENANT", UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO iam_app_settings VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1)", actor.tenantId().toString(), APP.toString());
        settings = mock(JdbcAppSettingsStore.class);
        references = mock(AppReferenceClient.class);
        divisions = mock(StoreDivisionClient.class);
        when(settings.applicationId()).thenReturn(APP);
        when(settings.permissions(actor)).thenReturn(Set.of("supply:territory:read", "supply:territory:write"));
        when(references.references(actor.tenantId(), "DEPARTMENT")).thenReturn(List.of(
                new AppReferenceClient.Reference("10", "杭州销售部", null, "ACTIVE", 1),
                new AppReferenceClient.Reference("20", "停用部门", null, "INACTIVE", 1)));
        when(divisions.published(actor.tenantId())).thenReturn(CATALOG);
        doAnswer(invocation -> jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=UUID_TO_BIN(?) AND application_id=UUID_TO_BIN(?) FOR UPDATE", Long.class, actor.tenantId().toString(), APP.toString())).when(settings).lock(actor);
        doAnswer(invocation -> jdbc.update("UPDATE iam_app_settings SET version=version+1 WHERE tenant_id=UUID_TO_BIN(?) AND application_id=UUID_TO_BIN(?)", actor.tenantId().toString(), APP.toString())).when(settings).bump(actor);
        doAnswer(invocation -> jdbc.update("INSERT INTO territory_test_audit VALUES(?,?,?,?)", actor.tenantId().toString(), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)))
                .when(settings).audit(eq(actor), anyString(), anyString(), anyString());
        store = new JdbcStoreTerritoryStore(jdbc, settings, references, divisions, new DataSourceTransactionManager(ds));
    }

    Update command(long version, String... codes) { return new Update(version, CATALOG.version(), List.of(codes), "调整部门客户范围"); }
    List<Area> areas() { return store.departments(actor.tenantId().toString(), List.of("10")); }
    long version() { return jdbc.queryForObject("SELECT version FROM iam_app_settings WHERE tenant_id=UUID_TO_BIN(?)", Long.class, actor.tenantId().toString()); }
    long audits() { return jdbc.queryForObject("SELECT COUNT(*) FROM territory_test_audit WHERE tenant=?", Long.class, actor.tenantId().toString()); }

    @Test void savesNormalizedScopeAndReadsItBackForAuthorization() {
        store.saveTerritory(actor, "10", command(1, "330000", "330100", "330105", "330100"));
        assertThat(areas()).containsExactly(new Area(CATALOG.version(), "330000"));
        var result = store.management(actor);
        assertThat(result.applicationVersion()).isEqualTo(2);
        assertThat(result.departments().getFirst().areas()).isEqualTo(areas());
        assertThat(audits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_by FROM department_admin_scope WHERE tenant_id=?", String.class, actor.tenantId().toString())).isEqualTo(actor.principalId().toString());
    }

    @Test void replacesAndClearsWithoutDeletingPriorAssignments() {
        store.saveTerritory(actor, "10", command(1, "330100"));
        store.saveTerritory(actor, "10", command(2, "620100"));
        assertThat(areas()).containsExactly(new Area(CATALOG.version(), "620100"));
        assertThat(jdbc.queryForObject("SELECT status_code FROM department_admin_scope WHERE tenant_id=? AND admin_area_code='330100'", String.class, actor.tenantId().toString())).isEqualTo("DISABLED");
        store.saveTerritory(actor, "10", command(3));
        assertThat(areas()).isEmpty();
        assertThat(version()).isEqualTo(4);
        assertThat(audits()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM department_admin_scope WHERE tenant_id=?", Long.class, actor.tenantId().toString())).isEqualTo(2);
    }

    @Test void rejectsOtherTenantOrDisabledDepartmentAndUnknownArea() {
        assertThatThrownBy(() -> store.saveTerritory(actor, "999", command(1, "330100"))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> store.saveTerritory(actor, "20", command(1, "330100"))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> store.saveTerritory(actor, "10", command(1, "999999"))).isInstanceOf(RequestValidationException.class);
        assertThat(areas()).isEmpty();
        store.saveTerritory(actor, "10", command(1, "330100"));
        assertThat(store.departments(UUID.randomUUID().toString(), List.of("10"))).isEmpty();
    }

    @Test void staleVersionOrCatalogCannotOverwrite() {
        store.saveTerritory(actor, "10", command(1, "330100"));
        assertThatThrownBy(() -> store.saveTerritory(actor, "10", command(1, "620100"))).isInstanceOf(StateConflictException.class);
        assertThatThrownBy(() -> store.saveTerritory(actor, "10", new Update(2, "OLD", List.of("620100"), "过期目录"))).isInstanceOf(StateConflictException.class);
        assertThat(areas()).containsExactly(new Area(CATALOG.version(), "330100"));
        assertThat(audits()).isEqualTo(1);
    }

    @Test void unauthorizedWriteDoesNotReadMasterDataOrMutate() {
        doThrow(new AccessDeniedException("denied")).when(settings).requirePermission(actor, "supply:territory:write");
        assertThatThrownBy(() -> store.saveTerritory(actor, "10", command(1, "330100"))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(references, divisions);
        assertThat(areas()).isEmpty();
    }

    @Test void auditFailureRollsBackScopeAndApplicationVersion() {
        store.saveTerritory(actor, "10", command(1, "330100"));
        doThrow(new IllegalStateException("audit unavailable")).when(settings).audit(eq(actor), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> store.saveTerritory(actor, "10", command(2, "620100"))).isInstanceOf(IllegalStateException.class);
        assertThat(areas()).containsExactly(new Area(CATALOG.version(), "330100"));
        assertThat(version()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
    }

    @Test void twoEditorsCannotSilentlyOverwriteOneAnother() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (String code : List.of("330100", "620100")) results.add(executor.submit(() -> {
                start.await();
                try { store.saveTerritory(actor, "10", command(1, code)); return true; }
                catch (StateConflictException conflict) { return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(areas()).hasSize(1);
        assertThat(audits()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
    }
}
