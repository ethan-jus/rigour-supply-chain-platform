-- 0 now means no cumulative recording threshold. Per-clip minimum duration is checked separately.
-- Preserve published policy rows and visit snapshots; the unsigned column still rejects negatives.
ALTER TABLE sales_visit_policy_version
    DROP CHECK ck_sales_visit_policy_recording_duration;
