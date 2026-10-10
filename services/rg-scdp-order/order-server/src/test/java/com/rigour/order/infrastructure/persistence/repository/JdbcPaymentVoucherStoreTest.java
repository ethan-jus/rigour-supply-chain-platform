package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

class JdbcPaymentVoucherStoreTest {
    @Test void looksUpEveryVoucherAcrossMonthsWithoutDoublingTheLegacyNumberAndIsolatesTenants() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE order_payment_record(tenant_id VARCHAR(36),id BIGINT,payment_no VARCHAR(80),order_id BIGINT,customer_name_snapshot VARCHAR(100),paid_amount DECIMAL(18,2),payment_time TIMESTAMP,payment_status_code VARCHAR(32),deleted INT,transaction_no VARCHAR(128))");
        jdbc.execute("CREATE TABLE order_sales_order(tenant_id VARCHAR(36),id BIGINT,order_no VARCHAR(80),owner_employee_name_snapshot VARCHAR(100),deleted INT)");
        jdbc.execute("CREATE TABLE order_payment_voucher_transaction(tenant_id VARCHAR(36),payment_id BIGINT,voucher_key VARCHAR(512),voucher_amount DECIMAL(18,2),transaction_no VARCHAR(128),evidence_note VARCHAR(1000))");
        jdbc.execute("INSERT INTO order_sales_order VALUES('a',1,'SO-1','销售',0),('a',2,'SO-2','销售',0),('b',3,'SECRET','其他租户',0)");
        jdbc.execute("INSERT INTO order_payment_record VALUES('a',11,'PAY-1',1,'客户',444.60,'2026-09-30 13:54:20','CHECKED',0,'TXN'),('a',12,'PAY-2',2,'客户',148.20,'2026-08-30 00:00:00','CANCELLED',1,'TXN'),('b',13,'SECRET',3,'保密',148.20,'2026-09-30 00:00:00','CHECKED',0,'TXN')");
        jdbc.execute("INSERT INTO order_payment_voucher_transaction VALUES('a',11,'a/first',148.20,'TXN','部分凭证'),('a',11,'a/second',296.40,NULL,'未显示单号'),('b',13,'b/secret',148.20,'TXN',NULL)");
        var scopes = org.mockito.Mockito.mock(OrderDataScope.class);
        org.mockito.Mockito.when(scopes.predicate("order:read", "o.", null))
                .thenReturn(new OrderDataScope.Predicate("1=1", java.util.List.of()));
        var store = new JdbcPaymentVoucherStore(jdbc, scopes);
        jdbc.execute("ALTER TABLE order_payment_record ADD voucher_keys_json VARCHAR(2000)");
        jdbc.update("UPDATE order_payment_record SET voucher_keys_json=? WHERE tenant_id='a' AND id=11", "[\"a/first\",\"a/second\",\"a/unrecognized\"]");
        assertThat(store.attachmentKeys("a", 11)).containsExactly("a/first", "a/second", "a/unrecognized");
        assertThat(store.attachmentKeys("b", 11)).isEmpty();
        var result = store.transactionMatches("a", "TXN");
        assertThat(result).hasSize(2);
        assertThat(result).extracting(v -> v.paymentNo()).containsExactly("PAY-1", "PAY-2");
        assertThat(result.get(0).voucherAmount()).isEqualByComparingTo("148.20");
        assertThat(result.get(1).deleted()).isTrue();
        assertThat(store.transactionMatches("a", "TX")).isEmpty();
        assertThat(store.vouchers("a", 11)).hasSize(2);
        assertThat(store.vouchers("b", 11)).isEmpty();
        jdbc.update("UPDATE order_payment_record SET transaction_no=NULL WHERE tenant_id='a' AND id=11");
        assertThat(store.transactionMatches("a", "TXN")).hasSize(2);
        org.mockito.Mockito.when(scopes.predicate("order:read", "o.", null))
                .thenReturn(new OrderDataScope.Predicate("o.id=?", java.util.List.of(2L)));
        assertThat(store.transactionMatches("a", "TXN")).hasSize(1);
        assertThat(store.vouchers("a", 11)).isEmpty();
    }
}
