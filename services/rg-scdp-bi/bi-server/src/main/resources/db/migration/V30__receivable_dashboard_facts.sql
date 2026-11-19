-- BI 源快照按租户事务刷新。保留到账、分配和账期各自的日期与粒度。
CREATE TABLE bi_cash_receipt (
    id BINARY(16) NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    reference_no VARCHAR(160),
    customer_id BIGINT,
    cash_at DATETIME(6),
    amount DECIMAL(24,6),
    channel VARCHAR(64), payee VARCHAR(255), owner_code VARCHAR(128), verified_at DATETIME(6),
    kind VARCHAR(12),
    verified TINYINT,
    PRIMARY KEY(tenant_id,id), KEY ix_receipt_day(tenant_id,cash_at,customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_cash_application (
    id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT,
    cash_key CHAR(32),
    effective_at DATETIME(6),
    amount DECIMAL(24,6),
    verified TINYINT,
    PRIMARY KEY(tenant_id,id), KEY ix_application_order(tenant_id,order_id,effective_at), KEY ix_application_receipt(tenant_id,cash_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_receivable_installment (
    id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT,
    plan_version INT,
    installment_no INT,
    effective_at DATETIME(6),
    due_date DATE,
    amount DECIMAL(24,6),
    PRIMARY KEY(tenant_id,id), KEY ix_receivable_schedule(tenant_id,order_id,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_receivable_adjustment (
    id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT,
    payment_id BIGINT,
    event_type VARCHAR(32),
    receivable_delta DECIMAL(24,6),
    received_delta DECIMAL(24,6),
    effective_at DATETIME(6),
    deleted INT,
    PRIMARY KEY(tenant_id,id), KEY ix_receivable_adjustment(tenant_id,order_id,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_receivable_opening (
    id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    baseline_at DATETIME(6),
    opening_paid DECIMAL(24,6),
    PRIMARY KEY(tenant_id,id), KEY ix_receivable_opening(tenant_id,baseline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_receivable_archive (
 tenant_id VARCHAR(64) NOT NULL, period_start DATE NOT NULL, as_of_date DATE NOT NULL, version_key CHAR(64) NOT NULL,
 snapshot LONGBLOB NOT NULL, archived_by VARCHAR(64) NOT NULL, archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,period_start,as_of_date,version_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 商品账龄与合同账期共用到账、期初和获准调整的事实及生效时间。
CREATE VIEW bi_order_balance_event AS
SELECT a.tenant_id,a.order_id,GREATEST(a.effective_at,r.cash_at) effective_at,
       a.amount paid, CAST(0 AS DECIMAL(24,6)) adjustment
FROM bi_cash_application a JOIN bi_cash_receipt r
  ON r.tenant_id=a.tenant_id AND r.id=UNHEX(a.cash_key)
WHERE a.verified=1 AND r.verified=1
UNION ALL
SELECT tenant_id,id,baseline_at,opening_paid,0 FROM bi_receivable_opening
UNION ALL
SELECT tenant_id,order_id,effective_at,0,receivable_delta-received_delta FROM bi_receivable_adjustment
WHERE deleted=0 AND event_type IN ('CREDIT_NOTE','WRITE_OFF','DEBIT_NOTE','PAYMENT_REVERSAL','CREDIT_REVERSAL');
