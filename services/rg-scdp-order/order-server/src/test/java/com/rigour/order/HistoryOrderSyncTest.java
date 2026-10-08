package com.rigour.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.application.port.out.OrderAttributionClient;
import com.rigour.order.infrastructure.persistence.repository.JdbcOrderHistorySyncStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

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
    com.rigour.order.application.service.sales.OrderSalesOrderService salesOrders;
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
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V50__dhb_projection_change_audit.sql"));
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
                    + " VARCHAR(128),source_record_id VARCHAR(128),payment_status_code VARCHAR(32),order_id BIGINT,sales_order_no_snapshot"
                    + " VARCHAR(50),customer_id BIGINT,customer_code_snapshot"
                    + " VARCHAR(50),customer_name_snapshot VARCHAR(200),collector_staff_code"
                    + " VARCHAR(50),collector_name_snapshot VARCHAR(100),payment_time"
                    + " TIMESTAMP,paid_amount DECIMAL(20,2),remark VARCHAR(1000),created_by"
                    + " VARCHAR(50),updated_by VARCHAR(50),created_time TIMESTAMP,updated_time"
                    + " TIMESTAMP,revision INT,deleted INT)");
        salesOrders = mock(com.rigour.order.application.service.sales.OrderSalesOrderService.class);
        store =
                new JdbcOrderHistorySyncStore(
                        db,
                        mock(OrderAttributionClient.class),
                        mock(
                                com.rigour.order.application.port.out.OrderSalesPaymentRecordStore
                                        .class), salesOrders);
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

    @Test
    void explicitlyApprovesOnlyUnboundHistoricalSourcesWithoutChangingTheirSourceDate() {
        intake("H-NEW", 1, "78", "1");
        var command = new NewOrder(new SourceRef(connector, "H-NEW", 0), "用户确认历史缺单补新增，业务日期统一八月三十一日");
        tx.executeWithoutResult(s -> store.confirmHistoricalNew(tenant, "tester", command));
        var saved = db.queryForMap("SELECT * FROM order_sync_source WHERE source_no='H-NEW'");
        assertThat(saved.get("state")).isEqualTo("NEW");
        assertThat(saved.get("classification_actor")).isEqualTo("tester");
        assertThat(saved.get("source_date").toString()).startsWith("2026-08-25");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.confirmHistoricalNew(tenant, "tester", command)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.confirmHistoricalNew("another-tenant", "tester", command)))
                .isInstanceOf(IllegalArgumentException.class);
        order(1, 1, "78", "0", "1");
        intake("H-BOUND", 1, "78", "1");
        bind(List.of("H-BOUND"), List.of(base(1, "0")));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.confirmHistoricalNew(tenant, "tester",
                new NewOrder(new SourceRef(connector, "H-BOUND", 1), "不能重复创建已经绑定的历史来源订单"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesOnlyUnpaidUnlinkedHistoryAndKeepsRecoverableRows() {
        order(1, 1, "78", "0", "1");
        order(2, 1, "78", "1", "1");
        db.execute("ALTER TABLE order_sales_order ADD updated_by VARCHAR(50)");
        db.execute("ALTER TABLE order_sales_order ADD remark VARCHAR(1000)");
        db.execute("ALTER TABLE order_sales_order_line ADD revision INT DEFAULT 0");
        db.execute("ALTER TABLE order_sales_order_line ADD updated_by VARCHAR(50)");
        db.execute("ALTER TABLE order_sales_order_line ADD updated_time TIMESTAMP");
        for (String table : List.of("order_refund_record", "order_financial_event", "order_fulfillment_execution"))
            db.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(36),order_id BIGINT)");
        db.execute("CREATE TABLE order_fund_document(tenant_id VARCHAR(36),related_order_id BIGINT)");
        for (String table : List.of("order_sales_shipment", "order_invoice"))
            db.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(36),sales_order_id BIGINT)");
        db.execute("CREATE TABLE order_number_mapping(tenant_id VARCHAR(36),internal_order_no VARCHAR(50),deleted INT)");
        var command = new DeleteUnlinkedHistory(1, 0, "已完整核对订货宝单号和回款，无关联无回款才清理");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.deleteUnlinkedHistory(tenant, "tester",
                new DeleteUnlinkedHistory(2, 0, command.evidence())))).isInstanceOf(IllegalArgumentException.class);
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,deleted) VALUES(100,?,1,0,1)", tenant);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.deleteUnlinkedHistory(tenant, "tester", command)))
                .isInstanceOf(IllegalArgumentException.class);
        db.update("DELETE FROM order_payment_record WHERE id=100");
        db.update("INSERT INTO order_number_mapping VALUES(?,'O1',0)", tenant);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.deleteUnlinkedHistory(tenant, "tester", command)))
                .isInstanceOf(IllegalArgumentException.class);
        db.update("DELETE FROM order_number_mapping");
        tx.executeWithoutResult(s -> store.deleteUnlinkedHistory(tenant, "tester", command));
        assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted FROM order_sales_order_line WHERE order_id=1", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT remark FROM order_sales_order WHERE id=1", String.class)).contains(command.evidence());
        assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=2", Integer.class)).isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> store.deleteUnlinkedHistory(tenant, "tester", command)))
                .isInstanceOf(IllegalArgumentException.class);
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
    void correctedSettlementIsSavedEvenWhenRawChecksumIsUnchanged() {
        var original = source("CORRECT-NET", 1, "234", "3");
        store.sourceOrder(tenant, original);
        store.sourceOrder(tenant, new SourceOrder(connector, original.sourceNo(), original.order(),
                new BigDecimal("222.30"), original.checksum()));
        assertThat(db.queryForObject("SELECT amount FROM order_sync_source WHERE tenant_id=? AND source_no=?",
                BigDecimal.class, tenant, original.sourceNo())).isEqualByComparingTo("222.30");
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
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
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
    void changedConfirmedAmountUpdatesExactReceiptAndKeepsAuditWithoutDuplicating() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h2")).state())
                .isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("500");
        assertThat(db.queryForObject("SELECT amount FROM order_sync_receipt", BigDecimal.class))
                .isEqualByComparingTo("500");
        assertThat(
                        db.queryForObject(
                                "SELECT paid_amount FROM order_payment_record WHERE deleted=0",
                                BigDecimal.class))
                .isEqualByComparingTo("500");
        pay(receipt("R1", "D1", "500", "CONFIRMED", "h2"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isEqualTo(1);
    }

    @Test
    void boundOneToOneUpdatesCommercialDataAndPreservesHistoricalIdentity() {
        order(1, 1, "1000", "300", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "300")));
        db.execute("ALTER TABLE order_sales_order ADD payment_voucher_keys_json VARCHAR(1000)");
        db.update("UPDATE order_sales_order SET payment_voucher_keys_json='[\"original-proof\"]'");
        when(salesOrders.update(eq(1L), any())).thenAnswer(call -> {
            SalesOrderCommand c = call.getArgument(1);
            assertThat(c.sourceSystemCode()).isEqualTo("FEISHU");
            assertThat(c.sourceOrderNo()).isEqualTo("F1");
            assertThat(c.orderDate()).isEqualTo(date);
            assertThat(c.ownerEmployeeCode()).isEqualTo("ZHANG");
            assertThat(c.paymentVoucherKeys()).containsExactly("original-proof");
            assertThat(c.lines().getFirst().quantity()).isEqualByComparingTo("8");
            db.update("UPDATE order_sales_order SET payable_amount=800 WHERE id=1");
            return JsonMapper.builder().build().readValue("{\"id\":1,\"payableAmount\":800}",
                    com.rigour.order.api.v1.model.SalesOrderDetailView.class);
        });
        var result = tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8")));
        assertThat(result.state()).isEqualTo("UPDATED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT unpaid_amount FROM order_sales_order", BigDecimal.class)).isEqualByComparingTo("500");
        assertThat(db.queryForObject("SELECT difference_amount FROM order_history_group", BigDecimal.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isEqualTo(1);
        tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8")));
        verify(salesOrders, times(1)).update(eq(1L), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void replacementReceiptReleasesDeletedOpeningBalanceWithoutChangingOtherDates(boolean historical) {
        order(1, 1, "1000", "300", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "300")));
        db.update("UPDATE order_sales_order SET paid_amount=0,unpaid_amount=1000,payment_status_code='UNPAID'");
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_time,payment_status_code,deleted)"
                + " VALUES(99,?,1,300,?,'RECEIVED',1)", tenant, java.sql.Timestamp.from(date));
        Instant replacementDate = historical ? date : paidAt;
        var replacement = new Receipt(connector, "R2", "D1", 1L, new BigDecimal("1000"),
                replacementDate, "CONFIRMED", paidAt, "new-receipt");
        assertThat(pay(replacement).state()).isEqualTo("ALLOCATED");
        assertThat(paid(1)).isEqualByComparingTo("1000");
        assertThat(db.queryForObject("SELECT opening_paid FROM order_history_member", BigDecimal.class)).isZero();
        var dates = db.queryForList("SELECT id,payment_time FROM order_payment_record ORDER BY id");
        pay(replacement);
        assertThat(db.queryForList("SELECT id,payment_time FROM order_payment_record ORDER BY id")).isEqualTo(dates);
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record WHERE id=99", java.sql.Timestamp.class).toInstant()).isEqualTo(date);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record WHERE deleted=0", Integer.class)).isEqualTo(1);
    }

    @Test
    void boundCommercialUpdateDoesNotResurrectReconciledOpeningPayment() {
        order(1, 1, "1000", "296.40", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "296.40")));
        // 已完成的历史对账退出了旧款，但当年的关联期初仍保留旧值。
        db.update("UPDATE order_sales_order SET paid_amount=0,unpaid_amount=1000,payment_status_code='UNPAID'");
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,payment_status_code,deleted)"
                + " VALUES(99,?,1,296.4,'RECEIVED',1)", tenant);
        when(salesOrders.update(eq(1L), any())).thenAnswer(call -> {
            db.update("UPDATE order_sales_order SET payable_amount=800 WHERE id=1");
            return JsonMapper.builder().build().readValue("{\"id\":1,\"payableAmount\":800}",
                    com.rigour.order.api.v1.model.SalesOrderDetailView.class);
        });
        assertThat(tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8"))).state())
                .isEqualTo("UPDATED");
        assertThat(paid(1)).isZero();
        assertThat(db.queryForObject("SELECT opening_paid FROM order_history_member", BigDecimal.class)).isZero();
        assertThat(db.queryForObject("SELECT unpaid_amount FROM order_sales_order", BigDecimal.class)).isEqualByComparingTo("800");
        assertThat(db.queryForObject("SELECT deleted FROM order_payment_record WHERE id=99", Integer.class)).isEqualTo(1);
        pay(receipt("R2", "D1", "100", "CONFIRMED", "new-payment"));
        assertThat(paid(1)).isEqualByComparingTo("100");
        tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8")));
        assertThat(paid(1)).isEqualByComparingTo("100");
        verify(salesOrders, times(1)).update(eq(1L), any());
    }

    @Test
    void boundCommercialUpdateKeepsActiveAllocationsWithoutDoubleCounting() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.update("UPDATE order_history_member SET opening_paid=296.4");
        when(salesOrders.update(eq(1L), any())).thenAnswer(call -> {
            db.update("UPDATE order_sales_order SET payable_amount=800 WHERE id=1");
            return JsonMapper.builder().build().readValue("{\"id\":1,\"payableAmount\":800}",
                    com.rigour.order.api.v1.model.SalesOrderDetailView.class);
        });
        tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8")));
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT opening_paid FROM order_history_member", BigDecimal.class)).isZero();
        assertThat(db.queryForObject("SELECT SUM(amount) FROM order_sync_allocation", BigDecimal.class)).isEqualByComparingTo("300");
        pay(receipt("R2", "D1", "100", "CONFIRMED", "h2"));
        assertThat(paid(1)).isEqualByComparingTo("400");
    }

    @Test
    void boundUpdateFailureRollsBackSourceAndAudit() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        when(salesOrders.update(eq(1L), any())).thenThrow(new IllegalStateException("执行约束阻止覆盖"));
        assertThatThrownBy(() -> tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "800", "8"))))
                .hasMessageContaining("执行约束阻止覆盖");
        assertThat(db.queryForObject("SELECT amount FROM order_sync_source", BigDecimal.class)).isEqualByComparingTo("1000");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isZero();
        doReturn(JsonMapper.builder().build().readValue("{\"id\":1,\"payableAmount\":800}",
                com.rigour.order.api.v1.model.SalesOrderDetailView.class)).when(salesOrders).update(eq(1L), any());
        assertThatThrownBy(() -> tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "900", "9"))))
                .hasMessageContaining("来源明细折后金额与订单金额不一致");
        assertThat(db.queryForObject("SELECT amount FROM order_sync_source", BigDecimal.class)).isEqualByComparingTo("1000");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isZero();
    }

    @Test
    void splitBaselineReceiptChangesKeepVouchersDatesAndTotalThenCancelOnce() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.execute("ALTER TABLE order_payment_record ADD voucher_keys_json VARCHAR(1000)");
        db.update("UPDATE order_payment_record SET paid_amount=150,voucher_keys_json='[\"proof\"]'");
        db.update("INSERT INTO order_payment_record(id,tenant_id,connector_id,source_system_code,source_record_id,source_document_no,"
                + "order_id,customer_id,paid_amount,payment_status_code,payment_time,collector_staff_code,revision,deleted,voucher_keys_json)"
                + " SELECT id+1,tenant_id,connector_id,source_system_code,source_record_id,'R1#C21-2',order_id,customer_id,150,"
                + "payment_status_code,payment_time,'ZHANG',0,0,voucher_keys_json FROM order_payment_record");
        db.update("UPDATE order_sync_receipt SET state='BASELINE_COVERED',occurred_at=?", java.sql.Timestamp.from(date));
        db.update("UPDATE order_history_member SET opening_paid=300");
        var before = db.queryForList("SELECT id,payment_time,voucher_keys_json FROM order_payment_record ORDER BY id");
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h2")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("500");
        assertThat(db.queryForList("SELECT id,payment_time,voucher_keys_json FROM order_payment_record ORDER BY id")).isEqualTo(before);
        assertThat(db.queryForList("SELECT paid_amount FROM order_payment_record", BigDecimal.class))
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("250"));
        pay(receipt("R1", "D1", "500", "CONFIRMED", "h2"));
        assertThat(paid(1)).isEqualByComparingTo("500");
        pay(receipt("R1", "D1", "500", "CANCELLED", "h3"));
        pay(receipt("R1", "D1", "500", "CANCELLED", "h3"));
        assertThat(paid(1)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record WHERE deleted=0", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isEqualTo(2);
    }

    private void pendingHistoricalReceipt() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "initial"));
        db.update("DELETE FROM order_sync_allocation");
        db.update("UPDATE order_sync_receipt SET occurred_at=?,source_status='PENDING',state='STATUS_REVIEW'", java.sql.Timestamp.from(date));
        db.update("UPDATE order_payment_record SET payment_time=?,payment_status_code='PENDING'", java.sql.Timestamp.from(date.minusSeconds(86400)));
        db.update("UPDATE order_sales_order SET paid_amount=0,unpaid_amount=1000");
        db.execute("CREATE TABLE order_fund_document(id BIGINT,tenant_id VARCHAR(36),connector_id VARCHAR(36),"
                + "source_system_code VARCHAR(32),direction_code VARCHAR(20),source_document_no VARCHAR(128),"
                + "document_status_code VARCHAR(32),amount DECIMAL(20,2),occurred_time TIMESTAMP,"
                + "revision INT,updated_by VARCHAR(50),updated_time TIMESTAMP,deleted INT)");
        db.update("INSERT INTO order_fund_document VALUES(1,?,?,'DINGHUOBAO','RECEIPT','R1','PENDING',300,?,0,NULL,NULL,0)",
                tenant, connector.toString(), java.sql.Timestamp.from(date));
    }

    private Receipt historicalStatus(String status, String hash) {
        return new Receipt(connector, "R1", "D1", 1L, new BigDecimal("300"), date.plusSeconds(86400),
                status, paidAt.plusSeconds(86400), hash);
    }

    private Intake syncHistorical(Receipt c) {
        return tx.execute(s -> store.historicalReceiptStatus(tenant, c));
    }

    @Test
    void historicalPendingConfirmationPreservesDatesAndIsIdempotent() {
        pendingHistoricalReceipt();
        var orderDate = db.queryForObject("SELECT order_date FROM order_sales_order", java.sql.Timestamp.class);
        var paymentDate = db.queryForObject("SELECT payment_time FROM order_payment_record", java.sql.Timestamp.class);
        var command = historicalStatus("CONFIRMED", "confirmed");
        assertThat(syncHistorical(command).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT opening_paid FROM order_history_member", BigDecimal.class)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("CHECKED");
        assertThat(db.queryForObject("SELECT state FROM order_sync_receipt", String.class)).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT occurred_at FROM order_sync_receipt", java.sql.Timestamp.class)).isEqualTo(java.sql.Timestamp.from(date));
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.sql.Timestamp.class)).isEqualTo(paymentDate);
        assertThat(db.queryForObject("SELECT order_date FROM order_sales_order", java.sql.Timestamp.class)).isEqualTo(orderDate);
        assertThat(db.queryForObject("SELECT occurred_time FROM order_fund_document", java.sql.Timestamp.class)).isEqualTo(java.sql.Timestamp.from(date));
        assertThat(db.queryForObject("SELECT document_status_code FROM order_fund_document", String.class)).isEqualTo("CONFIRMED");
        var before = db.queryForList("SELECT * FROM order_payment_record");
        int audits = db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class);
        assertThat(syncHistorical(command).state()).isEqualTo("SYNCED");
        assertThat(db.queryForList("SELECT * FROM order_payment_record")).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isEqualTo(audits);
        assertThat(paid(1)).isEqualByComparingTo("300");
    }

    @Test
    void historicalAmountChangeIsRetriableReviewWithoutChangingMoney() {
        pendingHistoricalReceipt();
        var change = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("400"), date, "CONFIRMED", paidAt.plusSeconds(1), "changed");
        assertThat(syncHistorical(change).state()).isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(db.queryForObject("SELECT paid_amount FROM order_payment_record", BigDecimal.class)).isEqualByComparingTo("300");
        assertThat(paid(1)).isZero();
        assertThat(db.queryForObject("SELECT pending_checksum FROM order_sync_receipt", String.class)).isEqualTo("changed");
        assertThat(syncHistorical(historicalStatus("CONFIRMED", "fixed")).state()).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT pending_checksum FROM order_sync_receipt", String.class)).isNull();
    }

    @Test
    void historicalMissingPaymentDoesNotCreateOrAcknowledgePayment() {
        pendingHistoricalReceipt();
        db.update("DELETE FROM order_payment_record");
        assertThat(syncHistorical(historicalStatus("CONFIRMED", "confirmed")).state()).isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isZero();
        assertThat(paid(1)).isZero();
    }

    @Test
    void historicalConfirmationKeepsExistingAllocationEffective() {
        pendingHistoricalReceipt();
        long paymentId = db.queryForObject("SELECT id FROM order_payment_record", Long.class);
        db.update("INSERT INTO order_sync_allocation(tenant_id,connector_id,receipt_no,order_id,amount,payment_id,evidence,actor_id,created_at)"
                + " VALUES(?,?,?,1,300,?,'已有关联','test',?)", tenant, connector.toString(), "R1", paymentId,
                java.sql.Timestamp.from(paidAt));
        assertThat(syncHistorical(historicalStatus("CONFIRMED", "confirmed")).state()).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT state FROM order_sync_receipt", String.class)).isEqualTo("ALLOCATED");
        assertThat(db.queryForObject("SELECT opening_paid FROM order_history_member", BigDecimal.class)).isZero();
        assertThat(paid(1)).isEqualByComparingTo("300");
    }

    @Test
    void unknownHistoricalReceiptRemainsReviewAndCannotCrossTenantOrConnector() {
        pendingHistoricalReceipt();
        var other = new Receipt(UUID.randomUUID(), "R1", "D1", 1L, new BigDecimal("300"), date, "CONFIRMED", paidAt, "other");
        assertThat(syncHistorical(other).state()).isEqualTo("HISTORY_REVIEW");
        assertThat(tx.execute(s -> store.historicalReceiptStatus(UUID.randomUUID().toString(), historicalStatus("CONFIRMED", "other"))).state())
                .isEqualTo("HISTORY_REVIEW");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sync_receipt", Integer.class)).isEqualTo(1);
        assertThat(paid(1)).isZero();
    }

    @Test
    void historicalCancellationAndStaleResponsesCannotDuplicateMoney() {
        pendingHistoricalReceipt();
        syncHistorical(historicalStatus("CONFIRMED", "confirmed"));
        assertThat(syncHistorical(historicalStatus("CANCELLED", "cancelled")).state()).isEqualTo("CANCELLED");
        assertThat(paid(1)).isZero();
        assertThat(syncHistorical(historicalStatus("CANCELLED", "cancelled")).state()).isEqualTo("CANCELLED");
        var stale = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("300"), date, "CONFIRMED", paidAt, "stale");
        assertThat(syncHistorical(stale).state()).isEqualTo("STALE");
        assertThat(syncHistorical(historicalStatus("CONFIRMED", "restore")).state()).isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(paid(1)).isZero();
    }

    @Test
    void receivedBeforeFinancialCheckCountsOnceAndConfirmationOnlyChecksIt() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        assertThat(pay(receipt("R1", "D1", "300", "RECEIVED", "same-source")).state()).isEqualTo("ALLOCATED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("RECEIVED");
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "same-source")).state()).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("CHECKED");
        assertThat(db.queryForObject("SELECT source_status FROM order_sync_receipt", String.class)).isEqualTo("CONFIRMED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        pay(receipt("R1", "D1", "300", "CONFIRMED", "same-source"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(paid(1)).isEqualByComparingTo("300");
        pay(receipt("R1", "D1", "300", "CANCELLED", "cancel"));
        assertThat(paid(1)).isZero();
    }

    @Test
    void historicalReceivedCountsBeforeFinancialCheckWithoutChangingDates() {
        pendingHistoricalReceipt();
        assertThat(syncHistorical(historicalStatus("RECEIVED", "unreviewed")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("RECEIVED");
        assertThat(db.queryForObject("SELECT document_status_code FROM order_fund_document", String.class)).isEqualTo("PENDING");
        assertThat(syncHistorical(historicalStatus("CONFIRMED", "checked")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("CHECKED");
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.from(date.minusSeconds(86400)));
    }

    @Test
    void sameRawChecksumCanUpgradeReceiptTimeWithoutDuplicatingMoneyOrChangingHistory() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        var orderBefore = db.queryForObject("SELECT order_date FROM order_sales_order", java.time.LocalDateTime.class);
        Instant operation = Instant.parse("2026-09-11T07:47:02Z");
        var upgraded = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("300"), operation,
                "CONFIRMED", paidAt, "h1");
        assertThat(pay(upgraded).state()).isEqualTo("SYNCED");
        pay(upgraded);
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.ofInstant(operation, java.time.ZoneOffset.UTC));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_dhb_projection_change_audit", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT order_date FROM order_sales_order", java.time.LocalDateTime.class)).isEqualTo(orderBefore);
        assertThat(paid(1)).isEqualByComparingTo("300");
    }

    @Test
    void newlyAllocatedHistoricalReceiptUsesFallbackButKeepsRawOccurredAt() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        db.update("UPDATE order_history_member SET baseline_at=?", java.sql.Timestamp.from(date));
        Instant sourceDate = Instant.parse("2026-09-02T00:00:00Z");
        var receipt = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("300"), sourceDate, "CONFIRMED", paidAt, "h1");
        assertThat(pay(receipt).state()).isEqualTo("ALLOCATED");
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.ofInstant(com.rigour.order.domain.sync.HistorySyncRules.HISTORICAL_FALLBACK, java.time.ZoneOffset.UTC));
        assertThat(db.queryForObject("SELECT occurred_at FROM order_sync_receipt", java.time.LocalDateTime.class))
                .isEqualTo(java.time.LocalDateTime.ofInstant(sourceDate, java.time.ZoneOffset.UTC));
        pay(receipt);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(paid(1)).isEqualByComparingTo("300");
    }

    @Test
    void historicalReceiptDateSurvivesMultipleSourceDateChanges() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        var historical = java.sql.Timestamp.from(com.rigour.order.domain.sync.HistorySyncRules.HISTORICAL_FALLBACK);
        db.update("UPDATE order_payment_record SET payment_time=?", historical);
        db.update("UPDATE order_sync_receipt SET occurred_at=?", java.sql.Timestamp.from(date));
        pay(receipt("R1", "D1", "400", "CONFIRMED", "h2"));
        // 第一次更新后来源 occurred_at 已变为切换点之后，第二次仍须保护业务日期。
        pay(receipt("R1", "D1", "500", "CONFIRMED", "h3"));
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.sql.Timestamp.class))
                .isEqualTo(historical);
        assertThat(paid(1)).isEqualByComparingTo("500");
        pay(receipt("R1", "D1", "500", "CONFIRMED", "h3"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2026-09-05T10:12:05Z", "2026-09-28T10:33:45Z"})
    void voucherCorrectedDateAfterCutoverSurvivesReceiptReplayAndConfirmation(String correctedDate) {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "RECEIVED", "h1"));
        var corrected = java.sql.Timestamp.from(Instant.parse(correctedDate));
        db.update("UPDATE order_payment_record SET payment_time=?", corrected);
        pay(receipt("R1", "D1", "300", "RECEIVED", "replayed"));
        var confirmed = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("300"),
                paidAt.plusSeconds(86400), "CONFIRMED", paidAt.plusSeconds(86400), "confirmed");
        pay(confirmed);
        pay(confirmed);
        assertThat(db.queryForObject("SELECT payment_time FROM order_payment_record", java.sql.Timestamp.class))
                .isEqualTo(corrected);
        assertThat(db.queryForObject("SELECT payment_status_code FROM order_payment_record", String.class)).isEqualTo("CHECKED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(paid(1)).isEqualByComparingTo("300");
    }

    @Test
    void netRefundAdjustedReceiptIsNotOverwrittenByRawSourceAmount() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.update("UPDATE order_payment_record SET paid_amount=200");
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h2")).state()).isEqualTo("SOURCE_CHANGED_REVIEW");
        assertThat(db.queryForObject("SELECT paid_amount FROM order_payment_record", BigDecimal.class)).isEqualByComparingTo("200");
        // 待处理版本在阻塞解除后可以重试，不被 pending_checksum 永久挡住。
        db.update("UPDATE order_payment_record SET paid_amount=300");
        assertThat(pay(receipt("R1", "D1", "500", "CONFIRMED", "h2")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("500");
    }

    @Test
    void baselineReceiptCanMoveBetweenPendingAndConfirmedWithoutDuplicate() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.update("UPDATE order_sync_receipt SET state='BASELINE_COVERED'");
        db.update("UPDATE order_history_member SET opening_paid=300");
        assertThat(pay(receipt("R1", "D1", "300", "PENDING", "h2")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isZero();
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "h3")).state()).isEqualTo("SYNCED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        var stale = new Receipt(connector, "R1", "D1", 1L, new BigDecimal("900"), paidAt, "CONFIRMED", paidAt.minusSeconds(1), "old");
        assertThat(pay(stale).state()).isEqualTo("STALE");
        assertThat(paid(1)).isEqualByComparingTo("300");
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
    void reconciledBaselineKeepsArchivedAllocationsInactiveAfterMetadataChange() {
        order(1, 1, "1000", "0", "10");
        intake("D1", 1, "1000", "10");
        bind(List.of("D1"), List.of(base(1, "0")));
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.update("UPDATE order_history_member SET opening_paid=300,baseline_at=?",
                java.sql.Timestamp.from(paidAt.plusSeconds(1)));
        db.update("UPDATE order_sync_receipt SET state='BASELINE_COVERED'");
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "metadata-new")).state())
                .isEqualTo("BASELINE_COVERED");
        assertThat(paid(1)).isEqualByComparingTo("300");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
    }

    @Test
    void reconciledNewSourceDoesNotReprojectCoveredReceiptWithOrWithoutMetadataChange() {
        intake("D1", 1, "1000", "10");
        db.update("UPDATE order_sync_source SET state='NEW'");
        pay(receipt("R1", "D1", "300", "CONFIRMED", "h1"));
        db.update("UPDATE order_sync_receipt SET state='BASELINE_COVERED'");
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "h1")).state()).isEqualTo("BASELINE_COVERED");
        assertThat(pay(receipt("R1", "D1", "300", "CONFIRMED", "h2")).state()).isEqualTo("BASELINE_COVERED");
        assertThat(pay(receipt("R1", "D1", "350", "CONFIRMED", "h3")).state()).isEqualTo("SOURCE_CHANGED_REVIEW");
    }

    @Test
    void coveredReceiptReplayPreservesReconciledFactsWhenRawGrossTotalExceedsOrderAmount() {
        intake("D1", 1, "1176", "10");
        db.update("UPDATE order_sync_source SET state='NEW'");
        pay(receipt("R1", "D1", "1176", "CONFIRMED", "h1"));
        db.update("UPDATE order_sync_receipt SET state='BASELINE_COVERED'");
        db.update("INSERT INTO order_sync_receipt(tenant_id,connector_id,receipt_no,source_order_no,customer_id,amount,occurred_at,source_status,checksum,state)"
                + " VALUES(?,?,'R2','D1',1,583.20,?,'CONFIRMED','h2','BASELINE_COVERED')",
                tenant, connector.toString(), java.sql.Timestamp.from(paidAt));

        assertThat(pay(receipt("R1", "D1", "1176", "CONFIRMED", "h1")).state()).isEqualTo("BASELINE_COVERED");
        assertThat(pay(receipt("R1", "D1", "1176", "CONFIRMED", "metadata-new")).state()).isEqualTo("BASELINE_COVERED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sync_allocation", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT occurred_at FROM order_sync_receipt WHERE receipt_no='R1'", java.sql.Timestamp.class))
                .isEqualTo(java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(paidAt, java.time.ZoneOffset.UTC)));

        assertThatThrownBy(() -> pay(receipt("R1", "D1", "1200", "CONFIRMED", "changed")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        intake("D2", 1, "100", "1");
        assertThatThrownBy(() -> pay(receipt("R1", "D2", "1176", "CONFIRMED", "moved")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(db.queryForObject("SELECT amount FROM order_sync_receipt WHERE receipt_no='R1'", BigDecimal.class))
                .isEqualByComparingTo("1176");
        assertThat(db.queryForObject("SELECT checksum FROM order_sync_receipt WHERE receipt_no='R1'", String.class))
                .isEqualTo("metadata-new");
        assertThat(db.queryForObject("SELECT source_order_no FROM order_sync_receipt WHERE receipt_no='R1'", String.class))
                .isEqualTo("D1");
    }

    @Test
    void newReceiptOverSourceAmountReturnsConflictWithoutWritingCash() {
        intake("D1", 1, "1000", "10");
        assertThatThrownBy(() -> pay(receipt("R1", "D1", "1001", "CONFIRMED", "h1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sync_receipt", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isZero();
    }

    @Test
    void unchangedReceiptPersistsRecoveredMappingOnceWithoutRewritingPaymentFacts() {
        var unmatched = new Receipt(connector, "R1", "D1", null, new BigDecimal("156"),
                paidAt, "CONFIRMED", paidAt, "h1");
        assertThat(pay(unmatched).state()).isEqualTo("MAPPING_PENDING");
        intake("D1", 1, "156", "1");
        db.update("UPDATE order_sync_source SET state='NEW'");
        order(1, 1, "156", "156", "1");
        db.update("INSERT INTO order_payment_record(id,tenant_id,connector_id,source_system_code,source_document_no,"
                + "order_id,customer_id,paid_amount,payment_time,payment_status_code,revision,deleted)"
                + " VALUES(100,?,?,'DINGHUOBAO','R1',1,1,156,?,'CHECKED',1,0)",
                tenant, connector.toString(), java.time.LocalDateTime.ofInstant(paidAt, java.time.ZoneOffset.UTC));
        var beforePayment = db.queryForMap("SELECT * FROM order_payment_record WHERE id=100");
        var beforeReceipt = db.queryForMap("SELECT * FROM order_sync_receipt WHERE receipt_no='R1'");
        var replay = receipt("R1", "D1", "156", "CONFIRMED", "h1");

        assertThat(pay(replay).state()).isEqualTo("NEW");
        assertThat(pay(replay).state()).isEqualTo("NEW");
        var saved = db.queryForMap("SELECT * FROM order_sync_receipt WHERE receipt_no='R1'");
        assertThat(saved.get("state")).isEqualTo("NEW");
        assertThat(((Number) saved.get("customer_id")).longValue()).isEqualTo(1);
        assertThat(((Number) saved.get("revision")).intValue()).isEqualTo(1);
        assertThat((BigDecimal) saved.get("amount")).isEqualByComparingTo("156");
        assertThat(saved.get("occurred_at")).isEqualTo(beforeReceipt.get("occurred_at"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sync_receipt_revision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM order_payment_record WHERE id=100")).isEqualTo(beforePayment);
        db.update("UPDATE order_sync_receipt SET state='SYNCED' WHERE receipt_no='R1'");
        assertThat(pay(replay).state()).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT state FROM order_sync_receipt WHERE receipt_no='R1'", String.class)).isEqualTo("SYNCED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM order_payment_record WHERE id=100")).isEqualTo(beforePayment);
    }

    @Test
    void unchangedReceiptDoesNotActivateMappingWithPendingSourceChange() {
        pay(new Receipt(connector, "R1", "D1", null, new BigDecimal("156"),
                paidAt, "CONFIRMED", paidAt, "h1"));
        intake("D1", 1, "156", "1");
        db.update("UPDATE order_sync_source SET state='NEW'");
        db.update("UPDATE order_sync_receipt SET pending_payload='{}',pending_checksum='review'");

        assertThat(pay(receipt("R1", "D1", "156", "CONFIRMED", "h1")).state()).isEqualTo("MAPPING_PENDING");
        assertThat(db.queryForObject("SELECT state FROM order_sync_receipt", String.class)).isEqualTo("MAPPING_PENDING");
        assertThat(db.queryForObject("SELECT customer_id FROM order_sync_receipt", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isZero();
    }

    @Test
    void performanceExcludesPendingCancelledAndRetiredOrderPayments() {
        order(1, 1, "1000", "0", "10");
        order(2, 1, "1000", "0", "10");
        db.update("UPDATE order_sales_order SET deleted=1 WHERE id=2");
        int id=1;
        for(String status:List.of("RECEIVED","CHECKED","PENDING","CANCELLED"))
            db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,collector_staff_code,paid_amount,payment_time,payment_status_code,deleted) VALUES(?,?,1,'ZHANG',100,?,?,0)",id++,tenant,java.sql.Timestamp.from(paidAt),status);
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,collector_staff_code,paid_amount,payment_time,payment_status_code,deleted) VALUES(5,?,2,'ZHANG',100,?,'CHECKED',0)",tenant,java.sql.Timestamp.from(paidAt));
        var performance=store.performance(tenant,"2026-09");
        assertThat(performance.receipts()).hasSize(1);
        assertThat((BigDecimal)performance.receipts().getFirst().get("amount")).isEqualByComparingTo("200");
        assertThat((BigDecimal)performance.pending().get("product_pending_amount")).isEqualByComparingTo("200");
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
