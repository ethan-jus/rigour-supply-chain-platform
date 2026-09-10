-- Backfill Feishu import template, dependency and deduplication schema.
-- The shared development database previously recorded V19/V20 before the final
-- template and dedup schema was present, so this migration is deliberately
-- idempotent and safe on both fresh and partially migrated databases.

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_batch ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''重复行数量'' AFTER total_rows',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_batch'
      AND column_name = 'duplicate_rows'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_table ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''重复行数量'' AFTER row_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_table'
      AND column_name = 'duplicate_rows'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN deduplication_key VARCHAR(255) NULL COMMENT ''导入去重键'' AFTER raw_row_hash',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'deduplication_key'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN duplicate_scope VARCHAR(32) NULL COMMENT ''重复范围'' AFTER deduplication_key',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'duplicate_scope'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN duplicate_of_raw_row_id BINARY(16) NULL COMMENT ''重复来源原始行ID'' AFTER duplicate_scope',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'duplicate_of_raw_row_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD INDEX idx_integration_feishu_import_raw_row_dedup (tenant_id, deduplication_key, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND index_name = 'idx_integration_feishu_import_raw_row_dedup'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD INDEX idx_integration_feishu_import_raw_row_import_projection (tenant_id, batch_id, import_status, projection_status)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND index_name = 'idx_integration_feishu_import_raw_row_import_projection'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS integration_feishu_import_template (
    id                           BINARY(16)      NOT NULL COMMENT '模板ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局模板',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '模板编码',
    template_name                VARCHAR(128)    NOT NULL COMMENT '模板名称',
    domain_code                  VARCHAR(32)     NOT NULL COMMENT '目标业务域',
    object_type                  VARCHAR(64)     NOT NULL COMMENT '目标对象类型',
    aliases_json                 JSON            NOT NULL COMMENT '工作表别名',
    required_headers_json        JSON            NOT NULL COMMENT '必需字段',
    source_document_fields_json  JSON            NOT NULL COMMENT '来源单号候选字段',
    source_created_fields_json   JSON            NOT NULL COMMENT '来源创建时间候选字段',
    deduplication_strategy       VARCHAR(32)     NOT NULL DEFAULT 'SOURCE_DOCUMENT_NO' COMMENT '去重策略',
    deduplication_fields_json    JSON            NOT NULL COMMENT '字段组合去重字段',
    ready_by_default_flag        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否默认可投影',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark                       VARCHAR(500)    NULL COMMENT '备注',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_source CHECK (source_system = 'FEISHU'),
    CONSTRAINT ck_integration_feishu_import_template_dedup CHECK (
        deduplication_strategy IN ('SOURCE_DOCUMENT_NO', 'FIELD_VALUES', 'ROW_HASH')
    ),
    UNIQUE KEY uk_integration_feishu_import_template_code (
        tenant_id, source_system, template_code
    ),
    KEY idx_integration_feishu_import_template_domain (
        tenant_id, domain_code, object_type, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入模板';

CREATE TABLE IF NOT EXISTS integration_feishu_import_template_dependency (
    id                           BINARY(16)      NOT NULL COMMENT '依赖ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局依赖',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '当前模板编码',
    depends_on_template_code     VARCHAR(64)     NOT NULL COMMENT '依赖模板编码',
    relation_kind                VARCHAR(64)     NOT NULL COMMENT '依赖关系类型',
    source_reference_fields_json JSON            NOT NULL COMMENT '当前表引用字段',
    target_reference_fields_json JSON            NOT NULL COMMENT '依赖表匹配字段',
    required_flag                TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否必需依赖',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template_dependency PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_dependency_source CHECK (source_system = 'FEISHU'),
    UNIQUE KEY uk_integration_feishu_import_template_dependency (
        tenant_id, source_system, template_code, depends_on_template_code, relation_kind
    ),
    KEY idx_integration_feishu_import_template_dependency_template (
        tenant_id, source_system, template_code, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入模板依赖';

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_STAFF', '飞书销售人员',
       'HR', 'EMPLOYEE',
       JSON_ARRAY('销售人员信息', '渡江战役团队管理'),
       JSON_ARRAY('姓名'),
       JSON_ARRAY('销售姓名', '人员编号', '员工编号', '员工编码', '工号', 'ID', '手机号', '姓名'),
       JSON_ARRAY('创建时间', '新建时间', '入职日期', '销售姓名'),
       'FIELD_VALUES',
       JSON_ARRAY('手机号', '姓名', '销售姓名'),
       1, 1, '人员主数据落 HR，IAM 只做认证授权'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_SALES_STAFF'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_REGION', '飞书区域城市',
       'CRM', 'REGION',
       JSON_ARRAY('大区数据'),
       JSON_ARRAY(),
       JSON_ARRAY('城市人仓', '销售区域', '城市', '编号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '日期'),
       'FIELD_VALUES',
       JSON_ARRAY('销售区域', '城市', '城市人仓'),
       1, 1, '区域和城市作为 CRM 主数据'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_REGION'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_STORE', '飞书门店',
       'CRM', 'STORE',
       JSON_ARRAY('门店信息库'),
       JSON_ARRAY('门店编码', '门店名称'),
       JSON_ARRAY('门店编码', '门店编码名称', '商家编号', '商家编号名称', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('门店编码', '门店名称'),
       1, 1, '门店先落 CRM，再供订单引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_STORE'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_CUSTOMER', '飞书商家',
       'CRM', 'CUSTOMER',
       JSON_ARRAY('商家库'),
       JSON_ARRAY('商家编号'),
       JSON_ARRAY('商家编号', '商家编号名称', '客户编号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('商家编号', '商家名称'),
       1, 1, '商家先落 CRM，再供订单引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_CUSTOMER'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_PRODUCT', '飞书产品',
       'ERP', 'PRODUCT',
       JSON_ARRAY('产品信息库'),
       JSON_ARRAY('产品编码', '产品名称'),
       JSON_ARRAY('产品编码', '产品编码名称', '商品编号', '商品编码', 'SKU编码', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('产品编码', '产品名称', '规格'),
       1, 1, '商品先落 ERP，再供订单行引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_PRODUCT'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', '飞书销售订单',
       'ORDER', 'SALES_ORDER',
       JSON_ARRAY('销售订单表', '武汉销售订单表'),
       JSON_ARRAY('订单编号', '创建时间'),
       JSON_ARRAY('订单编号', '订单编号门店', '来源单号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '下单时间', '订单时间', '销售日期'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('订单编号'),
       1, 1, '订单依赖 CRM 客户/门店和 ERP 商品'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_SALES_ORDER'
);

INSERT INTO integration_feishu_import_template_dependency (
    id, tenant_id, source_system, template_code, depends_on_template_code, relation_kind,
    source_reference_fields_json, target_reference_fields_json, required_flag, enabled_flag
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_STORE',
       'ORDER_CUSTOMER',
       JSON_ARRAY('关联门店', '门店', '客户名称', '客户', '门店编码'),
       JSON_ARRAY('门店编码', '门店名称', '客户名称'),
       1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template_dependency
    WHERE tenant_id IS NULL
      AND source_system = 'FEISHU'
      AND template_code = 'FEISHU_SALES_ORDER'
      AND depends_on_template_code = 'FEISHU_STORE'
      AND relation_kind = 'ORDER_CUSTOMER'
);

INSERT INTO integration_feishu_import_template_dependency (
    id, tenant_id, source_system, template_code, depends_on_template_code, relation_kind,
    source_reference_fields_json, target_reference_fields_json, required_flag, enabled_flag
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_PRODUCT',
       'ORDER_PRODUCT',
       JSON_ARRAY('订单产品', '产品编号', '产品名称', '商品名称', '商品编码', 'SKU编码'),
       JSON_ARRAY('产品编码', '产品名称', '商品名称', '规格'),
       1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template_dependency
    WHERE tenant_id IS NULL
      AND source_system = 'FEISHU'
      AND template_code = 'FEISHU_SALES_ORDER'
      AND depends_on_template_code = 'FEISHU_PRODUCT'
      AND relation_kind = 'ORDER_PRODUCT'
);
