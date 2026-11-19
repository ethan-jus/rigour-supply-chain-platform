-- 每个合同版本是完整的分期清单。按生效日重建历史，缺少协议时保留未知到期日。
CREATE TABLE order_receivable_installment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, order_id BIGINT NOT NULL,
    plan_version INT NOT NULL, installment_no INT NOT NULL, effective_at DATETIME(6) NOT NULL,
    due_date DATE NOT NULL, amount DECIMAL(24,6) NOT NULL, evidence VARCHAR(500) NOT NULL,
    created_by VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_receivable_plan(tenant_id,order_id,plan_version,installment_no),
    KEY ix_receivable_asof(tenant_id,order_id,effective_at),
    CHECK(amount>0 AND installment_no>0 AND plan_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 匹配订单所属租户列的实际排序规则，支持不同批次安装的数据库。
SET @plan_collation=(SELECT COLLATION_NAME FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_sales_order' AND COLUMN_NAME='tenant_id');
SET @plan_fk=CONCAT('ALTER TABLE order_receivable_installment MODIFY tenant_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE ',
                    @plan_collation,' NOT NULL, ADD CONSTRAINT fk_receivable_plan_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id)');
PREPARE plan_fk_stmt FROM @plan_fk;
EXECUTE plan_fk_stmt;
DEALLOCATE PREPARE plan_fk_stmt;

-- 有原始资金单的预收，后续按同一银行流水分配到订单，到账只计算一次。
CREATE VIEW order_cash_advance_v AS
SELECT f.id document_id,MD5(CONCAT('advance:',f.id)) cash_key,f.tenant_id COLLATE utf8mb4_general_ci tenant_id,
       f.document_no COLLATE utf8mb4_general_ci reference_no,f.customer_id,f.occurred_time cash_at,f.amount,
       f.settlement_method_code channel,f.bank_account_name payee,f.handler_staff_code owner_code,f.confirmed_at verified_at,
       NULLIF(TRIM(f.payment_serial_no),'') serial
FROM order_fund_document f
WHERE f.deleted=0 AND f.direction_code='RECEIPT' AND f.counterparty_type_code='CUSTOMER'
 AND f.document_status_code='CONFIRMED' AND f.business_type_code IN ('ADVANCE','PREPAYMENT','CUSTOMER_ADVANCE')
 AND f.related_order_id IS NULL AND f.amount>0
 AND NOT EXISTS (SELECT 1 FROM order_sync_receipt s WHERE s.tenant_id COLLATE utf8mb4_general_ci=f.tenant_id
   AND s.connector_id COLLATE utf8mb4_general_ci=f.connector_id AND s.receipt_no COLLATE utf8mb4_general_ci=f.source_document_no);

CREATE VIEW order_cash_application_v AS
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
 AND s.receipt_no COLLATE utf8mb4_general_ci=COALESCE(a.receipt_no,NULLIF(p.source_document_no,''),p.source_record_id)
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

CREATE VIEW order_cash_receipt_v AS
SELECT UNHEX(MD5(CONCAT('sync:',r.connector_id,':',r.receipt_no))) id,r.tenant_id COLLATE utf8mb4_general_ci tenant_id,r.receipt_no COLLATE utf8mb4_general_ci reference_no,
       r.customer_id,r.occurred_at cash_at,r.amount,'RECEIPT' kind,
       IF(r.pending_payload IS NULL AND (r.source_status='CONFIRMED' OR r.confirmed_at IS NOT NULL OR x.checked_total=r.amount),1,0) verified,
       x.channel COLLATE utf8mb4_general_ci channel,x.payee COLLATE utf8mb4_general_ci payee,r.employee_code COLLATE utf8mb4_general_ci owner_code,COALESCE(r.confirmed_at,x.verified_at) verified_at
FROM order_sync_receipt r LEFT JOIN (
  SELECT tenant_id,cash_key,SUM(IF(verified=1,amount,0)) checked_total,MAX(verified_at) verified_at,
   IF(COUNT(DISTINCT channel)=1,MIN(channel),NULL) channel,IF(COUNT(DISTINCT payee)=1,MIN(payee),NULL) payee
  FROM order_cash_application_v WHERE has_source=1 GROUP BY tenant_id,cash_key) x
 ON x.tenant_id COLLATE utf8mb4_general_ci=r.tenant_id AND x.cash_key COLLATE utf8mb4_general_ci=MD5(CONCAT('sync:',r.connector_id,':',r.receipt_no))
WHERE r.source_status IN ('RECEIVED','CONFIRMED') AND r.state<>'CANCELLED' AND r.occurred_at IS NOT NULL AND r.amount>0
UNION ALL
SELECT UNHEX(a.cash_key),a.tenant_id,MIN(a.reference_no COLLATE utf8mb4_general_ci),IF(COUNT(DISTINCT a.customer_id)=1,MIN(a.customer_id),NULL),
       MIN(a.cash_at),SUM(a.amount),'RECEIPT',
       IF(MIN(a.verified)=1 AND (COUNT(*)=1 OR (COUNT(DISTINCT a.voucher_amount)=1 AND MAX(a.voucher_amount)=SUM(a.amount))),1,0),
       IF(COUNT(DISTINCT a.channel)=1,MIN(a.channel),NULL),IF(COUNT(DISTINCT a.payee)=1,MIN(a.payee),NULL),
       IF(COUNT(DISTINCT a.owner_code)=1,MIN(a.owner_code),NULL),MAX(a.verified_at)
FROM order_cash_application_v a WHERE a.has_source=0 GROUP BY a.tenant_id,a.cash_key
UNION ALL
SELECT UNHEX(MD5(CONCAT('refund:',r.id))),r.tenant_id,r.refund_no,r.customer_id,r.refund_time,r.refund_amount,'REFUND',1,
       r.refund_method_code,NULL,r.refund_staff_code,NULL
FROM order_refund_record r WHERE r.deleted=0 AND r.refund_status_code='CONFIRMED' AND r.refund_amount>0
UNION ALL
SELECT UNHEX(cash_key),tenant_id,reference_no,customer_id,cash_at,amount,'RECEIPT',1,channel,payee,owner_code,verified_at FROM order_cash_advance_v;

CREATE VIEW order_verified_payment_v AS
SELECT p.*,a.effective_at AS allocation_effective_at,r.cash_at AS actual_cash_at
FROM order_payment_record p
JOIN order_cash_application_v a ON a.tenant_id COLLATE utf8mb4_general_ci=p.tenant_id AND a.id=p.id AND a.verified=1
JOIN order_cash_receipt_v r ON r.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id AND r.id=UNHEX(a.cash_key) AND r.verified=1;

-- 迁移期初只补足无法由已有回款重建的部分，不把历史累计额再算成一次到账。
CREATE VIEW order_receivable_opening_v AS
SELECT h.order_id id,h.tenant_id,h.baseline_at,
       GREATEST(0,h.opening_paid-COALESCE((SELECT SUM(a.amount) FROM order_cash_application_v a
         JOIN order_cash_receipt_v r ON r.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id AND r.id=UNHEX(a.cash_key) AND r.verified=1
         WHERE a.tenant_id COLLATE utf8mb4_general_ci=h.tenant_id AND a.order_id=h.order_id AND a.effective_at<=h.baseline_at AND a.verified=1),0)) opening_paid
FROM order_history_member h;
