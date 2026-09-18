ALTER TABLE crm_customer
    ADD COLUMN login_account VARCHAR(160) NULL COMMENT '客户登录账号；订货宝主来源clientAccount',
    ADD COLUMN dhb_customer_code VARCHAR(128) NULL COMMENT '订货宝主客户编码，非系统客户编号或来源ID',
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近客户同步时间 UTC',
    ADD COLUMN synced_by VARCHAR(64) NULL COMMENT '最近同步发起人；历史服务同步为SYSTEM';

-- Use verified bindings only. Retain business creation facts and the existing modification audit.
UPDATE crm_customer c
JOIN crm_source_binding b ON b.tenant_id=UUID_TO_BIN(c.tenant_id) AND b.target_id=c.party_id
    AND b.source_system='DINGHUOBAO' AND b.source_object_type='CUSTOMER'
    AND b.binding_status='RESOLVED' AND b.deleted=0 AND b.primary_customer_source_id IS NULL
LEFT JOIN crm_customer_profile p ON p.tenant_id=b.tenant_id AND p.party_id=b.target_id AND p.deleted=0
LEFT JOIN crm_sync_run r ON r.tenant_id=b.tenant_id AND r.id=b.last_sync_run_id
SET c.login_account=COALESCE(NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientAccount')),'null'),''),p.login_account),
    c.dhb_customer_code=b.source_code,
    c.settlement_type_code=COALESCE(NULLIF(NULLIF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientClearingForm'))),'null'),''),c.settlement_type_code),
    c.synced_at=b.synced_at, c.synced_by=COALESCE(r.created_by,'SYSTEM'),
    c.updated_time=c.updated_time
WHERE c.deleted=0;
