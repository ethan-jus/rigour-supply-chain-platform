INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted, allow_new_items)
VALUES ('ATTENDANCE_STATUS', '考勤状态', 'HR', '月度考勤确认流程，状态编码固定', 1, 'SYSTEM', 'SYSTEM', 0, FALSE);

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code, dictionary_item_name, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('ATTENDANCE_STATUS', 1, NULL, 'PENDING', '待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ATTENDANCE_STATUS', 1, NULL, 'CONFIRMED', '已确认', 20, 1, 'SYSTEM', 'SYSTEM', 0);
