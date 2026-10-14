-- Resume the ERP import that stopped at the first rigour_bi_app GRANT statement.
-- Select the SAME target ERP database where the first 4 statements succeeded.
-- Prerequisite: erp_inventory_warehouse and its 3 secondary indexes already exist;
-- the remaining 27 tables in this script must not exist yet.
-- The warehouse table and indexes are skipped. No existing tables are dropped or altered.
-- No account grants, business data or Flyway history records are included.
-- Stop on the first error; do not run this after a partially completed continuation.

create table erp_master_data_sync_checkpoint
(
    id                  char(36)                         not null
        primary key,
    tenant_id           varchar(64)                      not null,
    connector_id        char(36)                         not null,
    source_system       varchar(32)                      not null,
    object_type         varchar(32)                      not null,
    cursor_type         varchar(24) default 'PAGE_TOKEN' not null,
    cursor_value        varchar(1024)                    null,
    source_updated_at   datetime(6)                      null,
    last_success_run_id char(36)                         null,
    revision            bigint      default 0            not null,
    created_by          varchar(50)                      null comment '创建人',
    created_time        datetime(6)                      not null,
    updated_by          varchar(50)                      null comment '更新人',
    updated_time        datetime(6)                      not null,
    deleted             int         default 0            not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_master_data_sync_checkpoint
        unique (tenant_id, connector_id, source_system, object_type)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品主数据同步游标';

create table erp_master_data_sync_lock
(
    id            char(36)                                 not null
        primary key,
    tenant_id     varchar(64)                              not null,
    source_system varchar(32)                              not null,
    object_type   varchar(32)                              not null,
    run_id        char(36)                                 not null,
    acquired_at   datetime(6)                              not null,
    expires_at    datetime(6)                              not null,
    revision      int         default 1                    not null comment '乐观锁版本',
    created_by    varchar(50)                              null comment '创建人',
    created_time  datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by    varchar(50)                              null comment '更新人',
    updated_time  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted       int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_master_data_sync_lock_scope
        unique (tenant_id, source_system, object_type)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP同步互斥锁';

create index idx_erp_master_data_sync_lock_expiry
    on erp_master_data_sync_lock (expires_at);

create table erp_master_data_sync_run
(
    id                  char(36)                       not null
        primary key,
    tenant_id           varchar(64)                    not null,
    connector_id        char(36)                       null,
    source_task_id      char(36)                       null comment 'Integration同步任务UUID，用于追溯定时调度来源',
    source_system       varchar(32)                    not null,
    object_type         varchar(32)                    not null,
    trigger_type        varchar(16)  default 'MANUAL'  not null,
    status              varchar(32)  default 'RUNNING' not null comment '批次状态：RUNNING/SUCCEEDED/SUCCEEDED_WITH_WARNINGS/FAILED/SKIPPED',
    window_from         datetime(6)                    null,
    window_to           datetime(6)                    null,
    max_pages           int unsigned default '100'     not null,
    page_size           int unsigned default '100'     not null,
    fetched_count       bigint       default 0         not null,
    created_count       bigint       default 0         not null,
    changed_count       bigint       default 0         not null,
    duplicate_count     bigint       default 0         not null,
    rejected_count      bigint       default 0         not null,
    unmapped_count      bigint       default 0         not null comment '已落库但未找到唯一有效字典映射的来源枚举出现次数',
    dict_snapshot_json  json                           null comment '本批次使用的字典编码及整本内容版本',
    mapping_issues_json json                           null comment '按字典、字段和来源值聚合的未映射项，不含完整来源报文',
    error_code          varchar(64)                    null,
    error_message       varchar(2000)                  null,
    started_at          datetime(6)                    not null,
    finished_at         datetime(6)                    null,
    revision            int          default 1         not null comment '乐观锁版本',
    created_by          char(36)                       null,
    created_time        datetime(6)                    not null,
    updated_by          varchar(50)                    null comment '更新人',
    updated_time        datetime(6)                    not null,
    deleted             int          default 0         not null comment '删除标识：0未删除，1已删除',
    constraint ck_erp_sync_run_skipped_terminal
        check ((`status` <> _utf8mb4'SKIPPED') or ((`source_task_id` is not null) and (`connector_id` is not null) and (`finished_at` is not null) and (`error_code` is not null) and (`error_message` is not null)))
)
    default character set utf8mb4 collate utf8mb4_general_ci
comment 'ERP商品主数据同步批次';

create index idx_erp_master_data_sync_run_status
    on erp_master_data_sync_run (tenant_id, status, started_at);

create index idx_erp_master_data_sync_run_tenant_time
    on erp_master_data_sync_run (tenant_id, started_at);

create index idx_erp_sync_run_source_task
    on erp_master_data_sync_run (tenant_id, source_task_id, started_at);

create table erp_master_source_binding
(
    id                  char(36)                                                   not null
        primary key,
    tenant_id           varchar(64)                                                not null,
    connector_id        char(36)    default '00000000-0000-0000-0000-000000000000' not null comment 'Integration连接器ID；旧来源绑定无法追溯时使用全0占位',
    source_system       varchar(32)                                                not null,
    source_object_type  varchar(32)                                                not null,
    source_object_id    varchar(128)                                               not null,
    target_type         varchar(32)                                                not null,
    target_id           char(36)                                                   not null,
    source_code         varchar(128)                                               null,
    source_name         varchar(200)                                               null,
    source_status       varchar(32)                                                null,
    source_putaway      varchar(16)                                                null,
    source_updated_at   datetime(6)                                                null,
    source_payload_hash char(64)                                                   not null,
    source_presence     varchar(24) default 'UNKNOWN'                              not null,
    source_absent_at    datetime(6)                                                null,
    last_sync_run_id    char(36)                                                   null,
    synced_at           datetime(6)                                                not null,
    revision            bigint      default 0                                      not null,
    created_by          varchar(50)                                                null comment '创建人',
    created_time        datetime(6)                                                not null,
    updated_by          varchar(50)                                                null comment '更新人',
    updated_time        datetime(6)                                                not null,
    deleted             int         default 0                                      not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_master_source_binding_source
        unique (tenant_id, connector_id, source_system, source_object_type, source_object_id),
    constraint uk_erp_master_source_binding_target
        unique (tenant_id, connector_id, source_system, target_type, target_id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品主数据外部来源绑定';

create index idx_erp_master_source_binding_code
    on erp_master_source_binding (tenant_id, source_system, source_code);

create index idx_erp_master_source_binding_connector
    on erp_master_source_binding (tenant_id, connector_id, source_system, source_object_type);

create index idx_erp_master_source_binding_target
    on erp_master_source_binding (tenant_id, target_type, target_id);

create index idx_erp_source_presence
    on erp_master_source_binding (tenant_id, source_system, source_object_type, source_presence, source_absent_at);

create table erp_product_brand
(
    id           bigint auto_increment comment 'ID'
        primary key,
    tenant_id    varchar(64)                              not null comment '租户ID',
    brand_code   varchar(50)                              not null comment '商品品牌编码，由ERP编码规则生成',
    brand_name   varchar(120)                             not null comment '商品品牌名称',
    remark       varchar(500)                             null comment '备注',
    revision     int         default 1                    not null comment '乐观锁',
    created_by   varchar(50)                              null comment '创建人',
    created_time datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by   varchar(50)                              null comment '更新人',
    updated_time datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted      int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_brand_code
        unique (tenant_id, brand_code)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品品牌';

create index idx_erp_product_brand_name
    on erp_product_brand (tenant_id, brand_name, deleted);


create table erp_product_category
(
    id             bigint auto_increment comment 'ID'
        primary key,
    tenant_id      varchar(64)                              not null comment '租户ID',
    parent_id      bigint                                   null comment '父级分类ID',
    category_code  varchar(50)                              not null comment '商品分类编码，由ERP编码规则生成',
    category_name  varchar(120)                             not null comment '商品分类名称',
    category_level int         default 1                    not null comment '分类层级，根节点为1',
    ordinal        int         default 0                    not null comment '展示顺序，数值越小越靠前',
    remark         varchar(500)                             null comment '备注',
    revision       int         default 1                    not null comment '乐观锁',
    created_by     varchar(50)                              null comment '创建人',
    created_time   datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by     varchar(50)                              null comment '更新人',
    updated_time   datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted        int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_category_code
        unique (tenant_id, category_code),
    constraint fk_erp_product_category_parent
        foreign key (parent_id) references erp_product_category (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品分类';

create table erp_product
(
    id                         bigint auto_increment comment 'ID'
        primary key,
    tenant_id                  varchar(64)                              not null comment '租户ID',
    product_code               varchar(50)                              not null comment '商品编码，由ERP编码规则生成',
    product_name               varchar(200)                             null comment '商品名称；草稿允许暂为空，提交时由应用服务校验',
    business_line_name         varchar(120)                             null comment '业务线名称，飞书业务线原文',
    category_id                bigint                                   null comment '商品分类ID',
    category_name_snapshot     varchar(120)                             null comment '商品分类名称快照，飞书品类原文',
    brand_id                   bigint                                   null comment '商品品牌ID',
    brand_name_snapshot        varchar(120)                             null comment '商品品牌名称快照，飞书品牌原文',
    industry_name              varchar(120)                             null comment '行业名称，飞书行业原文',
    product_specification      varchar(500)                             null comment '商品规格说明',
    unit_code                  varchar(64)                              null comment '商品单位，关联 PRODUCT_UNIT 字典项；草稿允许暂为空，提交时由应用服务校验',
    min_order_quantity         decimal(24, 6)                           null comment '起订量',
    order_multiple_flag        tinyint(1)  default 0                    not null comment '是否整倍订货：0否，1是',
    order_multiple_quantity    decimal(24, 6)                           null comment '整倍订货数量',
    sale_type_code             varchar(64) default 'SPOT'               not null comment '售卖类型，关联 PRODUCT_SALE_TYPE 字典项',
    shelf_status_code          varchar(64) default 'OFF_SHELF'          not null comment '上架状态，关联 PRODUCT_SHELF_STATUS 字典项',
    source_status_name         varchar(80)                              null comment '来源商品状态名称',
    tag_codes_json             json                                     null comment '商品标签编码数组，来源商品标签列表',
    limit_quantity             decimal(24, 6)                           null comment '限购量',
    default_warehouse_id       bigint                                   null comment '默认归属仓库ID',
    image_keys_json            json                                     null comment '商品图片COS key数组，最多24张，区分主图和详情图',
    recommend_product_ids_json json                                     null comment '商品页关联推荐商品ID数组',
    submit_status_code         varchar(64) default 'DRAFT'              not null comment '提交状态，关联 PRODUCT_SUBMIT_STATUS 字典项',
    remark                     varchar(1000)                            null comment '商品备注',
    source_system_code         varchar(32)                              null comment '来源系统编码，如FEISHU',
    source_tenant_key          varchar(128)                             null comment '来源租户/表标识',
    source_product_id          varchar(128)                             null comment '来源商品ID',
    source_document_no         varchar(128)                             null comment '来源单号/来源商品编码',
    source_created_at          datetime(6)                              null comment '来源创建时间',
    source_updated_at          datetime(6)                              null comment '来源更新时间',
    source_payload_hash        char(64)                                 null comment '来源字段SHA-256摘要',
    source_payload_json        json                                     null comment '来源原始字段快照',
    revision                   int         default 1                    not null comment '乐观锁',
    created_by                 varchar(50)                              null comment '创建人',
    created_time               datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by                 varchar(50)                              null comment '更新人',
    updated_time               datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                    int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_code
        unique (tenant_id, product_code),
    constraint uk_erp_product_source
        unique (tenant_id, source_system_code, source_tenant_key, source_product_id),
    constraint fk_erp_product_brand
        foreign key (brand_id) references erp_product_brand (id),
    constraint fk_erp_product_category
        foreign key (category_id) references erp_product_category (id),
    constraint fk_erp_product_default_warehouse
        foreign key (default_warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品主表';

create index idx_erp_product_brand
    on erp_product (tenant_id, brand_id, deleted);

create index idx_erp_product_business_line
    on erp_product (tenant_id, business_line_name, deleted);

create index idx_erp_product_category
    on erp_product (tenant_id, category_id, deleted);

create index idx_erp_product_name
    on erp_product (tenant_id, product_name, deleted);

create index idx_erp_product_shelf
    on erp_product (tenant_id, shelf_status_code, updated_time);

create index idx_erp_product_source_doc
    on erp_product (tenant_id, source_system_code, source_document_no);

create index idx_erp_product_submit
    on erp_product (tenant_id, submit_status_code, updated_time);


create index idx_erp_product_category_parent
    on erp_product_category (tenant_id, parent_id, deleted, ordinal);


create table erp_product_specification
(
    id                 bigint auto_increment comment 'ID'
        primary key,
    tenant_id          varchar(64)                              not null comment '租户ID',
    specification_code varchar(50)                              not null comment '多规格编号，业务可见且租户内唯一',
    specification_name varchar(120)                             not null comment '多规格名称',
    status_code        varchar(64) default 'ACTIVE'             not null comment '状态，关联 PRODUCT_SPECIFICATION_STATUS 字典项',
    revision           int         default 1                    not null comment '乐观锁',
    created_by         varchar(50)                              null comment '创建人',
    created_time       datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by         varchar(50)                              null comment '更新人',
    updated_time       datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted            int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_specification_code
        unique (tenant_id, specification_code)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品规格';

create index idx_erp_product_specification_name
    on erp_product_specification (tenant_id, specification_name, deleted);

create index idx_erp_product_specification_status
    on erp_product_specification (tenant_id, status_code, deleted, updated_time);

create table erp_product_specification_value
(
    id               bigint auto_increment comment 'ID'
        primary key,
    tenant_id        varchar(64)                              not null comment '租户ID',
    specification_id bigint                                   not null comment '商品规格ID',
    value_code       varchar(50)                              not null comment '子规格编号，所属规格下唯一',
    value_name       varchar(120)                             not null comment '子规格名称',
    ordinal          int         default 0                    not null comment '排序值，数值越小越靠前',
    status_code      varchar(64) default 'ACTIVE'             not null comment '状态，关联 PRODUCT_SPECIFICATION_VALUE_STATUS 字典项',
    revision         int         default 1                    not null comment '乐观锁',
    created_by       varchar(50)                              null comment '创建人',
    created_time     datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by       varchar(50)                              null comment '更新人',
    updated_time     datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted          int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_specification_value_code
        unique (tenant_id, specification_id, value_code),
    constraint fk_erp_product_specification_value_spec
        foreign key (specification_id) references erp_product_specification (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品规格值';

create index idx_erp_product_specification_value_name
    on erp_product_specification_value (tenant_id, specification_id, value_name, deleted);

create index idx_erp_product_specification_value_sort
    on erp_product_specification_value (tenant_id, specification_id, deleted, ordinal);

create table erp_product_tag
(
    id            bigint auto_increment comment 'ID'
        primary key,
    tenant_id     varchar(64)                              not null comment '租户ID',
    tag_code      varchar(50)                              not null comment '商品标签编码，由ERP编码规则生成',
    tag_name      varchar(120)                             not null comment '商品标签名称',
    tag_type_code varchar(64)                              null comment '标签类型，关联 PRODUCT_TAG_TYPE 字典项',
    remark        varchar(500)                             null comment '备注',
    revision      int         default 1                    not null comment '乐观锁',
    created_by    varchar(50)                              null comment '创建人',
    created_time  datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by    varchar(50)                              null comment '更新人',
    updated_time  datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted       int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_tag_code
        unique (tenant_id, tag_code)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品标签';

create index idx_erp_product_tag_name
    on erp_product_tag (tenant_id, tag_name, deleted);

create table erp_product_variant
(
    id                      bigint auto_increment comment 'ID'
        primary key,
    tenant_id               varchar(64)                              not null comment '租户ID',
    product_id              bigint                                   not null comment '商品ID',
    variant_code            varchar(50)                              not null comment '商品规格编码，由ERP编码规则生成',
    specification_snapshot  varchar(500)                             null comment '规格快照，如口味/包装组合',
    unit_code               varchar(64)                              null comment '订货单位，关联 PRODUCT_UNIT 字典项；草稿允许暂为空，提交时由应用服务校验',
    sale_price              decimal(24, 6)                           null comment '订货价；草稿允许暂为空，提交时由应用服务校验',
    market_price            decimal(24, 6)                           null comment '市场价',
    purchase_price          decimal(24, 6)                           null comment '采购参考价',
    min_order_quantity      decimal(24, 6)                           null comment '当前规格起订量',
    order_multiple_quantity decimal(24, 6)                           null comment '当前规格整倍订货数量',
    limit_quantity          decimal(24, 6)                           null comment '当前规格限购量',
    default_flag            tinyint(1)  default 0                    not null comment '是否默认规格：0否，1是',
    remark                  varchar(1000)                            null comment '备注',
    revision                int         default 1                    not null comment '乐观锁',
    created_by              varchar(50)                              null comment '创建人',
    created_time            datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by              varchar(50)                              null comment '更新人',
    updated_time            datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                 int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_product_variant_code
        unique (tenant_id, variant_code),
    constraint fk_erp_product_variant_product
        foreign key (product_id) references erp_product (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP商品规格价格';

create index idx_erp_product_variant_price
    on erp_product_variant (tenant_id, sale_price, deleted);

create index idx_erp_product_variant_product
    on erp_product_variant (tenant_id, product_id, deleted);


create table erp_stock_balance
(
    id                  bigint auto_increment comment 'ID'
        primary key,
    tenant_id           varchar(64)                                 not null comment '租户ID',
    warehouse_id        bigint                                      not null comment '仓库ID',
    product_id          bigint                                      not null comment '商品ID',
    product_variant_id  bigint                                      not null comment '商品规格ID；无规格商品也应创建默认规格',
    available_quantity  decimal(24, 6) default 0.000000             not null comment '可用库存',
    locked_quantity     decimal(24, 6) default 0.000000             not null comment '锁定库存',
    in_transit_quantity decimal(24, 6) default 0.000000             not null comment '在途库存',
    revision            int            default 1                    not null comment '乐观锁',
    created_by          varchar(50)                                 null comment '创建人',
    created_time        datetime(6)    default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by          varchar(50)                                 null comment '更新人',
    updated_time        datetime(6)    default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted             int            default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_stock_balance
        unique (tenant_id, warehouse_id, product_id, product_variant_id),
    constraint fk_erp_stock_balance_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_stock_balance_variant
        foreign key (product_variant_id) references erp_product_variant (id),
    constraint fk_erp_stock_balance_warehouse
        foreign key (warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP库存余额表';

create index idx_erp_stock_balance_product
    on erp_stock_balance (tenant_id, product_id, product_variant_id);

create index idx_erp_stock_balance_warehouse
    on erp_stock_balance (tenant_id, warehouse_id, updated_time);


create table erp_stock_flow
(
    id                 bigint auto_increment comment 'ID'
        primary key,
    tenant_id          varchar(64)                              not null comment '租户ID',
    flow_no            varchar(50)                              not null comment '库存流水号，由ERP编码规则生成',
    warehouse_id       bigint                                   not null comment '仓库ID',
    product_id         bigint                                   not null comment '商品ID',
    product_variant_id bigint                                   not null comment '商品规格ID；无规格商品也应创建默认规格',
    business_type_code varchar(64)                              not null comment '业务类型：PURCHASE_IN/SALES_OUT/TRANSFER_OUT/TRANSFER_IN/ADJUST',
    business_order_id  bigint                                   null comment '业务单据ID',
    business_order_no  varchar(50)                              null comment '业务单据号',
    quantity_delta     decimal(24, 6)                           not null comment '库存变动数量，入库为正，出库为负',
    before_quantity    decimal(24, 6)                           not null comment '变动前可用库存',
    after_quantity     decimal(24, 6)                           not null comment '变动后可用库存',
    remark             varchar(1000)                            null comment '备注',
    revision           int         default 1                    not null comment '乐观锁版本',
    created_by         varchar(50)                              null comment '创建人',
    created_time       datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by         varchar(50)                              null comment '更新人',
    updated_time       datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted            int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_stock_flow_no
        unique (tenant_id, flow_no),
    constraint fk_erp_stock_flow_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_stock_flow_variant
        foreign key (product_variant_id) references erp_product_variant (id),
    constraint fk_erp_stock_flow_warehouse
        foreign key (warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP库存流水表';

create index idx_erp_stock_flow_business
    on erp_stock_flow (tenant_id, business_type_code, business_order_no);

create index idx_erp_stock_flow_product
    on erp_stock_flow (tenant_id, product_id, product_variant_id, created_time);

create table erp_stock_out_order
(
    id                     bigint auto_increment comment 'ID'
        primary key,
    tenant_id              varchar(64)                              not null comment '租户ID',
    stock_out_no           varchar(50)                              not null comment '出库单号，由ERP编码规则生成',
    connector_id           char(36)                                 null comment 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空',
    source_system_code     varchar(64)                              null comment '来源系统编码；订货宝同步时为DINGHUOBAO',
    source_document_no     varchar(128)                             null comment '来源出库/发货单号；订货宝为ships_num',
    stock_out_type_code    varchar(64)                              not null comment '出库类型，关联 STOCK_OUT_TYPE 字典项',
    warehouse_id           bigint                                   not null comment '出库仓库ID',
    sales_order_id         bigint                                   null comment '销售订单ID，跨Order服务引用',
    sales_order_no         varchar(50)                              null comment '销售订单号快照',
    transfer_order_id      bigint                                   null comment '调拨单ID',
    transfer_order_no      varchar(50)                              null comment '调拨单号快照',
    customer_id            bigint                                   null comment '客户ID，跨CRM服务引用',
    customer_name_snapshot varchar(200)                             null comment '客户名称快照',
    status_code            varchar(64) default 'DRAFT'              not null comment '出库单状态，关联 STOCK_OUT_STATUS 字典项',
    stock_out_time         datetime(6)                              null comment '确认出库时间',
    remark                 varchar(1000)                            null comment '备注',
    revision               int         default 1                    not null comment '乐观锁',
    created_by             varchar(50)                              null comment '创建人',
    created_time           datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by             varchar(50)                              null comment '更新人',
    updated_time           datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_stock_out_no
        unique (tenant_id, stock_out_no),
    constraint uk_erp_stock_out_source
        unique (tenant_id, connector_id, source_system_code, source_document_no),
    constraint fk_erp_stock_out_warehouse
        foreign key (warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP出库单';

create index idx_erp_stock_out_sales
    on erp_stock_out_order (tenant_id, sales_order_no);

create index idx_erp_stock_out_source
    on erp_stock_out_order (tenant_id, connector_id, source_system_code, source_document_no);

create index idx_erp_stock_out_status
    on erp_stock_out_order (tenant_id, status_code, created_time);

create index idx_erp_stock_out_transfer
    on erp_stock_out_order (tenant_id, transfer_order_no);

create index idx_erp_stock_out_warehouse
    on erp_stock_out_order (tenant_id, warehouse_id, stock_out_time);


create table erp_stock_out_order_line
(
    id                     bigint auto_increment comment 'ID'
        primary key,
    tenant_id              varchar(64)                              not null comment '租户ID',
    stock_out_order_id     bigint                                   not null comment '出库单ID',
    line_no                int                                      not null comment '行号',
    sales_order_line_id    bigint                                   null comment '销售订单明细ID；销售出库时用于追溯来源销售明细，跨Order服务引用不建外键',
    transfer_order_line_id bigint                                   null comment '调拨单明细ID；调拨出库时用于追溯来源调拨明细',
    product_id             bigint                                   not null comment '商品ID',
    product_variant_id     bigint                                   not null comment '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot  varchar(50)                              null comment '商品编码快照',
    variant_code_snapshot  varchar(50)                              null comment '规格编码快照',
    product_name_snapshot  varchar(200)                             null comment '商品名称快照',
    unit_code              varchar(64)                              not null comment '单位，关联 PRODUCT_UNIT 字典项',
    quantity               decimal(24, 6)                           not null comment '出库数量',
    remark                 varchar(1000)                            null comment '备注',
    revision               int         default 1                    not null comment '乐观锁版本',
    created_by             varchar(50)                              null comment '创建人',
    created_time           datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by             varchar(50)                              null comment '更新人',
    updated_time           datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint fk_erp_stock_out_line_order
        foreign key (stock_out_order_id) references erp_stock_out_order (id),
    constraint fk_erp_stock_out_line_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_stock_out_line_variant
        foreign key (product_variant_id) references erp_product_variant (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP出库单明细';

create index idx_erp_stock_out_line_no
    on erp_stock_out_order_line (tenant_id, stock_out_order_id, line_no, deleted);

create index idx_erp_stock_out_line_product
    on erp_stock_out_order_line (tenant_id, product_id, product_variant_id);

create index idx_erp_stock_out_line_sales
    on erp_stock_out_order_line (tenant_id, sales_order_line_id);

create index idx_erp_stock_out_line_transfer
    on erp_stock_out_order_line (tenant_id, transfer_order_line_id);


create table erp_supplier_profile
(
    id              bigint auto_increment comment 'ID'
        primary key,
    tenant_id       varchar(64)                              not null comment '租户ID',
    supplier_code   varchar(50)                              not null comment '供应商编码，由ERP编码规则生成',
    supplier_name   varchar(200)                             not null comment '供应商名称',
    contact_name    varchar(100)                             null comment '联系人',
    contact_phone   varchar(50)                              null comment '联系电话',
    address         varchar(1000)                            null comment '供应商地址',
    bank_name       varchar(100)                             null comment '开户银行',
    bank_account_no varchar(100)                             null comment '银行账号',
    status_code     varchar(64) default 'ACTIVE'             not null comment '供应商状态，关联 SUPPLIER_STATUS 字典项',
    remark          varchar(1000)                            null comment '备注',
    revision        int         default 1                    not null comment '乐观锁',
    created_by      varchar(50)                              null comment '创建人',
    created_time    datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by      varchar(50)                              null comment '更新人',
    updated_time    datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted         int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_supplier_profile_code
        unique (tenant_id, supplier_code)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP供应商档案';

create table erp_procurement_order
(
    id                    bigint auto_increment comment 'ID'
        primary key,
    tenant_id             varchar(64)                                 not null comment '租户ID',
    procurement_no        varchar(50)                                 not null comment '采购订单号，由ERP编码规则生成',
    connector_id          char(36)                                    null comment 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空',
    source_system_code    varchar(64)                                 null comment '来源系统编码；订货宝同步时为DINGHUOBAO',
    source_document_no    varchar(128)                                null comment '来源采购单号；订货宝为采购订单号',
    supplier_id           bigint                                      not null comment '供应商ID',
    target_warehouse_id   bigint                                      not null comment '计划入库仓库ID',
    status_code           varchar(64)    default 'DRAFT'              not null comment '采购状态，关联 PURCHASE_STATUS 字典项',
    expected_arrival_time datetime(6)                                 null comment '预计到货时间',
    total_quantity        decimal(24, 6) default 0.000000             not null comment '采购总数量，由明细汇总',
    total_amount          decimal(24, 6) default 0.000000             not null comment '采购总金额，由明细汇总',
    remark                varchar(1000)                               null comment '备注',
    revision              int            default 1                    not null comment '乐观锁',
    created_by            varchar(50)                                 null comment '创建人',
    created_time          datetime(6)    default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by            varchar(50)                                 null comment '更新人',
    updated_time          datetime(6)    default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted               int            default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_procurement_no
        unique (tenant_id, procurement_no),
    constraint uk_erp_procurement_source
        unique (tenant_id, connector_id, source_system_code, source_document_no),
    constraint fk_erp_procurement_supplier
        foreign key (supplier_id) references erp_supplier_profile (id),
    constraint fk_erp_procurement_warehouse
        foreign key (target_warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP采购订单';

create index idx_erp_procurement_status
    on erp_procurement_order (tenant_id, status_code, created_time);

create index idx_erp_procurement_supplier
    on erp_procurement_order (tenant_id, supplier_id, created_time);


create table erp_procurement_order_line
(
    id                    bigint auto_increment comment 'ID'
        primary key,
    tenant_id             varchar(64)                                 not null comment '租户ID',
    procurement_order_id  bigint                                      not null comment '采购订单ID',
    line_no               int                                         not null comment '行号',
    product_id            bigint                                      not null comment '商品ID',
    product_variant_id    bigint                                      not null comment '商品规格ID',
    product_code_snapshot varchar(50)                                 null comment '商品编码快照',
    variant_code_snapshot varchar(50)                                 null comment '商品规格编码快照',
    product_name_snapshot varchar(200)                                not null comment '商品名称快照',
    unit_code             varchar(64)                                 not null comment '采购单位，关联 PRODUCT_UNIT 字典项',
    quantity              decimal(24, 6)                              not null comment '采购数量',
    unit_price            decimal(24, 6) default 0.000000             not null comment '采购单价',
    line_amount           decimal(24, 6) default 0.000000             not null comment '采购金额',
    received_quantity     decimal(24, 6) default 0.000000             not null comment '已入库数量',
    remark                varchar(1000)                               null comment '备注',
    revision              int            default 1                    not null comment '乐观锁版本',
    created_by            varchar(50)                                 null comment '创建人',
    created_time          datetime(6)    default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by            varchar(50)                                 null comment '更新人',
    updated_time          datetime(6)    default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted               int            default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint fk_erp_procurement_line_order
        foreign key (procurement_order_id) references erp_procurement_order (id),
    constraint fk_erp_procurement_line_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_procurement_line_variant
        foreign key (product_variant_id) references erp_product_variant (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP采购订单明细';

create index idx_erp_procurement_line_no
    on erp_procurement_order_line (tenant_id, procurement_order_id, line_no, deleted);

create index idx_erp_procurement_line_product
    on erp_procurement_order_line (tenant_id, product_id, product_variant_id);


create table erp_purchase_return_order
(
    id                           bigint auto_increment comment 'ID'
        primary key,
    tenant_id                    varchar(64)                                 not null comment '租户ID',
    purchase_return_no           varchar(50)                                 not null comment '采购退货单号，由ERP编码规则生成',
    supplier_id                  bigint                                      not null comment '供应商ID',
    warehouse_id                 bigint                                      not null comment '退货出库仓库ID',
    operator_staff_code          varchar(50)                                 null comment '经办员工编码，关联IAM员工中心员工编码',
    operator_staff_name_snapshot varchar(100)                                null comment '经办员工名称快照',
    status_code                  varchar(64)    default 'DRAFT'              not null comment '采购退货状态，关联 PURCHASE_RETURN_STATUS 字典项',
    total_quantity               decimal(24, 6) default 0.000000             not null comment '退货总数量，由明细汇总或来源换算',
    total_amount                 decimal(24, 6) default 0.000000             not null comment '退货总金额',
    discount_amount              decimal(24, 6) default 0.000000             not null comment '优惠/折让金额',
    return_time                  datetime(6)                                 null comment '退货发出时间',
    contact_name                 varchar(100)                                null comment '联系人',
    contact_phone                varchar(50)                                 null comment '联系电话',
    contact_address              varchar(1000)                               null comment '联系地址',
    reason                       varchar(1000)                               null comment '退货原因',
    remark                       varchar(1000)                               null comment '备注',
    revision                     int            default 1                    not null comment '乐观锁',
    created_by                   varchar(50)                                 null comment '创建人',
    created_time                 datetime(6)    default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by                   varchar(50)                                 null comment '更新人',
    updated_time                 datetime(6)    default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                      int            default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_purchase_return_order_no
        unique (tenant_id, purchase_return_no),
    constraint fk_erp_purchase_return_order_supplier
        foreign key (supplier_id) references erp_supplier_profile (id),
    constraint fk_erp_purchase_return_order_warehouse
        foreign key (warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP采购退货单';

create index idx_erp_purchase_return_order_staff
    on erp_purchase_return_order (tenant_id, operator_staff_code, created_time);

create index idx_erp_purchase_return_order_status
    on erp_purchase_return_order (tenant_id, status_code, created_time);

create index idx_erp_purchase_return_order_supplier
    on erp_purchase_return_order (tenant_id, supplier_id, created_time);

create index idx_erp_purchase_return_order_warehouse
    on erp_purchase_return_order (tenant_id, warehouse_id, return_time);

create table erp_purchase_return_order_line
(
    id                       bigint auto_increment comment 'ID'
        primary key,
    tenant_id                varchar(64)                                 not null comment '租户ID',
    purchase_return_order_id bigint                                      not null comment '采购退货单ID',
    line_no                  int                                         not null comment '行号',
    procurement_order_id     bigint                                      null comment '关联采购订单ID；可由来源采购单号映射到ERP自研采购单',
    procurement_no_snapshot  varchar(50)                                 null comment '关联采购订单号快照',
    product_id               bigint                                      not null comment '商品ID',
    product_variant_id       bigint                                      not null comment '商品规格ID',
    product_code_snapshot    varchar(50)                                 null comment '商品编码快照',
    variant_code_snapshot    varchar(50)                                 null comment '商品规格编码快照',
    product_name_snapshot    varchar(200)                                not null comment '商品名称快照',
    unit_code                varchar(64)                                 not null comment '退货单位，关联 PRODUCT_UNIT 字典项',
    requested_quantity       decimal(24, 6) default 0.000000             not null comment '申请退货数量',
    returned_quantity        decimal(24, 6) default 0.000000             not null comment '确认退货数量',
    unit_price               decimal(24, 6) default 0.000000             not null comment '退货单价',
    confirmed_unit_price     decimal(24, 6) default 0.000000             not null comment '确认退货单价',
    line_amount              decimal(24, 6) default 0.000000             not null comment '明细金额',
    cost_price               decimal(24, 6) default 0.000000             not null comment '成本价快照',
    remark                   varchar(1000)                               null comment '备注',
    revision                 int            default 1                    not null comment '乐观锁版本',
    created_by               varchar(50)                                 null comment '创建人',
    created_time             datetime(6)    default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by               varchar(50)                                 null comment '更新人',
    updated_time             datetime(6)    default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                  int            default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint fk_erp_purchase_return_line_order
        foreign key (purchase_return_order_id) references erp_purchase_return_order (id),
    constraint fk_erp_purchase_return_line_procurement
        foreign key (procurement_order_id) references erp_procurement_order (id),
    constraint fk_erp_purchase_return_line_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_purchase_return_line_variant
        foreign key (product_variant_id) references erp_product_variant (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP采购退货单明细';

create index idx_erp_purchase_return_line_no
    on erp_purchase_return_order_line (tenant_id, purchase_return_order_id, line_no, deleted);

create index idx_erp_purchase_return_line_procurement
    on erp_purchase_return_order_line (tenant_id, procurement_order_id);

create index idx_erp_purchase_return_line_product
    on erp_purchase_return_order_line (tenant_id, product_id, product_variant_id);

create table erp_stock_in_order
(
    id                   bigint auto_increment comment 'ID'
        primary key,
    tenant_id            varchar(64)                              not null comment '租户ID',
    stock_in_no          varchar(50)                              not null comment '入库单号，由ERP编码规则生成',
    connector_id         char(36)                                 null comment 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空',
    source_system_code   varchar(64)                              null comment '来源系统编码；订货宝同步时为DINGHUOBAO',
    source_document_no   varchar(128)                             null comment '来源入库凭证号；订货宝为入库单号',
    stock_in_type_code   varchar(64)                              not null comment '入库类型，关联 STOCK_IN_TYPE 字典项',
    procurement_order_id bigint                                   null comment '采购订单ID',
    procurement_no       varchar(50)                              null comment '采购订单号快照',
    transfer_order_id    bigint                                   null comment '调拨单ID；调拨入库时用于追溯来源调拨单',
    transfer_order_no    varchar(50)                              null comment '调拨单号快照',
    warehouse_id         bigint                                   not null comment '入库仓库ID',
    supplier_id          bigint                                   null comment '供应商ID',
    status_code          varchar(64) default 'DRAFT'              not null comment '入库单状态，关联 STOCK_IN_STATUS 字典项',
    stock_in_time        datetime(6)                              null comment '确认入库时间',
    remark               varchar(1000)                            null comment '备注',
    revision             int         default 1                    not null comment '乐观锁',
    created_by           varchar(50)                              null comment '创建人',
    created_time         datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by           varchar(50)                              null comment '更新人',
    updated_time         datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted              int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_stock_in_no
        unique (tenant_id, stock_in_no),
    constraint uk_erp_stock_in_source
        unique (tenant_id, connector_id, source_system_code, source_document_no),
    constraint fk_erp_stock_in_procurement
        foreign key (procurement_order_id) references erp_procurement_order (id),
    constraint fk_erp_stock_in_supplier
        foreign key (supplier_id) references erp_supplier_profile (id),
    constraint fk_erp_stock_in_warehouse
        foreign key (warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP入库单';

create index idx_erp_stock_in_procurement
    on erp_stock_in_order (tenant_id, procurement_order_id);

create index idx_erp_stock_in_status
    on erp_stock_in_order (tenant_id, status_code, created_time);

create index idx_erp_stock_in_transfer
    on erp_stock_in_order (tenant_id, transfer_order_no);

create index idx_erp_stock_in_warehouse
    on erp_stock_in_order (tenant_id, warehouse_id, stock_in_time);

create table erp_stock_in_order_line
(
    id                        bigint auto_increment comment 'ID'
        primary key,
    tenant_id                 varchar(64)                              not null comment '租户ID',
    stock_in_order_id         bigint                                   not null comment '入库单ID',
    line_no                   int                                      not null comment '行号',
    procurement_order_line_id bigint                                   null comment '采购订单明细ID；采购入库时用于回写已入库数量',
    transfer_order_line_id    bigint                                   null comment '调拨单明细ID；调拨入库时用于追溯来源调拨明细',
    product_id                bigint                                   not null comment '商品ID',
    product_variant_id        bigint                                   not null comment '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot     varchar(50)                              null comment '商品编码快照',
    variant_code_snapshot     varchar(50)                              null comment '规格编码快照',
    product_name_snapshot     varchar(200)                             null comment '商品名称快照',
    unit_code                 varchar(64)                              not null comment '单位，关联 PRODUCT_UNIT 字典项',
    quantity                  decimal(24, 6)                           not null comment '入库数量',
    unit_price                decimal(24, 6)                           null comment '入库单价',
    amount                    decimal(24, 6)                           null comment '入库金额',
    remark                    varchar(1000)                            null comment '备注',
    revision                  int         default 1                    not null comment '乐观锁版本',
    created_by                varchar(50)                              null comment '创建人',
    created_time              datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by                varchar(50)                              null comment '更新人',
    updated_time              datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                   int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint fk_erp_stock_in_line_order
        foreign key (stock_in_order_id) references erp_stock_in_order (id),
    constraint fk_erp_stock_in_line_procurement_line
        foreign key (procurement_order_line_id) references erp_procurement_order_line (id),
    constraint fk_erp_stock_in_line_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_stock_in_line_variant
        foreign key (product_variant_id) references erp_product_variant (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP入库单明细';

create index idx_erp_stock_in_line_no
    on erp_stock_in_order_line (tenant_id, stock_in_order_id, line_no, deleted);

create index idx_erp_stock_in_line_procurement
    on erp_stock_in_order_line (tenant_id, procurement_order_line_id);

create index idx_erp_stock_in_line_product
    on erp_stock_in_order_line (tenant_id, product_id, product_variant_id);

create index idx_erp_stock_in_line_transfer
    on erp_stock_in_order_line (tenant_id, transfer_order_line_id);

create index idx_erp_supplier_profile_name
    on erp_supplier_profile (tenant_id, supplier_name, deleted);

create index idx_erp_supplier_profile_status
    on erp_supplier_profile (tenant_id, status_code, deleted, updated_time);

create table erp_transfer_order
(
    id                                    bigint auto_increment comment 'ID'
        primary key,
    tenant_id                             varchar(64)                              not null comment '租户ID',
    transfer_no                           varchar(50)                              not null comment '调拨单号，由ERP编码规则生成',
    connector_id                          char(36)                                 null comment 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空',
    source_system_code                    varchar(64)                              null comment '来源系统编码；订货宝同步时为DINGHUOBAO',
    source_document_no                    varchar(128)                             null comment '来源调拨出库单号；订货宝为ships_num',
    source_warehouse_id                   bigint                                   not null comment '来源仓库ID',
    target_warehouse_id                   bigint                                   not null comment '目标仓库ID',
    outbound_operator_staff_code          varchar(50)                              null comment '调拨出库经办员工编码，关联IAM员工中心员工编码',
    outbound_operator_staff_name_snapshot varchar(100)                             null comment '调拨出库经办员工名称快照',
    inbound_operator_staff_code           varchar(50)                              null comment '调拨入库经办员工编码，关联IAM员工中心员工编码',
    inbound_operator_staff_name_snapshot  varchar(100)                             null comment '调拨入库经办员工名称快照',
    status_code                           varchar(64) default 'DRAFT'              not null comment '调拨单状态，关联 TRANSFER_STATUS 字典项',
    stock_out_time                        datetime(6)                              null comment '确认调拨出库时间',
    stock_in_time                         datetime(6)                              null comment '确认调拨入库时间',
    remark                                varchar(1000)                            null comment '备注',
    revision                              int         default 1                    not null comment '乐观锁',
    created_by                            varchar(50)                              null comment '创建人',
    created_time                          datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by                            varchar(50)                              null comment '更新人',
    updated_time                          datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted                               int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint uk_erp_transfer_no
        unique (tenant_id, transfer_no),
    constraint uk_erp_transfer_source
        unique (tenant_id, connector_id, source_system_code, source_document_no),
    constraint fk_erp_transfer_source_warehouse
        foreign key (source_warehouse_id) references erp_inventory_warehouse (id),
    constraint fk_erp_transfer_target_warehouse
        foreign key (target_warehouse_id) references erp_inventory_warehouse (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP调拨单';

create index idx_erp_transfer_external_source
    on erp_transfer_order (tenant_id, connector_id, source_system_code, source_document_no);

create index idx_erp_transfer_inbound_operator
    on erp_transfer_order (tenant_id, inbound_operator_staff_code, created_time);

create index idx_erp_transfer_outbound_operator
    on erp_transfer_order (tenant_id, outbound_operator_staff_code, created_time);

create index idx_erp_transfer_source
    on erp_transfer_order (tenant_id, source_warehouse_id, created_time);

create index idx_erp_transfer_status
    on erp_transfer_order (tenant_id, status_code, created_time);

create index idx_erp_transfer_target
    on erp_transfer_order (tenant_id, target_warehouse_id, created_time);

create table erp_transfer_order_line
(
    id                    bigint auto_increment comment 'ID'
        primary key,
    tenant_id             varchar(64)                              not null comment '租户ID',
    transfer_order_id     bigint                                   not null comment '调拨单ID',
    line_no               int                                      not null comment '行号',
    product_id            bigint                                   not null comment '商品ID',
    product_variant_id    bigint                                   not null comment '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot varchar(50)                              null comment '商品编码快照',
    variant_code_snapshot varchar(50)                              null comment '规格编码快照',
    product_name_snapshot varchar(200)                             null comment '商品名称快照',
    unit_code             varchar(64)                              not null comment '单位，关联 PRODUCT_UNIT 字典项',
    quantity              decimal(24, 6)                           not null comment '调拨数量',
    remark                varchar(1000)                            null comment '备注',
    revision              int         default 1                    not null comment '乐观锁版本',
    created_by            varchar(50)                              null comment '创建人',
    created_time          datetime(6) default CURRENT_TIMESTAMP(6) not null comment '创建时间',
    updated_by            varchar(50)                              null comment '更新人',
    updated_time          datetime(6) default CURRENT_TIMESTAMP(6) not null on update CURRENT_TIMESTAMP(6) comment '更新时间',
    deleted               int         default 0                    not null comment '删除标识：0未删除，1已删除',
    constraint fk_erp_transfer_line_order
        foreign key (transfer_order_id) references erp_transfer_order (id),
    constraint fk_erp_transfer_line_product
        foreign key (product_id) references erp_product (id),
    constraint fk_erp_transfer_line_variant
        foreign key (product_variant_id) references erp_product_variant (id)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment 'ERP调拨单明细';

create index idx_erp_transfer_line_no
    on erp_transfer_order_line (tenant_id, transfer_order_id, line_no, deleted);

create index idx_erp_transfer_line_product
    on erp_transfer_order_line (tenant_id, product_id, product_variant_id);

-- Source: V31__erp_order_execution_receipt.sql
CREATE TABLE erp_order_execution_receipt (
    tenant_id VARCHAR(64) NOT NULL,execution_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,
    stock_out_id BIGINT NULL,stock_out_no VARCHAR(64) NULL,stock_out_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,execution_id),UNIQUE KEY uk_erp_execution_order(tenant_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V33__erp_customer_type_price.sql
CREATE TABLE erp_customer_type_price (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    product_id            BIGINT(20)    NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)    NOT NULL COMMENT '商品规格ID',
    customer_type_code    VARCHAR(128)  NOT NULL COMMENT '客户类型编码，引用CRM客户类型type_code',
    sale_price            DECIMAL(24,6) NOT NULL COMMENT '等级订货价（小单位）',
    remark                VARCHAR(500)  NULL COMMENT '备注',
    revision              INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)   NULL COMMENT '创建人',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)   NULL COMMENT '更新人',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_customer_type_price_variant_type (tenant_id, product_variant_id, customer_type_code),
    CONSTRAINT fk_erp_customer_type_price_product
        FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_customer_type_price_variant
        FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_customer_type_price_type (tenant_id, customer_type_code, deleted, updated_time),
    KEY idx_erp_customer_type_price_product (tenant_id, product_id, deleted, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP客户类型等级价';

create table flyway_schema_history
(
    installed_rank int                                 not null
        primary key,
    version        varchar(50)                         null,
    description    varchar(200)                        not null,
    type           varchar(20)                         not null,
    script         varchar(1000)                       not null,
    checksum       int                                 null,
    installed_by   varchar(100)                        not null,
    installed_on   timestamp default CURRENT_TIMESTAMP not null,
    execution_time int                                 not null,
    success        tinyint(1)                          not null
)
    default character set utf8mb4 collate utf8mb4_general_ci;

create index flyway_schema_history_s_idx
    on flyway_schema_history (success);
