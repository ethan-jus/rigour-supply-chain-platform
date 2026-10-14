-- 一期按月设置统一应出勤，实际天数只读 Sales 日期汇总；原因不改写打卡和工资。
CREATE TABLE hr_attendance_rule (
 tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL,
 expected_days INT NOT NULL, revision INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,attendance_month), CONSTRAINT ck_hr_attendance_days CHECK(expected_days BETWEEN 0 AND 31)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_attendance_note (
 tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL, employee_code VARCHAR(50) NOT NULL,
 reason VARCHAR(500) NOT NULL DEFAULT '', revision INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,attendance_month,employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_attendance_audit (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL,
 employee_code VARCHAR(50) NULL, action_code VARCHAR(16) NOT NULL, expected_days INT NULL,
 reason VARCHAR(500) NULL, revision INT NOT NULL, actor_id VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(id),
 KEY ix_hr_attendance_audit(tenant_id,attendance_month,employee_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
