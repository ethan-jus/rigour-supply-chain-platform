-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__bi_city_cost_record_baseline.sql
-- Analytics BI：城市端成本看板快照表。
--
-- 当前 city-operations 服务尚未落城市成本主业务表，本表先作为 BI 读模型/快照承接成本看板。
-- 它不作为城市运营业务主表；后续城市运营主数据或导入链路完成后，应通过 ETL/同步写入本表。

CREATE TABLE bi_city_cost_record (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    region_code         VARCHAR(64)    NOT NULL COMMENT '城市/区域编码，关联REGION字典项',
    region_name         VARCHAR(120)   NULL COMMENT '城市/区域名称快照',
    cost_type_code      VARCHAR(64)    NOT NULL COMMENT '成本类型编码，如MARKETING/MATERIAL/LOGISTICS/OPERATION',
    cost_type_name      VARCHAR(120)   NULL COMMENT '成本类型名称快照',
    cost_date           DATETIME(6)    NOT NULL COMMENT '成本发生或归属时间',
    cost_amount         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '成本金额',
    budget_amount       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '预算金额',
    source_system_code  VARCHAR(32)    NULL COMMENT '来源系统编码，如FEISHU_IMPORT/CITY_OPERATION',
    source_record_id    VARCHAR(120)   NULL COMMENT '来源记录ID，用于导入去重和追溯',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted             INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_city_cost_source (tenant_id, source_system_code, source_record_id),
    KEY idx_bi_city_cost_region_date (tenant_id, region_code, cost_date),
    KEY idx_bi_city_cost_type_date (tenant_id, cost_type_code, cost_date),
    KEY idx_bi_city_cost_updated (tenant_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI城市端成本快照表';

-- Source: V2__bi_supply_dashboard_facts_and_etl.sql
-- Analytics BI：供应链经营看板事实/维度表与刷新任务状态。
--
-- 看板正式请求只聚合本 schema 下 BI 表；跨业务源库 SQL 仅用于 BI 服务刷新任务。

CREATE TABLE bi_customer_dim (
    id                   BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id            VARCHAR(64)   NOT NULL COMMENT '租户ID',
    customer_id          BIGINT(20)    NOT NULL COMMENT 'CRM客户ID',
    customer_code        VARCHAR(50)   NULL COMMENT '客户编号快照',
    customer_name        VARCHAR(200)  NULL COMMENT '客户/门店名称快照',
    region_code          VARCHAR(64)   NULL COMMENT '城市/区域编码快照',
    owner_staff_code     VARCHAR(50)   NULL COMMENT '归属销售员工编码快照',
    owner_staff_name     VARCHAR(100)  NULL COMMENT '归属销售姓名快照',
    customer_type_code   VARCHAR(64)   NULL COMMENT '客户类型编码快照',
    status_code          VARCHAR(64)   NULL COMMENT '客户状态编码快照',
    source_updated_time  DATETIME(6)   NULL COMMENT '源表更新时间水位',
    synced_time          DATETIME(6)   NOT NULL COMMENT '同步到BI时间',
    deleted              INT           NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_customer_dim_source (tenant_id, customer_id),
    KEY idx_bi_customer_dim_region (tenant_id, region_code, deleted),
    KEY idx_bi_customer_dim_owner (tenant_id, owner_staff_code, deleted),
    KEY idx_bi_customer_dim_type (tenant_id, customer_type_code, deleted),
    KEY idx_bi_customer_dim_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI客户/门店维度';

CREATE TABLE bi_sales_order_fact (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    order_id              BIGINT(20)     NOT NULL COMMENT '销售订单ID',
    order_no              VARCHAR(50)    NULL COMMENT '销售订单号快照',
    source_system_code    VARCHAR(32)    NOT NULL DEFAULT 'MANUAL' COMMENT '来源系统编码',
    source_order_no       VARCHAR(80)    NULL COMMENT '来源平台订单号',
    customer_id           BIGINT(20)     NULL COMMENT 'CRM客户ID',
    customer_code         VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name         VARCHAR(200)   NULL COMMENT '客户名称快照',
    customer_type_code    VARCHAR(64)    NULL COMMENT '客户类型编码快照',
    customer_status_code  VARCHAR(64)    NULL COMMENT '客户状态编码快照',
    region_code           VARCHAR(64)    NULL COMMENT '城市/区域编码快照',
    owner_staff_code      VARCHAR(50)    NULL COMMENT '归属销售员工编码快照',
    owner_staff_name      VARCHAR(100)   NULL COMMENT '归属销售姓名快照',
    order_date            DATETIME(6)    NOT NULL COMMENT '销售日期',
    payment_time          DATETIME(6)    NULL COMMENT '付款时间快照',
    shipment_time         DATETIME(6)    NULL COMMENT '发货时间快照',
    order_status_code     VARCHAR(64)    NULL COMMENT '订单状态编码',
    payment_status_code   VARCHAR(64)    NULL COMMENT '收款状态编码',
    outbound_status_code  VARCHAR(64)    NULL COMMENT '出库状态编码',
    order_type_code       VARCHAR(64)    NULL COMMENT '订单类型编码',
    total_quantity        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '订单数量',
    payable_amount        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '应收金额',
    paid_amount           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '已收金额',
    unpaid_amount         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '待收金额',
    source_updated_time   DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time           DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_sales_order_fact_source (tenant_id, order_id),
    KEY idx_bi_sales_order_fact_date (tenant_id, order_date, deleted),
    KEY idx_bi_sales_order_fact_region (tenant_id, region_code, order_date),
    KEY idx_bi_sales_order_fact_owner (tenant_id, owner_staff_code, order_date),
    KEY idx_bi_sales_order_fact_customer (tenant_id, customer_id, order_date),
    KEY idx_bi_sales_order_fact_source_sys (tenant_id, source_system_code, order_date),
    KEY idx_bi_sales_order_fact_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI销售订单事实';

CREATE TABLE bi_sales_payment_fact (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    payment_id            BIGINT(20)     NOT NULL COMMENT '销售回款记录ID',
    payment_no            VARCHAR(50)    NULL COMMENT '回款单号快照',
    order_id              BIGINT(20)     NULL COMMENT '销售订单ID',
    order_no              VARCHAR(50)    NULL COMMENT '销售订单号快照',
    source_system_code    VARCHAR(32)    NOT NULL DEFAULT 'MANUAL' COMMENT '来源系统编码',
    customer_id           BIGINT(20)     NULL COMMENT 'CRM客户ID',
    customer_code         VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name         VARCHAR(200)   NULL COMMENT '客户名称快照',
    customer_type_code    VARCHAR(64)    NULL COMMENT '客户类型编码快照',
    region_code           VARCHAR(64)    NULL COMMENT '城市/区域编码快照',
    owner_staff_code      VARCHAR(50)    NULL COMMENT '订单归属销售员工编码快照',
    owner_staff_name      VARCHAR(100)   NULL COMMENT '订单归属销售姓名快照',
    collector_staff_code  VARCHAR(50)    NULL COMMENT '回款人员工编码快照',
    collector_staff_name  VARCHAR(100)   NULL COMMENT '回款人姓名快照',
    payment_time          DATETIME(6)    NOT NULL COMMENT '回款时间',
    payment_method_code   VARCHAR(64)    NULL COMMENT '付款方式编码',
    paid_amount           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '回款金额',
    source_updated_time   DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time           DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_sales_payment_fact_source (tenant_id, payment_id),
    KEY idx_bi_sales_payment_fact_time (tenant_id, payment_time, deleted),
    KEY idx_bi_sales_payment_fact_customer (tenant_id, customer_id, payment_time),
    KEY idx_bi_sales_payment_fact_collector (tenant_id, collector_staff_code, payment_time),
    KEY idx_bi_sales_payment_fact_owner (tenant_id, owner_staff_code, payment_time),
    KEY idx_bi_sales_payment_fact_source_sys (tenant_id, source_system_code, payment_time),
    KEY idx_bi_sales_payment_fact_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI销售回款事实';

CREATE TABLE bi_inventory_balance_current (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    warehouse_id             BIGINT(20)     NOT NULL COMMENT 'ERP仓库ID',
    warehouse_code           VARCHAR(50)    NULL COMMENT '仓库编码快照',
    warehouse_name           VARCHAR(120)   NULL COMMENT '仓库名称快照',
    region_code              VARCHAR(64)    NULL COMMENT '仓库城市/区域编码快照',
    product_id               BIGINT(20)     NOT NULL COMMENT 'ERP商品ID',
    product_code             VARCHAR(50)    NULL COMMENT '商品编码快照',
    product_name             VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id      BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_variant_id       BIGINT(20)     NOT NULL COMMENT 'ERP商品规格ID',
    variant_code             VARCHAR(50)    NULL COMMENT '规格编码快照',
    specification_snapshot   VARCHAR(500)   NULL COMMENT '规格描述快照',
    available_quantity       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '可用库存',
    locked_quantity          DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '锁定库存',
    in_transit_quantity      DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '在途库存',
    source_updated_time      DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time              DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '源维度不可用或被本次快照淘汰',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_inventory_balance_current_source (tenant_id, warehouse_id, product_id, product_variant_id),
    KEY idx_bi_inventory_balance_current_region (tenant_id, region_code, deleted),
    KEY idx_bi_inventory_balance_current_product (tenant_id, product_id, product_variant_id),
    KEY idx_bi_inventory_balance_current_category (tenant_id, product_category_id, deleted),
    KEY idx_bi_inventory_balance_current_risk (tenant_id, available_quantity, locked_quantity),
    KEY idx_bi_inventory_balance_current_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI当前库存余额快照';

CREATE TABLE bi_etl_checkpoint (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    source_code           VARCHAR(64)   NOT NULL COMMENT '来源编码或任务锁编码',
    source_name           VARCHAR(120)  NULL COMMENT '来源名称',
    last_watermark_time   DATETIME(6)   NULL COMMENT '上次成功处理水位',
    last_success_time     DATETIME(6)   NULL COMMENT '上次成功时间',
    last_run_id           BIGINT(20)    NULL COMMENT '最近成功运行ID',
    status_code           VARCHAR(32)   NOT NULL DEFAULT 'IDLE' COMMENT '状态：IDLE/RUNNING/SUCCESS/FAILED',
    locked_until          DATETIME(6)   NULL COMMENT '运行锁过期时间',
    failure_reason        VARCHAR(1000) NULL COMMENT '最近失败原因',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_etl_checkpoint_source (tenant_id, source_code),
    KEY idx_bi_etl_checkpoint_status (tenant_id, status_code, locked_until),
    KEY idx_bi_etl_checkpoint_watermark (tenant_id, source_code, last_watermark_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI ETL水位与运行锁';

CREATE TABLE bi_etl_run (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    job_code              VARCHAR(64)   NOT NULL COMMENT '任务编码',
    status_code           VARCHAR(32)   NOT NULL COMMENT '状态：RUNNING/SUCCESS/FAILED/SKIPPED',
    started_time          DATETIME(6)   NOT NULL COMMENT '开始时间',
    completed_time        DATETIME(6)   NULL COMMENT '完成时间',
    watermark_time        DATETIME(6)   NULL COMMENT '本次处理到的最大源更新时间',
    pulled_count          BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '源读取数量',
    upserted_count        BIGINT(20)    NOT NULL DEFAULT 0 COMMENT 'BI写入影响数量',
    skipped_count         BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '跳过数量',
    failure_reason        VARCHAR(1000) NULL COMMENT '失败原因',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_bi_etl_run_tenant_job (tenant_id, job_code, started_time),
    KEY idx_bi_etl_run_status (tenant_id, job_code, status_code, started_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI ETL运行记录';

-- Source: V3__bi_feishu_legacy_archive.sql
-- Analytics BI：飞书旧看板退场归档元信息。
--
-- 本表只登记归档文件和核对报告元信息，不保存飞书明细数据，不参与正式 BI 趋势。

CREATE TABLE bi_feishu_legacy_archive (
    id                         BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)   NOT NULL COMMENT '租户ID',
    archive_code               VARCHAR(64)   NOT NULL COMMENT '归档批次编码',
    table_id                   VARCHAR(120)  NOT NULL COMMENT '飞书多维表格tableId',
    view_id                    VARCHAR(120)  NULL COMMENT '飞书视图ID',
    table_name                 VARCHAR(120)  NOT NULL COMMENT '飞书表名称快照',
    file_name                  VARCHAR(255)  NOT NULL COMMENT '导出文件名',
    file_format                VARCHAR(16)   NOT NULL COMMENT '导出格式：CSV/XLSX',
    exported_by                VARCHAR(100)  NOT NULL COMMENT '导出人',
    exported_time              DATETIME(6)   NOT NULL COMMENT '导出时间',
    frozen_time                DATETIME(6)   NOT NULL COMMENT '飞书冻结时间',
    record_count               BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '导出记录数',
    checksum_sha256            CHAR(64)      NOT NULL COMMENT '导出文件SHA-256',
    storage_uri                VARCHAR(500)  NULL COMMENT '归档文件存储位置',
    field_mapping_uri          VARCHAR(500)  NULL COMMENT '字段映射说明位置',
    reconciliation_report_uri  VARCHAR(500)  NULL COMMENT '一次性核对报告位置',
    archive_status_code        VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED' COMMENT '状态：ARCHIVED/VERIFIED/REJECTED',
    remark                     VARCHAR(1000) NULL COMMENT '备注',
    created_time               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                    INT           NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_feishu_legacy_archive_code (tenant_id, archive_code),
    UNIQUE KEY uk_bi_feishu_legacy_archive_file (tenant_id, table_id, view_id, checksum_sha256),
    KEY idx_bi_feishu_legacy_archive_exported (tenant_id, exported_time, deleted),
    KEY idx_bi_feishu_legacy_archive_status (tenant_id, archive_status_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI飞书旧看板归档元信息';

-- Source: V4__bi_supply_reconciliation_current.sql
-- Analytics BI：供应链看板对账快照。
--
-- 普通看板请求读取本表；跨源库对账 SQL 只在 BI 刷新任务中执行并写入本表。

CREATE TABLE bi_supply_reconciliation_current (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    subject_code          VARCHAR(64)    NOT NULL COMMENT '对账对象编码',
    subject_name          VARCHAR(120)   NOT NULL COMMENT '对账对象名称',
    scope_code            VARCHAR(64)    NOT NULL COMMENT '对账范围编码',
    scope_name            VARCHAR(120)   NOT NULL COMMENT '对账范围名称',
    from_time             DATETIME(6)    NOT NULL COMMENT '对账开始时间',
    to_time               DATETIME(6)    NOT NULL COMMENT '对账结束时间',
    source_row_count      BIGINT(20)     NOT NULL DEFAULT 0 COMMENT '来源Raw数量',
    business_row_count    BIGINT(20)     NOT NULL DEFAULT 0 COMMENT '业务主表数量',
    bi_row_count          BIGINT(20)     NOT NULL DEFAULT 0 COMMENT 'BI表数量',
    source_amount         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '来源金额',
    business_amount       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '业务主表金额',
    bi_amount             DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT 'BI金额',
    observed_time         DATETIME(6)    NOT NULL COMMENT '快照生成时间',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_supply_reconciliation_scope (tenant_id, subject_code, scope_code),
    KEY idx_bi_supply_reconciliation_observed (tenant_id, observed_time, deleted),
    KEY idx_bi_supply_reconciliation_status (tenant_id, subject_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI供应链当前对账快照';

-- Source: V5__bi_sales_order_line_fact.sql
-- Analytics BI：销售订单行事实表。
--
-- 用于商品、规格和分类维度销售统计；Portal 请求只聚合本 BI 表。

CREATE TABLE bi_sales_order_line_fact (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    order_line_id            BIGINT(20)     NOT NULL COMMENT '销售订单行ID',
    order_id                 BIGINT(20)     NOT NULL COMMENT '销售订单ID',
    order_no                 VARCHAR(50)    NULL COMMENT '销售订单号快照',
    source_system_code       VARCHAR(32)    NOT NULL DEFAULT 'MANUAL' COMMENT '来源系统编码',
    source_order_no          VARCHAR(80)    NULL COMMENT '来源平台订单号',
    customer_id              BIGINT(20)     NULL COMMENT 'CRM客户ID',
    customer_code            VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name            VARCHAR(200)   NULL COMMENT '客户名称快照',
    customer_type_code       VARCHAR(64)    NULL COMMENT '客户类型编码快照',
    customer_status_code     VARCHAR(64)    NULL COMMENT '客户状态编码快照',
    region_code              VARCHAR(64)    NULL COMMENT '城市/区域编码快照',
    owner_staff_code         VARCHAR(50)    NULL COMMENT '归属销售员工编码快照',
    owner_staff_name         VARCHAR(100)   NULL COMMENT '归属销售姓名快照',
    order_date               DATETIME(6)    NOT NULL COMMENT '销售日期',
    payment_time             DATETIME(6)    NULL COMMENT '付款时间快照',
    shipment_time            DATETIME(6)    NULL COMMENT '发货时间快照',
    order_status_code        VARCHAR(64)    NULL COMMENT '订单状态编码',
    payment_status_code      VARCHAR(64)    NULL COMMENT '收款状态编码',
    outbound_status_code     VARCHAR(64)    NULL COMMENT '出库状态编码',
    order_type_code          VARCHAR(64)    NULL COMMENT '订单类型编码',
    product_id               BIGINT(20)     NULL COMMENT 'ERP商品ID',
    product_variant_id       BIGINT(20)     NULL COMMENT 'ERP商品规格ID',
    product_code             VARCHAR(50)    NULL COMMENT '商品编码快照',
    sku_code                 VARCHAR(50)    NULL COMMENT 'SKU编码快照',
    product_name             VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id      BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_category_code    VARCHAR(50)    NULL COMMENT '商品分类编码快照',
    product_category_name    VARCHAR(120)   NULL COMMENT '商品分类名称快照',
    specification_snapshot   VARCHAR(500)   NULL COMMENT '规格描述快照',
    unit_code                VARCHAR(64)    NULL COMMENT '单位编码快照',
    quantity                 DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '订货数量',
    unit_price               DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '成交单价',
    discount_rate            DECIMAL(10,4)  NULL COMMENT '折扣率',
    discount_amount          DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '折扣金额',
    line_amount              DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '订单行金额',
    source_updated_time      DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time              DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_sales_order_line_fact_source (tenant_id, order_line_id),
    KEY idx_bi_sales_order_line_fact_date (tenant_id, order_date, deleted),
    KEY idx_bi_sales_order_line_fact_region (tenant_id, region_code, order_date),
    KEY idx_bi_sales_order_line_fact_owner (tenant_id, owner_staff_code, order_date),
    KEY idx_bi_sales_order_line_fact_customer (tenant_id, customer_id, order_date),
    KEY idx_bi_sales_order_line_fact_product (tenant_id, product_id, product_variant_id, order_date),
    KEY idx_bi_sales_order_line_fact_category (tenant_id, product_category_id, order_date),
    KEY idx_bi_sales_order_line_fact_source_sys (tenant_id, source_system_code, order_date),
    KEY idx_bi_sales_order_line_fact_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI销售订单行事实';

-- Source: V6__bi_supply_dashboard_dimension_names.sql
-- Analytics BI：补充业务维度名称快照。
--
-- 城市、客户类型等编码用于过滤和幂等，业务看板展示优先使用名称快照。

ALTER TABLE bi_customer_dim
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_order_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_payment_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_order_line_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

-- Source: V7__bi_sales_order_line_profit_fields.sql
-- Analytics BI：销售订单行毛利估算字段。
--
-- 成本口径为 ERP 商品规格采购参考价估算，不等同于真实出库成本。

ALTER TABLE bi_sales_order_line_fact
    ADD COLUMN brand_id BIGINT(20) NULL COMMENT '商品品牌ID快照' AFTER product_category_name,
    ADD COLUMN brand_code VARCHAR(50) NULL COMMENT '商品品牌编码快照' AFTER brand_id,
    ADD COLUMN brand_name VARCHAR(120) NULL COMMENT '商品品牌名称快照' AFTER brand_code,
    ADD COLUMN refund_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '订单级退款按订单行金额比例分摊金额' AFTER line_amount,
    ADD COLUMN sales_net_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '销售净收入：订单行金额-退款分摊金额' AFTER refund_amount,
    ADD COLUMN estimated_unit_cost DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算单位成本：ERP规格采购参考价' AFTER sales_net_amount,
    ADD COLUMN estimated_cost_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算销售成本：数量*估算单位成本' AFTER estimated_unit_cost,
    ADD COLUMN estimated_gross_profit_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算毛利：销售净收入-估算销售成本' AFTER estimated_cost_amount,
    ADD COLUMN estimated_gross_profit_rate DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '估算毛利率' AFTER estimated_gross_profit_amount,
    ADD COLUMN cost_covered TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在采购参考价：0否，1是' AFTER estimated_gross_profit_rate,
    ADD KEY idx_bi_sales_order_line_fact_brand (tenant_id, brand_id, order_date),
    ADD KEY idx_bi_sales_order_line_fact_profit (tenant_id, order_date, estimated_gross_profit_amount);

-- Source: V8__bi_product_dim.sql
-- Analytics BI：ERP 商品维度快照。
--
-- 商品销售看板按商品模式以 ERP 有效商品为底表，再左连订单行事实。

CREATE TABLE bi_product_dim (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    product_id                 BIGINT(20)     NOT NULL COMMENT 'ERP商品ID',
    product_code               VARCHAR(50)    NULL COMMENT '商品编码快照',
    product_name               VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id        BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_category_code      VARCHAR(50)    NULL COMMENT '商品分类编码快照',
    product_category_name      VARCHAR(120)   NULL COMMENT '商品分类名称快照',
    brand_id                   BIGINT(20)     NULL COMMENT '商品品牌ID快照',
    brand_code                 VARCHAR(50)    NULL COMMENT '商品品牌编码快照',
    brand_name                 VARCHAR(120)   NULL COMMENT '商品品牌名称快照',
    shelf_status_code          VARCHAR(64)    NULL COMMENT '上架状态编码快照',
    submit_status_code         VARCHAR(64)    NULL COMMENT '提交状态编码快照',
    source_updated_time        DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time                DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_product_dim_source (tenant_id, product_id),
    KEY idx_bi_product_dim_category (tenant_id, product_category_id, deleted),
    KEY idx_bi_product_dim_brand (tenant_id, brand_id, deleted),
    KEY idx_bi_product_dim_status (tenant_id, shelf_status_code, submit_status_code, deleted),
    KEY idx_bi_product_dim_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI商品维度';

-- Source: V9__bi_operating_dashboard_business_metrics.sql
-- Analytics BI：经营化看板补充客户建联、账期、目标和库存流转读模型。
--
-- Portal 仍只读取 rigour_bi；跨 CRM/Order/ERP 的 SQL 只在刷新任务中使用。

ALTER TABLE bi_customer_dim
    ADD COLUMN contact_name_snapshot VARCHAR(160) NULL COMMENT '主联系人名称快照' AFTER customer_name,
    ADD COLUMN contact_phone_snapshot VARCHAR(128) NULL COMMENT '主联系人电话快照' AFTER contact_name_snapshot,
    ADD COLUMN has_contact TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已建联：联系人或电话非空' AFTER contact_phone_snapshot,
    ADD COLUMN payment_term_days INT UNSIGNED NOT NULL DEFAULT 30 COMMENT '客户账期天数；缺失默认30天' AFTER status_code;

CREATE INDEX idx_bi_customer_dim_contact
    ON bi_customer_dim (tenant_id, has_contact, status_code, deleted);

ALTER TABLE bi_sales_order_fact
    ADD COLUMN payment_term_days INT UNSIGNED NOT NULL DEFAULT 30 COMMENT '订单客户账期天数快照' AFTER shipment_time,
    ADD COLUMN payment_due_date DATETIME(6) NULL COMMENT '订单应回款到期日，按订单日期+账期计算' AFTER payment_term_days;

CREATE INDEX idx_bi_sales_order_fact_due
    ON bi_sales_order_fact (tenant_id, payment_due_date, unpaid_amount, deleted);

UPDATE bi_sales_order_fact
   SET payment_due_date = DATE_ADD(order_date, INTERVAL COALESCE(payment_term_days, 30) DAY)
 WHERE payment_due_date IS NULL;

ALTER TABLE bi_inventory_balance_current
    ADD COLUMN unit_code VARCHAR(64) NULL COMMENT '库存单位，来自ERP商品规格订货单位' AFTER variant_code;

CREATE INDEX idx_bi_inventory_balance_current_category_unit
    ON bi_inventory_balance_current (tenant_id, product_category_id, unit_code, deleted);

CREATE TABLE bi_business_target (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    target_month        DATE           NOT NULL COMMENT '目标月份，取当月1日',
    dimension_type      VARCHAR(32)    NOT NULL COMMENT '目标维度：CITY/SALES_OWNER',
    dimension_code      VARCHAR(64)    NOT NULL COMMENT '维度编码',
    dimension_name      VARCHAR(160)   NULL COMMENT '维度名称快照',
    metric_code         VARCHAR(64)    NOT NULL COMMENT '指标编码：CONTACTED_CUSTOMER/COOPERATED_CUSTOMER/SALES_AMOUNT/PAID_AMOUNT',
    target_value        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '目标值',
    source_system_code  VARCHAR(64)    NOT NULL DEFAULT 'MANUAL_IMPORT' COMMENT '目标来源',
    source_record_id    VARCHAR(120)   NULL COMMENT '来源记录ID',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    synced_time         DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted             INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_business_target_metric (
        tenant_id, target_month, dimension_type, dimension_code, metric_code
    ),
    KEY idx_bi_business_target_dimension (tenant_id, dimension_type, dimension_code, target_month, deleted),
    KEY idx_bi_business_target_metric (tenant_id, metric_code, target_month, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI城市/销售经营目标配置';

CREATE TABLE bi_inventory_operation_fact (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    operation_type             VARCHAR(32)    NOT NULL COMMENT '流转类型：PROCUREMENT/SALES_SHIPMENT',
    source_document_id         BIGINT(20)     NOT NULL COMMENT '来源单据ID',
    source_line_id             BIGINT(20)     NOT NULL COMMENT '来源明细ID',
    source_document_no         VARCHAR(50)    NULL COMMENT '来源单据号快照',
    operation_time             DATETIME(6)    NOT NULL COMMENT '业务发生时间',
    product_id                 BIGINT(20)     NULL COMMENT 'ERP商品ID',
    product_code               VARCHAR(50)    NULL COMMENT '商品编码快照',
    product_name               VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id        BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_category_code      VARCHAR(64)    NULL COMMENT '商品分类编码快照',
    product_category_name      VARCHAR(160)   NULL COMMENT '商品分类名称快照',
    product_variant_id         BIGINT(20)     NULL COMMENT 'ERP商品规格ID',
    variant_code               VARCHAR(50)    NULL COMMENT '规格编码快照',
    unit_code                  VARCHAR(64)    NOT NULL COMMENT '数量单位',
    quantity                   DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '数量',
    source_updated_time        DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time                DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_inventory_operation_source (
        tenant_id, operation_type, source_document_id, source_line_id
    ),
    KEY idx_bi_inventory_operation_time (tenant_id, operation_time, operation_type, deleted),
    KEY idx_bi_inventory_operation_category (tenant_id, product_category_id, unit_code, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI采购/发货库存流转事实';

-- Source: V10__bi_operating_actions.sql
-- BI仅持有运营跟进及目标修订，不写订单资金、库存和人事薪资。
CREATE TABLE bi_operating_action (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    business_ref VARCHAR(160) NOT NULL,
    business_label VARCHAR(240) NOT NULL,
    city_code VARCHAR(64) NULL,
    employee_code VARCHAR(64) NULL,
    assignee VARCHAR(160) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    note VARCHAR(2000) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_operating_action_tenant_id (tenant_id, id),
    KEY idx_bi_action_queue (tenant_id, status, due_at),
    KEY idx_bi_action_business (tenant_id, kind, business_ref),
    KEY idx_bi_action_scope (tenant_id, city_code, employee_code, due_at),
    CONSTRAINT chk_bi_action_kind CHECK (kind IN ('COLLECTION', 'CUSTOMER', 'STOCK')),
    CONSTRAINT chk_bi_action_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'DISMISSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI运营跟进，不代表业务单据完成';

CREATE TABLE bi_operating_action_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    previous_status VARCHAR(24) NULL,
    status VARCHAR(24) NOT NULL,
    previous_assignee VARCHAR(160) NULL,
    assignee VARCHAR(160) NOT NULL,
    previous_due_at DATETIME(6) NULL,
    due_at DATETIME(6) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_action_event_revision (tenant_id, action_id, revision),
    CONSTRAINT fk_bi_action_event_action FOREIGN KEY (tenant_id, action_id)
        REFERENCES bi_operating_action (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='不可覆盖的运营处理记录';

CREATE TABLE bi_business_target_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    revision INT NOT NULL,
    target_value DECIMAL(24,6) NOT NULL,
    dimension_name VARCHAR(160) NULL,
    remark VARCHAR(1000) NULL,
    deleted INT NOT NULL DEFAULT 0,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_target_event_revision (tenant_id, target_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI经营目标修订记录';

-- Source: V11__bi_reconciliation_reviews.sql
-- 只保存显式复核作业生成的不可变证据，不变更任何领域业务数据。
CREATE TABLE bi_reconciliation_review (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    source_batch_id VARCHAR(36) NOT NULL,
    captured_time DATETIME(6) NOT NULL,
    completed_time DATETIME(6) NOT NULL,
    review_json JSON NOT NULL COMMENT '版本、范围、原始口径、逐订单/SKU差异；无附件和凭据',
    PRIMARY KEY (id),
    KEY idx_bi_review_actor (tenant_id, actor_id, captured_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='BI只读来源复核证据';

-- Source: V12__bi_data_access_scope.sql
-- IAM/HR/CRM 的已验证只读授权投影。不从姓名或既有业务可见性推导授权，不自动插入用户授权。
CREATE TABLE bi_data_access_identity (
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    owner_staff_code VARCHAR(50) NOT NULL,
    iam_binding_ref VARCHAR(128) NOT NULL,
    hr_employee_ref VARCHAR(128) NOT NULL,
    crm_employee_ref VARCHAR(128) NOT NULL,
    user_security_version BIGINT NOT NULL,
    tenant_policy_version BIGINT NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    KEY idx_bi_scope_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_data_access_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    operation_code VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_bi_access_audit_subject (tenant_id, user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_data_access_scope (
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    region_code VARCHAR(64) NOT NULL,
    iam_policy_ref VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_code, scope_type, region_code),
    CONSTRAINT fk_bi_scope_identity FOREIGN KEY (tenant_id, user_id)
        REFERENCES bi_data_access_identity (tenant_id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_bi_scope_type CHECK (scope_type IN ('SELF', 'MY_CITY', 'MY_REGION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V13__bi_erp_category_hierarchy.sql
-- ERP分类全量投影，含无成交分类；只在BI刷新事务中维护。
CREATE TABLE bi_product_category_dim (
    tenant_id VARCHAR(64) NOT NULL,
    category_id BIGINT NOT NULL,
    category_code VARCHAR(64) NULL,
    category_name VARCHAR(160) NOT NULL,
    parent_id BIGINT NULL,
    category_level INT NULL,
    ordinal INT NULL,
    deleted INT NOT NULL DEFAULT 0,
    source_updated_time DATETIME(6) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, category_id),
    KEY idx_bi_category_parent (tenant_id, parent_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_product_category_closure (
    tenant_id VARCHAR(64) NOT NULL,
    ancestor_id BIGINT NOT NULL,
    descendant_id BIGINT NOT NULL,
    depth INT NOT NULL,
    PRIMARY KEY (tenant_id, ancestor_id, descendant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V14__bi_employee_dimension.sql
-- HR 是人员主数据单一写者；本表仅为 BI 刷新任务维护的可重建投影。
CREATE TABLE bi_employee_dim (
    tenant_id VARCHAR(64) NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    employee_name VARCHAR(128) NOT NULL,
    employment_status VARCHAR(32) NOT NULL,
    city_name VARCHAR(160) NULL,
    region_code VARCHAR(128) NULL,
    position_name VARCHAR(120) NULL,
    department_name VARCHAR(128) NULL,
    entry_date DATETIME(6) NULL,
    leave_date DATETIME(6) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, employee_code),
    KEY idx_bi_employee_region (tenant_id, region_code, employment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR当前员工统计投影';

CREATE TABLE bi_employee_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='员工投影完整刷新标记，区分未接入和空档案';

-- Source: V15__bi_sales_city_contacts.sql
CREATE TABLE bi_sales_contact_fact (
    tenant_id VARCHAR(64) NOT NULL,
    submission_id VARCHAR(36) NOT NULL,
    store_id VARCHAR(36) NOT NULL,
    city_name VARCHAR(160) NULL,
    region_code VARCHAR(128) NULL,
    submitted_at DATETIME(6) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, submission_id),
    KEY idx_bi_contact_date (tenant_id, submitted_at, region_code, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales已提交未删除的拜访记录，含审核状态';

CREATE TABLE bi_sales_contact_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales建联投影完整刷新标记';

CREATE TABLE bi_sales_contact_city_dim (
    tenant_id VARCHAR(64) NOT NULL,
    region_code VARCHAR(128) NOT NULL,
    city_name VARCHAR(160) NOT NULL,
    PRIMARY KEY (tenant_id, region_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='城市建联统计目录，保留期间无凭证的城市';

-- Source: V16__bi_customer_attributes.sql
-- CRM 维护客户来源和经营类别，BI 只保存可重建的当前档案投影。
CREATE TABLE bi_customer_attribute_current (
    tenant_id VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_source_name VARCHAR(120) NULL,
    business_category_name VARCHAR(120) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户来源和经营类别当前投影';

CREATE TABLE bi_customer_attribute_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户属性完整刷新标记';

-- Source: V17__bi_sales_business_links.sql
ALTER TABLE bi_sales_contact_fact
    ADD COLUMN salesperson_id VARCHAR(36) NULL,
    ADD COLUMN owner_staff_code VARCHAR(128) NULL COMMENT '提交人已核对的 HR 员工编码，非 CRM 客户负责人',
    ADD COLUMN customer_id BIGINT NULL,
    ADD COLUMN customer_code VARCHAR(128) NULL,
    ADD KEY idx_bi_contact_owner (tenant_id, owner_staff_code, submitted_at, region_code, store_id);

-- 旧快照未带固定编码时不能把个人查询误报为零。
ALTER TABLE bi_sales_contact_snapshot
    ADD COLUMN business_links_ready TINYINT NOT NULL DEFAULT 0;

-- Source: V18__bi_supply_authority_projection.sql
-- 只接收所属领域提供的权威归属，不在 BI 编辑，也不从当前客户猜历史订单。
CREATE TABLE bi_order_authority (
 tenant_id VARCHAR(64) NOT NULL, order_id BIGINT NOT NULL, employee_code VARCHAR(64), employee_name VARCHAR(128),
 department_id BIGINT, department_path JSON, region_code VARCHAR(64), region_path JSON,
 warehouse_id BIGINT, attribution_state VARCHAR(32) NOT NULL, source_version VARCHAR(200),
 projected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,order_id), KEY idx_bi_order_authority_employee(tenant_id,employee_code)
);
CREATE TABLE bi_customer_authority (
 tenant_id VARCHAR(64) NOT NULL, customer_id BIGINT NOT NULL, employee_code VARCHAR(64),
 region_code VARCHAR(64), region_path JSON, source_revision BIGINT NOT NULL,
 projected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,customer_id)
);
CREATE TABLE bi_region_authority (
 tenant_id VARCHAR(64) NOT NULL, region_code VARCHAR(64) NOT NULL, region_path JSON NOT NULL,
 PRIMARY KEY(tenant_id,region_code)
);
CREATE TABLE bi_authority_checkpoint (
 tenant_id VARCHAR(64) NOT NULL, source_code VARCHAR(32) NOT NULL,
 source_version VARCHAR(200) NOT NULL, projected_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,source_code)
);
ALTER TABLE bi_inventory_operation_fact ADD COLUMN warehouse_id BIGINT NULL COMMENT '业务单据实际仓库，缺失时不推断';

-- Source: V19__bi_domain_source_mirrors.sql
-- BI 本地源投影，只由受信领域 API 装载，禁止回写业务源表。

CREATE TABLE bi_source_crm_crm_contact (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `party_id` binary(16) NULL,
 `contact_name` varchar(160) NULL,
 `phone` varchar(128) NULL,
 `is_primary` tinyint(1) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_party_id` (tenant_id,`party_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_crm_crm_customer (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `customer_code` varchar(50) NULL,
 `party_id` binary(16) NULL,
 `customer_name` varchar(200) NULL,
 `contact_name` varchar(100) NULL,
 `contact_phone` varchar(50) NULL,
 `customer_type_code` varchar(64) NULL,
 `region_code` varchar(64) NULL,
 `region_name` varchar(80) NULL,
 `owner_sales_name` varchar(100) NULL,
 `owner_employee_code` varchar(50) NULL,
 `owner_employee_name_snapshot` varchar(100) NULL,
 `status_code` varchar(64) NULL,
 `source_system_code` varchar(32) NULL,
 `source_document_no` varchar(128) NULL,
 `source_created_at` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_customer_code` (tenant_id,`customer_code`),
 KEY `idx_party_id` (tenant_id,`party_id`),
 KEY `idx_customer_type_code` (tenant_id,`customer_type_code`),
 KEY `idx_region_code` (tenant_id,`region_code`),
 KEY `idx_owner_employee_code` (tenant_id,`owner_employee_code`),
 KEY `idx_status_code` (tenant_id,`status_code`),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_crm_crm_customer_area (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `area_code` varchar(128) NULL,
 `area_name` varchar(160) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_area_code` (tenant_id,`area_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_crm_crm_customer_policy (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `party_id` binary(16) NULL,
 `payment_term_days` int unsigned NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_party_id` (tenant_id,`party_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_crm_crm_customer_type (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `type_code` varchar(128) NULL,
 `type_name` varchar(160) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_type_code` (tenant_id,`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_inventory_warehouse (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `warehouse_code` varchar(50) NULL,
 `warehouse_name` varchar(120) NULL,
 `region_code` varchar(64) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_warehouse_code` (tenant_id,`warehouse_code`),
 KEY `idx_region_code` (tenant_id,`region_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_procurement_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `procurement_no` varchar(50) NULL,
 `source_document_no` varchar(128) NULL,
 `target_warehouse_id` bigint NULL,
 `status_code` varchar(64) NULL,
 `expected_arrival_time` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_target_warehouse_id` (tenant_id,`target_warehouse_id`),
 KEY `idx_status_code` (tenant_id,`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_procurement_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `procurement_order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(50) NULL,
 `variant_code_snapshot` varchar(50) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_procurement_order_id` (tenant_id,`procurement_order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_product (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `product_code` varchar(50) NULL,
 `product_name` varchar(200) NULL,
 `category_id` bigint NULL,
 `brand_id` bigint NULL,
 `unit_code` varchar(64) NULL,
 `shelf_status_code` varchar(64) NULL,
 `submit_status_code` varchar(64) NULL,
 `source_system_code` varchar(32) NULL,
 `source_document_no` varchar(128) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_product_code` (tenant_id,`product_code`),
 KEY `idx_category_id` (tenant_id,`category_id`),
 KEY `idx_brand_id` (tenant_id,`brand_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`),
 KEY `idx_shelf_status_code` (tenant_id,`shelf_status_code`),
 KEY `idx_submit_status_code` (tenant_id,`submit_status_code`),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_product_brand (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `brand_code` varchar(50) NULL,
 `brand_name` varchar(120) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_brand_code` (tenant_id,`brand_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_product_category (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `parent_id` bigint NULL,
 `category_code` varchar(50) NULL,
 `category_name` varchar(120) NULL,
 `category_level` int NULL,
 `ordinal` int NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_parent_id` (tenant_id,`parent_id`),
 KEY `idx_category_code` (tenant_id,`category_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_product_variant (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `product_id` bigint NULL,
 `variant_code` varchar(50) NULL,
 `specification_snapshot` varchar(500) NULL,
 `unit_code` varchar(64) NULL,
 `purchase_price` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_variant_code` (tenant_id,`variant_code`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_stock_balance (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `warehouse_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `available_quantity` decimal(24,6) NULL,
 `locked_quantity` decimal(24,6) NULL,
 `in_transit_quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_warehouse_id` (tenant_id,`warehouse_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_stock_out_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `stock_out_no` varchar(50) NULL,
 `source_document_no` varchar(128) NULL,
 `stock_out_type_code` varchar(64) NULL,
 `warehouse_id` bigint NULL,
 `status_code` varchar(64) NULL,
 `stock_out_time` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_stock_out_type_code` (tenant_id,`stock_out_type_code`),
 KEY `idx_warehouse_id` (tenant_id,`warehouse_id`),
 KEY `idx_status_code` (tenant_id,`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_erp_erp_stock_out_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `stock_out_order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(50) NULL,
 `variant_code_snapshot` varchar(50) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_stock_out_order_id` (tenant_id,`stock_out_order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_integration_integration_feishu_import_batch (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_url` varchar(2000) NULL,
 `original_file_name` varchar(255) NULL,
 `file_sha256` char(64) NULL,
 `status` varchar(32) NULL,
 `total_rows` bigint unsigned NULL,
 `created_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_integration_integration_feishu_import_raw_row (
 `id` binary(16) NOT NULL,
 `batch_id` binary(16) NULL,
 `tenant_id` binary(16) NOT NULL,
 `sheet_name` varchar(255) NULL,
 `table_code` varchar(64) NULL,
 `source_row_number` int unsigned NULL,
 `source_document_no` varchar(128) NULL,
 `source_created_at` datetime(6) NULL,
 `raw_row_hash` char(64) NULL,
 `row_json` json NULL,
 `import_status` varchar(32) NULL,
 `projection_status` varchar(32) NULL,
 `error_code` varchar(64) NULL,
 `created_at` datetime(6) NULL,
 `updated_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_batch_id` (tenant_id,`batch_id`),
 KEY `idx_table_code` (tenant_id,`table_code`),
 KEY `idx_error_code` (tenant_id,`error_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_integration_integration_feishu_online_capture (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_id` varchar(128) NULL,
 `source_name` varchar(255) NULL,
 `source_url` varchar(2000) NULL,
 `started_at` datetime(6) NULL,
 `completed_at` datetime(6) NULL,
 `complete` tinyint(1) NULL,
 `filtered` tinyint(1) NULL,
 `checksum` char(64) NULL,
 `record_count` int NULL,
 `page_count` int NULL,
 `payload_json` longtext NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_id` (tenant_id,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_integration_integration_raw_landing (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_system` varchar(32) NULL,
 `source_object_type` varchar(64) NULL,
 `source_id` varchar(128) NULL,
 `received_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_id` (tenant_id,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_order_order_payment_record (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `payment_no` varchar(50) NULL,
 `source_system_code` varchar(64) NULL,
 `source_document_no` varchar(128) NULL,
 `order_id` bigint NULL,
 `sales_order_no_snapshot` varchar(50) NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `collector_staff_code` varchar(50) NULL,
 `collector_name_snapshot` varchar(100) NULL,
 `payment_time` datetime(6) NULL,
 `payment_method_code` varchar(64) NULL,
 `paid_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_collector_staff_code` (tenant_id,`collector_staff_code`),
 KEY `idx_payment_method_code` (tenant_id,`payment_method_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_order_order_refund_record (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_id` bigint NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `refund_status_code` varchar(64) NULL,
 `refund_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_refund_status_code` (tenant_id,`refund_status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_order_order_sales_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_no` varchar(50) NULL,
 `source_system_code` varchar(32) NULL,
 `source_order_no` varchar(80) NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `region_code` varchar(64) NULL,
 `owner_sales_name` varchar(100) NULL,
 `owner_employee_code` varchar(50) NULL,
 `owner_employee_name_snapshot` varchar(100) NULL,
 `order_date` datetime(6) NULL,
 `order_status_code` varchar(64) NULL,
 `order_type_code` varchar(64) NULL,
 `payment_method_code` varchar(64) NULL,
 `payment_status_code` varchar(64) NULL,
 `outbound_status_code` varchar(64) NULL,
 `total_quantity` decimal(24,6) NULL,
 `discount_rate` decimal(10,6) NULL,
 `discount_amount` decimal(24,6) NULL,
 `payable_amount` decimal(24,6) NULL,
 `paid_amount` decimal(24,6) NULL,
 `unpaid_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 `payment_time` datetime(6) NULL,
 `shipment_time` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_region_code` (tenant_id,`region_code`),
 KEY `idx_owner_employee_code` (tenant_id,`owner_employee_code`),
 KEY `idx_order_status_code` (tenant_id,`order_status_code`),
 KEY `idx_order_type_code` (tenant_id,`order_type_code`),
 KEY `idx_payment_method_code` (tenant_id,`payment_method_code`),
 KEY `idx_payment_status_code` (tenant_id,`payment_status_code`),
 KEY `idx_outbound_status_code` (tenant_id,`outbound_status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_order_order_sales_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(128) NULL,
 `sku_code_snapshot` varchar(128) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `specification_snapshot` varchar(500) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `unit_price` decimal(24,6) NULL,
 `discount_rate` decimal(10,6) NULL,
 `discount_amount` decimal(24,6) NULL,
 `line_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bi_source_snapshot_checkpoint (
 tenant_id VARCHAR(64) NOT NULL,dataset VARCHAR(100) NOT NULL,source_version VARCHAR(1000) NOT NULL,
 projected_at DATETIME(6) NOT NULL,PRIMARY KEY(tenant_id,dataset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V20__bi_people_source_mirrors.sql
-- 新的人事/拜访数据走所属领域 API，维持 BI 本地投影和租户边界。
ALTER TABLE bi_source_crm_crm_customer ADD customer_source_name VARCHAR(120) NULL, ADD business_category_name VARCHAR(120) NULL;
ALTER TABLE bi_source_crm_crm_customer_area ADD parent_area_code VARCHAR(128) NULL;
ALTER TABLE bi_employee_dim ADD department_id BIGINT NULL, ADD department_path JSON NULL;
CREATE TABLE bi_source_hr_hr_employee (
 id BIGINT NOT NULL,tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,employment_status VARCHAR(32) NOT NULL,city_name VARCHAR(160) NULL,
 position_name VARCHAR(120) NULL,department_name VARCHAR(128) NULL,department_id BIGINT NULL,department_path JSON NULL,
 entry_date DATETIME(6) NULL,leave_date DATETIME(6) NULL,PRIMARY KEY(tenant_id,id),KEY ix_bi_hr_employee(tenant_id,employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE bi_source_sales_sales_submitted_visit (
 id BINARY(16) NOT NULL,tenant_id BINARY(16) NOT NULL,store_id BINARY(16) NOT NULL,city_name VARCHAR(160) NULL,
 submitted_at DATETIME(6) NOT NULL,review_status VARCHAR(32) NOT NULL,salesperson_id BINARY(16) NULL,
 owner_staff_code VARCHAR(128) NULL,customer_id BIGINT NULL,customer_code VARCHAR(128) NULL,PRIMARY KEY(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
-- 导出字段发生变化，重新装载镜像后才标记为同步完成。
DELETE FROM bi_source_snapshot_checkpoint WHERE dataset IN ('CRM_CUSTOMER','CRM_CUSTOMER_AREA');

-- Source: V21__customer_business_creation_mirror.sql
-- 月度新增客户以 business_created_at（UTC）分桶，禁止回退到入库时间。
ALTER TABLE bi_source_crm_crm_customer
 ADD COLUMN business_created_at DATETIME(6) NULL,
 ADD COLUMN business_created_by_id VARCHAR(128) NULL,
 ADD COLUMN business_created_by_name VARCHAR(160) NULL,
 ADD COLUMN business_creation_source VARCHAR(32) NULL,
 ADD INDEX idx_bi_customer_business_created (tenant_id, deleted, business_created_at);

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
