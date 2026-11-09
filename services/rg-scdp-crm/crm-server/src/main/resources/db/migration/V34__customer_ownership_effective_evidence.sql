-- 旧同步时间不等于真实交接时间，不把历史观察记录自动认证为归属证据。
ALTER TABLE crm_customer_responsibility_history
 ADD COLUMN recorded_at DATETIME(6) NULL,
 ADD COLUMN effective_kind VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OBSERVED',
 ADD COLUMN new_department_id BIGINT NULL,
 ADD COLUMN new_department_name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
UPDATE crm_customer_responsibility_history SET recorded_at=effective_at;
ALTER TABLE crm_customer_responsibility_history MODIFY recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
-- 本地人工操作原有生效时刻就是操作时刻，可保留这一已知事实；外部观察不认证。
UPDATE crm_customer_responsibility_history SET effective_kind='CONFIRMED' WHERE source_system='LOCAL';
