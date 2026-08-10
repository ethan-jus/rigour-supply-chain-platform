-- Sales Work V3：拜访结果采集字段（KP 关键人称呼、联系电话、合作意向、结果备注）。
-- 只追加可空列，不改写 V1/V2 历史迁移；拜访结果属于销售事实，不做绩效计算。

ALTER TABLE sales_visit
    ADD COLUMN kp_name VARCHAR(128) NULL COMMENT '门店关键人称呼' AFTER final_reason_code,
    ADD COLUMN kp_phone VARCHAR(32) NULL COMMENT '门店关键人联系电话' AFTER kp_name,
    ADD COLUMN intention_level VARCHAR(24) NULL COMMENT '合作意向 HIGH/MEDIUM/LOW/NONE' AFTER kp_phone,
    ADD COLUMN result_note VARCHAR(1024) NULL COMMENT '拜访结果备注' AFTER intention_level,
    ADD COLUMN result_submitted_at DATETIME(6) NULL COMMENT '拜访结果最近提交时间' AFTER result_note;
