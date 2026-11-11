package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class VoucherRecognitionMySqlTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4");
    @Test void durableJobsNeverReviveRemovedImagesOrOverwriteHumanEvidence() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE order_payment_record(tenant_id VARCHAR(36),id BIGINT,deleted INT,voucher_keys_json JSON,paid_amount DECIMAL(18,2),payment_time DATETIME(6),payment_status_code VARCHAR(24)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        jdbc.execute("CREATE TABLE order_sync_receipt(id INT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        try(var c=ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V51__payment_voucher_transactions.sql"));
            jdbc.execute("ALTER TABLE order_payment_voucher_transaction ADD voucher_paid_time DATETIME(6), ADD voucher_paid_time_precision VARCHAR(8)");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V62__voucher_recognition_and_paid_time.sql"));
        }
        jdbc.update("INSERT INTO order_payment_record VALUES('a',1,0,'[\"a/image\"]',156,'2026-10-26','CHECKED'),('a',2,0,'[\"a/image\"]',78,'2026-10-26','CHECKED'),('b',3,0,'[\"b/image\"]',156,'2026-10-26','CHECKED')");
        var money=jdbc.queryForList("SELECT * FROM order_payment_record");
        var queue=new JdbcVoucherRecognitionStore(jdbc);
        JdbcVoucherRecognitionStore.enqueue(jdbc,"a",1,"a/image");
        JdbcVoucherRecognitionStore.enqueue(jdbc,"a",1,"a/image");
        JdbcVoucherRecognitionStore.enqueue(jdbc,"a",2,"a/image");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_voucher_recognition_job",Integer.class)).isEqualTo(1);
        var job=queue.claim();assertThat(queue.claim()).isNull();
        String result="""
            {"amount":"156.00","transactionNo":"1234567890","paidTime":"2026-10-10T12:37:52Z","paidTimePrecision":"SECOND","recognitionStatus":"RECOGNIZED","payee":"公司","paymentResult":"SUCCESS","note":"OCR"}
            """;
        jdbc.update("UPDATE order_payment_voucher_transaction SET created_by='HUMAN',voucher_amount=78 WHERE payment_id=2");
        queue.complete(job,JsonMapper.builder().build().readTree(result),result);
        assertThat(jdbc.queryForObject("SELECT transaction_no FROM order_payment_voucher_transaction WHERE payment_id=1",String.class)).isEqualTo("1234567890");
        assertThat(jdbc.queryForObject("SELECT voucher_amount FROM order_payment_voucher_transaction WHERE payment_id=2",java.math.BigDecimal.class)).isEqualByComparingTo("78");
        assertThat(jdbc.queryForList("SELECT * FROM order_payment_record")).isEqualTo(money);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_voucher_transaction WHERE tenant_id='b'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT transaction_no FROM order_payment_voucher_transaction WHERE payment_id=2",String.class)).isEqualTo("1234567890");
        assertThat(jdbc.queryForObject("SELECT created_by FROM order_payment_voucher_transaction WHERE payment_id=2",String.class)).isEqualTo("HUMAN");
        var evidence=jdbc.queryForList("SELECT * FROM order_payment_voucher_transaction");
        queue.complete(job,JsonMapper.builder().build().readTree(result.replace("156.00","999.00")),result);
        assertThat(jdbc.queryForList("SELECT * FROM order_payment_voucher_transaction")).isEqualTo(evidence);
        JdbcVoucherRecognitionStore.enqueue(jdbc,"b",3,"b/image");
        var removed=queue.claim();jdbc.update("UPDATE order_payment_record SET voucher_keys_json='[]' WHERE id=3");
        queue.complete(removed,JsonMapper.builder().build().readTree(result),result);
        assertThat(jdbc.queryForObject("SELECT transaction_no FROM order_payment_voucher_transaction WHERE payment_id=3",String.class)).isNull();
        jdbc.update("INSERT INTO order_payment_record VALUES('a',4,0,'[\"a/new1\",\"a/new2\"]',20,'2026-10-26','CHECKED')");
        assertThat(queue.discoverUnrecognized(1)).isEqualTo(1);
        assertThat(queue.discoverUnrecognized(100)).isEqualTo(1);
        assertThat(queue.discoverUnrecognized(100)).isZero();
        jdbc.update("UPDATE order_voucher_recognition_job SET status='FAILED',attempts=3,updated_time=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 DAY) WHERE voucher_key='a/new1'");
        assertThat(queue.discoverUnrecognized(100)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM order_voucher_recognition_job WHERE voucher_key='a/new1'",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM order_voucher_recognition_job WHERE voucher_key='a/new1'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_voucher_transaction WHERE payment_id=4",Integer.class)).isEqualTo(2);
        // 历史证据已有金额/单号，仅缺时间：自动入队补缺，不覆盖人工确认值，也不无限重试模糊结果。
        jdbc.update("INSERT INTO order_payment_record VALUES('a',5,0,'[\"a/history\"]',10,'2026-10-26','CHECKED')");
        jdbc.update("INSERT INTO order_payment_voucher_transaction(tenant_id,payment_id,voucher_key,voucher_amount,transaction_no,created_by)"
                + " VALUES('a',5,'a/history',10,'HUMAN-NUMBER','HUMAN')");
        assertThat(queue.discoverUnrecognized(100)).isEqualTo(1);
        jdbc.update("UPDATE order_voucher_recognition_job SET status='SUCCEEDED',result_json=JSON_OBJECT('ruleScriptSha256',?) WHERE voucher_key<>'a/history'",JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT);
        var historyJob=queue.claim();
        queue.complete(historyJob,JsonMapper.builder().build().readTree(result),result);
        var history=jdbc.queryForMap("SELECT * FROM order_payment_voucher_transaction WHERE payment_id=5");
        assertThat(history).containsEntry("transaction_no","HUMAN-NUMBER").containsEntry("created_by","HUMAN");
        assertThat((java.math.BigDecimal)history.get("voucher_amount")).isEqualByComparingTo("10");
        assertThat(history.get("voucher_paid_time")).isNotNull();
        // An old successful result missing fields is retried once per script, not forever.
        jdbc.update("UPDATE order_voucher_recognition_job SET result_json=JSON_OBJECT('engine','rules-1') WHERE voucher_key='a/new1'");
        assertThat(queue.discoverUnrecognized(100)).isEqualTo(1);
        var upgraded=queue.claim();
        String ambiguous="{\"recognitionStatus\":\"REVIEW\",\"ruleScriptSha256\":\""+JdbcVoucherRecognitionStore.SCRIPT_FINGERPRINT+"\"}";
        queue.complete(upgraded,JsonMapper.builder().build().readTree(ambiguous),ambiguous);
        assertThat(queue.discoverUnrecognized(100)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM order_payment_record WHERE id<=2")).isEqualTo(money.subList(0,2));
        assertThat(queue.discoverUnrecognized(100)).isZero();
        assertThat(jdbc.queryForObject("SELECT created_by FROM order_payment_voucher_transaction WHERE payment_id=2",String.class)).isEqualTo("HUMAN");
    }
}
