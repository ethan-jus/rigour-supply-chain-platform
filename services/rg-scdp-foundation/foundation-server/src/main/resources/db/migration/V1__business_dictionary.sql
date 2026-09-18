-- 公共业务设置 V1：两表字典模型。
-- scope_type 表示数据所有者；level_no/parent_id 表示字典项树，两者不能混用。

CREATE TABLE biz_dict (
    id            CHAR(36)      NOT NULL COMMENT '字典主键UUID',
    code          VARCHAR(64)   NOT NULL COMMENT '字典编码，同一作用域和模块内唯一',
    name          VARCHAR(100)  NOT NULL COMMENT '字典中文名称',
    scope_type    VARCHAR(16)   NOT NULL COMMENT '作用域类型：SYSTEM/MODULE/TENANT',
    scope_id      VARCHAR(64)   NOT NULL COMMENT '作用域标识：SYSTEM、模块编码或租户ID',
    module_code   VARCHAR(32)   NOT NULL COMMENT '业务模块编码，如COMMON/ERP/CRM/ORDER',
    tenant_id     VARCHAR(64)   NULL COMMENT '租户级字典所属租户，非租户级为空',
    base_dict_id  CHAR(36)      NULL COMMENT '租户字典复制来源，指向系统级或模块级字典',
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '治理状态：ACTIVE/DISABLED',
    sort_no       INT           NOT NULL DEFAULT 0 COMMENT '同模块字典展示顺序，数值越小越靠前',
    remark        VARCHAR(500)  NULL COMMENT '字典用途和维护说明',
    version       BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by    CHAR(36)      NULL COMMENT '创建人或服务主体UUID',
    updated_by    CHAR(36)      NULL COMMENT '最后修改人或服务主体UUID',
    created_at    DATETIME(6)   NOT NULL COMMENT 'UTC创建时间',
    updated_at    DATETIME(6)   NOT NULL COMMENT 'UTC最后修改时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_biz_dict UNIQUE (scope_type, scope_id, module_code, code),
    CONSTRAINT ck_biz_dict_scope_type
        CHECK (scope_type IN ('SYSTEM', 'MODULE', 'TENANT')),
    CONSTRAINT ck_biz_dict_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_biz_dict_scope_owner CHECK (
        (scope_type = 'SYSTEM' AND scope_id = 'SYSTEM' AND tenant_id IS NULL)
        OR (scope_type = 'MODULE' AND scope_id = module_code AND tenant_id IS NULL)
        OR (scope_type = 'TENANT' AND scope_id = tenant_id AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT fk_biz_dict_base
        FOREIGN KEY (base_dict_id) REFERENCES biz_dict (id),
    KEY idx_biz_dict_tenant (tenant_id, module_code, status),
    KEY idx_biz_dict_lookup (module_code, code, scope_type, scope_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公共业务字典';

CREATE TABLE biz_dict_item (
    id          CHAR(36)      NOT NULL COMMENT '字典项主键UUID',
    dict_id     CHAR(36)      NOT NULL COMMENT '所属字典主键',
    parent_id   CHAR(36)      NULL COMMENT '父字典项主键，根节点为空',
    level_no    INT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '树层级，根节点为1，由服务端计算',
    code        VARCHAR(64)   NOT NULL COMMENT '字典项业务编码，同一本字典内唯一',
    name        VARCHAR(100)  NOT NULL COMMENT '面向业务人员的显示名称',
    value       VARCHAR(255)  NULL COMMENT '可选业务值，不用于保存第三方原始报文',
    sort_no     INT           NOT NULL DEFAULT 0 COMMENT '同级展示顺序，数值越小越靠前',
    status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '治理状态：ACTIVE/DISABLED',
    extra_json  JSON          NULL COMMENT '颜色、图标、精度等非核心展示扩展',
    version     BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by  CHAR(36)      NULL COMMENT '创建人或服务主体UUID',
    updated_by  CHAR(36)      NULL COMMENT '最后修改人或服务主体UUID',
    created_at  DATETIME(6)   NOT NULL COMMENT 'UTC创建时间',
    updated_at  DATETIME(6)   NOT NULL COMMENT 'UTC最后修改时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_biz_dict_item UNIQUE (dict_id, code),
    CONSTRAINT ck_biz_dict_item_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_biz_dict_item_dict
        FOREIGN KEY (dict_id) REFERENCES biz_dict (id),
    CONSTRAINT fk_biz_dict_item_parent
        FOREIGN KEY (parent_id) REFERENCES biz_dict_item (id),
    KEY idx_biz_dict_item_tree (dict_id, parent_id, level_no, sort_no),
    KEY idx_biz_dict_item_status (dict_id, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公共业务字典项';
