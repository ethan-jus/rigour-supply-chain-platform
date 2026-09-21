-- ERP 商品默认统计单位：订单明细等页面按该层级换算数量与单价，金额口径不变。
--
-- 业务口径：
-- 1. statistics_unit_level 复用现有三级单位：BASE=基础单位、MIDDLE=中包装单位、BIG=大包装单位。
-- 2. 为空按 BASE 处理，保持存量商品行为不变。
-- 3. 订货宝按箱售卖时，中包装单位通常就是箱，默认统计单位选 MIDDLE 即按箱统计。
ALTER TABLE erp_product
    ADD COLUMN statistics_unit_level VARCHAR(16) NULL COMMENT '默认统计单位层级：BASE/MIDDLE/BIG，空按基础单位' AFTER base_to_big_rate;
