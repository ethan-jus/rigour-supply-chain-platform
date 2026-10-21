-- 人工调整独立于销售打卡来源；NULL 表示继续使用系统计算。
ALTER TABLE hr_attendance_note
    ADD COLUMN actual_days_override DECIMAL(3,1) NULL,
    ADD COLUMN absent_days_override DECIMAL(3,1) NULL,
    ADD COLUMN leave_type_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT chk_attendance_actual_override CHECK (actual_days_override BETWEEN 0 AND 31 AND MOD(actual_days_override * 10, 5) = 0),
    ADD CONSTRAINT chk_attendance_absent_override CHECK (absent_days_override BETWEEN 0 AND 31 AND MOD(absent_days_override * 10, 5) = 0);

ALTER TABLE hr_attendance_audit
    ADD COLUMN actual_days_override DECIMAL(3,1) NULL,
    ADD COLUMN absent_days_override DECIMAL(3,1) NULL,
    ADD COLUMN leave_type_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
