-- 看板的订单关联索引；业务表不变。金额保留六位，汇总后再显示到分。
ALTER TABLE bi_sales_order_line_fact ADD KEY idx_bi_line_order (tenant_id, order_id, deleted);
ALTER TABLE bi_sales_payment_fact
 ADD KEY idx_bi_payment_order (tenant_id, order_id, deleted),
 ADD KEY idx_bi_payment_region_time (tenant_id, region_code, payment_time, deleted);

-- 每张订单按商品行预分摊，保留 order_id 以执行原有订单数据权限。
CREATE TABLE bi_dashboard_order_product (
 tenant_id VARCHAR(64) NOT NULL,
 order_id BIGINT NOT NULL,
 order_line_id BIGINT NOT NULL COMMENT '0 表示没有可分摊的商品明细',
 product_id BIGINT NULL,
 product_variant_id BIGINT NULL,
 product_category_id BIGINT NULL,
 product_name VARCHAR(200) NULL,
 product_category_name VARCHAR(120) NULL,
 sku_code VARCHAR(50) NULL,
 specification_snapshot VARCHAR(500) NULL,
 allocation_status VARCHAR(32) NOT NULL,
 allocation_weight DECIMAL(30,16) NULL,
 sales_amount DECIMAL(24,6) NOT NULL,
 cohort_paid_amount DECIMAL(24,6) NOT NULL,
 synced_time DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, order_id, order_line_id),
 KEY idx_bi_dashboard_product (tenant_id, product_id, product_variant_id),
 KEY idx_bi_dashboard_category (tenant_id, product_category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 按真实到账日期保存回款分摊；不能用订单日期过滤本期到账。
CREATE TABLE bi_dashboard_payment_product (
 tenant_id VARCHAR(64) NOT NULL,
 payment_id BIGINT NOT NULL,
 order_id BIGINT NULL,
 order_line_id BIGINT NOT NULL,
 payment_time DATETIME(6) NOT NULL,
 allocated_amount DECIMAL(24,6) NOT NULL,
 allocation_status VARCHAR(32) NOT NULL,
 synced_time DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, payment_id, order_line_id),
 KEY idx_bi_dashboard_payment_time (tenant_id, payment_time),
 KEY idx_bi_dashboard_payment_order (tenant_id, order_id, order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_dashboard_product_snapshot (
 tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
 run_id BIGINT NOT NULL,
 synced_time DATETIME(6) NOT NULL,
 order_product_count BIGINT NOT NULL,
 payment_product_count BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
