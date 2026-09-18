-- CRM 自研客户与 CRM 往来主体建立内部关联。
-- 订货宝来源编号只保留在 crm_source_binding/source_identity_alias；crm_customer.customer_code 始终使用我方编码规则。

ALTER TABLE crm_customer
    ADD COLUMN party_id BINARY(16) NULL COMMENT '客户对应CRM往来主体ID；用于来源同步后关联CRM自研客户主档' AFTER customer_code,
    ADD UNIQUE KEY uk_crm_customer_party (tenant_id, party_id),
    ADD KEY idx_crm_customer_party (party_id);
