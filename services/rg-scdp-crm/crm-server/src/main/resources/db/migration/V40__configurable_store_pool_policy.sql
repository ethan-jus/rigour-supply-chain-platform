-- Existing protection versions remain immutable; new fields preserve the previous display thresholds.
ALTER TABLE crm_pool_policy
 ADD COLUMN activity_days SMALLINT UNSIGNED NOT NULL DEFAULT 60,
 ADD COLUMN due_soon_hours SMALLINT UNSIGNED NOT NULL DEFAULT 24,
 ADD COLUMN request_id VARCHAR(64) NULL,
 ADD UNIQUE KEY uk_pool_policy_request(tenant_id,request_id),
 ADD CHECK(activity_days>0 AND due_soon_hours>0);
-- Every future publication must supply explicit values. No runtime default policy is created.
ALTER TABLE crm_pool_policy
 ALTER COLUMN first_visit_hours DROP DEFAULT,
 ALTER COLUMN followup_days DROP DEFAULT,
 ALTER COLUMN cooperating_days DROP DEFAULT,
 ALTER COLUMN return_cooldown_hours DROP DEFAULT,
 ALTER COLUMN activity_days DROP DEFAULT,
 ALTER COLUMN due_soon_hours DROP DEFAULT;
