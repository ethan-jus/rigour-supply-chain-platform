-- Sales Work V7：记录本次是否真正接触KP，支持关门、KP不在、拒绝接待等真实离店场景。
-- 不将未接触场景伪造成空白KP资料；录音不足与未接触结果进入后续复核，不阻断离店。

ALTER TABLE sales_visit
    ADD COLUMN contact_outcome VARCHAR(32) NULL COMMENT 'CONTACTED/STORE_CLOSED/KP_ABSENT/REFUSED/OTHER_NO_CONTACT'
        AFTER final_reason_code;

UPDATE sales_visit
   SET contact_outcome='CONTACTED'
 WHERE result_submitted_at IS NOT NULL
   AND contact_outcome IS NULL;
