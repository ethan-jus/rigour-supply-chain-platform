-- 仅新入口显式关联；既有订单列保持空，不迁移旧订单。
ALTER TABLE order_sales_order
 ADD COLUMN store_id BIGINT NULL,
 ADD COLUMN ownership_id BIGINT NULL,
 ADD COLUMN store_salesperson_id BIGINT NULL,
 ADD COLUMN store_department_id BIGINT NULL,
 ADD COLUMN store_name_snapshot VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN store_request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN store_operator_user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN store_submission_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN store_authorization_revision BIGINT NULL,
 ADD COLUMN store_authorization_expires_at DATETIME(6) NULL,
 ADD COLUMN store_authorized_at DATETIME(6) NULL,
 ADD COLUMN store_effective_at DATETIME(6) NULL,
 ADD UNIQUE KEY uk_order_store_request(tenant_id,store_request_id),
 ADD KEY ix_order_store_facts(tenant_id,store_id,store_effective_at,id),
 ADD KEY ix_order_store_pending(tenant_id,ownership_id,store_submission_status,store_authorization_expires_at,id);
