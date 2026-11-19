ALTER TABLE bi_operating_action ADD COLUMN promised_on DATE NULL;
ALTER TABLE bi_operating_action ADD COLUMN disputed BOOLEAN NULL;
ALTER TABLE bi_operating_action_event ADD COLUMN promised_on DATE NULL;
ALTER TABLE bi_operating_action_event ADD COLUMN disputed BOOLEAN NULL;
