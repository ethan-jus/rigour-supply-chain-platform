-- 工资按员工月份锁定，数值薪资标准独立于花名册原文；不改写历史员工档案。
CREATE TABLE hr_payroll_guard (
 tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_rule (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, request_id VARCHAR(36) NOT NULL,
 kind_code VARCHAR(32) NOT NULL, name VARCHAR(120) NOT NULL, employee_code VARCHAR(50) NULL,
 effective_from DATE NOT NULL,effective_to DATE NOT NULL,enabled BOOLEAN NOT NULL,
 data_json JSON NOT NULL,revision INT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_pay_rule_request(tenant_id,request_id),KEY ix_pay_rule(tenant_id,kind_code,effective_from,effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_rule_department (
 tenant_id VARCHAR(64) NOT NULL,rule_id VARCHAR(36) NOT NULL,department_id BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,rule_id,department_id),KEY ix_pay_rule_department(tenant_id,department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_input (
 tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,payroll_month CHAR(7) NOT NULL,
 base_override DECIMAL(18,2) NULL,performance_override DECIMAL(18,2) NULL,deduction DECIMAL(18,2) NOT NULL,
 reason VARCHAR(500) NOT NULL,revision INT NOT NULL DEFAULT 1,updated_by VARCHAR(50) NOT NULL,
 updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,employee_code,payroll_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll (
 id VARCHAR(36) NOT NULL,tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,department_id BIGINT NULL,department_name VARCHAR(128) NULL,city_name VARCHAR(80) NULL,
 payroll_month CHAR(7) NOT NULL,status_code VARCHAR(20) NOT NULL,revision INT NOT NULL DEFAULT 1,
 calculation_json JSON NOT NULL,created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_payroll_employee_month(tenant_id,employee_code,payroll_month),
 KEY ix_payroll_list(tenant_id,payroll_month,department_id,status_code),
 CONSTRAINT ck_payroll_status CHECK(status_code IN ('REVIEW','READY','CONFIRMED','PAID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_commission_release (
 tenant_id VARCHAR(64) NOT NULL,source_line_id VARCHAR(50) NOT NULL,recipient_role_code VARCHAR(20) NOT NULL,payroll_id VARCHAR(36) NOT NULL,
 amount DECIMAL(18,2) NOT NULL,PRIMARY KEY(tenant_id,source_line_id,recipient_role_code),KEY ix_payroll_release(tenant_id,payroll_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_expense (
 id VARCHAR(36) NOT NULL,tenant_id VARCHAR(64) NOT NULL,request_id VARCHAR(36) NOT NULL,document_no VARCHAR(50) NOT NULL,
 title VARCHAR(200) NOT NULL,type_code VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,department_id BIGINT NULL,department_name VARCHAR(128) NULL,
 city_code VARCHAR(64) NOT NULL,cost_date DATE NOT NULL,amount DECIMAL(18,2) NOT NULL,
 payment_status_code VARCHAR(32) NOT NULL,status_code VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 data_json JSON NOT NULL,revision INT NOT NULL DEFAULT 1,created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_expense_request(tenant_id,request_id),UNIQUE KEY uk_expense_no(tenant_id,document_no),
 KEY ix_expense_query(tenant_id,cost_date,employee_code,status_code),CONSTRAINT ck_expense_amount CHECK(amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_audit (
 id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,object_id VARCHAR(128) NOT NULL,
 action_code VARCHAR(32) NOT NULL,actor_id VARCHAR(50) NOT NULL,reason VARCHAR(500) NULL,snapshot_json JSON NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),KEY ix_payroll_audit(tenant_id,object_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 默认提成口径来自本期确认；只是可选模板，必须关联真实商品、单位、部门和生效日期后才参与核算。
CREATE TABLE hr_commission_policy (
 policy_code VARCHAR(50) NOT NULL,policy_name VARCHAR(120) NOT NULL,method_code VARCHAR(20) NOT NULL,
 sales_rate DECIMAL(18,2) NOT NULL,manager_rate DECIMAL(18,2) NOT NULL,ordinal INT NOT NULL,
 PRIMARY KEY(policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO hr_commission_policy VALUES
 ('INSTANT_NOODLES','快消品：泡面','PERCENT',4,1,10),
 ('BILLIARD_ACCESSORIES','台球周边：巧克粉、皮头等（除台呢）','PERCENT',10,2,20),
 ('CLOTH_A300','澳洋华彩（中式）A300','QUANTITY',15,5,30),
 ('CLOTH_A500B','澳洋华彩（中式）A500B','QUANTITY',15,5,40),
 ('CLOTH_A600','澳洋华彩（中式）A600','QUANTITY',25,10,50),
 ('CLOTH_A700','澳洋华彩（中式）A700','QUANTITY',25,10,60),
 ('CLOTH_A800','澳洋华彩（中式）A800','QUANTITY',25,10,70),
 ('CLOTH_SNOOKER_AURORA','澳洋斯诺克：极光','QUANTITY',35,10,80),
 ('CLOTH_SNOOKER_PEAK','澳洋斯诺克：极峰','QUANTITY',35,10,90),
 ('CLOTH_SNOOKER_POINT','澳洋斯诺克：极点','QUANTITY',35,10,100);
