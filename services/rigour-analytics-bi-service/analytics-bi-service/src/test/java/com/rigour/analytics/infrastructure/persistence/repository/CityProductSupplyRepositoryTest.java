package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.service.CityProductSupplyService;
import com.rigour.analytics.infrastructure.persistence.mapper.CityProductSupplyMapper;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真实SQL验证仓库和单位隔离、全仓采购及失败/过期同步状态，不访问共享数据库。 */
class CityProductSupplyRepositoryTest {
    private SqlSession session;
    private JdbcTemplate jdbc;
    private CityProductSupplyService service;
    private static final UUID TENANT = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    @BeforeEach void setUp() {
        var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:supply_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(CityProductSupplyMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        service = new CityProductSupplyService(new MybatisCityProductSupplyRepository(session.getMapper(CityProductSupplyMapper.class)), Clock.fixed(NOW, ZoneOffset.UTC));
        jdbc.execute("CREATE TABLE bi_inventory_balance_current (tenant_id VARCHAR(64), warehouse_id BIGINT, warehouse_name VARCHAR(160), region_code VARCHAR(64), product_id BIGINT, product_name VARCHAR(160), product_variant_id BIGINT, specification_snapshot VARCHAR(160), unit_code VARCHAR(64), available_quantity DECIMAL(24,6), locked_quantity DECIMAL(24,6), in_transit_quantity DECIMAL(24,6), synced_time DATETIME(6), deleted INT)");
        jdbc.execute("CREATE TABLE bi_inventory_operation_fact (tenant_id VARCHAR(64), product_id BIGINT, product_variant_id BIGINT, unit_code VARCHAR(64), operation_time DATETIME(6), operation_type VARCHAR(64), quantity DECIMAL(24,6), synced_time DATETIME(6), deleted INT)");
        jdbc.execute("CREATE TABLE bi_etl_checkpoint (tenant_id VARCHAR(64), source_code VARCHAR(64), status_code VARCHAR(32), last_success_time DATETIME(6))");
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, TENANT, user, null, UUID.randomUUID(), 0,0,0,Set.of(),Set.of("analytics:dashboard:read")));
    }
    @AfterEach void tearDown() { TestAuthorizationContext.clear(); session.close(); }

    @Test void stockIsWarehouseAndTenantScopedAndProcurementRemainsSeparateByOriginalUnit() {
        stock(TENANT.toString(), 9, "BOX");
        stock(TENANT.toString(), 10, "BUCKET");
        stock("other", 9, "BOX");
        jdbc.update("INSERT INTO bi_inventory_operation_fact VALUES (?,100,1000,'BOX',?,'PROCUREMENT',12,?,0)", TENANT.toString(), local(NOW.minusSeconds(60)), local(NOW));
        jdbc.update("INSERT INTO bi_inventory_operation_fact VALUES (?,100,1000,'BUCKET',?,'PROCUREMENT',144,?,0)", TENANT.toString(), local(NOW.minusSeconds(60)), local(NOW));
        var report = service.supply(NOW.minusSeconds(3600), NOW, 100L, 9L, 1000L);
        assertThat(report.stocks()).singleElement().satisfies(row -> {
            assertThat(row.warehouseId()).isEqualTo("9");
            assertThat(row.productId()).isEqualTo("100");
            assertThat(row.unitCode()).isEqualTo("BOX");
            assertThat(row.availableQuantity()).isEqualByComparingTo("12");
        });
        assertThat(report.operations()).hasSize(2);
        assertThat(report.operations()).allSatisfy(row -> {
            assertThat(row.productId()).isEqualTo("100");
            assertThat(row.skuId()).isEqualTo("1000");
            assertThat(row.month()).isEqualTo("2026-09");
        });
        assertThat(report.operations()).extracting(row -> row.unitCode()).containsExactlyInAnyOrder("BOX","BUCKET");
        assertThat(report.inventoryStatus().status()).isEqualTo("UNAVAILABLE");
        assertThat(service.supply(NOW.minusSeconds(3600), NOW, 100L, 99L, 1000L).stocks()).isEmpty();
        assertThat(service.supply(NOW.minusSeconds(3600), NOW, 200L, 9L, 1000L).operations()).isEmpty();
    }

    @Test void freshnessUsesSuccessfulSyncRatherThanOldSourceActivityOrAnEmptyZero() {
        jdbc.update("INSERT INTO bi_etl_checkpoint VALUES (?,'ERP_STOCK_BALANCE','SUCCESS',?)", TENANT.toString(), local(NOW.minusSeconds(86401)));
        jdbc.update("INSERT INTO bi_etl_checkpoint VALUES (?,'ERP_INVENTORY_OPERATION','FAILED',?)", TENANT.toString(), local(NOW.minusSeconds(10)));
        var report = service.supply(NOW.minusSeconds(3600), NOW, 100L, 9L, null);
        assertThat(report.inventoryStatus().status()).isEqualTo("STALE");
        assertThat(report.operationStatus().status()).isEqualTo("FAILED");
        assertThat(report.stocks()).isEmpty();
        jdbc.update("UPDATE bi_etl_checkpoint SET last_success_time=? WHERE source_code='ERP_STOCK_BALANCE'", local(NOW));
        // JDBC update bypasses MyBatis; the next request must read a fresh session snapshot.
        session.clearCache();
        assertThat(service.supply(NOW.minusSeconds(3600), NOW, 100L, 9L, null).inventoryStatus().status()).isEqualTo("FRESH");
        assertThatThrownBy(() -> service.supply(NOW, NOW.minusSeconds(1), 100L, 9L, null)).hasMessageContaining("期间");
    }
    private void stock(String tenant, long warehouse, String unit) {
        jdbc.update("INSERT INTO bi_inventory_balance_current VALUES (?,?, '供货仓', 'GZ',100,'商品',1000,'规格',?,12,2,5,?,0)", tenant,warehouse,unit,local(NOW));
    }
    private static LocalDateTime local(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
}
