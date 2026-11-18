-- Republish completed capture evidence through the existing outbox. Policy snapshots stay unchanged.
UPDATE sales_visit SET crm_last_enqueued_at=NULL
WHERE status='CHECKED_OUT' AND crm_visit_id IS NOT NULL AND result_submitted_at IS NOT NULL;
