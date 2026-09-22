package com.rigour.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

/** 在与共享环境同类的MySQL事务及约束下重复验证历史资金接续。 */
@Testcontainers
class HistoryOrderSyncMySqlTest extends HistoryOrderSyncTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Override
    DataSource dataSource() {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds);
        for (String table :
                java.util.List.of(
                        "order_refund_record", "order_financial_event", "order_fulfillment_execution",
                        "order_fund_document", "order_sales_shipment", "order_invoice", "order_number_mapping",
                        "order_history_reconciliation_audit",
                        "order_sync_product_allocation",
                        "order_sync_allocation",
                        "order_sync_receipt_revision",
                        "order_sync_receipt",
                        "order_history_member",
                        "order_history_group",
                        "order_sync_source",
                        "order_payment_record",
                        "order_sales_order_line",
                        "order_sales_order")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        return ds;
    }

    private void cancellationColumns() {
        db.execute("ALTER TABLE order_sales_order ADD updated_by VARCHAR(64)");
        db.execute("ALTER TABLE order_sales_order_line ADD revision INT DEFAULT 0, ADD updated_by VARCHAR(64), ADD updated_time TIMESTAMP");
    }

    @org.junit.jupiter.api.Test
    void sourceCancellationDeletesAllProjectionsAndIsIdempotent() {
        order(1, 1, "78", "0", "1");
        order(2, 1, "78", "0", "1");
        intake("D1", 1, "78", "1");
        db.update("UPDATE order_sales_order SET source_system_code='DINGHUOBAO',source_order_no='D1' WHERE id=1");
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,paid_amount,revision,deleted) VALUES(100,?,1,78,0,0)", tenant);
        cancellationColumns();
        var command = new com.rigour.order.api.v1.model.HistorySyncModels.CancelSourceOrder(connector, "D1");
        tx.executeWithoutResult(s -> store.cancelSourceOrder(tenant, "tester", command));
        tx.executeWithoutResult(s -> store.cancelSourceOrder(tenant, "tester", command));
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT revision FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=2", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_sales_order_line WHERE order_id=1 AND deleted=0", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_payment_record WHERE id=100", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(pay(receipt("R1", "D1", "78", "CONFIRMED", "r1")).state()).isEqualTo("ORDER_CANCELLED");
        org.assertj.core.api.Assertions.assertThat(tx.execute(s -> store.sourceOrder(tenant, source("D1", 1, "78", "1"))).state()).isEqualTo("CANCELLED");
    }

    @org.junit.jupiter.api.Test
    void cancellationDoesNotDeleteAnotherTenantOrSharedActiveHistoricalOrder() {
        order(1, 1, "156", "0", "2");
        intake("D1", 1, "78", "1");
        intake("D2", 1, "78", "1");
        bind(java.util.List.of("D1", "D2"), java.util.List.of(base(1, "0")));
        cancellationColumns();
        var first = new com.rigour.order.api.v1.model.HistorySyncModels.CancelSourceOrder(connector, "D1");
        tx.executeWithoutResult(s -> store.cancelSourceOrder(java.util.UUID.randomUUID().toString(), "tester", first));
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT state FROM order_sync_source WHERE source_no='D1'", String.class)).isEqualTo("BOUND");
        org.assertj.core.api.Assertions.assertThat(tx.execute(s -> store.cancelSourceOrder(tenant, "tester", first)).state()).isEqualTo("CANCELLATION_REVIEW");
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=1", Integer.class)).isZero();
        tx.executeWithoutResult(s -> store.cancelSourceOrder(tenant, "tester", new com.rigour.order.api.v1.model.HistorySyncModels.CancelSourceOrder(connector, "D2")));
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=1", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SPLIT", "MERGE", "EXPAND"})
    void normalizesSplitGroupAtomicallyAndPreservesPaymentFacts(String shape) throws Exception {
        boolean merge = shape.equals("MERGE"), expand = shape.equals("EXPAND");
        order(1, 1, expand ? "234" : "156", "156", expand ? "3" : "2");
        if (!expand) order(2, 1, "78", "0", "1");
        intake("D1", 1, merge ? "234" : "78", merge ? "3" : "1");
        if (!merge) intake("D2", 1, "156", "2");
        String group = bind(merge ? java.util.List.of("D1") : java.util.List.of("D1", "D2"),
                expand ? java.util.List.of(base(1, "156")) : java.util.List.of(base(1, "156"), base(2, "0")));
        db.execute("ALTER TABLE order_sales_order ADD original_amount DECIMAL(20,6) DEFAULT 0, ADD updated_by VARCHAR(64), ADD payment_time TIMESTAMP");
        db.execute("ALTER TABLE order_sales_order_line ADD revision INT DEFAULT 0, ADD updated_by VARCHAR(64), ADD updated_time TIMESTAMP");
        db.execute("ALTER TABLE order_payment_record MODIFY id BIGINT AUTO_INCREMENT, ADD transaction_no VARCHAR(128)");
        db.update("INSERT INTO order_payment_record(id,tenant_id,order_id,payment_no,source_system_code,source_document_no,source_record_id,paid_amount,payment_time,collector_staff_code,payment_status_code,revision,deleted) VALUES(100,?,1,'PAY100','FEISHU','F100','F100',156,?,'EMP1','RECEIVED',0,0)", tenant, java.time.LocalDateTime.ofInstant(date, java.time.ZoneOffset.UTC));
        for (String table : java.util.List.of("order_refund_record", "order_financial_event", "order_fulfillment_execution"))
            db.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(36),order_id BIGINT)");
        db.execute("CREATE TABLE order_fund_document(tenant_id VARCHAR(36),related_order_id BIGINT,deleted INT)");
        for (String table : java.util.List.of("order_sales_shipment", "order_invoice"))
            db.execute("CREATE TABLE " + table + "(tenant_id VARCHAR(36),sales_order_id BIGINT)");
        try (var connection = db.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("db/migration/V48__history_order_reconciliation_audit.sql"));
        }
        org.mockito.Mockito.when(salesOrders.update(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            long id = call.getArgument(0); var total = new java.math.BigDecimal(merge ? "234" : id == 1 ? "78" : "156");
            db.update("UPDATE order_sales_order SET original_amount=?,payable_amount=?,revision=revision+1 WHERE tenant_id=? AND id=?", total, total, tenant, id);
            var view = org.mockito.Mockito.mock(com.rigour.order.api.v1.model.SalesOrderDetailView.class);
            org.mockito.Mockito.when(view.id()).thenReturn(id); return view;
        });
        org.mockito.Mockito.when(salesOrders.create(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var row = new java.util.LinkedHashMap<>(db.queryForMap("SELECT * FROM order_sales_order WHERE id=1"));
            row.put("id", 2L); row.put("source_system_code", "DINGHUOBAO"); row.put("source_order_no", "D2");
            row.put("order_no", "O2"); row.put("paid_amount", java.math.BigDecimal.ZERO); row.put("revision", 0);
            row.put("original_amount", new java.math.BigDecimal("156")); row.put("payable_amount", new java.math.BigDecimal("156"));
            new org.springframework.jdbc.core.simple.SimpleJdbcInsert(db).withTableName("order_sales_order").usingColumns(row.keySet().toArray(String[]::new)).execute(row);
            var view = org.mockito.Mockito.mock(com.rigour.order.api.v1.model.SalesOrderDetailView.class);
            org.mockito.Mockito.when(view.id()).thenReturn(2L); return view;
        });
        var targets = new java.util.ArrayList<com.rigour.order.api.v1.model.HistorySyncModels.SourceTarget>();
        var firstSource = source("D1", 1, merge ? "234" : "78", merge ? "3" : "1");
        targets.add(new com.rigour.order.api.v1.model.HistorySyncModels.SourceTarget(new com.rigour.order.api.v1.model.HistorySyncModels.SourceRef(connector, "D1", 1), 1L, firstSource.order(), firstSource));
        if (!merge) targets.add(new com.rigour.order.api.v1.model.HistorySyncModels.SourceTarget(new com.rigour.order.api.v1.model.HistorySyncModels.SourceRef(connector, "D2", 1), expand ? null : 2L, source("D2", 1, "156", "2").order(), source("D2", 1, "156", "2")));
        var orderRefs = expand ? java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.HistoryOrderRef(1, 0)) : java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.HistoryOrderRef(1, 0), new com.rigour.order.api.v1.model.HistorySyncModels.HistoryOrderRef(2, 0));
        var portions = merge ? java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentPortion("D1", new java.math.BigDecimal("156"))) : java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentPortion("D1", new java.math.BigDecimal("78")), new com.rigour.order.api.v1.model.HistorySyncModels.PaymentPortion("D2", new java.math.BigDecimal("78")));
        var command = new com.rigour.order.api.v1.model.HistorySyncModels.NormalizeGroup(java.util.UUID.randomUUID(), connector, group,
                orderRefs, targets, java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentSplit(100, 0, portions)), "用户确认一单一单，回款按来源日期先后冲抵拆分并保留原值");
        var bad = new com.rigour.order.api.v1.model.HistorySyncModels.NormalizeGroup(command.operationId(), connector, group, command.orders(), targets,
                java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentSplit(100, 0, java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentPortion("D1", java.math.BigDecimal.ONE)))), command.evidence());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> store.normalizeGroup(tenant, "tester", bad))).isInstanceOf(IllegalArgumentException.class);
        if (!merge) {
            var lateFailure = new com.rigour.order.api.v1.model.HistorySyncModels.NormalizeGroup(command.operationId(), connector, group, command.orders(), targets,
                    java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentSplit(100, 0, java.util.List.of(new com.rigour.order.api.v1.model.HistorySyncModels.PaymentPortion("D1", new java.math.BigDecimal("156"))))), command.evidence());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> store.normalizeGroup(tenant, "tester", lateFailure))).isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT revision FROM order_sales_order WHERE id=1", Integer.class)).isZero();
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record", Integer.class)).isEqualTo(1);
        }
        var result = tx.execute(s -> store.normalizeGroup(tenant, "tester", command));
        org.assertj.core.api.Assertions.assertThat(result).containsEntry("D1", 1L).hasSize(merge ? 1 : 2);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT SUM(paid_amount) FROM order_payment_record WHERE tenant_id=? AND deleted=0", java.math.BigDecimal.class, tenant)).isEqualByComparingTo("156");
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record WHERE tenant_id=? AND collector_staff_code='EMP1' AND payment_time=?", Integer.class, tenant, java.time.LocalDateTime.ofInstant(date, java.time.ZoneOffset.UTC))).isEqualTo(merge ? 1 : 2);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_history_group WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(merge ? 1 : 2);
        if (merge) org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT deleted FROM order_sales_order WHERE id=2", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_history_reconciliation_audit WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        var replayed = tx.execute(s -> store.normalizeGroup(tenant, "tester", command));
        org.assertj.core.api.Assertions.assertThat(replayed).isEqualTo(result);
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT COUNT(*) FROM order_payment_record WHERE tenant_id=?", Integer.class, tenant)).isEqualTo(merge ? 1 : 2);
    }
}
