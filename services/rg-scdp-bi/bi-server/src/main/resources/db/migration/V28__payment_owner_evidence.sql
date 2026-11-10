ALTER TABLE bi_source_order_order_payment_record
 ADD COLUMN payment_owner_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_evidence VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_department_id BIGINT NULL,
 ADD COLUMN payment_owner_department_name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
 ADD COLUMN payment_owner_region_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
