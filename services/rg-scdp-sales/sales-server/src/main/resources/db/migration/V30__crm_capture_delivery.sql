ALTER TABLE sales_visit
 ADD COLUMN crm_capture_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN crm_last_enqueued_at DATETIME(6) NULL;
ALTER TABLE sales_visit_policy_version
 ADD COLUMN source_system VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'SALES';
ALTER TABLE sales_outbox_event
 ADD KEY ix_sales_crm_delivery(event_type,publish_status,next_retry_at,occurred_at);

-- Legacy CRM receipts used the automatic-review version; new delivery must start above it.
UPDATE sales_visit v LEFT JOIN sales_visit_review r
 ON r.tenant_id=v.tenant_id AND r.visit_id=v.id AND r.review_type='AUTOMATIC_ASSESSMENT'
 SET v.crm_capture_revision=COALESCE(r.version+1,0) WHERE v.crm_visit_id IS NOT NULL;
