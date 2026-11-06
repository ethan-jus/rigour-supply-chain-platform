ALTER TABLE erp_inventory_warehouse
    ADD COLUMN location_area_code VARCHAR(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '所在地行政区县编码，省市区名称查询公共行政区划目录',
    ADD COLUMN location_area_version VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '所在地行政区划目录版本，与业务归属地区独立';
