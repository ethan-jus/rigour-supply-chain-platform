INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted, allow_new_items)
VALUES ('ATTENDANCE_LEAVE_TYPE', '考勤请假类型', 'HR', '员工月度考勤，请假类型单选，不参与天数自动计算', 1, 'SYSTEM', 'SYSTEM', 0, TRUE);

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code, dictionary_item_name, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('ATTENDANCE_LEAVE_TYPE', 1, NULL, 'NONE', '无', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ATTENDANCE_LEAVE_TYPE', 1, NULL, 'COMP_TIME', '调休', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ATTENDANCE_LEAVE_TYPE', 1, NULL, 'PERSONAL', '事假', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ATTENDANCE_LEAVE_TYPE', 1, NULL, 'SICK', '病假', 40, 1, 'SYSTEM', 'SYSTEM', 0);
