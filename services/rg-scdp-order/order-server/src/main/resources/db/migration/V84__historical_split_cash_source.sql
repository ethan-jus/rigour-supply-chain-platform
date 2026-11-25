-- 历史凭证按订单/日期拆分后，source_document_no 带本地后缀，source_record_id 仍是原收款单。
-- 这些分配共用原来源现金，不再作为无来源到账重复计入；保留租户、连接器和显式核销优先级。
CREATE OR REPLACE VIEW order_cash_application_v AS
SELECT p.id,p.tenant_id,p.order_id,p.paid_amount amount,
  CASE WHEN s.receipt_no IS NOT NULL THEN MD5(CONCAT('sync:',s.connector_id,':',s.receipt_no))
       WHEN adv.cash_key IS NOT NULL THEN adv.cash_key
       WHEN NULLIF(TRIM(p.transaction_no),'') IS NOT NULL THEN MD5(CONCAT('bank:',TRIM(p.transaction_no)))
       ELSE MD5(CONCAT('payment:',p.id)) END COLLATE utf8mb4_general_ci cash_key,
  CASE WHEN a.order_id IS NOT NULL AND NULLIF(TRIM(s.source_order_no),'') IS NULL THEN GREATEST(COALESCE(s.occurred_at,p.payment_time),a.created_at)
       WHEN adv.cash_key IS NOT NULL THEN GREATEST(adv.cash_at,p.created_time,p.payment_time)
       ELSE COALESCE(s.occurred_at,p.payment_time) END effective_at,
  COALESCE(s.occurred_at,adv.cash_at,p.payment_time) cash_at,
  COALESCE(s.receipt_no,adv.reference_no,NULLIF(p.transaction_no,''),p.payment_no) COLLATE utf8mb4_general_ci reference_no,
  p.customer_id,p.customer_name_snapshot AS customer_name,p.payment_method_code channel,p.payment_owner_code owner_code,v.payee,
  COALESCE(p.checked_at,s.confirmed_at,IF(v.voucher_amount=p.paid_amount,v.verified_at,NULL)) verified_at,v.voucher_amount,
  IF((p.payment_status_code='CHECKED' OR p.checked_at IS NOT NULL OR s.source_status='CONFIRMED' OR s.confirmed_at IS NOT NULL OR v.voucher_amount=p.paid_amount)
      AND (s.receipt_no IS NULL OR s.pending_payload IS NULL) AND COALESCE(adv.matches,1)=1,1,0) verified,
  IF(s.receipt_no IS NULL AND adv.cash_key IS NULL,0,1) has_source
FROM order_payment_record p
LEFT JOIN order_sync_allocation a ON a.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id AND a.payment_id=p.id AND a.order_id=p.order_id
LEFT JOIN order_sync_receipt s ON s.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id AND s.connector_id COLLATE utf8mb4_general_ci=COALESCE(a.connector_id,p.connector_id)
 AND s.receipt_no COLLATE utf8mb4_general_ci=COALESCE(a.receipt_no,
   CASE WHEN p.source_system_code='DINGHUOBAO' AND NULLIF(p.source_record_id,'') IS NOT NULL
        AND LEFT(p.source_document_no,CHAR_LENGTH(p.source_record_id)+1)=CONCAT(p.source_record_id,'#')
        THEN p.source_record_id ELSE COALESCE(NULLIF(p.source_document_no,''),p.source_record_id) END)
LEFT JOIN (SELECT tenant_id,serial,COUNT(*) matches,MIN(cash_key) cash_key,MIN(cash_at) cash_at,MIN(reference_no) reference_no,MIN(customer_id) customer_id
           FROM order_cash_advance_v WHERE serial IS NOT NULL GROUP BY tenant_id,serial) adv
  ON s.receipt_no IS NULL AND adv.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id AND adv.serial COLLATE utf8mb4_general_ci=TRIM(p.transaction_no)
 AND (adv.customer_id=p.customer_id OR adv.customer_id IS NULL OR p.customer_id IS NULL)
LEFT JOIN (SELECT tenant_id,payment_id,IF(COUNT(DISTINCT voucher_amount)=1,MIN(voucher_amount),NULL) voucher_amount,
           IF(COUNT(DISTINCT payee)=1,MIN(payee),NULL) payee,MAX(created_time) verified_at
           FROM order_payment_voucher_transaction WHERE NULLIF(TRIM(evidence_note),'') IS NOT NULL AND created_by<>'SYSTEM'
           AND recognition_status IS NULL AND voucher_paid_time IS NOT NULL GROUP BY tenant_id,payment_id) v
  ON v.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id AND v.payment_id=p.id
WHERE p.deleted=0 AND p.payment_status_code IN ('RECEIVED','CHECKED') AND p.paid_amount>0
 AND (s.receipt_no IS NULL OR (s.source_status IN ('RECEIVED','CONFIRMED') AND s.state<>'CANCELLED'));
