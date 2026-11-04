-- 在现有供应商档案上补充财务字段；存量及来源缺失资料保持为空。
ALTER TABLE erp_supplier_profile
    ADD COLUMN account_name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '开户名称',
    ADD COLUMN invoice_title VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '开票抬头',
    ADD COLUMN taxpayer_number VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '纳税人识别号';
