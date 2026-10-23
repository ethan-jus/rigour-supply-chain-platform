-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__business_dictionary.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='公共业务字典';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='公共业务字典项';

-- Source: V2__business_dictionary_revision.sql
-- 公共业务字典整本内容版本。
-- 字典定义或任一条目变化时递增，供消费者做批次快照审计和缓存失效判断。
ALTER TABLE biz_dict
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '整本字典内容版本，定义或任一条目变化时递增'
        AFTER version;

-- Source: V3__business_dictionary_source_value.sql
-- 来源值用于第三方枚举和单位的精确幂等匹配。
-- 使用二进制排序规则，避免大小写或重音差异被数据库误判为同一个来源值。
ALTER TABLE biz_dict_item
    MODIFY COLUMN value VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL
        COMMENT '可选业务或来源值；第三方同步按原值精确匹配',
    ADD CONSTRAINT uk_biz_dict_item_value UNIQUE (dict_id, value);

-- Source: V4__seed_sync_dictionary_definitions.sql
-- 为当前已实现的订货宝同步白名单预置模块级字典定义。
-- 只预置定义，不猜测来源值含义；字典项由各领域同步按来源原值和明确名称自动补齐。
INSERT INTO biz_dict
    (id, code, name, scope_type, scope_id, module_code, tenant_id, base_dict_id,
     status, sort_no, remark, version, revision, created_by, updated_by, created_at, updated_at)
VALUES
    ('10000000-0000-7000-8000-000000000001', 'DHB_UNIT', '订货宝计量单位', 'MODULE', 'COMMON', 'COMMON', NULL, NULL, 'ACTIVE', 10, 'ERP、CRM、Order共用的订货宝来源计量单位；按来源值精确同步', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('20000000-0000-7000-8000-000000000001', 'DHB_PRODUCT_PUTAWAY', '订货宝商品上下架状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 10, '商品putaway白名单来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000002', 'DHB_MIN_ORDER_UNIT_LEVEL', '订货宝最小订货单位层级', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 20, '商品minimumOrderUnit白名单来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000003', 'DHB_PURCHASE_ORDER_STATUS', '订货宝采购单状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 30, '采购单来源状态及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000004', 'DHB_PURCHASE_PAYMENT_STATUS', '订货宝采购付款状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 40, '采购单付款状态及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000005', 'DHB_PURCHASE_RETURN_STATUS', '订货宝采购退货状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 50, '采购退货单来源状态及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000006', 'DHB_WAREHOUSING_STATUS', '订货宝入库单状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 60, '入库单来源状态及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000007', 'DHB_WAREHOUSING_TYPE', '订货宝入库类型', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 70, '入库单类型ID及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000008', 'DHB_WAREHOUSE_STATUS', '订货宝仓库状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 80, '仓库来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('30000000-0000-7000-8000-000000000001', 'DHB_CUSTOMER_STATUS', '订货宝客户状态', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 10, '客户来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('30000000-0000-7000-8000-000000000002', 'DHB_CUSTOMER_CLEARING_FORM', '订货宝客户结算方式', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 20, '客户clientClearingForm明确字段', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('30000000-0000-7000-8000-000000000003', 'DHB_STAFF_STATUS', '订货宝职员状态', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 30, '职员来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('30000000-0000-7000-8000-000000000004', 'DHB_ADDRESS_STATUS', '订货宝地址状态', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 40, '客户地址来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('40000000-0000-7000-8000-000000000001', 'DHB_ORDER_STATUS', '订货宝订单状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 10, '订单来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000002', 'DHB_ORDER_PAYMENT_STATUS', '订货宝订单收款状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 20, '订单PayStatus来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000003', 'DHB_ORDER_TYPE', '订货宝订单类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 30, '订单类型来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000004', 'DHB_ORDER_API_STATUS', '订货宝订单下载状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 40, '订单API下载状态来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000005', 'DHB_ORDER_EXCEPTION_STATUS', '订货宝订单异常标记', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 50, '订单异常标记来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000006', 'DHB_ORDER_SEND_TYPE', '订货宝订单发货方式', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 60, '订单发货方式来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000007', 'DHB_ORDER_DEVICE', '订货宝下单设备', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 70, '订单来源设备', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000008', 'DHB_ORDER_ADMIN_FLAG', '订货宝管理员订单标记', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 80, '管理员订单来源标记', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000009', 'DHB_ORDER_SPLIT_TYPE', '订货宝拆单类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 90, '拆单类型及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000010', 'DHB_SETTLEMENT_METHOD', '订货宝结算方式', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 100, '订单结算方式来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000011', 'DHB_INVOICE_TYPE', '订货宝发票类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 110, '订单发票类型来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000012', 'DHB_SHIPMENT_STATUS', '订货宝出库发货状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 120, '发货单和物流快照来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000013', 'DHB_SHIPMENT_TYPE', '订货宝出库类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 130, '发货单出库类型ID及来源名称', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000014', 'DHB_RETURN_STATUS', '订货宝销售退货状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 140, '销售退货单来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000015', 'DHB_RETURN_TYPE', '订货宝销售退货类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 150, '退货退款类型来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000016', 'DHB_RETURN_DELIVERY_MODE', '订货宝退货配送方式', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 160, '退货配送方式来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000017', 'DHB_FINANCIAL_DOCUMENT_TYPE', '订货宝收付款单类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 170, '收款单和付款单类型', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000018', 'DHB_FINANCIAL_BUSINESS_TYPE', '订货宝收付款业务类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 180, '收付款IncexpId来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000019', 'DHB_PAYMENT_METHOD', '订货宝支付方式', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 190, '收付款TypeId来源值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000020', 'DHB_FINANCIAL_STATUS', '订货宝收付款状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 200, '收付款单来源状态', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = id;

-- Source: V5__complete_domain_dictionary_definitions.sql
-- 补齐 ERP、CRM、Order 已明确建模且需要业务翻译的有限枚举字段。
-- 来源字典只预置定义，字典项由各领域同步按订货宝原值自动补齐；不在 SQL 中猜测来源值含义。
-- 可直接判定的布尔技术标记和单条业务数据自身的层级不建字典，避免将普通字段误当成可维护映射。
INSERT INTO biz_dict
    (id, code, name, scope_type, scope_id, module_code, tenant_id, base_dict_id,
     status, sort_no, remark, version, revision, created_by, updated_by, created_at, updated_at)
VALUES
    ('20000000-0000-7000-8000-000000000009', 'DHB_PRODUCT_STATUS', '订货宝商品状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 5, '商品来源报文status原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000012', 'DHB_PURCHASE_RETURN_DEVICE', '订货宝采购退货来源设备', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 110, '采购退货单sourceDevice原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('20000000-0000-7000-8000-000000000014', 'DHB_WAREHOUSING_SPLIT_TYPE', '订货宝入库拆单类型', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 130, '入库单splitType原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('30000000-0000-7000-8000-000000000005', 'DHB_STAFF_TYPE', '订货宝员工类型', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 35, '员工staff_type原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('40000000-0000-7000-8000-000000000022', 'DHB_ORDER_LINE_TYPE', '订货宝订单明细商品类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 220, '订单明细contentType原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000024', 'DHB_GOODS_LIST_TYPE', '订货宝商品明细类型', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 240, '出库及待出库明细listType原值', 0, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = id;

-- 历史预置中两个定义不符合字典边界：
-- minimumOrderUnit 需结合当前商品的多级单位解释，不是全局枚举；地址接口没有明确的状态字段。
UPDATE biz_dict
SET status = 'DISABLED',
    remark = CASE code
        WHEN 'DHB_MIN_ORDER_UNIT_LEVEL' THEN '非全局字典；最小订货单位须结合商品单位关系展示'
        WHEN 'DHB_ADDRESS_STATUS' THEN '订货宝地址接口未提供明确状态，不生成映射'
        ELSE remark
    END,
    version = version + 1,
    revision = revision + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE scope_type = 'MODULE'
  AND ((module_code = 'ERP' AND code = 'DHB_MIN_ORDER_UNIT_LEVEL')
    OR (module_code = 'CRM' AND code = 'DHB_ADDRESS_STATUS'))
  AND status <> 'DISABLED';

-- 平台内部状态不是订货宝来源值，单独预置，避免前端将两者混用。
INSERT INTO biz_dict
    (id, code, name, scope_type, scope_id, module_code, tenant_id, base_dict_id,
     status, sort_no, remark, version, revision, created_by, updated_by, created_at, updated_at)
VALUES
    ('20000000-0000-7000-8000-000000000015', 'INTERNAL_STATUS', 'ERP内部业务状态', 'MODULE', 'ERP', 'ERP', NULL, NULL, 'ACTIVE', 1, '平台ERP内部状态，不等同于订货宝上下架状态', 0, 1, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('30000000-0000-7000-8000-000000000007', 'INTERNAL_STATUS', 'CRM内部业务状态', 'MODULE', 'CRM', 'CRM', NULL, NULL, 'ACTIVE', 1, '平台CRM内部状态，不等同于订货宝客户状态', 0, 1, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('40000000-0000-7000-8000-000000000025', 'INTERNAL_STATUS', 'Order内部业务状态', 'MODULE', 'ORDER', 'ORDER', NULL, NULL, 'ACTIVE', 1, '平台订单流程状态，不等同于订货宝订单状态', 0, 1, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO biz_dict_item
    (id, dict_id, parent_id, level_no, code, name, value, sort_no, status,
     extra_json, version, created_by, updated_by, created_at, updated_at)
VALUES
    ('21000000-0000-7000-8000-000000000001', '20000000-0000-7000-8000-000000000015', NULL, 1, 'ACTIVE', '启用', 'ACTIVE', 10, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('21000000-0000-7000-8000-000000000002', '20000000-0000-7000-8000-000000000015', NULL, 1, 'INACTIVE', '停用', 'INACTIVE', 20, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('31000000-0000-7000-8000-000000000001', '30000000-0000-7000-8000-000000000007', NULL, 1, 'ACTIVE', '启用', 'ACTIVE', 10, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('31000000-0000-7000-8000-000000000002', '30000000-0000-7000-8000-000000000007', NULL, 1, 'INACTIVE', '停用', 'INACTIVE', 20, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),

    ('41000000-0000-7000-8000-000000000001', '40000000-0000-7000-8000-000000000025', NULL, 1, 'RECEIVED', '已接收', 'RECEIVED', 10, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000002', '40000000-0000-7000-8000-000000000025', NULL, 1, 'PENDING_CONFIRMATION', '待确认', 'PENDING_CONFIRMATION', 20, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000003', '40000000-0000-7000-8000-000000000025', NULL, 1, 'ALLOCATING', '库存分配中', 'ALLOCATING', 30, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000004', '40000000-0000-7000-8000-000000000025', NULL, 1, 'SHIPPED', '已发货', 'SHIPPED', 40, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000005', '40000000-0000-7000-8000-000000000025', NULL, 1, 'COMPLETED', '已完成', 'COMPLETED', 50, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000006', '40000000-0000-7000-8000-000000000025', NULL, 1, 'CANCELLED', '已取消', 'CANCELLED', 60, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    ('41000000-0000-7000-8000-000000000007', '40000000-0000-7000-8000-000000000025', NULL, 1, 'EXCEPTION', '异常', 'EXCEPTION', 70, 'ACTIVE', NULL, 0, NULL, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id = id;

-- Source: V6__seed_verified_dhb_enum_mappings.sql
-- 只补齐订货宝官方接口文档已明确给出“原值 -> 中文含义”的有限枚举。
-- V5 已在共享 DEV 执行，本迁移负责向前修复，禁止回写或删除历史迁移。
-- 使用 MySQL 8 的表值构造器，不要求迁移账号拥有 CREATE TEMPORARY TABLES 权限。
INSERT INTO biz_dict_item
    (id, dict_id, parent_id, level_no, code, name, value, sort_no, status,
     extra_json, version, created_by, updated_by, created_at, updated_at)
SELECT UUID(), d.id, NULL, 1,
       CONCAT('SEED_', UPPER(SUBSTRING(SHA2(CONCAT(m.module_code, CHAR(0), m.dict_code,
           CHAR(0), m.source_value), 256), 1, 59))),
       m.display_name, m.source_value, m.sort_no, 'ACTIVE', NULL, 0, NULL, NULL,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM (
    VALUES
    -- ERP：getGoodsList、getPurchaseList、getPurchaseReturnList、getWarehousingList、getStockInfo。
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'T', '正常', 10),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'F', '回收站', 20),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'A', '待审', 30),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'N', '未通过', 40),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'R', '撤销', 50),
    ROW('ERP', 'DHB_PRODUCT_PUTAWAY', 'T', '上架', 10),
    ROW('ERP', 'DHB_PRODUCT_PUTAWAY', 'F', '下架', 20),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'pending', '待审核', 10),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'wh_up', '待入库', 20),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'wh_half', '部分入库', 30),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'cancelled', '已取消', 40),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'finished', '已完成', 50),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'oblig', '待付款', 10),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'uncollect', '部分付款', 20),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'paided', '已付款', 30),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'stock_up', '待出库', 10),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'cancelled', '已取消', 20),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'refunds', '待退款', 30),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'finished', '已完成', 40),
    ROW('ERP', 'DHB_WAREHOUSING_STATUS', 'throughed', '已审核', 10),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '-1', '退货入库', 10),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '1', '采购入库', 20),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '3', '盘盈入库', 30),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '8', '调拨入库', 40),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '20', '运营入库', 50),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '21', '红冲销售出库', 60),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '7', '其他入库', 70),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '9', '联营入库', 80),
    ROW('ERP', 'DHB_WAREHOUSE_STATUS', 'T', '正常', 10),
    ROW('ERP', 'DHB_WAREHOUSE_STATUS', 'F', '停用', 20),

    -- CRM：getDealersList、getStaffList。
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'T', '正常', 10),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'F', '停用', 20),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'A', '待激活', 30),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'C', '待审核', 40),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'prepaid', '预付', 10),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'forward', '现付', 20),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'postpaid', '后付', 30),
    ROW('CRM', 'DHB_STAFF_STATUS', 'T', '启用', 10),
    ROW('CRM', 'DHB_STAFF_STATUS', 'F', '停用', 20),
    ROW('CRM', 'DHB_STAFF_TYPE', 'salesman', '业务员', 10),
    ROW('CRM', 'DHB_STAFF_TYPE', 'boss', '老板', 20),
    ROW('CRM', 'DHB_STAFF_TYPE', 'indoorwork', '内勤', 30),
    ROW('CRM', 'DHB_STAFF_TYPE', 'driver', '司机', 40),

    -- Order：getOrderList、getOrderContent。
    ROW('ORDER', 'DHB_ORDER_STATUS', 'pricing', '待核价', 10),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'pending', '待审核', 20),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'stock_up', '待出库', 30),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'stockup', '待出库', 40),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'shipped', '待发货', 50),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'received', '待收货', 60),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'finished', '已完成', 70),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'forcedone', '强制完成', 80),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'cancelled', '已取消', 90),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'oblig', '待收款', 10),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'uncollect', '部分收款', 20),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'paided', '已收款', 30),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'wait', '待确认', 50),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'part', '部分确认', 60),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'unoblig', '待确认付款', 70),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'normal', '普通订单', 10),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'C', '经销商提交', 20),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'M', '管理端代提交', 30),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'S', '业务员代提交', 40),
    ROW('ORDER', 'DHB_ORDER_API_STATUS', 'F', '未下载', 10),
    ROW('ORDER', 'DHB_ORDER_API_STATUS', 'T', '已下载', 20),
    ROW('ORDER', 'DHB_ORDER_EXCEPTION_STATUS', 'F', '正常', 10),
    ROW('ORDER', 'DHB_ORDER_EXCEPTION_STATUS', 'T', '异常', 20),
    ROW('ORDER', 'DHB_ORDER_ADMIN_FLAG', 'T', '管理端下单', 10),
    ROW('ORDER', 'DHB_ORDER_ADMIN_FLAG', 'F', '订货端下单', 20),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'prepaid', '预付', 10),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'forward', '现付', 20),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'postpaid', '后付', 30),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'P', '普通发票', 10),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'Z', '增值税发票', 20),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'F', '无发票', 30),
    ROW('ORDER', 'DHB_ORDER_LINE_TYPE', 'c', '正常售卖', 10),
    ROW('ORDER', 'DHB_ORDER_LINE_TYPE', 'g', '赠品', 20),

    -- Order：getWaitShips、getShipsList、getReturnsList。
    ROW('ORDER', 'DHB_GOODS_LIST_TYPE', 'buy', '买', 10),
    ROW('ORDER', 'DHB_GOODS_LIST_TYPE', 'gift', '赠', 20),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'shipped', '待发货', 10),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'receivedin', '待收货', 20),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'received', '已收货', 30),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '-2', '采购退货', 10),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '10', '销售出库', 20),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '11', '盘亏出库', 30),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '17', '其他出库', 40),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '18', '调拨出库', 50),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '19', '联营出库', 60),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'return_audit', '待退货审核', 10),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'shipp_cust', '待客户发货', 20),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'shipped', '待收货', 30),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'refunded', '待退款', 40),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'finished', '已完成', 50),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'cancelled', '已取消', 60),
    ROW('ORDER', 'DHB_RETURN_TYPE', '0', '未确认', 10),
    ROW('ORDER', 'DHB_RETURN_TYPE', '1', '退货退款', 20),
    ROW('ORDER', 'DHB_RETURN_TYPE', '2', '仅退款', 30),

    -- Order：getReceiptsList、getPaymentList；RECEIPT/PAYMENT 是平台稳定归一化值。
    ROW('ORDER', 'DHB_FINANCIAL_DOCUMENT_TYPE', 'RECEIPT', '收款单', 10),
    ROW('ORDER', 'DHB_FINANCIAL_DOCUMENT_TYPE', 'PAYMENT', '付款单', 20),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '1', '普通充值', 10),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '19', '预付款充值', 20),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '13', '订单收款', 30),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '8', '期初充值', 40),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '2', '退货退款', 50),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '10', '退款失败回冲', 60),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '9', '退款红冲', 70),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '5', '预存款扣款', 80),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'pend_receipt', '待确认', 10),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'pend_receipted', '已确认', 20),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'canceled', '已取消', 30),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Alipay', '支付宝支付（原生）', 10),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Quick', '快捷支付', 20),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Micro', '微信支付（原生）', 30),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Offline', '转账支付', 40),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Deposit', '预存款支付', 50),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Delivery', '货到付款', 60),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Credit', '赊销支付', 70),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Rebate', '返利支付', 80),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Alipay', '支付宝支付', 90),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Wechat', '微信支付', 100),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Quick', '银联快捷', 110),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Netbank', '网银支付', 120),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Wechat', 'iOS移动管理端微信支付', 130),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Alipay', 'iOS移动管理端支付宝支付', 140),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Wechat', 'Android移动管理端微信支付', 150),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Alipay', 'Android移动管理端支付宝支付', 160),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Account_Bank_Transfer', '中金来账识别', 170),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Mybank_Cloud', '支付宝云资金', 180),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Refund_Fail_Recharge', '退款失败回充', 190),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Mse', '微企付支付', 200),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Hht', '品牌资金账户', 210),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Alipay_Transfer', '支付宝转账', 220),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'WECHAT_B2B_DIRECT', '微信B2B支付', 230),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'MYBANK_BALANCE', '云资金余额支付', 240),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Refund_Red_Reversal', '退款红冲', 250),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Yw_Pay', '云闪付', 260),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Tp_Pay', '通企付', 270),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_MINIAPP', '微信支付（通企付）', 280),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY', '支付宝支付（通企付）', 290),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_QR', '吱口令（通企付）', 300),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_YT_PAY', '云闪付（通企付）', 310),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_QUICK', '银联快捷（通企付）', 320),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_FRIEND', '微信好友代付（通企付）', 330),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_SCAN_POS', '微信扫码支付（通企付）', 340),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_SCAN_POS', '支付宝扫码支付（通企付）', 350)
) AS m(module_code, dict_code, source_value, display_name, sort_no)
JOIN biz_dict d
  ON d.scope_type = 'MODULE'
 AND d.scope_id = m.module_code
 AND d.module_code = m.module_code
 AND d.code = m.dict_code
ON DUPLICATE KEY UPDATE
    updated_at = IF(biz_dict_item.name = biz_dict_item.value, UTC_TIMESTAMP(6), biz_dict_item.updated_at),
    version = IF(biz_dict_item.name = biz_dict_item.value, biz_dict_item.version + 1, biz_dict_item.version),
    name = IF(biz_dict_item.name = biz_dict_item.value, VALUES(name), biz_dict_item.name);

UPDATE biz_dict d
SET d.revision = d.revision + 1,
    d.updated_at = UTC_TIMESTAMP(6)
WHERE EXISTS (
    SELECT 1
    FROM biz_dict_item i
    WHERE i.dict_id = d.id
      AND i.code LIKE 'SEED\_%' ESCAPE '\\'
);

-- 官方文档未给出固定取值映射，或字段允许任意业务文本；这些定义不能冒充枚举。
UPDATE biz_dict
SET status = 'DISABLED',
    remark = CASE code
        WHEN 'DHB_PURCHASE_RETURN_DEVICE' THEN '官方接口未给出完整设备枚举，不自动猜测映射'
        WHEN 'DHB_WAREHOUSING_SPLIT_TYPE' THEN '官方接口仅返回split_type原值，未给出固定值含义'
        WHEN 'DHB_ORDER_SEND_TYPE' THEN '发货方式为业务配置文本，不是固定编码枚举'
        WHEN 'DHB_ORDER_DEVICE' THEN '官方接口仅说明下单渠道，未给出完整编码枚举'
        WHEN 'DHB_RETURN_DELIVERY_MODE' THEN '退单配送方式为快递、物流等业务文本，不是固定编码枚举'
        ELSE remark
    END,
    version = version + 1,
    revision = revision + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE scope_type = 'MODULE'
  AND ((module_code = 'ERP' AND code IN ('DHB_PURCHASE_RETURN_DEVICE', 'DHB_WAREHOUSING_SPLIT_TYPE'))
    OR (module_code = 'ORDER' AND code IN ('DHB_ORDER_SEND_TYPE', 'DHB_ORDER_DEVICE',
                                           'DHB_RETURN_DELIVERY_MODE')))
  AND status <> 'DISABLED';

-- orders_units 的 base_units/middle_units/container_units 是单位层级代码，不是计量单位名称。
UPDATE biz_dict_item i
JOIN biz_dict d ON d.id = i.dict_id
SET i.status = 'DISABLED',
    i.version = i.version + 1,
    i.updated_at = UTC_TIMESTAMP(6)
WHERE d.scope_type = 'MODULE'
  AND d.module_code = 'COMMON'
  AND d.code = 'DHB_UNIT'
  AND i.value IN ('base_units', 'middle_units', 'container_units', 'big_units')
  AND i.status <> 'DISABLED';

UPDATE biz_dict
SET revision = revision + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE scope_type = 'MODULE'
  AND module_code = 'COMMON'
  AND code = 'DHB_UNIT';

-- Source: V7__rename_business_dictionary_tables.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典项';

-- Source: V8__seed_internal_business_dictionaries.sql
-- 自研业务基线字典。
-- 表名、字段名统一 lower_snake_case；字典编码和字典项编码统一 UPPER_SNAKE_CASE。
-- 业务表只保存 dictionary_item_code，不保存订货宝状态值；订货宝映射后续放在 Integration。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', '商品单位', 'COMMON', '商品订货、采购、库存展示单位', 1, 'SYSTEM', 'SYSTEM', 0),
    ('REGION', '业务地区', 'COMMON', '客户归属地区与仓库地区匹配', 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SUBMIT_STATUS', '商品提交状态', 'ERP', '商品保存草稿与提交校验状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', '商品售卖类型', 'ERP', '商品现货、预售、停售', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SHELF_STATUS', '商品上架状态', 'ERP', '商品上架与下架', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', '商品标签类型', 'ERP', '新品、推荐、热销等商品标签', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SUPPLIER_STATUS', '供应商状态', 'ERP', '供应商启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_STATUS', '仓库状态', 'ERP', '仓库启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', '采购订单状态', 'ERP', '采购订单创建、提交、入库状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_TYPE', '入库类型', 'ERP', '采购入库、调拨入库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', '入库单状态', 'ERP', '入库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', '出库类型', 'ERP', '销售出库、调拨出库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', '出库单状态', 'ERP', '出库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', '调拨单状态', 'ERP', '调拨单出库与入库确认状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_STATUS', '客户状态', 'CRM', '客户启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_SETTLEMENT_TYPE', '客户结算类型', 'CRM', 'A类商家、B类商家等结算分类', 1, 'SYSTEM', 'SYSTEM', 0),

    ('ORDER_TYPE', '销售订单类型', 'ORDER', '新拓、复购等销售订单类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', '付款方式', 'ORDER', '全款、月结等付款方式', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', '付款状态', 'ORDER', '销售订单回款状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', '出库状态', 'ORDER', '销售订单出库状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'BUCKET', '桶', '泡面等桶装商品单位', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'BOX', '箱', '箱装商品单位', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'PORTION', '份', '按份售卖商品单位', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'SET', '套', '套装商品单位', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SUBMIT_STATUS', 1, NULL, 'DRAFT', '草稿', '保存，允许必填项暂不完整', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SUBMIT_STATUS', 1, NULL, 'SUBMITTED', '已提交', '提交，服务端校验必填项完整', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SALE_TYPE', 1, NULL, 'SPOT', '现货', '有货或可正常销售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', 1, NULL, 'PRE_SALE', '预售', '允许预售下单', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', 1, NULL, 'STOP_SALE', '停售', '停止销售但保留历史数据', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SHELF_STATUS', 1, NULL, 'ON_SHELF', '上架', '前台可见可售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SHELF_STATUS', 1, NULL, 'OFF_SHELF', '下架', '前台不可售', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_TAG_TYPE', 1, NULL, 'NEW', '新品', '新品标签', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', 1, NULL, 'RECOMMENDED', '推荐', '推荐标签', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', 1, NULL, 'HOT', '热销', '热销标签', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('SUPPLIER_STATUS', 1, NULL, 'ACTIVE', '启用', '供应商可用于采购下单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SUPPLIER_STATUS', 1, NULL, 'DISABLED', '停用', '供应商暂不可用于新采购', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('WAREHOUSE_STATUS', 1, NULL, 'ACTIVE', '启用', '仓库可用于入库、出库和调拨', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_STATUS', 1, NULL, 'DISABLED', '停用', '仓库暂不可用于新库存业务', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_STATUS', 1, NULL, 'ACTIVE', '启用', '客户可用于销售下单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_STATUS', 1, NULL, 'DISABLED', '停用', '客户暂不可用于新业务', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_SETTLEMENT_TYPE', 1, NULL, 'A', 'A类商家', 'A类客户结算分类', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_SETTLEMENT_TYPE', 1, NULL, 'B', 'B类商家', 'B类客户结算分类', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('ORDER_TYPE', 1, NULL, 'NEW', '新拓', '新客户首单或拓展订单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ORDER_TYPE', 1, NULL, 'REPEAT', '复购', '老客户复购订单', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PAYMENT_METHOD', 1, NULL, 'FULL', '全款', '下单或出库前付清', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'MONTHLY', '月结', '按账期结算', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PAYMENT_STATUS', 1, NULL, 'UNPAID', '未付款', '订单尚未回款', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', 1, NULL, 'PARTIAL_PAID', '部分付款', '订单已有部分回款', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', 1, NULL, 'PAID', '已结清', '订单已收清款项', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('OUTBOUND_STATUS', 1, NULL, 'PENDING', '待出库', '订单尚未完成出库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'PARTIAL', '部分出库', '订单已有部分商品出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'COMPLETED', '已出库', '订单已全部出库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'SHORTAGE', '缺货', '当前库存不足，需要采购或调拨', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PURCHASE_STATUS', 1, NULL, 'DRAFT', '草稿', '采购单保存未提交', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'SUBMITTED', '已提交', '采购单已提交供应商或内部确认', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'PARTIAL_IN', '部分入库', '采购商品部分到货入库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'COMPLETED', '已入库', '采购商品已全部入库', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_IN_TYPE', 1, NULL, 'PURCHASE', '采购入库', '采购到货后确认入库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_TYPE', 1, NULL, 'TRANSFER', '调拨入库', '调拨到货后确认入库', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_IN_STATUS', 1, NULL, 'DRAFT', '草稿', '入库单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', 1, NULL, 'CONFIRMED', '已入库', '入库单已确认并写库存', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', 1, NULL, 'CANCELLED', '已取消', '入库单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_OUT_TYPE', 1, NULL, 'SALES', '销售出库', '销售订单确认出库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'TRANSFER', '调拨出库', '调拨单确认出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_OUT_STATUS', 1, NULL, 'DRAFT', '草稿', '出库单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', 1, NULL, 'CONFIRMED', '已出库', '出库单已确认并写库存', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', 1, NULL, 'CANCELLED', '已取消', '出库单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('TRANSFER_STATUS', 1, NULL, 'DRAFT', '草稿', '调拨单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'OUT_CONFIRMED', '已确认出库', '来源仓库已确认出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'IN_CONFIRMED', '已确认入库', '目标仓库已确认入库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'CANCELLED', '已取消', '调拨单已取消', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V9__seed_dhb_source_dictionaries_for_new_model.sql
-- 补齐新字典模型中的订货宝来源字典。
-- V1-V6 属于旧 biz_dict 模型；V7/V8 切换到 data_dictionary 后，需要把仍被 ERP/CRM/Order
-- 同步白名单使用的来源字典定义迁入新表。这里不回写历史迁移，也不把来源值写入业务主表。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('DHB_UNIT', '订货宝计量单位', 'COMMON', 'ERP、CRM、Order共用的订货宝来源计量单位；按来源值精确同步', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_PRODUCT_STATUS', '订货宝商品状态', 'ERP', '商品来源报文status原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PRODUCT_PUTAWAY', '订货宝商品上下架状态', 'ERP', '商品putaway来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_ORDER_STATUS', '订货宝采购单状态', 'ERP', '采购单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_PAYMENT_STATUS', '订货宝采购付款状态', 'ERP', '采购单付款状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_RETURN_STATUS', '订货宝采购退货状态', 'ERP', '采购退货单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSING_STATUS', '订货宝入库单状态', 'ERP', '入库单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSING_TYPE', '订货宝入库类型', 'ERP', '入库单类型ID及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSE_STATUS', '订货宝仓库状态', 'ERP', '仓库来源状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_CUSTOMER_STATUS', '订货宝客户状态', 'CRM', '客户来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_CUSTOMER_CLEARING_FORM', '订货宝客户结算方式', 'CRM', '客户clientClearingForm明确字段', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_STAFF_STATUS', '订货宝职员状态', 'CRM', '职员来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_STAFF_TYPE', '订货宝员工类型', 'CRM', '员工staff_type原值', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_ORDER_STATUS', '订货宝订单状态', 'ORDER', '订单来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_PAYMENT_STATUS', '订货宝订单收款状态', 'ORDER', '订单PayStatus来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_TYPE', '订货宝订单类型', 'ORDER', '订单类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_API_STATUS', '订货宝订单下载状态', 'ORDER', '订单API下载状态来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_EXCEPTION_STATUS', '订货宝订单异常标记', 'ORDER', '订单异常标记来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_ADMIN_FLAG', '订货宝管理员订单标记', 'ORDER', '管理员订单来源标记', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_SPLIT_TYPE', '订货宝拆单类型', 'ORDER', '拆单类型及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SETTLEMENT_METHOD', '订货宝结算方式', 'ORDER', '订单结算方式来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_INVOICE_TYPE', '订货宝发票类型', 'ORDER', '订单发票类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_LINE_TYPE', '订货宝订单明细商品类型', 'ORDER', '订单明细contentType原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SHIPMENT_STATUS', '订货宝出库发货状态', 'ORDER', '发货单和物流快照来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SHIPMENT_TYPE', '订货宝出库类型', 'ORDER', '发货单出库类型ID及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_GOODS_LIST_TYPE', '订货宝商品明细类型', 'ORDER', '出库及待出库明细listType原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_RETURN_STATUS', '订货宝销售退货状态', 'ORDER', '销售退货单来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_RETURN_TYPE', '订货宝销售退货类型', 'ORDER', '退货退款类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_DOCUMENT_TYPE', '订货宝收付款单类型', 'ORDER', '收款单和付款单类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_BUSINESS_TYPE', '订货宝收付款业务类型', 'ORDER', '收付款IncexpId来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PAYMENT_METHOD', '订货宝支付方式', 'ORDER', '收付款TypeId来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_STATUS', '订货宝收付款状态', 'ORDER', '收付款单来源状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
SELECT m.dict_code, 1, NULL,
       CASE
           WHEN REGEXP_LIKE(UPPER(TRIM(m.source_value)), '^[A-Z][A-Z0-9_]{0,49}$')
               THEN UPPER(TRIM(m.source_value))
           ELSE CONCAT('AUTO_', UPPER(SUBSTRING(SHA2(CONCAT(m.dict_code, CHAR(0), m.source_value), 256), 1, 45)))
       END AS item_code,
       m.display_name,
       CONCAT('外部来源值：', m.source_value),
       m.sort_no,
       1,
       'SYSTEM',
       'SYSTEM',
       0
FROM (
    VALUES
    ROW('DHB_PRODUCT_STATUS', 'T', '正常', 10),
    ROW('DHB_PRODUCT_STATUS', 'F', '回收站', 20),
    ROW('DHB_PRODUCT_STATUS', 'A', '待审', 30),
    ROW('DHB_PRODUCT_STATUS', 'N', '未通过', 40),
    ROW('DHB_PRODUCT_STATUS', 'R', '撤销', 50),
    ROW('DHB_PRODUCT_PUTAWAY', 'T', '上架', 10),
    ROW('DHB_PRODUCT_PUTAWAY', 'F', '下架', 20),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'pending', '待审核', 10),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'wh_up', '待入库', 20),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'wh_half', '部分入库', 30),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'finished', '已完成', 50),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'oblig', '待付款', 10),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'uncollect', '部分付款', 20),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'paided', '已付款', 30),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'stock_up', '待出库', 10),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'cancelled', '已取消', 20),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'refunds', '待退款', 30),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'finished', '已完成', 40),
    ROW('DHB_WAREHOUSING_STATUS', 'throughed', '已审核', 10),
    ROW('DHB_WAREHOUSING_TYPE', '-1', '退货入库', 10),
    ROW('DHB_WAREHOUSING_TYPE', '1', '采购入库', 20),
    ROW('DHB_WAREHOUSING_TYPE', '3', '盘盈入库', 30),
    ROW('DHB_WAREHOUSING_TYPE', '8', '调拨入库', 40),
    ROW('DHB_WAREHOUSING_TYPE', '20', '运营入库', 50),
    ROW('DHB_WAREHOUSING_TYPE', '21', '红冲销售出库', 60),
    ROW('DHB_WAREHOUSING_TYPE', '7', '其他入库', 70),
    ROW('DHB_WAREHOUSING_TYPE', '9', '联营入库', 80),
    ROW('DHB_WAREHOUSE_STATUS', 'T', '正常', 10),
    ROW('DHB_WAREHOUSE_STATUS', 'F', '停用', 20),

    ROW('DHB_CUSTOMER_STATUS', 'T', '正常', 10),
    ROW('DHB_CUSTOMER_STATUS', 'F', '停用', 20),
    ROW('DHB_CUSTOMER_STATUS', 'A', '待激活', 30),
    ROW('DHB_CUSTOMER_STATUS', 'C', '待审核', 40),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'prepaid', '预付', 10),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'forward', '现付', 20),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'postpaid', '后付', 30),
    ROW('DHB_STAFF_STATUS', 'T', '启用', 10),
    ROW('DHB_STAFF_STATUS', 'F', '停用', 20),
    ROW('DHB_STAFF_TYPE', 'salesman', '业务员', 10),
    ROW('DHB_STAFF_TYPE', 'boss', '老板', 20),
    ROW('DHB_STAFF_TYPE', 'indoorwork', '内勤', 30),
    ROW('DHB_STAFF_TYPE', 'driver', '司机', 40),

    ROW('DHB_ORDER_STATUS', 'pricing', '待核价', 10),
    ROW('DHB_ORDER_STATUS', 'pending', '待审核', 20),
    ROW('DHB_ORDER_STATUS', 'stock_up', '待出库', 30),
    ROW('DHB_ORDER_STATUS', 'stockup', '待出库', 40),
    ROW('DHB_ORDER_STATUS', 'shipped', '待发货', 50),
    ROW('DHB_ORDER_STATUS', 'received', '待收货', 60),
    ROW('DHB_ORDER_STATUS', 'finished', '已完成', 70),
    ROW('DHB_ORDER_STATUS', 'forcedone', '强制完成', 80),
    ROW('DHB_ORDER_STATUS', 'cancelled', '已取消', 90),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'oblig', '待收款', 10),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'uncollect', '部分收款', 20),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'paided', '已收款', 30),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'wait', '待确认', 50),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'part', '部分确认', 60),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'unoblig', '待确认付款', 70),
    ROW('DHB_ORDER_TYPE', 'normal', '普通订单', 10),
    ROW('DHB_ORDER_TYPE', 'C', '经销商提交', 20),
    ROW('DHB_ORDER_TYPE', 'M', '管理端代提交', 30),
    ROW('DHB_ORDER_TYPE', 'S', '业务员代提交', 40),
    ROW('DHB_ORDER_API_STATUS', 'F', '未下载', 10),
    ROW('DHB_ORDER_API_STATUS', 'T', '已下载', 20),
    ROW('DHB_ORDER_EXCEPTION_STATUS', 'F', '正常', 10),
    ROW('DHB_ORDER_EXCEPTION_STATUS', 'T', '异常', 20),
    ROW('DHB_ORDER_ADMIN_FLAG', 'T', '管理端下单', 10),
    ROW('DHB_ORDER_ADMIN_FLAG', 'F', '订货端下单', 20),
    ROW('DHB_SETTLEMENT_METHOD', 'prepaid', '预付', 10),
    ROW('DHB_SETTLEMENT_METHOD', 'forward', '现付', 20),
    ROW('DHB_SETTLEMENT_METHOD', 'postpaid', '后付', 30),
    ROW('DHB_INVOICE_TYPE', 'P', '普通发票', 10),
    ROW('DHB_INVOICE_TYPE', 'Z', '增值税发票', 20),
    ROW('DHB_INVOICE_TYPE', 'F', '无发票', 30),
    ROW('DHB_ORDER_LINE_TYPE', 'c', '正常售卖', 10),
    ROW('DHB_ORDER_LINE_TYPE', 'g', '赠品', 20),
    ROW('DHB_GOODS_LIST_TYPE', 'buy', '买', 10),
    ROW('DHB_GOODS_LIST_TYPE', 'gift', '赠', 20),
    ROW('DHB_SHIPMENT_STATUS', 'shipped', '待发货', 10),
    ROW('DHB_SHIPMENT_STATUS', 'receivedin', '待收货', 20),
    ROW('DHB_SHIPMENT_STATUS', 'received', '已收货', 30),
    ROW('DHB_SHIPMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_SHIPMENT_TYPE', '-2', '采购退货', 10),
    ROW('DHB_SHIPMENT_TYPE', '10', '销售出库', 20),
    ROW('DHB_SHIPMENT_TYPE', '11', '盘亏出库', 30),
    ROW('DHB_SHIPMENT_TYPE', '17', '其他出库', 40),
    ROW('DHB_SHIPMENT_TYPE', '18', '调拨出库', 50),
    ROW('DHB_SHIPMENT_TYPE', '19', '联营出库', 60),
    ROW('DHB_RETURN_STATUS', 'return_audit', '待退货审核', 10),
    ROW('DHB_RETURN_STATUS', 'shipp_cust', '待客户发货', 20),
    ROW('DHB_RETURN_STATUS', 'shipped', '待收货', 30),
    ROW('DHB_RETURN_STATUS', 'refunded', '待退款', 40),
    ROW('DHB_RETURN_STATUS', 'finished', '已完成', 50),
    ROW('DHB_RETURN_STATUS', 'cancelled', '已取消', 60),
    ROW('DHB_RETURN_TYPE', '0', '未确认', 10),
    ROW('DHB_RETURN_TYPE', '1', '退货退款', 20),
    ROW('DHB_RETURN_TYPE', '2', '仅退款', 30),
    ROW('DHB_FINANCIAL_DOCUMENT_TYPE', 'RECEIPT', '收款单', 10),
    ROW('DHB_FINANCIAL_DOCUMENT_TYPE', 'PAYMENT', '付款单', 20),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '1', '普通充值', 10),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '19', '预付款充值', 20),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '13', '订单收款', 30),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '8', '期初充值', 40),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '2', '退货退款', 50),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '10', '退款失败回冲', 60),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '9', '退款红冲', 70),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '5', '预存款扣款', 80),
    ROW('DHB_FINANCIAL_STATUS', 'pend_receipt', '待确认', 10),
    ROW('DHB_FINANCIAL_STATUS', 'pend_receipted', '已确认', 20),
    ROW('DHB_FINANCIAL_STATUS', 'canceled', '已取消', 30),
    ROW('DHB_PAYMENT_METHOD', 'Alipay', '支付宝支付（原生）', 10),
    ROW('DHB_PAYMENT_METHOD', 'Quick', '快捷支付', 20),
    ROW('DHB_PAYMENT_METHOD', 'Micro', '微信支付（原生）', 30),
    ROW('DHB_PAYMENT_METHOD', 'Offline', '转账支付', 40),
    ROW('DHB_PAYMENT_METHOD', 'Deposit', '预存款支付', 50),
    ROW('DHB_PAYMENT_METHOD', 'Delivery', '货到付款', 60),
    ROW('DHB_PAYMENT_METHOD', 'Credit', '赊销支付', 70),
    ROW('DHB_PAYMENT_METHOD', 'Rebate', '返利支付', 80),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Alipay', '支付宝支付', 90),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Wechat', '微信支付', 100),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Quick', '银联快捷', 110),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Netbank', '网银支付', 120),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Wechat', 'iOS移动管理端微信支付', 130),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Alipay', 'iOS移动管理端支付宝支付', 140),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Wechat', 'Android移动管理端微信支付', 150),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Alipay', 'Android移动管理端支付宝支付', 160),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Account_Bank_Transfer', '中金来账识别', 170),
    ROW('DHB_PAYMENT_METHOD', 'Mybank_Cloud', '支付宝云资金', 180),
    ROW('DHB_PAYMENT_METHOD', 'Refund_Fail_Recharge', '退款失败回充', 190),
    ROW('DHB_PAYMENT_METHOD', 'Mse', '微企付支付', 200),
    ROW('DHB_PAYMENT_METHOD', 'Hht', '品牌资金账户', 210),
    ROW('DHB_PAYMENT_METHOD', 'Alipay_Transfer', '支付宝转账', 220),
    ROW('DHB_PAYMENT_METHOD', 'WECHAT_B2B_DIRECT', '微信B2B支付', 230),
    ROW('DHB_PAYMENT_METHOD', 'MYBANK_BALANCE', '云资金余额支付', 240),
    ROW('DHB_PAYMENT_METHOD', 'Refund_Red_Reversal', '退款红冲', 250),
    ROW('DHB_PAYMENT_METHOD', 'Yw_Pay', '云闪付', 260),
    ROW('DHB_PAYMENT_METHOD', 'Tp_Pay', '通企付', 270),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_MINIAPP', '微信支付（通企付）', 280),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY', '支付宝支付（通企付）', 290),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_QR', '吱口令（通企付）', 300),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_YT_PAY', '云闪付（通企付）', 310),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_QUICK', '银联快捷（通企付）', 320),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_FRIEND', '微信好友代付（通企付）', 330),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_SCAN_POS', '微信扫码支付（通企付）', 340),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_SCAN_POS', '支付宝扫码支付（通企付）', 350)
) AS m(dict_code, source_value, display_name, sort_no)
JOIN data_dictionary d ON d.dictionary_code = m.dict_code AND d.deleted = 0
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary d
SET d.revision = d.revision + 1,
    d.updated_by = 'SYSTEM'
WHERE EXISTS (
    SELECT 1
      FROM data_dictionary_item i
     WHERE i.dictionary_code = d.dictionary_code
       AND i.updated_by = 'SYSTEM'
       AND i.deleted = 0
)
  AND d.dictionary_code LIKE 'DHB\_%' ESCAPE '\\';

-- Source: V10__drop_legacy_business_dictionary_tables.sql
-- 字典服务已切换到 data_dictionary / data_dictionary_item。
-- 旧模型表由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_business_dictionary_cleanup_deferred;

-- Source: V11__extend_erp_purchase_return_dictionaries.sql
-- 补齐 ERP 新方案运行所需字典项。
-- 业务表只存我方字典项编码；订货宝来源状态仍保留在来源字典和同步绑定中。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PURCHASE_RETURN_STATUS', '采购退货状态', 'ERP', '采购退货单创建、出库、退款、完成与取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PIECE', '件', '通用件装/单件商品单位', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'DRAFT', '草稿', '采购退货单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'WAIT_STOCK_OUT', '待出库', '采购退货单等待退货出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'REFUND_PENDING', '待退款', '采购退货单等待供应商退款或冲账', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'COMPLETED', '已完成', '采购退货单已完成', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购退货单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V12__extend_order_shipment_dictionaries.sql
-- 补齐 Order 发货单新方案所需字典。
-- 业务表只保存我方字典项编码；订货宝发货状态在同步层映射后再写入业务表。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_SHIPMENT_STATUS', '销售发货单状态', 'ORDER', '销售发货单创建、发货、签收、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CREATED', '已创建', '发货单已生成，等待实际发货', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SHIPPED', '已发货', '发货单已交付物流或配送人员', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SIGNED', '已签收', '客户已签收', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '发货单已取消', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V13__extend_order_payment_method_items.sql
-- 销售回款需要承接实际收款方式；保留原有全款/月结用于销售订单结算条款。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('PAYMENT_METHOD', 1, NULL, 'CASH', '现金', '线下现金收款', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'BANK_TRANSFER', '银行转账', '银行或网银转账收款', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'WECHAT', '微信支付', '微信或微信扫码收款', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'ALIPAY', '支付宝', '支付宝或支付宝扫码收款', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'DEPOSIT', '预存款', '客户预存款抵扣', 70, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'CREDIT', '赊销', '客户账期赊销', 80, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'OTHER', '其他', '未归类收款方式', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

-- Source: V14__extend_order_refund_dictionaries.sql
-- Business Settings V14：销售退款状态字典。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_REFUND_STATUS', '销售退款状态', 'ORDER', '销售退款待确认、已确认、已取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_REFUND_STATUS', 1, NULL, 'PENDING', '待确认', '退款记录已生成，等待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CONFIRMED', '已确认', '退款已确认并影响订单实收金额', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CANCELLED', '已取消', '退款已取消，不影响订单实收金额', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V15__extend_order_fund_document_dictionaries.sql
-- Business Settings V15：Order资金收付款单字典。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('FUND_DOCUMENT_DIRECTION', '资金单据方向', 'ORDER', '资金单据收款、付款方向', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', '资金单据业务类型', 'ORDER', '订货宝收付款单业务类型映射后的我方业务分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', '资金单据状态', 'ORDER', '资金单据待确认、已确认、已取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', '往来方类型', 'ORDER', '资金收付款单对应客户、供应商或其他往来方', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('FUND_DOCUMENT_DIRECTION', 1, NULL, 'RECEIPT', '收款', '资金流入单据', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_DIRECTION', 1, NULL, 'PAYMENT', '付款', '资金流出单据', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'ORDER_RECEIPT', '订单收款', '销售订单直接收款；可同步生成销售回款核销记录', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'CUSTOMER_RECHARGE', '客户充值', '客户预存款或余额充值产生的收款流水', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'BALANCE_DEDUCTION', '余额抵扣', '客户预存款或余额被订单抵扣产生的付款/消费流水', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'CUSTOMER_REFUND', '客户退款', '明确向客户退款的付款流水；订货宝普通付款单不默认归入此类', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'PREPAYMENT', '预收预付', '无法细分为充值或抵扣的预收预付相关流水', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'REVERSAL', '冲正回冲', '退款失败、红冲或其他资金冲正', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'OTHER', '其他', '无法明确映射的资金业务类型', 90, 1, 'SYSTEM', 'SYSTEM', 0),

    ('FUND_DOCUMENT_STATUS', 1, NULL, 'PENDING', '待确认', '资金单据待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', 1, NULL, 'CONFIRMED', '已确认', '资金单据已确认', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '资金单据已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('COUNTERPARTY_TYPE', 1, NULL, 'CUSTOMER', '客户', '客户往来方', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', 1, NULL, 'SUPPLIER', '供应商', '供应商往来方', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', 1, NULL, 'OTHER', '其他', '其他往来方', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V16__extend_product_unit_bed.sql
-- 补齐订货宝真实来源单位“床”，供商品和订单同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'BED', '床', '台呢、底布等按床计量的商品单位', 60, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V17__extend_product_unit_pair.sql
-- 补齐订货宝真实来源单位“副”，供商品和订单同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PAIR', '副', '球杆皮头等按副计量的商品单位', 70, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V18__extend_erp_stock_out_type_items.sql
-- 扩展 ERP 统一出库单类型，支持订货宝 getShipsList 除销售/调拨外的出库类型先落出库单。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('STOCK_OUT_TYPE', 1, NULL, 'PURCHASE_RETURN', '采购退货出库',
     '订货宝采购退货出库，暂先落ERP统一出库单', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'INVENTORY_LOSS', '盘亏出库',
     '订货宝盘亏出库，暂先落ERP统一出库单', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'OTHER', '其他出库',
     '订货宝其他出库，暂先落ERP统一出库单', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'JOINT_OPERATION', '联营出库',
     '订货宝联营出库，暂先落ERP统一出库单', 60, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V19__correct_crm_customer_status_dictionary.sql
-- CRM 自研客户状态与 merchant-crm 服务枚举对齐：停用统一使用 INACTIVE。
-- V8 曾将 CUSTOMER_STATUS 停用项写成 DISABLED，导致 Portal 筛选发出的状态码无法被 CRM 服务接受。

UPDATE data_dictionary_item
SET dictionary_item_name = '停用',
    remark = '客户暂不可用于新业务',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'INACTIVE';

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('CUSTOMER_STATUS', 1, NULL, 'INACTIVE', '停用', '客户暂不可用于新业务', 20, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET dictionary_item_level = 1,
    parent_dictionary_item_code = NULL,
    dictionary_item_name = '停用',
    remark = '已迁移为 CUSTOMER_STATUS/INACTIVE',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'DISABLED';

-- Source: V20__seed_verified_dhb_unit_dictionary_items.sql
-- 补齐订货宝来源计量单位原始值映射。
-- base_units/middle_units/container_units 是单位层级字段名，不是计量单位名称，不在此处建项。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('DHB_UNIT', 1, NULL, 'PIECE', '件', '订货宝来源单位原值：件', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BOX', '箱', '订货宝来源单位原值：箱', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BUCKET', '桶', '订货宝来源单位原值：桶', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PORTION', '份', '订货宝来源单位原值：份', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'SET', '套', '订货宝来源单位原值：套', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BED', '床', '订货宝来源单位原值：床', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PAIR', '副', '订货宝来源单位原值：副', 70, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'DHB_UNIT';

-- Source: V21__extend_product_unit_bottle_and_strip.sql
-- 补齐订货宝真实来源单位“瓶”“条”，供商品、订单和出入库同步落我方 PRODUCT_UNIT。
INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('PRODUCT_UNIT', 1, NULL, 'BOTTLE', '瓶', '瓶装商品单位', 80, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('PRODUCT_UNIT', 1, NULL, 'STRIP', '条', '条状或条目计量商品单位', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'PRODUCT_UNIT';

-- Source: V22__extend_erp_stock_in_return_type.sql
-- 增加 ERP 入库类型“退货入库”，避免订货宝 type_id=-1 被归入采购入库。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('STOCK_IN_TYPE', '入库类型', 'ERP', '采购入库、调拨入库、退货入库', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('STOCK_IN_TYPE', 1, NULL, 'RETURN', '退货入库', '订货宝退货产生的入库凭证', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'STOCK_IN_TYPE';

-- Source: V23__extend_order_sales_order_status_dictionary.sql
-- 增加 Order 销售订单状态字典，避免内部状态码在业务页面直接展示。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_ORDER_STATUS', '销售订单状态', 'ORDER', '销售订单草稿、提交、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_ORDER_STATUS', 1, NULL, 'DRAFT', '草稿', '销售订单保存未提交', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'SUBMITTED', '已提交', '销售订单已提交，可进入出库流程', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'SALES_ORDER_STATUS';

-- Source: V24__correct_order_outbound_status_dictionary.sql
-- 修正 Order 销售订单出库状态字典编码，与后端 SalesOrderOutboundStatus 枚举保持一致。
-- V8 曾写入 PARTIAL/COMPLETED/SHORTAGE，但当前订单服务只接受 PENDING/PARTIAL_OUT/OUT_CONFIRMED。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('OUTBOUND_STATUS', '出库状态', 'ORDER', '销售订单出库状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('OUTBOUND_STATUS', 1, NULL, 'PENDING', '待出库', '销售订单尚未生成出库结果', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'PARTIAL_OUT', '部分出库', '销售订单已有部分商品出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'OUT_CONFIRMED', '已出库', '销售订单已完成出库确认', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/PARTIAL_OUT',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'PARTIAL';

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/OUT_CONFIRMED',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'COMPLETED';

UPDATE data_dictionary_item
SET remark = '当前销售订单出库状态机未使用该状态',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'SHORTAGE';

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'OUTBOUND_STATUS';

-- Source: V25__extend_order_payment_status_cancelled.sql
-- 增加 Order 销售订单收款状态“已取消”，用于取消订单关闭应收口径。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PAYMENT_STATUS', '收款状态', 'ORDER', '销售订单回款状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('PAYMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消，不再形成待收款', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'PAYMENT_STATUS';

-- Source: V26__extend_product_unit_grain.sql
-- 补齐订货宝真实来源单位“颗”，避免订单同步把颗粒/枚数类单位静默折到“件”。
INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('PRODUCT_UNIT', 1, NULL, 'GRAIN', '颗', '颗粒或小件按颗计量的商品单位', 95, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('DHB_UNIT', 1, NULL, 'GRAIN', '颗', '订货宝来源单位原值：颗', 95, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code IN ('PRODUCT_UNIT', 'DHB_UNIT');

-- Source: V27__seed_feishu_import_business_dictionaries.sql
-- 飞书全量导入需要的业务字典定义。
-- 同步接口只补齐已存在字典下的来源值，因此这里先创建稳定业务枚举定义。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('EMPLOYEE_STATUS', '员工状态', 'HR', '员工在职、离职等状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_SOURCE', '客户来源', 'CRM', '飞书商家来源、渠道来源等客户来源', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_CATEGORY', '客户类目', 'CRM', '飞书商家类目、门店属性等客户分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', '门店状态', 'CRM', '门店合作、暂停、关闭等状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_ATTRIBUTE', '门店属性', 'CRM', '门店属性，如台球、游泳馆、网球等', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_BUSINESS_TYPE', '门店经营类型', 'CRM', '门店经营类型，如竞技赛事、商业娱乐、综合经营', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', '门店规模', 'CRM', '门店面积或规模分层', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_TAG', '门店标签', 'CRM', '飞书门店标签原文', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_RISK_LEVEL', '风控等级', 'CRM', '商家或门店风控等级', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', '合作等级', 'CRM', '商家合作等级', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_STATUS', '合作状态', 'CRM', '商家签约、合作、暂未合作等状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', '合作意向', 'CRM', '招商留资或拜访门店合作意向', 1, 'SYSTEM', 'SYSTEM', 0),
    ('LEAD_SUBJECT_IDENTITY', '留资主体身份', 'CRM', '招商留资主体身份', 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_FORM', '活动形式', 'CRM', '活动申请形式，如试吃活动、游戏活动', 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', '活动预热周期', 'CRM', '活动申请预热周期', 1, 'SYSTEM', 'SYSTEM', 0),

    ('BUSINESS_LINE', '业务线', 'ERP', '商品、客户和订单关联的业务线', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_INDUSTRY', '商品行业', 'ERP', '飞书产品行业分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_CATEGORY_SOURCE', '来源商品品类', 'ERP', '飞书产品品类原文，用于后续匹配ERP分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', '来源商品状态', 'ERP', '飞书产品状态原文', 1, 'SYSTEM', 'SYSTEM', 0),
    ('MATERIAL_REQUEST_STATUS', '物料申请状态', 'ERP', '飞书物料申请处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_ORDER_STATUS', '采购订单状态', 'ERP', '飞书采购订单状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('INVENTORY_LOCATION_TYPE', '库存地点类型', 'ERP', '飞书库存盘点地点类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_ISSUE_TYPE', '品控问题类型', 'ERP', '飞书品控反馈问题类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_SEVERITY', '品控严重程度', 'ERP', '飞书品控反馈严重程度', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_DISCOVERY_CHANNEL', '品控发现渠道', 'ERP', '飞书品控问题发现渠道', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_PROCESS_STATUS', '品控处理状态', 'ERP', '飞书品控城市处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('MANUFACTURER_COMPENSATION_STATUS', '厂家赔付状态', 'ERP', '飞书品控厂家赔付状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('INVOICE_STATUS', '发票状态', 'ORDER', '飞书发票处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SAMPLE_REQUEST_TYPE', '样品申请类型', 'ORDER', '飞书样品申请类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SAMPLE_REQUEST_STATUS', '样品申请状态', 'ORDER', '飞书样品申请处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FELT_PICKUP_PAYMENT_STATUS', '台呢提货付款状态', 'ORDER', '台呢提货单付款状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('EMPLOYEE_STATUS', 1, NULL, 'ACTIVE', '在职', '员工当前在职', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'LEFT', '离职', '员工已离职', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'INACTIVE', '停用', '员工暂不参与业务', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', 1, NULL, 'ON_SALE', '在售', '来源商品在售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', 1, NULL, 'STOP_SALE', '停售', '来源商品停售', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'ACTIVE', '营业中', '来源门店正常营业', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'PAUSED', '暂停营业', '来源门店暂停营业', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'CLOSED', '倒闭', '来源门店已关闭', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'RENOVATING', '装修中', '来源门店装修中', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'HIGH', '高意向', '来源客户高合作意向', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'MEDIUM', '中意向', '来源客户中等合作意向', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'LOW', '低意向', '来源客户低合作意向', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'NONE', '无意向', '来源客户暂无合作意向', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'UNDER_100', '100平米以下', '来源门店面积分层', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'BETWEEN_100_300', '100-300平米', '来源门店面积分层', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'BETWEEN_300_600', '300-600平米', '来源门店面积分层', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'OVER_600', '600平米以上', '来源门店面积分层', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'A', 'A类', '来源客户或门店A类等级', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'B', 'B类', '来源客户或门店B类等级', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'C', 'C类', '来源客户或门店C类等级', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_1_3', '1-3天', '活动预热周期', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_3_5', '3-5天', '活动预热周期', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_5_7', '5-7天', '活动预热周期', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

-- Source: V28__extend_sales_order_completed_status.sql
-- 飞书完整导入的销售订单直接进入已完成状态，业务页面需要稳定字典展示。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_ORDER_STATUS', '销售订单状态', 'ORDER', '销售订单草稿、提交、完成、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_ORDER_STATUS', 1, NULL, 'COMPLETED', '已完成', '飞书等外部来源完整订单已完成', 25, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6)
WHERE dictionary_code = 'SALES_ORDER_STATUS';

-- Source: V29__align_hr_status_and_restore_grain_unit.sql
-- 对齐 HR 任职状态，并恢复已有迁移定义但当前字典缺失的 GRAIN；不改历史订单数量。
INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark, ordinal, revision,
    created_by, updated_by, deleted
) VALUES
    ('EMPLOYEE_STATUS', 1, NULL, 'ACTIVE', '在职', '员工当前在职', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'LEFT', '离职', '员工已离职', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'INACTIVE', '停用', '停用不代表已离职', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'PENDING', '待确认', '待入职或来源状态待确认', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'GRAIN', '颗', '按颗计量，不代表包装换算已经确认', 95, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark), ordinal = VALUES(ordinal), revision = revision + 1,
    updated_by = 'SYSTEM', deleted = 0;

UPDATE data_dictionary SET revision = revision + 1, updated_by = 'SYSTEM'
WHERE dictionary_code IN ('EMPLOYEE_STATUS', 'PRODUCT_UNIT');

-- 旧来源同义项保留为历史值，当前选项收口到标准编码。
UPDATE data_dictionary_item
SET deleted = 1, revision = revision + 1, updated_by = 'SYSTEM',
    remark = CONCAT('历史来源同义项，现使用标准员工状态；', COALESCE(remark, ''))
WHERE dictionary_code = 'EMPLOYEE_STATUS' AND LEFT(dictionary_item_code, 5) = 'AUTO_'
  AND dictionary_item_name IN ('在职', '离职', '停用', '待入职', '待确认') AND deleted = 0;

-- Source: V30__dictionary_canonical_aliases.sql
-- 标准选项与历史编码兼容分离：保留旧编码供历史解析，禁止来源观察自动扩充标准项。
ALTER TABLE data_dictionary_item
    ADD COLUMN canonical_dictionary_code VARCHAR(50) NULL COMMENT '历史兼容项的标准字典',
    ADD COLUMN canonical_item_code VARCHAR(50) NULL COMMENT '历史兼容项的标准编码',
    ADD CONSTRAINT ck_dictionary_canonical_pair CHECK ((canonical_dictionary_code IS NULL) = (canonical_item_code IS NULL)),
    ADD CONSTRAINT fk_dictionary_canonical_item FOREIGN KEY (canonical_dictionary_code, canonical_item_code)
        REFERENCES data_dictionary_item(dictionary_code, dictionary_item_code);

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND standard.dictionary_item_code='DAYS_1_3' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND old_item.dictionary_item_code='AUTO_4E5A27B003E626C701225727C58FBBF7F288960C257F5' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND standard.dictionary_item_code='DAYS_3_5' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND old_item.dictionary_item_code='AUTO_325287A8AEC72CE0ADA3385B1A48D2D83F6A4827D480E' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='HIGH' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_5AE6F0508F0B2EAB60FC98D71D25FA859F5956652FD6E' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='MEDIUM' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_165DE6EF8BCAD65646B3B2ED890F426B6C8CA216F401D' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='LOW' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_0E4B8B0D392F2910F10FC7B8FE1F2BC13E3FF7401C30C' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='NONE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_AF028E13AB57FF1AB28F63A586DE9BAFB168CE6500056' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code='STOCK_UP' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_ORDER_STATUS' AND old_item.dictionary_item_code='STOCKUP' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BOX' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_B83271F3825DC5A5668D3278D3C8898F7588CDFEFD98B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BUCKET' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_75DD5950072ED4B4CCA74D29ADB9A3326D0889C2EFCC4' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='PORTION' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_B4367149220FCE5B74104E35067603C289729CDE6DAD2' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='SET' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_976F7764129A59B95D400093493D5F235C5D66680A5FE' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_32F3D97C94848D9271E290C7AFA35EE01B2534EDA87FF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='PAIR' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_A5C7BB87103D948DCA2638D83A57E3E15FA70583340DD' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BOTTLE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_31E93AC913625A0885AF0E98AC20881C3BED383759627' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='STRIP' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_A05C43FEC5E24249DE4E607AF30A529455753E1437D29' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ORDER_TYPE' AND standard.dictionary_item_code='NEW' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ORDER_TYPE' AND old_item.dictionary_item_code='AUTO_4CD1395609352B6CF19A926E968CD1C88B51EB44139BD' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ORDER_TYPE' AND standard.dictionary_item_code='REPEAT' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ORDER_TYPE' AND old_item.dictionary_item_code='AUTO_8C160D8458977FA0B1F6DDA5AF7ACA63AD0D278C9430B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_METHOD' AND standard.dictionary_item_code='FULL' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_METHOD' AND old_item.dictionary_item_code='AUTO_DDEBEB90FCA558EE6C14705A6066DE05A5E751D1B8F78' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_METHOD' AND standard.dictionary_item_code='MONTHLY' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_METHOD' AND old_item.dictionary_item_code='AUTO_7C90931DCA87B14E5CFDE3483BDCBD1DB0DEEE945A462' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_STATUS' AND standard.dictionary_item_code='UNPAID' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_STATUS' AND old_item.dictionary_item_code='AUTO_9DFF390AA68194B77397EDFA4AEAD542923E594527C99' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_STATUS' AND standard.dictionary_item_code='PAID' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_STATUS' AND old_item.dictionary_item_code='AUTO_F961C8F802CE895879E4181BAB5303703FEDCF739750B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PRODUCT_SOURCE_STATUS' AND standard.dictionary_item_code='ON_SALE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PRODUCT_SOURCE_STATUS' AND old_item.dictionary_item_code='AUTO_8ADBB5C5886BE5B22D0AEDF60FD04A95CA84E5F7D626B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PRODUCT_SOURCE_STATUS' AND standard.dictionary_item_code='STOP_SALE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PRODUCT_SOURCE_STATUS' AND old_item.dictionary_item_code='AUTO_B99E922F2F6CA016C25D8578FE4AD950A8D57EECB171A' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='SALES_SHIPMENT_STATUS' AND standard.dictionary_item_code='SHIPPED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='SALES_SHIPMENT_STATUS' AND old_item.dictionary_item_code='AUTO_49EEB099699F4AB013C900A6F428AF8F56A722A3F9DC7' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='UNDER_100' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_4B8B01F1FE3D0D25A831BA9278E7845891C35D1BD17C1' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='BETWEEN_100_300' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_0A0644A2DAE8ECC8A47335302A7D24972277C566874BF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='BETWEEN_300_600' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_F583530F3E9A5B6029646FFB9B7290EB26703ED0BF6C2' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='OVER_600' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_89A9EE0AF4577794AB0B2093EA553A056D6B3FF881CBF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='ACTIVE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_342EC393E5D21B3011604639906D593D86E70B5F15B8F' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='PAUSED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_0ACD89A98826F6403F931E3818CC8CA3657075B40D5F1' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='CLOSED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_D232FA16B4683B4D3CB4143F0A466C02AF42B06389E08' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='RENOVATING' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_2F8F04DF41EEE4BFDEA0940C7BF3C20931B5DEE1E0886' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='ACTIVE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_8BB6580D61E60378EE02CD185B02D3363BECCD7991497' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 同名限制只针对标准项、同父级；历史兼容项仍保留原名。
ALTER TABLE data_dictionary_item
    ADD COLUMN standard_parent_key VARCHAR(50) GENERATED ALWAYS AS (COALESCE(parent_dictionary_item_code, '')) STORED,
    ADD COLUMN standard_name_key VARCHAR(100) GENERATED ALWAYS AS
        (CASE WHEN deleted=0 AND canonical_item_code IS NULL THEN TRIM(dictionary_item_name) ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_dictionary_standard_name (dictionary_code, standard_parent_key, standard_name_key);

UPDATE data_dictionary d SET revision=revision+1, updated_by='SYSTEM', updated_time=UTC_TIMESTAMP()
WHERE EXISTS (SELECT 1 FROM data_dictionary_item i WHERE i.dictionary_code=d.dictionary_code AND i.canonical_item_code IS NOT NULL);

CREATE TABLE data_dictionary_merge_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL COMMENT '操作租户审计上下文',
    dictionary_code VARCHAR(50) NOT NULL,
    source_item_code VARCHAR(50) NOT NULL,
    target_item_code VARCHAR(50) NOT NULL,
    source_revision INT NOT NULL,
    target_revision INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_dictionary_merge_tenant (tenant_id, dictionary_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='字典合并审计';

-- Source: V31__separate_store_area_and_chain_size.sql
-- 面积和连锁门店数量是两个独立维度；保持旧编码可解析。
INSERT INTO data_dictionary (dictionary_code,dictionary_name,dictionary_type,remark,created_by,updated_by)
VALUES ('STORE_CHAIN_SIZE','门店连锁规模','CRM','连锁门店数量分层，与单店面积分开','SYSTEM','SYSTEM');
INSERT INTO data_dictionary_item (dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by,updated_by)
VALUES ('STORE_CHAIN_SIZE','SMALL','小型(1-3家)',10,'SYSTEM','SYSTEM'),
       ('STORE_CHAIN_SIZE','MEDIUM','中型(4-10家)',20,'SYSTEM','SYSTEM');
UPDATE data_dictionary_item SET canonical_dictionary_code='STORE_CHAIN_SIZE',canonical_item_code='SMALL',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP()
WHERE dictionary_code='STORE_SCALE' AND dictionary_item_code='AUTO_94B411579F15535B6E3511489C48BB363E7D786369FBF' AND deleted=0;
UPDATE data_dictionary_item SET canonical_dictionary_code='STORE_CHAIN_SIZE',canonical_item_code='MEDIUM',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP()
WHERE dictionary_code='STORE_SCALE' AND dictionary_item_code='AUTO_1997EE9CA3E365A94E0139DD8384BC21082E19C291C15' AND deleted=0;
UPDATE data_dictionary SET dictionary_name='门店面积',remark='单店面积分层；连锁门店数量使用 STORE_CHAIN_SIZE',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP() WHERE dictionary_code='STORE_SCALE';

-- Source: V32__merge_confirmed_business_line_synonyms.sql
-- 2026-09-15 用户确认两组业务线分别同义。
-- 沿用“鹰眼业务”和“城市零售代理”的现有编码；旧名称、编码和业务原始数据保留。
UPDATE data_dictionary_item legacy
JOIN data_dictionary_item standard
  ON standard.dictionary_code = 'BUSINESS_LINE'
 AND standard.dictionary_item_code = 'AUTO_340670D0AE6643CECA561135428529D55B606E0845379'
 AND standard.deleted = 0
 AND standard.canonical_item_code IS NULL
 AND standard.parent_dictionary_item_code <=> legacy.parent_dictionary_item_code
SET legacy.canonical_dictionary_code = standard.dictionary_code,
    legacy.canonical_item_code = standard.dictionary_item_code,
    legacy.revision = legacy.revision + 1,
    legacy.updated_by = 'SYSTEM',
    legacy.updated_time = UTC_TIMESTAMP(6)
WHERE legacy.dictionary_code = 'BUSINESS_LINE'
  AND legacy.dictionary_item_code = 'AUTO_3F893BBA2120A3CE805ED318DF7BE0F207490FEA4BACE'
  AND legacy.deleted = 0
  AND legacy.canonical_item_code IS NULL;

UPDATE data_dictionary_item legacy
JOIN data_dictionary_item standard
  ON standard.dictionary_code = 'BUSINESS_LINE'
 AND standard.dictionary_item_code = 'AUTO_3758C8870549A93C41CE149363FFE3836FCA022EBB5E5'
 AND standard.deleted = 0
 AND standard.canonical_item_code IS NULL
 AND standard.parent_dictionary_item_code <=> legacy.parent_dictionary_item_code
SET legacy.canonical_dictionary_code = standard.dictionary_code,
    legacy.canonical_item_code = standard.dictionary_item_code,
    legacy.revision = legacy.revision + 1,
    legacy.updated_by = 'SYSTEM',
    legacy.updated_time = UTC_TIMESTAMP(6)
WHERE legacy.dictionary_code = 'BUSINESS_LINE'
  AND legacy.dictionary_item_code = 'AUTO_0768CB7ABDABFB83FE4576AEF07970E4D14AAADB13522'
  AND legacy.deleted = 0
  AND legacy.canonical_item_code IS NULL;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    updated_time = UTC_TIMESTAMP(6)
WHERE dictionary_code = 'BUSINESS_LINE'
  AND deleted = 0
  AND EXISTS (
      SELECT 1 FROM data_dictionary_item item
      WHERE item.dictionary_code = 'BUSINESS_LINE'
        AND item.deleted = 0
        AND item.canonical_dictionary_code = 'BUSINESS_LINE'
        AND ((item.dictionary_item_code = 'AUTO_3F893BBA2120A3CE805ED318DF7BE0F207490FEA4BACE'
              AND item.canonical_item_code = 'AUTO_340670D0AE6643CECA561135428529D55B606E0845379')
          OR (item.dictionary_item_code = 'AUTO_0768CB7ABDABFB83FE4576AEF07970E4D14AAADB13522'
              AND item.canonical_item_code = 'AUTO_3758C8870549A93C41CE149363FFE3836FCA022EBB5E5'))
  );

-- Source: V33__tenant_dictionary_overrides.sql
-- 原有数据保持全局发布基线；租户覆盖从此迁移起独立维护，不猜测历史数据所属租户。
-- 历史别名按本租户有效视图解析，跨字典目标可来自 GLOBAL；原全局外键不能表达覆盖关系。
-- 标准目标存在性、启停、循环由锁内合并用例校验；远程已执行的 V30 保持原样。
ALTER TABLE data_dictionary_item DROP FOREIGN KEY fk_dictionary_canonical_item,
 DROP FOREIGN KEY fk_data_dictionary_item_dictionary,
 DROP INDEX uk_dictionary_standard_name;
ALTER TABLE data_dictionary ADD tenant_key VARCHAR(36) NOT NULL DEFAULT 'GLOBAL',
 ADD allow_new_items BOOLEAN NOT NULL DEFAULT FALSE,
 DROP INDEX uk_data_dictionary_code,
 ADD UNIQUE KEY uk_data_dictionary_tenant_code(tenant_key,dictionary_code);
ALTER TABLE data_dictionary_item ADD tenant_key VARCHAR(36) NOT NULL DEFAULT 'GLOBAL',
 ADD enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD locally_managed BOOLEAN NOT NULL DEFAULT FALSE,
 DROP INDEX uk_data_dictionary_item_code,
 ADD UNIQUE KEY uk_data_dictionary_item_tenant_code(tenant_key,dictionary_code,dictionary_item_code),
 ADD UNIQUE KEY uk_dictionary_tenant_standard_name(tenant_key,dictionary_code,standard_parent_key,standard_name_key),
 ADD CONSTRAINT fk_data_dictionary_item_tenant FOREIGN KEY(tenant_key,dictionary_code) REFERENCES data_dictionary(tenant_key,dictionary_code);
UPDATE data_dictionary SET allow_new_items=TRUE WHERE dictionary_code IN (
 'PRODUCT_UNIT','CUSTOMER_SOURCE','CUSTOMER_CATEGORY','STORE_ATTRIBUTE','STORE_BUSINESS_TYPE',
 'STORE_SCALE','STORE_TAG','CUSTOMER_RISK_LEVEL','CUSTOMER_COOP_LEVEL','CUSTOMER_INTENTION_LEVEL',
 'LEAD_SUBJECT_IDENTITY','ACTIVITY_FORM');
CREATE TABLE settings_operation_audit(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,
 actor_id VARCHAR(50) NOT NULL,module_code VARCHAR(32) NOT NULL,action_code VARCHAR(64) NOT NULL,
 object_code VARCHAR(100) NOT NULL,before_json JSON NULL,after_json JSON NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_settings_audit_tenant(tenant_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V34__seed_warehouse_type_dictionary.sql
-- ERP 仓库类型基线：仓库建档和编辑只保存字典项编码，默认统计口径仍是城市仓。
-- 归属地区复用客户归属地区（CRM 地区树），出库时按客户地区匹配对应地区的仓库。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted, allow_new_items)
VALUES
    ('WAREHOUSE_TYPE', '仓库类型', 'ERP', '仓库承担的库存与出库角色，可按城市仓、云仓、中心仓、门店仓继续维护', 1, 'SYSTEM', 'SYSTEM', 0, TRUE)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    allow_new_items = TRUE,
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('WAREHOUSE_TYPE', 1, NULL, 'CITY', '城市仓', '按城市覆盖发货，出库时按客户归属地区匹配', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'CLOUD', '云仓', '第三方云仓或云仓发货', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'CENTER', '中心仓', '区域中心仓，覆盖多个城市', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_TYPE', 1, NULL, 'STORE', '门店仓', '门店前店后仓或客户自提点', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    enabled = TRUE,
    updated_by = 'SYSTEM',
    deleted = 0;

-- Source: V35__cost_and_payroll_dictionaries.sql
-- 类型可维护，固定流程状态仅可改显示名称。营销与报销使用不同字典，历史归属不随类型名称变更。
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('MARKETING_EXPENSE_TYPE','营销费用类型','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('MARKETING_EXPENSE_TYPE',1,NULL,'ADVERTISING','广告费',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('MARKETING_EXPENSE_TYPE',1,NULL,'MATERIAL','物料制作',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('MARKETING_EXPENSE_TYPE',1,NULL,'PROMOTION','推广活动',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('MARKETING_EXPENSE_TYPE',1,NULL,'ENDORSEMENT','代言费',40,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('REIMBURSEMENT_EXPENSE_TYPE','报销费用类型','HR','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'TRAVEL','差旅',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'ACCOMMODATION','住宿',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'OFFICE_RENT','办公室租赁',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'OFFICE_SUPPLIES','办公用品',40,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'TAXI','交通打车',50,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_TRANSPORT_METHOD','运输方式','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_TRANSPORT_METHOD',1,NULL,'THIRD_PARTY','第三方配送',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_TRANSPORT_METHOD',1,NULL,'OWN_VEHICLE','自有车辆',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_TRANSPORT_METHOD',1,NULL,'EXPRESS','快递',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_LOGISTICS_LEG','物流类型','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_LOGISTICS_LEG',1,NULL,'PURCHASE','供应商到中心仓',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_LOGISTICS_LEG',1,NULL,'TRANSFER','中心仓到城市端 / 调拨',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_LOGISTICS_LEG',1,NULL,'DELIVERY','城市端到客户 / 订单配送',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_PAYER','物流费用承担方','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_PAYER',1,NULL,'COMPANY','本公司',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_PAYER',1,NULL,'SUPPLIER','供应商',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_PAYER',1,NULL,'CUSTOMER','客户',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_PAYMENT_STATUS','费用付款状态','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_PAYMENT_STATUS',1,NULL,'UNPAID','未付款',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_PAYMENT_STATUS',1,NULL,'PAID','已付款',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_STATUS','费用确认状态','COST','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_STATUS',1,NULL,'DRAFT','待确认',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_STATUS',1,NULL,'CONFIRMED','已确认',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COST_STATUS',1,NULL,'VOID','已作废',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('PAYROLL_COMMISSION_METHOD','提成计算方式','HR','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_COMMISSION_METHOD',1,NULL,'QUANTITY','按数量',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_COMMISSION_METHOD',1,NULL,'PERCENT','按回款金额比例',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('PAYROLL_STATUS','薪酬状态','HR','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_STATUS',1,NULL,'REVIEW','待核对',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_STATUS',1,NULL,'READY','可确认',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_STATUS',1,NULL,'CONFIRMED','已确认',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_STATUS',1,NULL,'PAID','已发放',40,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('PAYROLL_RULE_KIND','薪酬规则类型','HR','成本与薪酬管理',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_RULE_KIND',1,NULL,'BASE','薪资标准',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_RULE_KIND',1,NULL,'COMMISSION','商品提成',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_RULE_KIND',1,NULL,'PERFORMANCE','绩效规则',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_RULE_KIND',1,NULL,'ALLOWANCE','其他薪资',40,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_RULE_KIND',1,NULL,'CITY_MANAGER','城市总管辖设置',50,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COMMISSION_RECIPIENT_ROLE','提成受益角色','HR','薪酬提成',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_RECIPIENT_ROLE',1,NULL,'SALES','销售人员',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_RECIPIENT_ROLE',1,NULL,'CITY_MANAGER','城市总',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COMMISSION_PRODUCT_GROUP','提成商品组','HR','薪酬提成',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'INSTANT_NOODLES','快消品：泡面',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'BILLIARD_ACCESSORIES','台球周边（除台呢）',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_A300','澳洋华彩 A300',30,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_A500B','澳洋华彩 A500B',40,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_A600','澳洋华彩 A600',50,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_A700','澳洋华彩 A700',60,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_A800','澳洋华彩 A800',70,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_SNOOKER_AURORA','澳洋斯诺克 极光',80,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_SNOOKER_PEAK','澳洋斯诺克 极峰',90,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('COMMISSION_PRODUCT_GROUP',1,NULL,'CLOTH_SNOOKER_POINT','澳洋斯诺克 极点',100,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('PAYROLL_PERFORMANCE_MODE','绩效核算模式','HR','薪酬核算',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_PERFORMANCE_MODE',1,NULL,'FIXED','固定月绩效',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_PERFORMANCE_MODE',1,NULL,'SALES_KPI','销售过程与回款考核',20,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('PAYROLL_FIRST_MONTH_MODE','入职首月绩效口径','HR','薪酬核算',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_FIRST_MONTH_MODE',1,NULL,'EXEMPT_ALL','首月整体免考核，按铜牌金额',10,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES('PAYROLL_FIRST_MONTH_MODE',1,NULL,'EXEMPT_COLLECTION','仅回款部分免考核',20,1,'SYSTEM','SYSTEM',0);

-- Source: V36__payroll_allowance_types.sql
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items)
VALUES('PAYROLL_ALLOWANCE_TYPE','其他薪资类型','HR','销售人员的固定补贴与其他月度补贴',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('PAYROLL_ALLOWANCE_TYPE',1,NULL,'FIXED_ALLOWANCE','固定补贴',10,1,'SYSTEM','SYSTEM',0),
('PAYROLL_ALLOWANCE_TYPE',1,NULL,'OTHER_ALLOWANCE','其他补贴',20,1,'SYSTEM','SYSTEM',0);

-- Source: V37__attendance_leave_types.sql
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

-- Source: V38__attendance_status_dictionary.sql
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted, allow_new_items)
VALUES ('ATTENDANCE_STATUS', '考勤状态', 'HR', '月度考勤确认流程，状态编码固定', 1, 'SYSTEM', 'SYSTEM', 0, FALSE);

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code, dictionary_item_name, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('ATTENDANCE_STATUS', 1, NULL, 'PENDING', '待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ATTENDANCE_STATUS', 1, NULL, 'CONFIRMED', '已确认', 20, 1, 'SYSTEM', 'SYSTEM', 0);

-- Source: V39__manual_cost_premises_type.sql
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_PREMISES_TYPE','场地类型','COST','手工场地费用登记',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('COST_PREMISES_TYPE',1,NULL,'WAREHOUSE','仓储',10,1,'SYSTEM','SYSTEM',0),
('COST_PREMISES_TYPE',1,NULL,'OFFICE','办公室',20,1,'SYSTEM','SYSTEM',0);

-- Source: V40__cost_ledger_labels.sql
-- 仅收敛系统字典展示名称，保留租户自定义项和稳定编码。
UPDATE data_dictionary_item SET dictionary_item_name='中心仓到城市仓',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_LOGISTICS_LEG' AND dictionary_item_code='TRANSFER' AND deleted=0;
UPDATE data_dictionary_item SET dictionary_item_name='城市仓到客户',revision=revision+1,updated_by='SYSTEM'
WHERE dictionary_code='COST_LOGISTICS_LEG' AND dictionary_item_code='DELIVERY' AND deleted=0;

-- Source: V41__marketing_personnel_and_commission_types.sql
-- 扩展营销费用类型；租户仍按稳定编码覆盖全局字典，既有费用分类不变。
INSERT INTO data_dictionary_item(
    tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,
    dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted
) VALUES
('GLOBAL','MARKETING_EXPENSE_TYPE',1,NULL,'PERSONNEL_COST','人员成本',50,1,'SYSTEM','SYSTEM',0),
('GLOBAL','MARKETING_EXPENSE_TYPE',1,NULL,'COMMISSION','佣金提成',60,1,'SYSTEM','SYSTEM',0);

-- Source: V42__reimbursement_expense_categories.sql
-- Preserve existing expense types and add nature-based office/freight subtypes.
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'FIXED_ASSET','办公固定资产',60,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'CONSUMABLE','办公耗材',70,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'NETWORK','网络费用',80,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'OFFICE_MOVING','办公搬运',90,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'FREIGHT','货物运输',100,1,'SYSTEM','SYSTEM',0);

-- Source: V43__statutory_calendar_snapshots.sql
-- National holiday reference data is public GLOBAL configuration, never tenant business records.
CREATE TABLE settings_statutory_calendar_year (
 id VARCHAR(32) NOT NULL, tenant_id VARCHAR(64) NOT NULL, calendar_year INT NOT NULL,
 status VARCHAR(30) NOT NULL, content_json JSON NULL, content_version VARCHAR(64) NULL,
 fetched_at DATETIME(6) NULL, attempted_at DATETIME(6) NULL,
 lease_token VARCHAR(36) NULL, lease_until DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_statutory_calendar_year(tenant_id,calendar_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE settings_statutory_calendar_history (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, calendar_year INT NOT NULL,
 content_json JSON NOT NULL, content_version VARCHAR(64) NOT NULL, fetched_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY ix_statutory_calendar_history(tenant_id,calendar_year,fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V44__courier_reimbursement_type.sql
-- Employee-paid non-sales postage remains reimbursement; freight keeps its existing type.
INSERT INTO data_dictionary_item
    (tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,
     dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted)
VALUES ('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'COURIER','快递／邮寄费',110,1,'SYSTEM','SYSTEM',0);

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
