package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import com.rigour.order.api.v1.model.HistorySyncModels.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class VoucherBackfillMySqlTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4");
    @Test void scopedClaimsSkipImagesAndCooldownAndStaleResultsWithoutChangingMoney() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var db=new JdbcTemplate(ds);var connector=UUID.randomUUID();var other=UUID.randomUUID();
        db.execute("CREATE TABLE order_payment_record(id BIGINT PRIMARY KEY,tenant_id VARCHAR(36),connector_id VARCHAR(36),source_system_code VARCHAR(32),source_record_id VARCHAR(128),source_document_no VARCHAR(128),sales_order_no_snapshot VARCHAR(128),deleted INT,voucher_keys_json JSON,paid_amount DECIMAL(18,2)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        db.execute("CREATE TABLE order_sync_allocation(tenant_id VARCHAR(36),connector_id VARCHAR(36),payment_id BIGINT,receipt_no VARCHAR(128)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        db.execute("CREATE TABLE order_sync_receipt(tenant_id VARCHAR(36),connector_id VARCHAR(36),receipt_no VARCHAR(128),source_order_no VARCHAR(128)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V64__payment_voucher_backfill.sql"));}
        db.update("INSERT INTO order_payment_record VALUES(1,'a',?,'DINGHUOBAO','FR1','FR1','DH1',0,'[]',156),(2,'a',?,'DINGHUOBAO','FR2','FR2','DH1',0,'[\"a/image\"]',78),(3,'b',?,'DINGHUOBAO','FR3','FR3','DH3',0,'[]',9),(4,'a',?,'DINGHUOBAO','FR4','FR4','DH4',0,'[]',8),(5,'a',NULL,'FEISHU',NULL,NULL,'OLD',0,'[]',7)",connector.toString(),connector.toString(),connector.toString(),other.toString());
        db.update("INSERT INTO order_sync_allocation VALUES('a',?,5,'FR5')",connector.toString());
        db.update("INSERT INTO order_sync_receipt VALUES('a',?,'FR5','DH5')",connector.toString());
        var before=db.queryForList("SELECT * FROM order_payment_record ORDER BY id");
        var store=new JdbcOrderHistorySyncStore(db,null,null,null);var start=Instant.now().minusSeconds(1);
        var first=store.claimVoucherBackfill("a",new VoucherBackfillClaim(connector,1,start));
        assertThat(first).hasSize(1);assertThat(first.getFirst().paymentId()).isEqualTo(1);
        assertThat(first.getFirst().receipts()).containsExactly(new VoucherReceiptReference("FR1","DH1"));
        var second=store.claimVoucherBackfill("a",new VoucherBackfillClaim(connector,50,start));
        assertThat(second).hasSize(1);assertThat(second.getFirst().paymentId()).isEqualTo(5);
        assertThat(second.getFirst().receipts()).containsExactly(new VoucherReceiptReference("FR5","DH5"));
        assertThat(store.claimVoucherBackfill("a",new VoucherBackfillClaim(connector,50,start))).isEmpty();
        store.finishVoucherBackfill("a",new VoucherBackfillResult(connector,1,first.getFirst().leaseToken(),"SOURCE_EMPTY"));
        db.update("UPDATE order_payment_voucher_backfill SET next_check_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE payment_id=1");
        // Even a long-running scan must not revisit its already-checked failures indefinitely.
        assertThat(store.claimVoucherBackfill("a",new VoucherBackfillClaim(connector,50,start))).isEmpty();
        db.update("UPDATE order_payment_voucher_backfill SET checked_at=NULL WHERE payment_id=1");
        var retry=store.claimVoucherBackfill("a",new VoucherBackfillClaim(connector,1,Instant.now())).getFirst();
        store.finishVoucherBackfill("a",new VoucherBackfillResult(connector,1,first.getFirst().leaseToken(),"SYNCED"));
        assertThat(db.queryForObject("SELECT state FROM order_payment_voucher_backfill WHERE payment_id=1",String.class)).isEqualTo("RUNNING");
        store.finishVoucherBackfill("a",new VoucherBackfillResult(connector,1,retry.leaseToken(),"FAILED"));
        assertThat(db.queryForObject("SELECT TIMESTAMPDIFF(MINUTE,checked_at,next_check_at) FROM order_payment_voucher_backfill WHERE payment_id=1",Integer.class)).isEqualTo(15);
        assertThat(db.queryForList("SELECT * FROM order_payment_record ORDER BY id")).isEqualTo(before);
    }
}
