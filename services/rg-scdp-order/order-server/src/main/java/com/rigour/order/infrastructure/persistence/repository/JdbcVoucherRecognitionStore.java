package com.rigour.order.infrastructure.persistence.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Durable OCR jobs contain only tenant-owned keys; known evidence is preserved while missing fields are enriched. */
@Repository
public class JdbcVoucherRecognitionStore {
    public static final String SCRIPT_FINGERPRINT = scriptFingerprint();
    private final JdbcTemplate jdbc;
    public JdbcVoucherRecognitionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static void enqueue(JdbcTemplate jdbc, String tenant, long paymentId, String key) {
        int added = jdbc.update("INSERT IGNORE INTO order_payment_voucher_transaction"
                + " (tenant_id,payment_id,voucher_key,created_by,recognition_status) VALUES(?,?,?,'VOUCHER_OCR','PENDING')", tenant,paymentId,key);
        if (added == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_voucher_transaction"
                + " WHERE tenant_id=? AND payment_id=? AND voucher_key=?"
                + " AND (transaction_no IS NULL OR transaction_no='' OR voucher_paid_time IS NULL)",Integer.class,tenant,paymentId,key)==0) return;
        String id;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((tenant+"\n"+key).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        jdbc.update("INSERT INTO order_voucher_recognition_job(id,tenant_id,voucher_key) VALUES(?,?,?)"
                + " ON DUPLICATE KEY UPDATE status=IF(status='SUCCEEDED','PENDING',status),next_attempt_at=UTC_TIMESTAMP(6)",id,tenant,key);
    }
    @Transactional
    public int discoverUnrecognized(int limit) {
        // Failed items retry automatically on the next day, without retrying an ambiguous OCR result.
        jdbc.update("UPDATE order_voucher_recognition_job SET status='PENDING',attempts=0,next_attempt_at=UTC_TIMESTAMP(6)"
                + " WHERE status='FAILED' AND updated_time<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY) LIMIT 25");
        var rows = jdbc.queryForList("SELECT p.tenant_id,p.id,j.voucher_key FROM order_payment_record p"
                + " JOIN JSON_TABLE(COALESCE(p.voucher_keys_json,JSON_ARRAY()), '$[*]' COLUMNS(voucher_key VARCHAR(512) PATH '$')) j"
                + " WHERE p.deleted=0 AND NOT EXISTS(SELECT 1 FROM order_payment_voucher_transaction e"
                + " WHERE e.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id COLLATE utf8mb4_general_ci"
                + " AND e.payment_id=p.id AND e.voucher_key COLLATE utf8mb4_general_ci=j.voucher_key COLLATE utf8mb4_general_ci"
                + " AND (NULLIF(e.transaction_no,'') IS NOT NULL AND e.voucher_paid_time IS NOT NULL"
                + " OR EXISTS(SELECT 1 FROM order_voucher_recognition_job v WHERE v.tenant_id=e.tenant_id"
                + " AND v.voucher_key=e.voucher_key AND (v.status<>'SUCCEEDED'"
                + " OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(v.result_json,'$.ruleScriptSha256')),'')=?))))"
                + " ORDER BY p.id LIMIT ?", SCRIPT_FINGERPRINT, Math.max(1,Math.min(100,limit)));
        for (var row : rows) enqueue(jdbc,(String)row.get("tenant_id"),((Number)row.get("id")).longValue(),(String)row.get("voucher_key"));
        return rows.size();
    }

    @Transactional
    public Map<String,Object> claim() {
        var rows=jdbc.queryForList("SELECT * FROM order_voucher_recognition_job WHERE"
                + " (status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6))"
                + " OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6))"
                + " ORDER BY created_time LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty()) return null;
        var job=rows.getFirst(); String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE order_voucher_recognition_job SET status='RUNNING',lease_token=?,"
                + " lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),attempts=attempts+1,updated_time=UTC_TIMESTAMP(6) WHERE id=?",token,job.get("id"));
        job.put("lease_token",token); return job;
    }
    @Transactional
    public void complete(Map<String,Object> job, JsonNode result, String json) {
        var locked=jdbc.queryForList("SELECT id,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.note')) AS old_note FROM order_voucher_recognition_job WHERE id=? AND lease_token=? AND status='RUNNING' FOR UPDATE",job.get("id"),job.get("lease_token"));
        if(locked.isEmpty()) return;
        String tenant=(String)job.get("tenant_id"),key=(String)job.get("voucher_key");
        // Re-check the current attachment set after OCR: a cancelled/removed image cannot reappear.
        jdbc.update("UPDATE order_payment_voucher_transaction e JOIN order_payment_record p"
                + " ON p.tenant_id=? AND p.id=e.payment_id SET e.voucher_amount=COALESCE(e.voucher_amount,?),e.transaction_no=COALESCE(NULLIF(e.transaction_no,''),?),"
                + " e.voucher_paid_time_precision=IF(e.voucher_paid_time IS NULL,?,e.voucher_paid_time_precision),"
                + " e.voucher_paid_time=COALESCE(e.voucher_paid_time,?),e.recognition_status=?,"
                + " e.payee=COALESCE(NULLIF(e.payee,''),?),e.payment_result=COALESCE(NULLIF(e.payment_result,''),?),"
                + " e.evidence_note=IF(e.evidence_note=? OR e.evidence_note='自动识别暂未完成，请核对原图或稍后重试',?,COALESCE(NULLIF(e.evidence_note,''),?))"
                + " WHERE e.tenant_id=? AND e.voucher_key=? AND p.deleted=0"
                + " AND JSON_CONTAINS(p.voucher_keys_json,JSON_QUOTE(e.voucher_key))",
                tenant,value(result,"amount"),value(result,"transactionNo"),value(result,"paidTimePrecision"),
                value(result,"paidTime")==null?null:LocalDateTime.ofInstant(Instant.parse(value(result,"paidTime")),ZoneOffset.UTC),value(result,"recognitionStatus"),value(result,"payee"),value(result,"paymentResult"),
                locked.getFirst().get("old_note"),value(result,"note"),value(result,"note"),tenant,key);
        jdbc.update("UPDATE order_voucher_recognition_job SET status='SUCCEEDED',result_json=?,error_code=NULL,lease_token=NULL,lease_until=NULL,updated_time=UTC_TIMESTAMP(6) WHERE id=?",json,job.get("id"));
    }
    @Transactional
    public void fail(Map<String,Object> job, String code) {
        int changed=jdbc.update("UPDATE order_voucher_recognition_job SET status=IF(attempts>=3,'FAILED','PENDING'),"
                + "error_code=?,lease_token=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),"
                + "updated_time=UTC_TIMESTAMP(6) WHERE id=? AND lease_token=?",code,job.get("id"),job.get("lease_token"));
        if(changed>0) jdbc.update("UPDATE order_payment_voucher_transaction SET recognition_status='FAILED',evidence_note='自动识别暂未完成，请核对原图或稍后重试'"
                + " WHERE tenant_id=? AND voucher_key=? AND created_by='VOUCHER_OCR' AND voucher_amount IS NULL",job.get("tenant_id"),job.get("voucher_key"));
    }
    private static String value(JsonNode r,String key) { var n=r.get(key);return n==null||n.isNull()?null:n.asText(); }
    private static String scriptFingerprint() {
        try (var script=JdbcVoucherRecognitionStore.class.getResourceAsStream("/ocr/recognize_voucher.py")) {
            if (script==null) throw new IllegalStateException("OCR_SCRIPT_MISSING");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("OCR_SCRIPT_UNAVAILABLE",e);
        }
    }
}
