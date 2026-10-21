create table data_dictionary
(
    id              bigint auto_increment comment 'ID'
        primary key,
    dictionary_code varchar(50)                        not null comment '字典代码',
    dictionary_name varchar(100)                       not null comment '字典名称',
    dictionary_type varchar(50)                        not null comment '字典类型：COMMON/ERP/CRM/ORDER',
    remark          varchar(500)                       null comment '备注',
    revision        int      default 1                 not null comment '乐观锁',
    created_by      varchar(50)                        not null comment '创建人',
    created_time    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_by      varchar(50)                        null comment '更新人',
    updated_time    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted         int      default 0                 not null comment '删除标识',
    constraint uk_data_dictionary_code
        unique (dictionary_code)
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment '字典主表';

create index idx_data_dictionary_type
    on data_dictionary (dictionary_type, deleted, updated_time);

create table data_dictionary_item
(
    id                          bigint auto_increment comment 'ID'
        primary key,
    dictionary_code             varchar(50)                        not null comment '字典代码',
    dictionary_item_level       int      default 1                 not null comment '字典条目层级',
    parent_dictionary_item_code varchar(50)                        null comment '父级字典条目代码',
    dictionary_item_code        varchar(50)                        not null comment '字典条目代码',
    dictionary_item_name        varchar(100)                       not null comment '字典条目名称',
    remark                      varchar(500)                       null comment '备注',
    ordinal                     int      default 0                 not null comment '序号',
    revision                    int      default 1                 not null comment '乐观锁',
    created_by                  varchar(50)                        not null comment '创建人',
    created_time                datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_by                  varchar(50)                        null comment '更新人',
    updated_time                datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    deleted                     int      default 0                 not null comment '删除标识',
    canonical_dictionary_code   varchar(50)                        null comment '历史兼容项的标准字典',
    canonical_item_code         varchar(50)                        null comment '历史兼容项的标准编码',
    standard_parent_key         varchar(50) as (coalesce(`parent_dictionary_item_code`, _utf8mb4'')) stored,
    standard_name_key           varchar(100) as ((case
                                                      when ((`deleted` = 0) and (`canonical_item_code` is null))
                                                          then trim(`dictionary_item_name`)
                                                      else NULL end)) stored,
    constraint uk_data_dictionary_item_code
        unique (dictionary_code, dictionary_item_code),
    constraint uk_dictionary_standard_name
        unique (dictionary_code, standard_parent_key, standard_name_key),
    constraint fk_data_dictionary_item_dictionary
        foreign key (dictionary_code) references data_dictionary (dictionary_code),
    constraint fk_dictionary_canonical_item
        foreign key (canonical_dictionary_code, canonical_item_code) references data_dictionary_item (dictionary_code, dictionary_item_code),
    constraint ck_dictionary_canonical_pair
        check ((`canonical_dictionary_code` is null) = (`canonical_item_code` is null))
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment '字典项';

create index idx_data_dictionary_item_deleted
    on data_dictionary_item (dictionary_code, deleted, ordinal);

create index idx_data_dictionary_item_tree
    on data_dictionary_item (dictionary_code, parent_dictionary_item_code, dictionary_item_level, ordinal);

create table data_dictionary_merge_log
(
    id               bigint auto_increment
        primary key,
    tenant_id        char(36)                                 not null comment '操作租户审计上下文',
    dictionary_code  varchar(50)                              not null,
    source_item_code varchar(50)                              not null,
    target_item_code varchar(50)                              not null,
    source_revision  int                                      not null,
    target_revision  int                                      not null,
    reason           varchar(500)                             not null,
    created_by       varchar(50)                              not null,
    created_time     datetime(6) default CURRENT_TIMESTAMP(6) not null
)
    default character set utf8mb4 collate utf8mb4_general_ci
    comment '字典合并审计';

create index idx_dictionary_merge_tenant
    on data_dictionary_merge_log (tenant_id, dictionary_code, created_time);

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
