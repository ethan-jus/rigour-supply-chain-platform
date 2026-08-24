-- 公共字典表收敛为自研业务基线：data_dictionary / data_dictionary_item。
-- V1-V6 是旧的“作用域+模块+租户继承”字典模型；新分支不再把它作为主流程。
-- 本迁移只保证新业务表存在，不在同一步骤强制改名或删除旧表。
-- 原因：MySQL DDL 非全事务，RENAME TABLE 一旦中途失败会留下半迁移状态，导致 repair 后重复执行仍可能失败。
-- 旧 biz_dict / biz_dict_item 后续确认无运行时引用后，再通过新的迁移做归档或清理。

CREATE TABLE IF NOT EXISTS data_dictionary (
    id                BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    dictionary_code   VARCHAR(50)  NOT NULL COMMENT '字典代码',
    dictionary_name   VARCHAR(100) NOT NULL COMMENT '字典名称',
    dictionary_type   VARCHAR(50)  NOT NULL COMMENT '字典类型：COMMON/ERP/CRM/ORDER',
    remark            VARCHAR(500) NULL COMMENT '备注',
    revision          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by        VARCHAR(50)  NOT NULL COMMENT '创建人',
    created_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by        VARCHAR(50)  NULL COMMENT '更新人',
    updated_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           INT          NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_dictionary_code (dictionary_code),
    KEY idx_data_dictionary_type (dictionary_type, deleted, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典主表';

CREATE TABLE IF NOT EXISTS data_dictionary_item (
    id                          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    dictionary_code             VARCHAR(50)  NOT NULL COMMENT '字典代码',
    dictionary_item_level       INT          NOT NULL DEFAULT 1 COMMENT '字典条目层级',
    parent_dictionary_item_code VARCHAR(50)  NULL COMMENT '父级字典条目代码',
    dictionary_item_code        VARCHAR(50)  NOT NULL COMMENT '字典条目代码',
    dictionary_item_name        VARCHAR(100) NOT NULL COMMENT '字典条目名称',
    remark                      VARCHAR(500) NULL COMMENT '备注',
    ordinal                     INT          NOT NULL DEFAULT 0 COMMENT '序号',
    revision                    INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by                  VARCHAR(50)  NOT NULL COMMENT '创建人',
    created_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by                  VARCHAR(50)  NULL COMMENT '更新人',
    updated_time                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                     INT          NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_dictionary_item_code (dictionary_code, dictionary_item_code),
    KEY idx_data_dictionary_item_tree (dictionary_code, parent_dictionary_item_code, dictionary_item_level, ordinal),
    KEY idx_data_dictionary_item_deleted (dictionary_code, deleted, ordinal),
    CONSTRAINT fk_data_dictionary_item_dictionary
        FOREIGN KEY (dictionary_code) REFERENCES data_dictionary (dictionary_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典项';
