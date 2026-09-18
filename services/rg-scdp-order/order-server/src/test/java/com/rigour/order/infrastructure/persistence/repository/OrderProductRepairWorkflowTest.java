package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import com.rigour.order.application.service.sales.OrderProductRepairService;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderLineEntity;
import com.rigour.order.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 使用真实MyBatis SQL、H2事务和并行连接验证修复不改成交事实；不连接任何业务库。 */
class OrderProductRepairWorkflowTest {
    static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
    final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    final AtomicReference<List<Candidate>> candidates = new AtomicReference<>();
    volatile Runnable catalogGate = () -> { };
    MybatisOrderProductRepairStore store;
    OrderProductRepairService service;
    JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:repair_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        createOriginalTable("order_sales_order", InternalSalesOrderEntity.class);
        createOriginalTable("order_sales_order_line", InternalSalesOrderLineEntity.class);
        var migration = new ClassPathResource("db/migration/V36__sales_order_product_repair.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migration.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci", ""));
        jdbc.execute("CREATE TABLE order_payment_record(id BIGINT, order_id BIGINT, amount DECIMAL(24,6))");
        jdbc.execute("CREATE TABLE order_refund_record(id BIGINT, order_id BIGINT, amount DECIMAL(24,6))");
        jdbc.update("INSERT INTO order_payment_record VALUES (1,1,31.257123)");
        jdbc.update("INSERT INTO order_refund_record VALUES (1,1,7.321987)");
        jdbc.update("""
                INSERT INTO order_sales_order(id,tenant_id,order_no,source_system_code,source_order_no,
                owner_sales_user_id,order_status_code,total_quantity,original_amount,payable_amount,paid_amount,
                unpaid_amount,source_unpaid_amount,discount_rate,discount_amount,revision,deleted,remark)
                VALUES (1,?,'DD1','FEISHU','FS1',?,'COMPLETED',2,100.123456,90.345678,31.257123,
                59.088555,58.012345,0.932145,9.777778,1,0,'original order')
                """, TENANT.toString(), USER.toString());
        jdbc.update("""
                INSERT INTO order_sales_order_line(id,tenant_id,order_id,line_no,product_code_snapshot,
                sku_code_snapshot,product_name_snapshot,specification_snapshot,unit_code,quantity,
                unit_price,discount_rate,discount_amount,line_amount,revision,deleted,remark)
                VALUES (11,?,1,1,'OLD-P','OLD-S','面','12桶/箱','BUCKET',2,50.061728,0.932145,
                9.777778,90.345678,1,0,'original line')
                """, TENANT.toString());
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(InternalSalesOrderMapper.class);
        config.addMapper(InternalSalesOrderLineMapper.class);
        config.addMapper(OrderProductRepairPreviewMapper.class);
        config.addMapper(OrderProductRepairLineMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var scope=org.mockito.Mockito.mock(OrderDataScope.class);
        org.mockito.Mockito.when(scope.mapperPredicate(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new OrderDataScope.Predicate("1=1",java.util.List.of()));
        var raw = new MybatisOrderProductRepairStore(session.getMapper(InternalSalesOrderMapper.class),
                session.getMapper(InternalSalesOrderLineMapper.class), session.getMapper(OrderProductRepairPreviewMapper.class),
                session.getMapper(OrderProductRepairLineMapper.class), clock,scope,org.mockito.Mockito.mock(OrderAttributionWriter.class));
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        store = (MybatisOrderProductRepairStore) proxy.getProxy();
        candidates.set(List.of(candidate(1)));
        service = new OrderProductRepairService(store, (tenant, query) -> { catalogGate.run(); return candidates.get(); },
                clock, tenant -> Set.of("BOX", "BUCKET"));
        authorize(TENANT, USER, Set.of(), Set.of("order:read", "order:write"));
    }

    @AfterEach void close() { TestAuthorizationContext.clear(); jdbc.execute("SHUTDOWN"); }

    @Test void appliesOnlyKeysAndAuditPreservingAllOriginalFinancialAndQuantityFields() {
        var originalOrder = unchanged("order_sales_order", 1);
        var originalLine = unchanged("order_sales_order_line", 11);
        var payments = jdbc.queryForList("SELECT * FROM order_payment_record");
        var refunds = jdbc.queryForList("SELECT * FROM order_refund_record");
        var preview = preview(true, true);
        assertThat(preview.status()).isEqualTo("READY");
        assertThat(store.snapshot(TENANT.toString(), 1L).orElseThrow().revision()).isEqualTo(1);
        var result = service.apply(1L, preview.previewId(), new ApplyCommand(true));
        assertThat(result.revision()).isEqualTo(2);
        assertThat(unchanged("order_sales_order", 1)).isEqualTo(originalOrder);
        assertThat(unchanged("order_sales_order_line", 11)).isEqualTo(originalLine);
        assertThat(jdbc.queryForList("SELECT * FROM order_payment_record")).isEqualTo(payments);
        assertThat(jdbc.queryForList("SELECT * FROM order_refund_record")).isEqualTo(refunds);
        var evidence = service.evidence(0, 20000).items().getFirst();
        assertThat(evidence.storedUnitCode()).isEqualTo("BUCKET");
        assertThat(evidence.historicalTransactionUnitCode()).isEqualTo("BOX");
        assertThat(evidence.standardQuantity()).isEqualByComparingTo("24");
        assertThat(evidence.sourceIdentityStatus()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(evidence.sourceProductCode()).isEqualTo("SOURCE-33");
        assertThat(evidence.skuCode()).isEqualTo("S1");
        assertThat(evidence.sourceOrderNo()).isEqualTo("FS1");
    }

    @Test void bindingAloneNeverVerifiesErpDefaultUnitOrSourceIdentity() {
        var p = preview(false, false);
        assertThat(p.lines().getFirst().transactionUnitStatus()).isEqualTo("UNVERIFIED");
        service.apply(1L, p.previewId(), new ApplyCommand(true));
        var e = service.evidence(0, 1).items().getFirst();
        assertThat(e.standardQuantity()).isNull();
        assertThat(e.historicalTransactionUnitCode()).isNull();
        assertThat(e.sourceIdentityStatus()).isEqualTo("UNVERIFIED");
    }

    @Test void rejectsMissingPermissionAndOutOfScopeAndServiceActors() {
        authorize(TENANT, USER, Set.of(), Set.of("order:read"));
        assertThatThrownBy(() -> preview(false, false)).isInstanceOf(AuthorizationDeniedException.class);
        assertThat(service.evidence(0, 1).items()).isEmpty();
        authorize(TENANT, UUID.randomUUID(), Set.of(), Set.of("order:read", "order:write"));
        assertThatThrownBy(() -> service.context(1L)).isInstanceOf(AuthorizationDeniedException.class);
        authorize(UUID.randomUUID(), USER, Set.of("TENANT_SUPER_ADMIN"), Set.of("order:read", "order:write"));
        assertThatThrownBy(() -> service.context(1L)).isInstanceOf(BusinessException.class);
        TestAuthorizationContext.set(new CallerIdentity("SERVICE", USER, TENANT, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("order:read", "order:write")));
        assertThatThrownBy(() -> service.evidence(0, 1)).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void evidenceScopesAndBoundsAreEnforced() {
        var p = preview(false, true); service.apply(1L, p.previewId(), new ApplyCommand(true));
        authorize(TENANT, UUID.randomUUID(), Set.of(), Set.of("order:read"));
        assertThat(service.evidence(0, 20000).items()).isEmpty();
        authorize(TENANT, UUID.randomUUID(), Set.of("TENANT_SUPER_ADMIN"), Set.of("order:read"));
        assertThat(service.evidence(0, 20000).items()).hasSize(1);
        assertThat(service.evidence(11, 20000).items()).isEmpty();
        assertThatThrownBy(() -> service.evidence(0, 20001)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.evidence(-1, 1)).isInstanceOf(BusinessException.class);
    }

    @Test void requiresConfirmationAndPreviewCreatorEvenForAdmin() {
        var p = preview(false, false);
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(false))).isInstanceOf(BusinessException.class);
        authorize(TENANT, UUID.randomUUID(), Set.of("TENANT_SUPER_ADMIN"), Set.of("order:read", "order:write"));
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test void expiredPreviewAndChangedMoneyWithoutRevisionAreRejected() {
        var p = preview(false, false);
        time.set(NOW.plusSeconds(901));
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(BusinessException.class);
        time.set(NOW);
        jdbc.update("UPDATE order_sales_order SET paid_amount=32 WHERE id=1");
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_product_repair_line", Integer.class)).isZero();
    }

    @Test void changesInErpOrAmbiguousSkuCannotBeApplied() {
        var p = preview(false, false);
        candidates.set(List.of(candidate(2)));
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(BusinessException.class);
        candidates.set(List.of(candidate(1), candidate(2)));
        assertThat(preview(false, false).status()).isEqualTo("BLOCKED");
        candidates.set(List.of());
        assertThat(preview(false, false).status()).isEqualTo("BLOCKED");
    }

    @Test void rejectsRefundZeroNegativeAndDeletedRows() {
        jdbc.update("UPDATE order_sales_order_line SET quantity=0 WHERE id=11");
        assertThat(preview(false, false).status()).isEqualTo("BLOCKED");
        jdbc.update("UPDATE order_sales_order_line SET quantity=-2 WHERE id=11");
        assertThat(preview(false, false).status()).isEqualTo("BLOCKED");
        jdbc.update("UPDATE order_sales_order_line SET deleted=1 WHERE id=11");
        assertThatThrownBy(() -> preview(false, false)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE order_sales_order SET total_quantity=0 WHERE id=1");
        assertThatThrownBy(() -> preview(false, false)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT deleted FROM order_sales_order_line WHERE id=11", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsInvalidUnitsUnconfirmedUnitsAndFalseConversion() {
        var line = command(true, false);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String text : List.of(
                json.writeValueAsString(line).replace("\"BOX\"", "\"NOT_A_UNIT\""),
                json.writeValueAsString(line).replace("\"confirmHistoricalTransactionUnit\":true", "\"confirmHistoricalTransactionUnit\":false"),
                json.writeValueAsString(line).replace("\"standardQuantity\":24", "\"standardQuantity\":25"))) {
            var invalid = json.readValue(text, LineCommand.class);
            assertThat(service.preview(1L, new PreviewCommand(1, "Review", List.of(invalid))).status()).isEqualTo("BLOCKED");
        }
    }

    @Test void sourceIdentityRequiresAllFieldsAndExplicitOperatorConfirmation() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String source = json.writeValueAsString(command(false, true));
        for (String invalid : List.of(source.replace("\"confirmSourceIdentity\":true", "\"confirmSourceIdentity\":false"),
                source.replace("\"capture-v1\"", "\" \""))) {
            assertThat(service.preview(1L, new PreviewCommand(1, "Review", List.of(json.readValue(invalid, LineCommand.class)))).status())
                    .isEqualTo("BLOCKED");
        }
    }

    @Test void currentEvidenceRejectsChangedSkuEvenWithoutRevision() {
        var p = preview(false, true); service.apply(1L, p.previewId(), new ApplyCommand(true));
        jdbc.update("UPDATE order_sales_order_line SET product_variant_id=999 WHERE id=11");
        assertThat(service.evidence(0, 20000).items()).isEmpty();
        assertThat(service.context(1L).lines().getFirst().repair()).isNull();
        assertThat(service.context(1L).recentRepairs()).hasSize(1);
    }

    @Test void currentEvidenceRejectsChangedRevisionQuantityAndUnit() {
        var p = preview(false, true); service.apply(1L, p.previewId(), new ApplyCommand(true));
        for (String mutation : List.of("revision=3", "quantity=3", "unit_code='BOX'", "deleted=1")) {
            jdbc.update("UPDATE order_sales_order_line SET " + mutation + " WHERE id=11");
            assertThat(service.evidence(0, 20000).items()).isEmpty();
            jdbc.update("UPDATE order_sales_order_line SET revision=2,quantity=2,unit_code='BUCKET',deleted=0 WHERE id=11");
        }
    }

    @Test void concurrentReplayCommitsExactlyOnceAndExpiredReplayReturnsSameResult() throws Exception {
        var p = preview(false, false);
        var results = concurrentApply(p.previewId(), p.previewId());
        assertThat(results).allMatch(value -> value instanceof Applied);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_product_repair_line", Integer.class)).isEqualTo(1);
        time.set(NOW.plusSeconds(3600));
        assertThat(service.apply(1L, p.previewId(), new ApplyCommand(true))).isEqualTo(results.getFirst());
    }

    @Test void competingPreviewsCannotOverwriteEachOther() throws Exception {
        var a = preview(false, false); var b = preview(false, false);
        var results = concurrentApply(a.previewId(), b.previewId());
        assertThat(results.stream().filter(Applied.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(2);
    }

    @Test void auditFailureRollsBackKeysVersionsAndPreviewAtomically() {
        var p = preview(false, false);
        jdbc.execute("ALTER TABLE order_product_repair_line ADD CONSTRAINT reject_audit CHECK (line_id < 0)");
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT revision FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT product_id FROM order_sales_order_line WHERE id=11", Long.class)).isNull();
        assertThat(store.preview(TENANT.toString(), 1L, p.previewId()).orElseThrow().applied()).isNull();
    }

    @Test void ownerChangeDuringErpRevalidationRejectsApplyWithoutAnyRepairWrite() {
        var p = preview(false, true);
        catalogGate = () -> jdbc.update("UPDATE order_sales_order SET owner_sales_user_id=? WHERE id=1", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_product_repair_line", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(store.preview(TENANT.toString(), 1L, p.previewId()).orElseThrow().applied()).isNull();
    }

    @Test void sameOrderSameSkuKeepsPerLineEvidenceAndUntouchedSiblingFacts() {
        jdbc.update("""
                INSERT INTO order_sales_order_line(id,tenant_id,order_id,line_no,product_id,product_variant_id,
                sku_code_snapshot,unit_code,quantity,unit_price,line_amount,revision,deleted)
                VALUES (12,?,1,2,101,102,'OLD-S','BUCKET',3,1.123456,3.370368,1,0)
                """, TENANT.toString());
        var sibling = jdbc.queryForMap("SELECT * FROM order_sales_order_line WHERE id=12");
        var p = preview(true, true);
        service.apply(1L, p.previewId(), new ApplyCommand(true));
        assertThat(service.evidence(0, 20000).items()).singleElement().satisfies(e -> {
            assertThat(e.lineId()).isEqualTo(11);
            assertThat(e.transactionQuantity()).isEqualByComparingTo("2");
        });
        assertThat(jdbc.queryForMap("SELECT * FROM order_sales_order_line WHERE id=12")).isEqualTo(sibling);
        assertThat(service.context(1L).lines().getLast().repair()).isNull();
    }

    @Test void appliedReplayAndEvidenceStillRequireCurrentTenantPermissionsAndOwner() {
        var p = preview(false, true); service.apply(1L, p.previewId(), new ApplyCommand(true));
        authorize(TENANT, USER, Set.of("TENANT_SUPER_ADMIN"), Set.of());
        assertThatThrownBy(() -> service.evidence(0, 1)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(AuthorizationDeniedException.class);
        authorize(UUID.randomUUID(), USER, Set.of("TENANT_SUPER_ADMIN"), Set.of("order:read", "order:write"));
        assertThat(service.evidence(0, 1).items()).isEmpty();
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(BusinessException.class);
        authorize(TENANT, USER, Set.of(), Set.of("order:read", "order:write"));
        jdbc.update("UPDATE order_sales_order SET owner_sales_user_id=? WHERE id=1", UUID.randomUUID().toString());
        assertThat(service.evidence(0, 1).items()).isEmpty();
        assertThatThrownBy(() -> service.apply(1L, p.previewId(), new ApplyCommand(true))).isInstanceOf(AuthorizationDeniedException.class);
    }

    private List<Object> concurrentApply(String a, String b) throws Exception {
        var barrier = new CyclicBarrier(2);
        catalogGate = () -> { try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); } };
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (var id : List.of(a, b)) futures.add(pool.submit(() -> {
                authorize(TENANT, USER, Set.of(), Set.of("order:read", "order:write"));
                try { return service.apply(1L, id, new ApplyCommand(true)); }
                catch (RuntimeException exception) { return exception; }
                finally { TestAuthorizationContext.clear(); }
            }));
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally { catalogGate = () -> { }; }
    }

    private Preview preview(boolean standard, boolean source) { return service.preview(1L, new PreviewCommand(1, "凭证复核", List.of(command(standard, source)))); }
    private LineCommand command(boolean standard, boolean source) {
        return new LineCommand(11L, "P1", "S1", "Original invoice product reviewed",
                source ? "FEISHU:base:products" : null, source ? "capture-v1" : null, source ? "record33" : null,
                source ? "SOURCE-33" : null, source ? "Reviewed captured product relation" : null, source,
                standard ? "BOX" : null, standard ? "Original invoice says box" : null, standard,
                standard ? new BigDecimal("24") : null, standard ? "BUCKET" : null,
                standard ? new BigDecimal("12") : null, standard ? "Historical packaging document: 12 buckets per box" : null, standard);
    }
    private static Candidate candidate(int revision) { return new Candidate(101L, 102L, "P1", "S1", "面", "桶装", "BUCKET", revision, 1, NOW, NOW); }
    private static void authorize(UUID tenant, UUID user, Set<String> roles, Set<String> permissions) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1, roles, permissions));
    }
    private Map<String, Object> unchanged(String table, int id) {
        var result = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id));
        for (var key : List.of("PRODUCT_ID", "PRODUCT_VARIANT_ID", "REVISION", "UPDATED_BY", "UPDATED_TIME")) result.remove(key);
        return result;
    }
    private void createOriginalTable(String name, Class<?> entity) {
        var columns = new ArrayList<String>();
        for (var field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String column = field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            Class<?> type = field.getType();
            String sqlType = type == Long.class ? "BIGINT" : type == Integer.class ? "INT"
                    : type == BigDecimal.class ? "DECIMAL(24,6)" : type == LocalDateTime.class ? "TIMESTAMP(6)" : "VARCHAR(4000)";
            columns.add(column + " " + sqlType + (column.equals("id") ? " PRIMARY KEY" : ""));
        }
        jdbc.execute("CREATE TABLE " + name + " (" + String.join(",", columns) + ")");
    }
}
