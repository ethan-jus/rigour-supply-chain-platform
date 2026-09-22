package com.rigour.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.order.infrastructure.persistence.repository.JdbcOrderHistorySyncStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** 验证真实关系表约束和金额接续，不以接口200代替资金验收。 */
class HistoryOrderSyncTest {
    JdbcTemplate db;
    JdbcOrderHistorySyncStore store;
    TransactionTemplate tx;
    String tenant = UUID.randomUUID().toString();
    UUID connector = UUID.randomUUID();
    Instant date = Instant.parse("2026-08-20T00:00:00Z"),
            cutoff = Instant.parse("2026-09-03T16:00:00Z"),
            paidAt = Instant.parse("2026-09-10T02:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        var ds = dataSource();
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.execute(
                "CREATE TABLE order_sales_order(tenant_id VARCHAR(36),id BIGINT,customer_id"
                    + " BIGINT,source_system_code VARCHAR(32),source_order_no VARCHAR(128),order_no"
                    + " VARCHAR(50),customer_name_snapshot VARCHAR(100),order_date"
                    + " TIMESTAMP,owner_employee_code VARCHAR(50),owner_employee_name_snapshot"
                    + " VARCHAR(100),payable_amount DECIMAL(24,6),paid_amount"
                    + " DECIMAL(24,6),unpaid_amount DECIMAL(24,6),source_unpaid_amount"
                    + " DECIMAL(24,6),order_status_code VARCHAR(32),payment_status_code"
                    + " VARCHAR(32),revision INT,deleted INT,PRIMARY KEY(tenant_id,id))");
        db.execute(
                "CREATE TABLE order_sales_order_line(id BIGINT AUTO_INCREMENT PRIMARY"
                    + " KEY,product_name_snapshot VARCHAR(100),specification_snapshot"
                    + " VARCHAR(100),line_amount DECIMAL(20,2),tenant_id VARCHAR(36),order_id"
                    + " BIGINT,product_id BIGINT,product_variant_id BIGINT,unit_code"
                    + " VARCHAR(32),quantity DECIMAL(20,6),deleted INT)");
        try (var c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(
                    c,
                    new ClassPathResource(
                            "db/migration/V40__history_order_groups_and_receipt_ledger.sql"));
            ScriptUtils.executeSqlScript(
                    c,
                    new ClassPathResource(
                            "db/migration/V46__history_group_amount_summary.sql"));
        }
        db.execute("ALTER TABLE order_sales_order ADD updated_time TIMESTAMP");
        db.execute(
                "CREATE TABLE order_payment_record(id BIGINT PRIMARY KEY,tenant_id"
                    + " VARCHAR(36),payment_no VARCHAR(50),connector_id"
                    + " VARCHAR(36),source_system_code VARCHAR(64),source_document_no"
                    + " VARCHAR(128),order_id BIGINT,sales_order_no_snapshot"
                    + " VARCHAR(50),customer_id BIGINT,customer_code_snapshot"
                    + " VARCHAR(50),customer_name_snapshot VARCHAR(200),collector_staff_code"
                    + " VARCHAR(50),collector_name_snapshot VARCHAR(100),payment_time"
                    + " TIMESTAMP,paid_amount DECIMAL(20,2),remark VARCHAR(1000),created_by"
                    + " VARCHAR(50),updated_by VARCHAR(50),created_time TIMESTAMP,updated_time"
                    + " TIMESTAMP,revision INT,deleted INT)");
        store =
                new JdbcOrderHistorySyncStore(
                        db,
                        mock(OrderAttributionClient.class),
                        mock(
                                com.rigour.order.application.port.out.OrderSalesPaymentRecordStore
                                        .class));
    }

    javax.sql.DataSource dataSource() {
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }

    void order(long id, long customer, String total, String opening, String qty) {
        db.update(
                "INSERT INTO order_sales_order VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                tenant,
                id,
                customer,
                "FEISHU",
                "F" + id,
                "O" + id,
                "门店A",
                java.time.LocalDateTime.ofInstant(date, java.time.ZoneOffset.UTC),
                "ZHANG",
                "张三",
                new BigDecimal(total),
                new BigDecimal(opening),
                new BigDecimal(total).subtract(new BigDecimal(opening)),
                new BigDecimal(total).subtract(new BigDecimal(opening)),
                "SUBMITTED",
                "UNPAID",
                0,
                0);
        db.update(
                "INSERT INTO"
                    + " order_sales_order_line(tenant_id,order_id,product_id,product_variant_id,unit_code,quantity,deleted)"
                    + " VALUES(?,?,?,?,?,?,0)",
                tenant,
                id,
                1,
                11,
                "BOX",
                new BigDecimal(qty));
        db.update(
                "UPDATE order_sales_order_line SET"
                    + " line_amount=?,product_name_snapshot='测试商品',specification_snapshot='箱' WHERE"
                    + " tenant_id=? AND order_id=?",
                new BigDecimal(total),
                tenant,
                id);
    }

    SourceOrder source(String no, long customer, String amount, String qty) {
        String json =
                "{\"customerId\":"
                        + customer
                        + ",\"sourceSystemCode\":\"DINGHUOBAO\",\"sourceOrderNo\":\""
                        + no
                        + "\",\"orderDate\":\"2026-08-25T00:00:00Z\",\"lines\":[{\"productId\":1,\"productVariantId\":11,\"unitCode\":\"BOX\",\"quantity\":"
                        + qty
                        + "}]}";
        return new SourceOrder(
                connector,
                no,
                JsonMapper.builder().build().readValue(json, SalesOrderCommand.class),
                new BigDecimal(amount),
                "hash-" + no);
    }

    void intake(String no, long customer, String total, String qty) {
        tx.executeWithoutResult(s -> store.sourceOrder(tenant, source(no, customer, total, qty)));
    }

    String bind(List<String> sources, List<Baseline> orders) {
        return tx.execute(
                s ->
                        store.bind(
                                tenant,
                                "tester",
                                new Bind(
                                        1,
                                        sources.stream()
                                                .map(n -> new SourceRef(connector, n, 0))
                                                .toList(),
                                        orders,
                                        "原始商品明细和历史余额已核对")));
    }

    Baseline base(long id, String opening) {
        return new Baseline(id, 0, cutoff, new BigDecimal(opening));
    }

    Receipt receipt(String no, String source, String amount, String status, String hash) {
        return new Receipt(
                connector, no, source, 1L, new BigDecimal(amount), paidAt, status, paidAt, hash);
    }

    Intake pay(Receipt c) {
        return tx.execute(s -> store.receipt(tenant, c));
    }

    BigDecimal paid(long id) {
        return db.queryForObject(
                "SELECT paid_amount FROM order_sales_order WHERE tenant_id=? AND id=?",
                BigDecimal.class,
                tenant,
                id);
    }

    @Test
    void explainedAmountDifferenceIsRecordedAndRequiresReason() {
        order(1, 1, "95", "0", "10");
        intake("D1", 1, "100", "10");
        assertThatThrownBy(() -> bind(List.of("D1"), List.of(base(1, "0"))))
                .hasMessageContaining("差额原因");
        String group =
                tx.execute(
                        s ->
                                store.bind(
                                        tenant,
                                        "tester",
                                        new Bind(
                                                1,
                                                List.of(new SourceRef(connector, "D1", 0)),
                                                List.of(base(1, "0")),
                                                "原始商品明细和历史余额已核对",
                                                "订货宝为结算口径，飞书侧含95折差额5元")));
        var row =
                db.queryForMap(
                        "SELECT source_amount,order_amount,difference_amount,difference_reason"
                            + " FROM order_history_group WHERE tenant_id=? AND id=?",
                        tenant,
                        group);
        assertThat(new BigDecimal(String.valueOf(row.get("source_amount"))))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(row.get("order_amount"))))
                .isEqualByComparingTo("95.00");
        assertThat(new BigDecimal(String.valueOf(row.get("difference_amount"))))
                .isEqualByComparingTo("5.00");
        assertThat(String.valueOf(row.get("difference_reason"))).contains("95折");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM order_sync_source WHERE tenant_id=? AND source_no=?",
                                String.class,
                                tenant,
                                "D1"))
                .isEqualTo("BOUND");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_history_member WHERE tenant_id=?",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
    }

    @Test
    void alignOrderAmountUpdatesFeishuOrderToSourceAmountWithAudit() {
        order(1, 1, "95", "0", "10");
        intake("D1", 1, "100", "10");
        tx.execute(
                s ->
                        store.bind(
                                tenant,
                                "tester",
                                new Bind(
                                        1,
                                        List.of(new SourceRef(connector, "D1", 0)),
                                        List.of(base(1, "0")),
                                        "原始商品明细和历史余额已核对",
                                        "订货宝为结算口径，飞书侧含95折差额5元",
                                        true)));
        var row =
                db.queryForMap(
                        "SELECT payable_amount,unpaid_amount,source_unpaid_amount,paid_amount"
                            + " FROM order_sales_order WHERE tenant_id=? AND id=1",
                        tenant);
        assertThat(new BigDecimal(String.valueOf(row.get("payable_amount"))))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(row.get("unpaid_amount"))))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(row.get("source_unpaid_amount"))))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(row.get("paid_amount"))))
                .isEqualByComparingTo("0.00");
        var group =
                db.queryForMap(
                        "SELECT source_amount,order_amount,difference_amount"
                            + " FROM order_history_group WHERE tenant_id=?",
                        tenant);
        assertThat(new BigDecimal(String.valueOf(group.get("source_amount"))))
                .isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(group.get("order_amount"))))
                .isEqualByComparingTo("95.00");
        assertThat(new BigDecimal(String.valueOf(group.get("difference_amount"))))
                .isEqualByComparingTo("5.00");
    }

    @Test
    void multiDecimalOrderAmountAlignsAndRoundsAuditDifference() {
        order(1, 1, "225.0027", "0", "10");
        intake("D1", 1, "234.00", "10");
        tx.execute(
                s ->
                        store.bind(
                                tenant,
                                "tester",
                                new Bind(
                                        1,
                                        List.of(new SourceRef(connector, "D1", 0)),
                                        List.of(base(1, "0")),
                                        "原始商品明细和历史余额已核对",
                                        "订货宝为结算口径，飞书侧金额225.0027元按订货宝234元对齐",
                                        true)));
        var row =
                db.queryForMap(
                        "SELECT payable_amount,unpaid_amount FROM order_sales_order"
                            + " WHERE tenant_id=? AND id=1",
                        tenant);
        assertThat(new BigDecimal(String.valueOf(row.get("payable_amount"))))
                .isEqualByComparingTo("234.00");
        assertThat(new BigDecimal(String.valueOf(row.get("unpaid_amount"))))
                .isEqualByComparingTo("234.00");
        var group =
                db.queryForMap(
                        "SELECT source_amount,order_amount,difference_amount"
                            + " FROM order_history_group WHERE tenant_id=?",
                        tenant);
        assertThat(new BigDecimal(String.valueOf(group.get("source_amount"))))
                .isEqualByComparingTo("234.00");
        assertThat(new BigDecimal(String.valueOf(group.get("order_amount"))))
                .isEqualByComparingTo("225.00");
        assertThat(new BigDecimal(String.valueOf(group.get("difference_amount"))))
                .isEqualByComparingTo("9.00");
    }

    @Test
    void reclassificationAfterHistoryLinkedTurnsReviewIntoNew() {
        order(1, 1, "1000", "0", "10");
        intake("H1", 1, "1000", "10");
        SourceOrder fresh =
                new SourceOrder(
                        connector,
                        "N1",
                        JsonMapper.builder()
                                .build()
                                .readValue(
                                        "{\"customerId\":1,\"sourceSystemCode\":\"DINGHUOBAO\",\"sourceOrderNo\":\"N1\","
                                            + "\"orderDate\":\"2026-09-10T00:00:00Z\",\"lines\":[{\"productId\":1,"
                                            + "\"productVariantId\":11,\"unitCode\":\"BOX\",\"quantity\":10}]}",
                                        SalesOrderCommand.class),
                        new BigDecimal("1000"),
                        "hash-N1");
        // 客户仍有未关联历史单：切换后新单先进入新旧待确认
        assertThat(store.sourceOrder(tenant, fresh).state()).isEqualTo("NEW_OR_HISTORY_REVIEW");
        bind(List.of("H1"), List.of(base(1, "0")));
        // 历史关联完成后重放同一来源（校验和未变）：应向上收敛为可建单的 NEW
        assertThat(store.sourceOrder(tenant, fresh).state()).isEqualTo("NEW");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM order_sync_source WHERE tenant_id=? AND source_no=?",
                                String.class,
                                tenant,
                                "N1"))
                .isEqualTo("NEW");
    }

    @Test
    void historyNeverBecomesNewOrder() {
        order(1, 1, "1000", "0", "10");
        assertThat(store.sourceOrder(tenant, source("D1", 1, "1000", "10")).state())
                .isEqualTo("HISTORY_PENDING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sales_order", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void splitPaymentsAccumulateOnceAndKeepOrderOwner() {
        order(1, 1, "1000", "200", "10");
        intake("D1", 1, "600", "6");
        intake("D2", 1, "400", "4");
        bind(List.of("D1", "D2"), List.of(base(1, "200")));
        var p = receipt("R1", "D1", "300", "CONFIRMED", "h1");
        assertThat(pay(p).state()).isEqualTo("ALLOCATED");
        pay(p);
        pay(receipt("R2", "D2", "200", "CONFIRMED", "h2"));
        assertThat(paid(1)).isEqualByComparingTo("700");
        assertThat(
                        db.queryForObject(
                                "SELECT owner_employee_code FROM order_sales_order", String.class))
                .isEqualTo("ZHANG");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sales_order", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void mergeHoldsCashUntilExactAllocation() {
        order(1, 1, "600", "0", "6");
        order(2, 1, "400", "0", "4");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0"), base(2, "0")));
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h1")).state())
                .isEqualTo("ALLOCATION_PENDING");
        assertThat(paid(1)).isZero();
        assertThat(paid(2)).isZero();
        tx.executeWithoutResult(
                s ->
                        store.allocate(
                                tenant,
                                "reviewer",
                                new Allocate(
                                        connector,
                                        "R1",
                                        0,
                                        List.of(
                                                new Allocation(1, new BigDecimal("300")),
                                                new Allocation(2, new BigDecimal("200"))),
                                        "凭据明确两笔订单核销分配")));
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(paid(2)).isEqualByComparingTo("200");
    }

    @Test
    void rejectsCrossStoreEvenWhenAmountsMatch() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 2, "1000", "10");
        assertThatThrownBy(() -> bind(List.of("D1"), List.of(base(1, "0"))))
                .hasMessageContaining("门店");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_history_group", Integer.class))
                .isZero();
    }

    @Test
    void rejectsEqualAmountButDifferentProductQuantity() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "8");
        assertThatThrownBy(() -> bind(List.of("D1"), List.of(base(1, "0"))))
                .hasMessageContaining("数量");
    }

    @Test
    void groupMembersCannotBeConsumedTwice() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        assertThatThrownBy(() -> bind(List.of("D1"), List.of(base(1, "0"))))
                .hasMessageContaining("状态");
    }

    @Test
    void baselineCoveredReceiptDoesNotAddAgain() {
        order(1, 1, "1000", "200", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "200")));
        var p =
                new Receipt(
                        connector,
                        "R0",
                        "D1",
                        1L,
                        new BigDecimal("200"),
                        date,
                        "CONFIRMED",
                        paidAt,
                        "h0");
        assertThat(pay(p).state()).isEqualTo("BASELINE_COVERED");
        assertThat(paid(1)).isEqualByComparingTo("200");
    }

    @Test
    void cancellationReversesOnlyItsOwnAllocation() {
        order(1, 1, "1000", "200", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "200")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        pay(receipt("R1", "D1", "300", "CANCELLED", "h2"));
        assertThat(paid(1)).isEqualByComparingTo("200");
    }

    @Test
    void missingStatusDoesNotCountAsConfirmed() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        assertThat(pay(receipt("R1", "D1", "300", "UNKNOWN", "h1")).state())
                .isEqualTo("STATUS_REVIEW");
        assertThat(paid(1)).isZero();
    }

    @Test
    void correctedStatusReclassifiesSameChecksumAndAllocatesOnce() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        assertThat(pay(receipt("R1", "D1", "300", "UNKNOWN", "h1")).state())
                .isEqualTo("STATUS_REVIEW");

        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "h1")).state())
                .isEqualTo("ALLOCATED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(
                        db.queryForObject(
                                "SELECT source_status FROM order_sync_receipt WHERE tenant_id=? AND receipt_no=?",
                                String.class,
                                tenant,
                                "R1"))
                .isEqualTo("CONFIRMED");

        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void overpaymentRollsBackReceiptAndAllocation() {
        order(1, 1, "1000", "900", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "900")));
        assertThatThrownBy(() -> pay(receipt("R1", "D1", "300", "CONFIRMED", "h1")))
                .hasMessageContaining("超过");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sync_receipt", Integer.class))
                .isZero();
        assertThat(paid(1)).isEqualByComparingTo("900");
    }

    @Test
    void confirmedPaymentOwnerIsSeparateAndFrozen() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "500", "CONFIRMED", "h1"));
        int version = db.queryForObject("SELECT revision FROM order_sync_receipt", Integer.class);
        tx.executeWithoutResult(
                s ->
                        store.confirmOwner(
                                tenant,
                                "reviewer",
                                new OwnerReview(
                                        connector,
                                        "R1",
                                        version,
                                        "LI",
                                        "李四",
                                        "门店交接生效记录与该笔回款时间核对")));
        assertThat(db.queryForObject("SELECT employee_code FROM order_sync_receipt", String.class))
                .isEqualTo("LI");
        assertThat(
                        db.queryForObject(
                                "SELECT owner_employee_code FROM order_sales_order", String.class))
                .isEqualTo("ZHANG");
        assertThatThrownBy(
                        () ->
                                store.confirmOwner(
                                        tenant,
                                        "reviewer",
                                        new OwnerReview(
                                                connector,
                                                "R1",
                                                version + 1,
                                                "WANG",
                                                "王五",
                                                "后续门店再次交接不改写该款业绩")))
                .hasMessageContaining("冻结");
    }

    @Test
    void sourceAndReceiptCannotCrossTenants() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        assertThat(store.overview(UUID.randomUUID().toString(), 1L).historyOrders()).isEmpty();
        assertThatThrownBy(
                        () ->
                                store.bind(
                                        UUID.randomUUID().toString(),
                                        "actor",
                                        new Bind(
                                                1,
                                                List.of(new SourceRef(connector, "D1", 0)),
                                                List.of(base(1, "0")),
                                                "核对来源原始记录")))
                .hasMessageContaining("不存在");
    }

    @Test
    void connectorReplacementCannotDuplicateSource() {
        intake("D1", 1, "1000", "10");
        var old = source("D1", 1, "1000", "10");
        assertThatThrownBy(
                        () ->
                                store.sourceOrder(
                                        tenant,
                                        new SourceOrder(
                                                UUID.randomUUID(),
                                                old.sourceNo(),
                                                old.order(),
                                                old.amount(),
                                                old.checksum())))
                .hasMessageContaining("命名空间");
    }

    @Test
    void changedConfirmedAmountPreservesOriginalFactAndBalance() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h2")).state())
                .isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT amount FROM order_sync_receipt", BigDecimal.class))
                .isEqualByComparingTo("300");
        assertThat(
                        db.queryForObject(
                                "SELECT paid_amount FROM order_payment_record WHERE deleted=0",
                                BigDecimal.class))
                .isEqualByComparingTo("300");
    }

    @Test
    void checksumMetadataChangeDoesNotReallocateAnAcceptedReceipt() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "h2")).state())
                .isEqualTo("ALLOCATED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cancelledBaselineReceiptRequiresBaselineReview() {
        order(1, 1, "1000", "200", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "200")));
        pay(
                new Receipt(
                        connector,
                        "R0",
                        "D1",
                        1L,
                        new BigDecimal("200"),
                        date,
                        "CONFIRMED",
                        paidAt,
                        "h0"));
        assertThat(
                        pay(new Receipt(
                                        connector,
                                        "R0",
                                        "D1",
                                        1L,
                                        new BigDecimal("200"),
                                        date,
                                        "CANCELLED",
                                        paidAt,
                                        "h1"))
                                .state())
                .isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(paid(1)).isEqualByComparingTo("200");
    }

    @Test
    void cancelledReceiptProjectionAndProductPerformanceAreRemoved() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        int version = db.queryForObject("SELECT revision FROM order_sync_receipt", Integer.class);
        long line = db.queryForObject("SELECT id FROM order_sales_order_line", Long.class);
        tx.executeWithoutResult(
                x ->
                        store.allocateProducts(
                                tenant,
                                "reviewer",
                                new AllocateProducts(
                                        connector,
                                        "R1",
                                        version,
                                        List.of(
                                                new ProductAllocation(
                                                        1, line, new BigDecimal("300"))),
                                        "凭回款用途确认该商品核销")));
        assertThat(store.performance(tenant, "2026-09").productReceipts()).hasSize(1);
        pay(receipt("R1", "D1", "300", "CANCELLED", "h2"));
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_payment_record WHERE deleted=0",
                                Integer.class))
                .isZero();
        assertThat(store.performance(tenant, "2026-09").productReceipts()).isEmpty();
    }

    @Test
    void productAllocationMustConserveEachOriginalOrder() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        int version = db.queryForObject("SELECT revision FROM order_sync_receipt", Integer.class);
        long line = db.queryForObject("SELECT id FROM order_sales_order_line", Long.class);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        x ->
                                                store.allocateProducts(
                                                        tenant,
                                                        "reviewer",
                                                        new AllocateProducts(
                                                                connector,
                                                                "R1",
                                                                version,
                                                                List.of(
                                                                        new ProductAllocation(
                                                                                1,
                                                                                line,
                                                                                new BigDecimal(
                                                                                        "301"))),
                                                                "产品核销测试依据"))))
                .hasMessageContaining("分配");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM order_sync_product_allocation",
                                Integer.class))
                .isZero();
    }

    @Test
    void monthlyTradeAndReceiptUseDifferentSalespeopleAndDates() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        int version = db.queryForObject("SELECT revision FROM order_sync_receipt", Integer.class);
        store.confirmOwner(
                tenant,
                "reviewer",
                new OwnerReview(connector, "R1", version, "LI", "李四", "门店交接时间早于实际回款时间"));
        var august = store.performance(tenant, "2026-08");
        var september = store.performance(tenant, "2026-09");
        assertThat(august.sales().getFirst().get("employee_code")).isEqualTo("ZHANG");
        assertThat(august.receipts()).isEmpty();
        assertThat(september.sales()).isEmpty();
        assertThat(september.receipts().getFirst().get("employee_code")).isEqualTo("LI");
    }
}
