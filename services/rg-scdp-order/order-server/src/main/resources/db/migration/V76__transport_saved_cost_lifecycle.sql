-- 运输记录保存即生效；保留原操作审计，原已确认金额继续禁止来源自动覆盖。
ALTER TABLE order_logistics_cost
    DROP CHECK ck_cost_status,
    ADD COLUMN amount_manually_edited TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '本系统金额或自定义分摊已人工维护，平台同步仅刷新原账单，不覆盖成本';

INSERT INTO order_logistics_cost_audit
    (tenant_id, entry_id, action_code, actor_id, reason, snapshot_json)
SELECT tenant_id, id, 'STATE_UPGRADE', 'system',
       '运输记录取消确认环节，保存即生效；原状态和金额留存',
       JSON_OBJECT('previousStatus', status_code, 'amount', amount, 'revision', revision)
FROM order_logistics_cost
WHERE status_code IN ('DRAFT', 'CONFIRMED');

UPDATE order_logistics_cost
SET amount_manually_edited = CASE
        WHEN status_code = 'CONFIRMED' OR COALESCE(source_system, 'NONE') <> 'HUOLALA'
          OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.ledger.feeItems[0].allocationMethodCode')), '') = 'MANUAL'
          OR COALESCE(JSON_LENGTH(data_json, '$.ledger.feeItems'), 0) > 1
        THEN 1 ELSE 0 END,
    status_code = CASE WHEN status_code IN ('DRAFT', 'CONFIRMED') THEN 'ACTIVE' ELSE status_code END,
    payer_code = CASE WHEN source_system = 'HUOLALA' AND payer_code IS NULL THEN 'COMPANY' ELSE payer_code END,
    revision = revision + 1,
    updated_time = CURRENT_TIMESTAMP(6);

ALTER TABLE order_logistics_cost
    ALTER COLUMN status_code SET DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_cost_status CHECK (status_code IN ('ACTIVE', 'VOID'));

-- 所有缓存/分析使用既有版本源失效，不改写已发布迁移或历史账单。
UPDATE order_logistics_cost_guard SET version = version + 1;
