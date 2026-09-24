-- 只保存首次有效下单日期，供可见的本期客户判断跨城/跨销售复购。
-- 不含历史金额、历史销售等敏感明细；权限仍在本期订单聚合前执行。
CREATE TABLE bi_dashboard_customer_history (
 tenant_id VARCHAR(64) NOT NULL,
 customer_id BIGINT NOT NULL,
 first_order_date DATETIME(6) NOT NULL,
 synced_time DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
