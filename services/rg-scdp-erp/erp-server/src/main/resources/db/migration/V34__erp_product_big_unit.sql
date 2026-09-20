-- ERP 商品大包装单位与换算：等级价按基础单位维护，展示时支持按大单位换算展示。
--
-- 业务口径：
-- 1. big_unit_code 是大包装单位名称快照，如方便面「箱」，基础单位为「桶」。
-- 2. base_to_big_rate 是基础单位到大包装单位的换算率，如 1箱=12桶 时为 12，可为空。
-- 3. 订货宝同步时从 getGoodsList 的 bigunits/conversionnumber 落库；手工商品可后续在商品表单维护。

ALTER TABLE erp_product
    ADD COLUMN big_unit_code VARCHAR(50) NULL COMMENT '大包装单位名称，如箱' AFTER unit_code,
    ADD COLUMN base_to_big_rate DECIMAL(24,6) NULL COMMENT '基础单位到大包装单位换算率，如1箱=12桶时为12' AFTER big_unit_code;
