-- 历史回款默认待确认，禁止从当前客户或下单业务员推断。原回款经办人字段保留。
ALTER TABLE order_payment_record
 ADD COLUMN payment_owner_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_evidence VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_department_id BIGINT NULL,
 ADD COLUMN payment_owner_department_name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_region_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD KEY ix_payment_owner(tenant_id,payment_owner_code,payment_time);
