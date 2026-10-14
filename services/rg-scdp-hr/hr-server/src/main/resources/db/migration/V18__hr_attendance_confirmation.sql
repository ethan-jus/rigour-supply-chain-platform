-- 历史人工资料仍待确认；仅明确执行确认操作才冻结结果。
ALTER TABLE hr_attendance_note
    ADD COLUMN attendance_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING',
    ADD COLUMN confirmed_snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD COLUMN confirmed_department_id BIGINT NULL,
    ADD COLUMN confirmed_rule_department_id BIGINT NULL,
    ADD CONSTRAINT chk_attendance_confirmation CHECK (
        (attendance_status = 'PENDING' AND confirmed_snapshot_json IS NULL)
        OR (attendance_status = 'CONFIRMED' AND confirmed_snapshot_json IS NOT NULL)
    );
CREATE INDEX ix_attendance_status ON hr_attendance_note (tenant_id, attendance_month, attendance_status);

ALTER TABLE hr_attendance_audit
    ADD COLUMN confirmed_snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;

CREATE INDEX ix_attendance_confirmed_department ON hr_attendance_note (tenant_id, attendance_month, confirmed_department_id);
CREATE INDEX ix_attendance_confirmed_rule ON hr_attendance_note (tenant_id, attendance_month, confirmed_rule_department_id);

CREATE TABLE hr_attendance_month_lock (
    tenant_id VARCHAR(64) NOT NULL,
    attendance_month CHAR(7) NOT NULL,
    PRIMARY KEY (tenant_id, attendance_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
