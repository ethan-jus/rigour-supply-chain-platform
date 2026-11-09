package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.infrastructure.persistence.mapper.InternalInventoryWarehouseMapper;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.regex.Pattern;

/** 执行实际持久化语句核对所在地与业务归属独立保存；H2 不替代 MySQL 迁移验证。 */
class WarehouseLocationPersistenceTest {
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private MybatisPlusInventoryWarehouseRepository store;
    private final String tenant = UUID.randomUUID().toString();

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:warehouse_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String baseline =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V19__erp_internal_product_inventory_baseline.sql"));
        var table =
                Pattern.compile("CREATE TABLE erp_inventory_warehouse.*?;", Pattern.DOTALL)
                        .matcher(baseline);
        assertThat(table.find()).isTrue();
        jdbc.execute(
                table.group()
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci",
                                ""));
        jdbc.execute(
                "ALTER TABLE erp_inventory_warehouse ADD COLUMN status_code VARCHAR(64) NOT NULL"
                        + " DEFAULT 'ACTIVE'");
        String migration =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V44__warehouse_physical_location.sql"))
                        .replaceAll(" CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci", "");
        var additions =
                Pattern.compile("ADD COLUMN .*?(?=,\\s*ADD COLUMN|;)", Pattern.DOTALL)
                        .matcher(migration);
        while (additions.find())
            jdbc.execute("ALTER TABLE erp_inventory_warehouse " + additions.group());
        jdbc.execute(
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V46__warehouse_cost_department.sql")));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(InternalInventoryWarehouseMapper.class);
        config.addMapper(
                com.rigour.erp.infrastructure.persistence.mapper.MasterSourceBindingMapper.class);
        jdbc.execute(
                """
CREATE TABLE erp_master_source_binding (id VARCHAR(36) PRIMARY KEY,tenant_id VARCHAR(64),connector_id VARCHAR(36),source_system VARCHAR(32),source_object_type VARCHAR(32),source_object_id VARCHAR(128),target_type VARCHAR(32),target_id VARCHAR(36),source_code VARCHAR(128),source_name VARCHAR(200),source_status VARCHAR(32),source_putaway VARCHAR(16),source_updated_at TIMESTAMP,source_payload_hash VARCHAR(64),source_presence VARCHAR(24),source_absent_at TIMESTAMP,last_sync_run_id VARCHAR(36),synced_at TIMESTAMP,revision BIGINT,created_by VARCHAR(50),created_time TIMESTAMP,updated_by VARCHAR(50),updated_time TIMESTAMP,deleted INT DEFAULT 0)
""");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper =
                new SqlSessionTemplate(factory.getObject())
                        .getMapper(InternalInventoryWarehouseMapper.class);
        var scope = mock(ErpWarehouseDataScope.class);
        when(scope.visibleDocument(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        var bindings =
                new SqlSessionTemplate(factory.getObject())
                        .getMapper(
                                com.rigour.erp.infrastructure.persistence.mapper
                                        .MasterSourceBindingMapper.class);
        store =
                new MybatisPlusInventoryWarehouseRepository(
                        mapper, Clock.systemUTC(), scope, bindings);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void costDepartmentDoesNotReplaceWarehouseIdAndLegacyEditsPreserveIt() {
        var created =
                store.create(
                        tenant,
                        "WH-DEPT",
                        new InternalInventoryWarehouseCommand(
                                "仓库", "WH", "CITY", false, "地址", null, null, "ACTIVE", null, 0,
                                null, null, 901L),
                        "user");
        assertThat(created.id()).isNotNull().isNotEqualTo(901L);
        assertThat(created.costDepartmentId()).isEqualTo(901L);
        var updated =
                store.update(
                        tenant,
                        created.id(),
                        new InternalInventoryWarehouseCommand(
                                "仓库新名称",
                                "WH",
                                "CITY",
                                false,
                                "地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                created.revision()),
                        "user");
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.costDepartmentId()).isEqualTo(901L);
        var cleared =
                store.update(
                        tenant,
                        created.id(),
                        new InternalInventoryWarehouseCommand(
                                "仓库新名称",
                                "WH",
                                "CITY",
                                false,
                                "地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                updated.revision(),
                                null,
                                null,
                                0L),
                        "user");
        assertThat(cleared.id()).isEqualTo(created.id());
        assertThat(cleared.costDepartmentId()).isNull();
    }

    @Test
    void locationPersistsIndependentlyAndOldClientsPreserveItUntilExplicitlyCleared() {
        var created =
                store.create(
                        tenant,
                        "WH1",
                        new InternalInventoryWarehouseCommand(
                                "仓库",
                                "BUSINESS-WH",
                                "CITY",
                                false,
                                "详细地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                0,
                                "420102",
                                "2026"),
                        "user");
        var read = store.warehouse(tenant, created.id()).orElseThrow();
        assertThat(read.regionCode()).isEqualTo("BUSINESS-WH");
        assertThat(read.locationAreaCode()).isEqualTo("420102");
        assertThat(read.locationAreaVersion()).isEqualTo("2026");
        // 旧客户端未传所在地，只更新业务归属。
        var updated =
                store.update(
                        tenant,
                        created.id(),
                        new InternalInventoryWarehouseCommand(
                                "仓库",
                                "BUSINESS-HQ",
                                "CITY",
                                false,
                                "详细地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                1),
                        "user");
        assertThat(updated.regionCode()).isEqualTo("BUSINESS-HQ");
        assertThat(updated.locationAreaCode()).isEqualTo("420102");
        assertThat(updated.locationAreaVersion()).isEqualTo("2026");
        var cleared =
                store.update(
                        tenant,
                        created.id(),
                        new InternalInventoryWarehouseCommand(
                                "仓库",
                                "BUSINESS-HQ",
                                "CITY",
                                false,
                                "详细地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                2,
                                "",
                                null),
                        "user");
        assertThat(cleared.locationAreaCode()).isNull();
        assertThat(cleared.locationAreaVersion()).isNull();
        assertThat(cleared.regionCode()).isEqualTo("BUSINESS-HQ");
        assertThat(store.warehouse("another-tenant", created.id())).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.update(
                                        tenant,
                                        created.id(),
                                        new InternalInventoryWarehouseCommand(
                                                "旧修改", "OTHER", "CITY", false, null, null, null,
                                                "ACTIVE", null, 1, "420102", "2026"),
                                        "user"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void sourceCodesAreIndependentSearchableAndSurviveManualEditsWithoutTenantLeakage() {
        var created =
                store.create(
                        tenant,
                        "WH-LOCAL",
                        new InternalInventoryWarehouseCommand(
                                "仓库", "CITY", "CITY", false, "地址", null, null, "ACTIVE", null, 0),
                        "user");
        bind(tenant, created.id(), "0012", "26860", 0);
        bind("another-tenant", created.id(), "SECRET", "99999", 0);
        bind(tenant, created.id(), "OLD", "26861", 1);
        var read = store.warehouse(tenant, created.id()).orElseThrow();
        assertThat(read.sourceReferences()).hasSize(1);
        assertThat(read.sourceReferences().getFirst().sourceCode()).isEqualTo("0012");
        assertThat(read.sourceReferences().getFirst().sourceObjectId()).isEqualTo("26860");
        var query =
                new com.rigour.erp.application.port.out.ErpInventoryWarehouseStore
                        .WarehouseSearchCriteria(null, null, null, null, null, "0012");
        var page = store.warehouses(tenant, 0, 20, query);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().warehouseCode()).isEqualTo("WH-LOCAL");
        assertThat(
                        store.warehouses(
                                        tenant,
                                        0,
                                        20,
                                        new com.rigour.erp.application.port.out
                                                .ErpInventoryWarehouseStore.WarehouseSearchCriteria(
                                                null, null, null, null, null, "SECRET"))
                                .total())
                .isZero();
        var updated =
                store.update(
                        tenant,
                        created.id(),
                        new InternalInventoryWarehouseCommand(
                                "改名仓库",
                                "CITY",
                                "CITY",
                                false,
                                "地址",
                                null,
                                null,
                                "ACTIVE",
                                null,
                                created.revision()),
                        "user");
        assertThat(updated.sourceReferences().getFirst().sourceCode()).isEqualTo("0012");
        assertThat(updated.withLocationDisplay("所在地").sourceReferences())
                .isEqualTo(updated.sourceReferences());
    }

    private void bind(String sourceTenant, Long id, String code, String sourceId, int deleted) {
        jdbc.update(
                "INSERT INTO"
                    + " erp_master_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,source_code,source_name,deleted)"
                    + " VALUES (?,?,?,'DINGHUOBAO','WAREHOUSE',?,'INVENTORY_WAREHOUSE',?,?,?,?)",
                UUID.randomUUID().toString(),
                sourceTenant,
                UUID.randomUUID().toString(),
                sourceId,
                id.toString(),
                code,
                "来源仓库",
                deleted);
    }
}
