-- 按订单聚合已核验核销，避免每笔历史订单重复展开资金凭据和核销视图。
-- 保留原视图金额精度，BI 按原始行生成版本指纹。
CREATE OR REPLACE VIEW order_receivable_opening_v AS
SELECT h.order_id AS id,h.tenant_id,h.baseline_at,
       CAST(GREATEST(0,h.opening_paid-COALESCE(a.amount,0)) AS DECIMAL(47,6)) AS opening_paid
FROM order_history_member h
LEFT JOIN (
    SELECT baseline.tenant_id,baseline.order_id,SUM(a.amount) AS amount
    FROM order_history_member baseline
    JOIN order_cash_application_v a ON a.tenant_id COLLATE utf8mb4_general_ci=baseline.tenant_id
      AND a.order_id=baseline.order_id AND a.effective_at<=baseline.baseline_at AND a.verified=1
    JOIN order_cash_receipt_v r ON r.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id
      AND r.id=UNHEX(a.cash_key) AND r.verified=1
    GROUP BY baseline.tenant_id,baseline.order_id
) a ON a.tenant_id=h.tenant_id AND a.order_id=h.order_id;
