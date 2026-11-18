-- Formal visits use local speech recognition. Content analysis is not enabled this iteration.
ALTER TABLE sales_recording_analysis ADD COLUMN asr_model VARCHAR(128) NULL,
    ALTER COLUMN summary_status SET DEFAULT 'NOT_REQUESTED';
UPDATE sales_recording_analysis SET summary_status='NOT_REQUESTED' WHERE summary_status='WAITING';
-- Re-publish current evidence once to repair snapshots raced by completion and clip upload.
UPDATE sales_visit SET crm_last_enqueued_at=NULL
WHERE crm_visit_id IS NOT NULL AND status='CHECKED_OUT' AND result_submitted_at IS NOT NULL;
