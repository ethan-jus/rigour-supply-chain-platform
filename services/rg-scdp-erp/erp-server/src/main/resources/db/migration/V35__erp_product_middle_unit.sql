-- ERP 商品中包装单位与换算：与基础单位、大包装单位共同构成商品单位口径。
--
-- 业务口径：
-- 1. middle_unit_code 是中包装单位编码，关联 PRODUCT_UNIT 字典项，如「打」。
-- 2. base_to_middle_rate 是基础单位到中包装单位的换算率，如 1打=6桶 时为 6，可为空。
-- 3. 订货宝同步时从 getGoodsList 的 middle_units/base2middle_unit_rate 落库。

ALTER TABLE erp_product
    ADD COLUMN middle_unit_code VARCHAR(50) NULL COMMENT '中包装单位编码，关联PRODUCT_UNIT字典项' AFTER unit_code,
    ADD COLUMN base_to_middle_rate DECIMAL(24,6) NULL COMMENT '基础单位到中包装单位换算率，如1打=6桶时为6' AFTER middle_unit_code;
