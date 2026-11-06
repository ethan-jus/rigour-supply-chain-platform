package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.CostReferenceSource;
import com.rigour.erp.application.service.cost.ProcurementCostRecordService;
import com.rigour.erp.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.*;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 在隔离H2执行实际采购汇总、成本写入与确认；不将H2语法适配当作MySQL验收。 */
public class ProcurementCostRecordPersistenceTest extends ProcurementPersistenceTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    JdbcProcurementCostRecords records;
    MybatisPlusErpCostRepository costs;
    ProcurementCostRecordService costService;
    ErpCostReferenceMapper sourceMapper;
    JdbcProcurementGoodsCosts goodsProjection;

    @BeforeEach
    void costSetup() throws Exception {
        String ddl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V37__erp_cost_management.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ")
                        .replace("'MARKETING','RENTAL'", "'MARKETING','RENTAL','PURCHASE','LOSS'");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN deleted INT NOT NULL DEFAULT 0");
        for (String col :
                List.of(
                        "cost_month VARCHAR(7)",
                        "source_system VARCHAR(20)",
                        "premises_type VARCHAR(20)"))
            jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN " + col);
        jdbc.execute(
                "CREATE ALIAS JSON_EXTRACT FOR"
                    + " 'com.rigour.erp.infrastructure.persistence.repository.ProcurementCostRecordPersistenceTest.extract'");
        jdbc.execute(
                "CREATE ALIAS JSON_UNQUOTE FOR"
                    + " 'com.rigour.erp.infrastructure.persistence.repository.ProcurementCostRecordPersistenceTest.unquote'");
        jdbc.update("UPDATE erp_inventory_warehouse SET region_code='C1' WHERE id=2");
        when(scopes.predicate(anyString(), any(String[].class)))
                .thenReturn(new ErpWarehouseDataScope.Predicate("1=1", List.of()));
        when(scopes.manualPredicate(anyString(), anyString()))
                .thenReturn(new ErpWarehouseDataScope.Predicate("1=1", List.of()));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ErpCostMapper.class);
        config.addMapper(ErpCostAuditMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        sourceMapper = mock(ErpCostReferenceMapper.class);
        var refs = mock(CostReferenceSource.class);
        when(refs.city(anyString(), anyString())).thenReturn("武汉市");
        costs =
                transaction(
                        new MybatisPlusErpCostRepository(
                                session.getMapper(ErpCostMapper.class),
                                session.getMapper(ErpCostAuditMapper.class),
                                JSON,
                                scopes,
                                refs,
                                sourceMapper));
        JdbcTemplate adapted =
                new JdbcTemplate(ds) {
                    String sql(String s) {
                        return s.replace(" COLLATE utf8mb4_general_ci", "")
                                .replace(" AS UNSIGNED", " AS BIGINT")
                                .replace(
                                        "UNIX_TIMESTAMP(updated_time)*1000000",
                                        "CAST(UNIX_TIMESTAMP(updated_time) AS BIGINT)*1000000");
                    }

                    @Override
                    public List<Map<String, Object>> queryForList(String s, Object... args) {
                        return super.queryForList(sql(s), args);
                    }

                    @Override
                    public <T> T queryForObject(String s, Class<T> type, Object... args) {
                        return super.queryForObject(sql(s), type, args);
                    }
                };
        jdbc.execute(
                "CREATE ALIAS IF NOT EXISTS MD5 FOR"
                    + " 'com.rigour.erp.infrastructure.persistence.repository.ProcurementCostRecordPersistenceTest.md5'");
        goodsProjection = new JdbcProcurementGoodsCosts(adapted, JSON, scopes);
        records = new JdbcProcurementCostRecords(adapted, scopes);
        costService = transaction(new ProcurementCostRecordService(records, costs, refs));
        var user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.fromString(tenant),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("erp"),
                        Set.of(
                                "erp:supply:read",
                                "erp:supply:write",
                                "erp:cost:purchase:read",
                                "erp:cost:purchase:write",
                                "erp:cost:purchase:confirm",
                                "erp:cost:purchase:void")));
    }

    @SuppressWarnings("unchecked")
    private <T> T transaction(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new MatchAlwaysTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    public static String md5(String value) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("MD5")
                                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static String extract(String value, String path) {
        if (value == null) return null;
        var n = JSON.readTree(value).at(path.substring(1).replace('.', '/'));
        return n.isMissingNode() || n.isNull() ? null : n.toString();
    }

    public static String unquote(String value) {
        return value == null ? null : JSON.readTree(value).asText();
    }

    InternalProcurementOrderDetailView confirmedOrder() {
        var row = service.create(command(new BigDecimal("2")));
        row = service.confirm(row.id(), row.revision());
        var source =
                JSON.readValue(
                        "{\"id\":"
                                + row.id()
                                + ",\"documentNo\":\""
                                + row.procurementNo()
                                + "\",\"sourceType\":\"PURCHASE\",\"cityCode\":\"C1\",\"targetWarehouseId\":2}",
                        ErpCostSourceView.class);
        when(sourceMapper.purchaseSources(any())).thenReturn(List.of(source));
        return row;
    }

    ProcurementFreightConfirmation confirmation(ProcurementCostRecordView row, String amount) {
        return new ProcurementFreightConfirmation(
                UUID.randomUUID().toString(),
                row.orderRevision(),
                row.freightRevision(),
                row.freightRecordId(),
                LocalDate.of(2026, 11, 4),
                new BigDecimal(amount),
                "UNPAID",
                "按承运方账单核实实际运费");
    }

    @Test
    void agreementIsNotActualCostAndMultiLineOrderReceivesFreightOnlyOnce() {
        var order = confirmedOrder();
        jdbc.update(
                "INSERT INTO"
                    + " erp_procurement_order_line(tenant_id,procurement_no,product_code,variant_code,unit_code,quantity,unit_price,line_amount)"
                    + " VALUES (?,?,'PRD-1','SKU-EXTRA','BOTTLE',2,5,10)",
                tenant,
                order.procurementNo());
        jdbc.update(
                "UPDATE erp_procurement_order SET total_amount=total_amount+10 WHERE id=?",
                order.id());
        var before = costService.detail(order.procurementNo());
        assertThat(before.lines()).hasSize(2);
        assertThat(before.agreedFreightAmount()).isEqualByComparingTo("8");
        assertThat(before.actualFreightAmount()).isNull();
        assertThat(before.confirmedAmount()).isEqualByComparingTo(before.goodsAmount());
        var command = confirmation(before, "12.34");
        var after = costService.confirmFreight(order.procurementNo(), command);
        assertThat(costService.confirmFreight(order.procurementNo(), command).freightRecordId())
                .isEqualTo(after.freightRecordId());
        assertThat(after.actualFreightAmount()).isEqualByComparingTo("12.34");
        assertThat(after.confirmedAmount())
                .isEqualByComparingTo(before.goodsAmount().add(new BigDecimal("12.34")));
        assertThat(costService.page(0, 20, null, null, null, null, null).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT freight_amount FROM erp_procurement_order WHERE id=?",
                                BigDecimal.class,
                                order.id()))
                .isEqualByComparingTo("8");
    }

    @Test
    void correctionVoidAndZeroFreightRetainHistoryWithoutDoubleCounting() {
        var order = confirmedOrder();
        var first =
                costService.confirmFreight(
                        order.procurementNo(),
                        confirmation(costService.detail(order.procurementNo()), "12.34"));
        assertThatThrownBy(
                        () ->
                                costService.confirmFreight(
                                        order.procurementNo(), confirmation(first, "15")))
                .hasMessageContaining("撤销");
        costs.lifecycle(
                tenant,
                "PURCHASE",
                first.freightRecordId(),
                new ErpCostActionCommand(first.freightRevision(), "更正前撤销"),
                "actor",
                "erp:cost:purchase:confirm",
                false);
        var correction = confirmation(costService.detail(order.procurementNo()), "15");
        var corrected = costService.confirmFreight(order.procurementNo(), correction);
        assertThat(corrected.freightRecordId()).isEqualTo(first.freightRecordId());
        assertThat(costService.confirmFreight(order.procurementNo(), correction).freightRevision())
                .isEqualTo(corrected.freightRevision());
        assertThat(costs.history(tenant, first.freightRecordId()))
                .extracting(ErpCostAuditView::action)
                .contains("BEFORE_UPDATE", "UPDATE", "CONFIRM");
        var revoked =
                costs.lifecycle(
                        tenant,
                        "PURCHASE",
                        corrected.freightRecordId(),
                        new ErpCostActionCommand(corrected.freightRevision(), "撤销后作废"),
                        "actor",
                        "erp:cost:purchase:confirm",
                        false);
        costs.transition(
                tenant,
                "PURCHASE",
                corrected.freightRecordId(),
                new ErpCostActionCommand(revoked.revision(), "承运方撤销此笔账单"),
                "actor",
                "erp:cost:purchase:void");
        var pending = costService.detail(order.procurementNo());
        assertThat(pending.actualFreightAmount()).isNull();
        assertThat(pending.freightRecordStatus()).isEqualTo("VOID");
        assertThat(pending.freightStatus()).isEqualTo("VOID");
        assertThatThrownBy(
                        () ->
                                costService.confirmFreight(
                                        order.procurementNo(), confirmation(pending, "0")))
                .hasMessageContaining("作废");
        costs.lifecycle(
                tenant,
                "PURCHASE",
                pending.freightRecordId(),
                new ErpCostActionCommand(pending.freightRevision(), "删除作废运费"),
                "actor",
                "erp:cost:purchase:void",
                true);
        var free =
                costService.confirmFreight(
                        order.procurementNo(),
                        confirmation(costService.detail(order.procurementNo()), "0"));
        assertThat(free.actualFreightAmount()).isEqualByComparingTo("0");
        assertThat(free.freightRecordId()).isNotEqualTo(first.freightRecordId());
        assertThatThrownBy(
                        () ->
                                costService.confirmFreight(
                                        order.procurementNo(), confirmation(first, "10")))
                .hasMessageContaining("撤销");
        assertThat(costService.detail(order.procurementNo()).confirmedAmount())
                .isEqualByComparingTo(pending.goodsAmount());
        assertThat(costService.history(order.procurementNo()))
                .extracting(a -> a.snapshot().id())
                .contains(first.freightRecordId(), free.freightRecordId());
        assertThat(costService.history(order.procurementNo()))
                .extracting(ErpCostAuditView::action)
                .contains("VOID");
    }

    @Test
    void unknownPricesStayUnknownAndAgreementCannotCreateCostBeforeReview() {
        var order = confirmedOrder();
        jdbc.update(
                "UPDATE erp_procurement_order SET source_system_code='DINGHUOBAO',total_amount=0"
                        + " WHERE id=?",
                order.id());
        jdbc.update(
                "UPDATE erp_procurement_order_line SET unit_price=0,line_amount=0 WHERE tenant_id=?"
                        + " AND procurement_no=?",
                tenant,
                order.procurementNo());
        var record = costService.detail(order.procurementNo());
        assertThat(record.goodsAmount()).isNull();
        assertThat(record.confirmedAmount()).isNull();
        assertThat(record.lines().getFirst().amount()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isZero();
    }

    @Test
    void onlyFreightIsVerifiedWhileGoodsMismatchIsExcludedAndAutomaticallyRecovers() {
        var order = confirmedOrder();
        BigDecimal original = costService.detail(order.procurementNo()).goodsAmount();
        jdbc.update(
                "UPDATE erp_procurement_order SET total_amount=total_amount+1 WHERE id=?",
                order.id());
        var mismatch = costService.detail(order.procurementNo());
        assertThat(mismatch.goodsAmount()).isNull();
        assertThat(mismatch.goodsSource().issue()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(mismatch.goodsSource().orderAmount())
                .isEqualByComparingTo(original.add(BigDecimal.ONE));
        var facts = goodsProjection.page(tenant, "");
        assertThat(facts).isNotEmpty();
        assertThat(facts.getFirst().get("amount")).isNull();
        assertThat(JSON.readTree(facts.getFirst().get("data_json")).path("_costTreatment").asText())
                .isEqualTo("REVIEW");
        var confirmed =
                costService.confirmFreight(order.procurementNo(), confirmation(mismatch, "12.34"));
        assertThat(confirmed.freightStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.actualFreightAmount()).isEqualByComparingTo("12.34");
        assertThat(confirmed.goodsAmount()).isNull();
        assertThat(confirmed.confirmedAmount()).isNull();
        jdbc.update(
                "UPDATE erp_procurement_order SET total_amount=? WHERE id=?", original, order.id());
        var fixed = costService.detail(order.procurementNo());
        assertThat(fixed.goodsSource().issue()).isNull();
        assertThat(fixed.goodsAmount()).isEqualByComparingTo(original);
        assertThat(fixed.actualFreightAmount()).isEqualByComparingTo("12.34");
        assertThat(goodsProjection.page(tenant, "").getFirst().get("amount")).isNotNull();
    }

    @Test
    void dinghuobaoOrderPayableIncludesDiscountAndDoesNotInventSkuNetAmounts() {
        var order = confirmedOrder();
        jdbc.update(
                "INSERT INTO"
                    + " erp_procurement_order_line(tenant_id,procurement_no,product_code,variant_code,unit_code,quantity,unit_price,line_amount)"
                    + " VALUES (?,?,'PRD-1','SKU-EXTRA','BOTTLE',2,5,10)",
                tenant,
                order.procurementNo());
        BigDecimal gross =
                jdbc.queryForObject(
                        "SELECT SUM(line_amount) FROM erp_procurement_order_line WHERE tenant_id=?"
                                + " AND procurement_no=?",
                        BigDecimal.class,
                        tenant,
                        order.procurementNo());
        BigDecimal net = gross.subtract(new BigDecimal("7.31"));
        jdbc.update(
                "UPDATE erp_procurement_order SET source_system_code='DINGHUOBAO',total_amount=?"
                        + " WHERE id=?",
                net,
                order.id());
        var record = costService.detail(order.procurementNo());
        assertThat(record.goodsSource().issue()).isNull();
        assertThat(record.goodsAmount()).isEqualByComparingTo(net);
        assertThat(record.goodsSource().linesAmount()).isEqualByComparingTo(gross);
        assertThat(
                        record.lines().stream()
                                .map(x -> x.amount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(gross);
        var facts = goodsProjection.page(tenant, "");
        assertThat(facts).hasSize(1);
        assertThat(new BigDecimal(facts.getFirst().get("amount"))).isEqualByComparingTo(net);
        var data = JSON.readTree(facts.getFirst().get("data_json"));
        assertThat(data.path("_procurementAmountBasis").asText()).isEqualTo("ORDER_PAYABLE");
        assertThat(data.path("goods").path("productId").isNull()).isTrue();
        var ref =
                goodsProjection
                        .detail(tenant, facts.getFirst().get("id"), "erp:cost:purchase:read")
                        .orElseThrow();
        assertThat(ref.procurementReference().orderCode()).isEqualTo(order.procurementNo());
        assertThat(ref.procurementReference().lineId()).isNull();
        assertThat(ref.data().amount()).isEqualByComparingTo(net);
        String version = goodsProjection.version(tenant);
        jdbc.update(
                "UPDATE erp_procurement_order SET total_amount=total_amount-1,revision=revision+1"
                        + " WHERE id=?",
                order.id());
        assertThat(goodsProjection.version(tenant)).isNotEqualTo(version);
        assertThat(new BigDecimal(goodsProjection.page(tenant, "").getFirst().get("amount")))
                .isEqualByComparingTo(net.subtract(BigDecimal.ONE));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isZero();
    }

    @Test
    void supplierBorneAgreementKeepsOriginalAmountAndNeverBecomesActualAutomatically() {
        var order = confirmedOrder();
        jdbc.update(
                "UPDATE erp_procurement_order SET freight_bearer_code='SUPPLIER',freight_amount=18"
                        + " WHERE id=?",
                order.id());
        var row = costService.detail(order.procurementNo());
        assertThat(row.agreedFreightAmount()).isEqualByComparingTo("18");
        assertThat(row.freightBearerCode()).isEqualTo("SUPPLIER");
        assertThat(row.actualFreightAmount()).isNull();
        assertThat(row.confirmedAmount()).isEqualByComparingTo(row.goodsAmount());
        jdbc.update("UPDATE erp_procurement_order SET freight_amount=NULL WHERE id=?", order.id());
        assertThat(costService.detail(order.procurementNo()).agreedFreightAmount()).isNull();
    }

    @Test
    void staleRevisionAndMissingBusinessRegionCannotWriteCosts() {
        var order = confirmedOrder();
        var before = costService.detail(order.procurementNo());
        jdbc.update("UPDATE erp_procurement_order SET revision=revision+1 WHERE id=?", order.id());
        assertThatThrownBy(
                        () ->
                                costService.confirmFreight(
                                        order.procurementNo(), confirmation(before, "10")))
                .hasMessageContaining("已变化");
        when(sourceMapper.purchaseSources(any()))
                .thenReturn(
                        List.of(
                                JSON.readValue(
                                        "{\"id\":"
                                                + order.id()
                                                + ",\"sourceType\":\"PURCHASE\",\"targetWarehouseId\":2}",
                                        ErpCostSourceView.class)));
        var current = costService.detail(order.procurementNo());
        assertThatThrownBy(
                        () ->
                                costService.confirmFreight(
                                        order.procurementNo(), confirmation(current, "10")))
                .hasMessageContaining("业务归属地区");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isZero();
        assertThatThrownBy(
                        () ->
                                records.record(
                                        UUID.randomUUID().toString(),
                                        order.procurementNo(),
                                        "erp:cost:purchase:read",
                                        false))
                .hasMessageContaining("不存在");
    }

    @Test
    void unconfirmedOrCancelledOrdersAndInvalidMoneyCannotCreateActualCosts() {
        var order = service.create(command(BigDecimal.ONE));
        assertThat(costService.page(0, 20, null, null, null, null, null).total()).isZero();
        order = service.confirm(order.id(), order.revision());
        var row = costService.detail(order.procurementNo());
        String code = order.procurementNo();
        for (var amount : List.of("-1", "1.001", "10000000000000000.00", "1E+30"))
            assertThatThrownBy(() -> costService.confirmFreight(code, confirmation(row, amount)))
                    .hasMessageContaining("金额");
        jdbc.update(
                "UPDATE erp_procurement_order SET status_code='CANCELLED' WHERE id=?", order.id());
        assertThatThrownBy(() -> costService.confirmFreight(code, confirmation(row, "10")))
                .hasMessageContaining("不存在");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isZero();
    }

    @Test
    void originalPurchaseNumberCanBeReadAndFilteredWithoutReplacingLocalNumber() {
        var order = confirmedOrder();
        jdbc.update(
                "UPDATE erp_procurement_order SET"
                    + " source_system_code='DINGHUOBAO',source_document_no='JH.20260901.0000' WHERE"
                    + " id=?",
                order.id());
        var record = costService.detail(order.procurementNo());
        assertThat(record.sourceDocumentNo()).isEqualTo("JH.20260901.0000");
        assertThat(record.procurementNo()).isEqualTo(order.procurementNo());
        var filter =
                new com.rigour.erp.application.port.out.ErpProcurementOrderStore
                        .ProcurementOrderSearchCriteria(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "JH.20260901.0000");
        var page = orders.procurementOrders(tenant, 0, 20, filter);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().sourceDocumentNo()).isEqualTo("JH.20260901.0000");
        assertThat(page.totals().purchaseAmount())
                .isEqualByComparingTo(
                        page.items().getFirst().totalAmount().add(new java.math.BigDecimal("8")));
        assertThat(orders.procurementOrders(UUID.randomUUID().toString(), 0, 20, filter).total())
                .isZero();
    }
}
