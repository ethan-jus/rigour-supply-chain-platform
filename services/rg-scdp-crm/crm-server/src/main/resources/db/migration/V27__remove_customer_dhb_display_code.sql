-- 客户档案使用账号识别；接口来源编号继续保留在 crm_source_binding，不影响历史关联。
ALTER TABLE crm_customer DROP COLUMN dhb_customer_code;
