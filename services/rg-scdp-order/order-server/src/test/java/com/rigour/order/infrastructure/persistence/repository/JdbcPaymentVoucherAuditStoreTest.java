package com.rigour.order.infrastructure.persistence.repository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class JdbcPaymentVoucherAuditStoreTest {
    @Test void tenantAndActionScopesAreAppliedAndReviewsRemainAppendOnly() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE order_payment_record(tenant_id VARCHAR(36),id BIGINT,payment_no VARCHAR(80),order_id BIGINT,customer_name_snapshot VARCHAR(100),paid_amount DECIMAL(18,2),payment_time TIMESTAMP,payment_status_code VARCHAR(32),deleted INT,transaction_no VARCHAR(128),voucher_keys_json VARCHAR(2000))");
        jdbc.execute("CREATE TABLE order_sales_order(tenant_id VARCHAR(36),id BIGINT,order_no VARCHAR(80),owner_employee_name_snapshot VARCHAR(100),deleted INT)");
        jdbc.execute("CREATE TABLE order_payment_voucher_transaction(tenant_id VARCHAR(36),payment_id BIGINT,voucher_key VARCHAR(512),voucher_amount DECIMAL(18,2),transaction_no VARCHAR(128),evidence_note VARCHAR(1000))");
        jdbc.execute("CREATE TABLE order_payment_voucher_review(id VARCHAR(36),tenant_id VARCHAR(36),group_key VARCHAR(80),fingerprint VARCHAR(64),conclusion VARCHAR(32),note VARCHAR(1000),actor VARCHAR(128),payment_ids_json VARCHAR(2000),created_time TIMESTAMP)");
        jdbc.execute("INSERT INTO order_sales_order VALUES('a',1,'SO1','S',0),('a',2,'SO2','S',0),('b',3,'SECRET','X',0)");
        jdbc.execute("INSERT INTO order_payment_record VALUES('a',11,'P1',1,'C',10,'2026-09-01','CHECKED',0,'T','[\"a/image\"]'),('a',12,'P2',2,'C',10,'2026-08-01','CANCELLED',0,'T','[]'),('b',13,'SECRET',3,'X',10,'2026-09-01','CHECKED',0,'T','[]')");
        jdbc.execute("INSERT INTO order_payment_voucher_transaction VALUES('a',11,'a/image',10,'T',''),('a',11,'a/removed',999,'OLD',''),('b',11,'b/private',10,'PRIVATE','')");
        var scopes=mock(OrderDataScope.class);when(scopes.predicate("order:read","o.",null)).thenReturn(new OrderDataScope.Predicate("1=1",List.of()));
        when(scopes.predicate("order:payment:check","o.",null)).thenReturn(new OrderDataScope.Predicate("o.id=?",List.of(1L)));
        var store=new JdbcPaymentVoucherAuditStore(jdbc,scopes);
        var rows=store.payments("a","order:read");assertThat(rows).hasSize(2);assertThat(rows.get(0).evidence()).hasSize(1);assertThat(rows.get(1).excluded()).isTrue();
        assertThat(store.payments("a","order:payment:check")).hasSize(1);
        store.appendReview("a","G","F","NEED_EVIDENCE","first","U",List.of("11"));
        store.appendReview("a","G","F","NORMAL_COMBINED","second","U",List.of("11"));
        assertThat(store.reviews("a").get("G")).hasSize(2);assertThat(store.reviews("b")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_record",Integer.class)).isEqualTo(3);
    }
}
