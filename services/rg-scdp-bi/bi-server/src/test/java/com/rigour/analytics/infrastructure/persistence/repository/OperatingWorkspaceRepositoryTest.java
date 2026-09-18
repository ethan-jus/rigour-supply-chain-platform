package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.ActionFilter;
import com.rigour.analytics.infrastructure.persistence.mapper.OperatingWorkspaceMapper;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;

/** 隔离H2真实MyBatis SQL、乐观锁、租户隔离和Spring事务回滚测试。 */
class OperatingWorkspaceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private JdbcTemplate jdbc;
    private MybatisOperatingWorkspaceRepository repository;
    private SingleConnectionDataSource source;

    @BeforeEach void setup() throws Exception {
        var ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:operations_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        source = ds;
        jdbc = new JdbcTemplate(ds);
        String v9 = Files.readString(Path.of("src/main/resources/db/migration/V9__bi_operating_dashboard_business_metrics.sql"));
        String target = v9.substring(v9.indexOf("CREATE TABLE bi_business_target"), v9.indexOf("CREATE TABLE bi_inventory_operation_fact"));
        execute(target);
        execute(Files.readString(Path.of("src/main/resources/db/migration/V10__bi_operating_actions.sql")));
        jdbc.execute("CREATE TABLE bi_customer_dim (tenant_id VARCHAR(64), customer_id BIGINT, customer_code VARCHAR(50), customer_name VARCHAR(200), region_code VARCHAR(64), owner_staff_code VARCHAR(50), deleted INT)");
        jdbc.execute("CREATE TABLE bi_sales_order_fact (tenant_id VARCHAR(64), order_id BIGINT, order_no VARCHAR(50), source_order_no VARCHAR(80), region_code VARCHAR(64), owner_staff_code VARCHAR(50), deleted INT)");
        jdbc.execute("CREATE TABLE bi_inventory_balance_current (tenant_id VARCHAR(64), product_code VARCHAR(50), product_name VARCHAR(200), region_code VARCHAR(64), deleted INT)");
        jdbc.update("INSERT INTO bi_customer_dim VALUES ('T',17,'C17','客户','BJ','E1',0),('OTHER',18,'C17','其他客户','SH','E2',0)");
        var config = new Configuration();
        config.addMapper(OperatingWorkspaceMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OperatingWorkspaceMapper.class);
        var raw = new MybatisOperatingWorkspaceRepository(mapper);
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        repository = (MybatisOperatingWorkspaceRepository) proxy.getProxy();
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); source.destroy(); }

    private void execute(String sql) {
        // Same migration columns/constraints; only MySQL engine/collation storage clauses are removed for H2.
        jdbc.execute(sql.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", ""));
    }
    @Test void targetCrudUsesSameV9TableAndNeverOverwritesActiveRevision() {
        var saved = repository.saveTarget("T", "actor", target("123.45", 0), NOW);
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.month()).isEqualTo("2026-09");
        assertThat(repository.targets("T", LocalDate.of(2026,9,1), "CITY", null, null, null)).hasSize(1);
        assertThatThrownBy(() -> repository.saveTarget("T", "actor", target("50", 0), NOW)).isInstanceOf(BusinessException.class);
        var edited = repository.saveTarget("T", "actor", target("100", 1), NOW);
        assertThat(edited.revision()).isEqualTo(2);
        assertThatThrownBy(() -> repository.saveTarget("T", "actor", target("9", 1), NOW)).isInstanceOf(BusinessException.class);
        assertThat(repository.deleteTarget("OTHER", "actor", saved.id(), 2, NOW)).isFalse();
        assertThat(repository.deleteTarget("T", "actor", saved.id(), 2, NOW)).isTrue();
        assertThat(repository.targets("T", LocalDate.of(2026,9,1), null, null, null, null)).isEmpty();
        var restored = repository.saveTarget("T", "actor", target("80", 0), NOW);
        assertThat(restored.revision()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT SUM(target_value) FROM bi_business_target WHERE tenant_id='T' AND deleted=0", BigDecimal.class)).isEqualByComparingTo("80");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_business_target_event WHERE tenant_id='T'", Integer.class)).isEqualTo(4);
    }
    @Test void actionVersionAndAuditRemainTenantScoped() {
        var saved = repository.createAction("T", "actor", command("客户"), NOW);
        var result = repository.updateAction("T", "actor2", saved, new ActionUpdateCommand("E2", NOW.plusSeconds(3600), "IN_PROGRESS", "已联系", 1), NOW);
        assertThat(result).get().extracting(ActionView::revision).isEqualTo(2);
        assertThat(repository.updateAction("T", "actor", saved, new ActionUpdateCommand("E1", NOW, "IN_PROGRESS", "过期", 1), NOW)).isEmpty();
        assertThat(repository.action("OTHER", saved.id())).isEmpty();
        assertThat(repository.events("OTHER", saved.id())).isEmpty();
        var events = repository.events("T", saved.id());
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().previousStatus()).isEqualTo("OPEN");
        assertThat(events.getFirst().previousAssignee()).isEqualTo("E1");
        assertThat(events.getFirst().previousDueAt()).isEqualTo(NOW);
        assertThat(events.getFirst().note()).isEqualTo("已联系");
    }
    @Test void actionAndAuditRollbackTogether() {
        jdbc.execute("ALTER TABLE bi_operating_action_event ADD CONSTRAINT reject_actor CHECK(actor <> 'fail')");
        assertThatThrownBy(() -> repository.createAction("T", "fail", command("回滚"), NOW)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_operating_action", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_operating_action_event", Integer.class)).isZero();
    }
    @Test void targetAndAuditRollbackTogether() {
        jdbc.execute("ALTER TABLE bi_business_target_event ADD CONSTRAINT reject_target_actor CHECK(actor <> 'fail')");
        assertThatThrownBy(() -> repository.saveTarget("T", "fail", target("25", 0), NOW)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bi_business_target", Integer.class)).isZero();
    }
    @Test void scopedTargetListsExcludeOtherCityTotalsAndStockActions() {
        repository.saveTarget("T", "actor", target("50", 0), NOW);
        repository.saveTarget("T", "actor", new TargetCommand("2026-09", "SALES_OWNER", "E1", "销售", "SALES_AMOUNT", new BigDecimal("10"), null, 0), NOW);
        assertThat(repository.targets("T", LocalDate.of(2026,9,1), null, null, "BJ", null)).hasSize(2);
        assertThat(repository.targets("T", LocalDate.of(2026,9,1), null, null, "BJ", "E1")).hasSize(1);
        jdbc.update("INSERT INTO bi_customer_dim VALUES ('T',19,'C19','其他城市','SH','E1',0)");
        assertThat(repository.targets("T", LocalDate.of(2026,9,1), null, null, "BJ", null)).singleElement()
                .extracting(TargetView::dimensionType).isEqualTo("CITY");
        repository.createAction("T", "actor", new ActionCommand("STOCK", "product-code:P1", "库存", "BJ", null, "E1", NOW, "确认采购"), NOW);
        assertThat(repository.actions("T", new ActionFilter(null, null, "BJ", null, null, null, 1, 20, false)).total()).isZero();
    }
    @Test void listFiltersAndPaginationAreAppliedInSql() {
        repository.createAction("T", "actor", command("第一"), NOW);
        repository.createAction("T", "actor", command("第二"), NOW);
        repository.createAction("OTHER", "actor", command("不显示"), NOW);
        var page = repository.actions("T", new ActionFilter("CUSTOMER", null, "BJ", "E1", null, "OPEN", 2, 1, true));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(repository.actions("T", new ActionFilter(null, null, "SH", null, null, null, 1, 20, false)).items()).isEmpty();
    }
    @Test void businessReferencesResolveExactCodeOrCanonicalIdWithinTenant() {
        assertThat(repository.subjects("T", "CUSTOMER", "customer-code:C17")).singleElement().satisfies(row -> {
            assertThat(row.businessRef()).isEqualTo("customer-id:17");
            assertThat(row.cityCode()).isEqualTo("BJ");
        });
        assertThat(repository.subjects("T", "COLLECTION", "customer-id:17")).hasSize(1);
        assertThat(repository.subjects("T", "CUSTOMER", "C17")).hasSize(1);
        assertThat(repository.subjects("T", "CUSTOMER", "customer-id:18")).isEmpty();
        assertThat(repository.targetRegions("T", "SALES_OWNER", "E1")).containsExactly("BJ");
    }
    private TargetCommand target(String amount, int revision) {
        return new TargetCommand("2026-09", "CITY", "BJ", "北京", "SALES_AMOUNT", new BigDecimal(amount), "计划", revision);
    }
    private ActionCommand command(String label) {
        return new ActionCommand("CUSTOMER", "customer-id:17", label, "BJ", "E1", "E1", NOW, "拜访确认");
    }
}
