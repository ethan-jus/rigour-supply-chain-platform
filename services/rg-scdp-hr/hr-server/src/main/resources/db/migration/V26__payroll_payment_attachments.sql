-- Payment evidence is independent of reimbursement and never changes wage amounts or payment status.
ALTER TABLE hr_payroll_payment
 ADD CONSTRAINT uk_pay_payment_identity UNIQUE (tenant_id,payroll_id,id);
CREATE TABLE hr_payroll_payment_attachment (
 id VARCHAR(36) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 payroll_id VARCHAR(36) NOT NULL,
 payment_id VARCHAR(36) NOT NULL,
 file_key VARCHAR(500) NOT NULL,
 file_name VARCHAR(200) NOT NULL,
 content_type VARCHAR(100) NOT NULL,
 file_size BIGINT NOT NULL,
 created_by VARCHAR(50) NOT NULL,
 created_by_name VARCHAR(128) NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 KEY ix_pay_attachment_payment(tenant_id,payroll_id,payment_id),
 CONSTRAINT fk_pay_attachment_payment FOREIGN KEY (tenant_id,payroll_id,payment_id)
   REFERENCES hr_payroll_payment(tenant_id,payroll_id,id),
 CONSTRAINT ck_pay_attachment_size CHECK(file_size>0 AND file_size<=10485760),
 CONSTRAINT ck_pay_attachment_type CHECK(content_type IN ('image/png','image/jpeg','application/pdf'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
