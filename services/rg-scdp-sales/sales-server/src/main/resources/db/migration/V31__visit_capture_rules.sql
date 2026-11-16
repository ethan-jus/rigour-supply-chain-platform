-- Sales owns immutable visit capture rules; existing visits keep their historical snapshots.
ALTER TABLE sales_visit_policy_version ADD COLUMN capture_rules_json JSON NULL;
ALTER TABLE sales_recording_clip ADD COLUMN capture_source VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
