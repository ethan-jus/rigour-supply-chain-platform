-- 来源值用于第三方枚举和单位的精确幂等匹配。
-- 使用二进制排序规则，避免大小写或重音差异被数据库误判为同一个来源值。
ALTER TABLE biz_dict_item
    MODIFY COLUMN value VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT '可选业务或来源值；第三方同步按原值精确匹配',
    ADD CONSTRAINT uk_biz_dict_item_value UNIQUE (dict_id, value);
