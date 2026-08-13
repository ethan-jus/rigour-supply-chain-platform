# ERP 商品主数据数据库设计

## 1. 设计结论

本设计覆盖第一阶段从订货宝同步商品/SPU、SKU、分类、品牌、规格/规格值和标签，
并由 ERP 提供本地分页查询。实现采用第 9～11 节的“规范主数据 + 外部来源绑定”方案，
第 3 节单表方案仅保留为历史评审对照，未被 Flyway 采用。

数据责任边界如下：

```text
订货宝 getGoodsList / getSite / getBrands / getMultiOptionsList / getGoodsTag
        ↓
Integration：协议、Token、Secret、Raw Landing、重试、限流、字段归一化
        ↓ 版本化 ProductView
ERP：主数据模型、幂等导入、内部状态、同步批次、本地查询
        ↓
Portal：只查询 ERP 本地列表，通过单一 ERP 同步接口传对象类型
```

ERP 不保存订货宝账号、密码、Token、API Key 或连接器 Secret。
Integration 的 Raw Landing 继续保存协议层原始报文；ERP 同时保存商品/SPU、SKU 的完整
业务来源字段到商品实体的 `attributes_json`，并将常用字段结构化到商品业务表，保证 ERP
脱离原始接口也能追溯和展示订货宝商品数据。

## 2. 当前第一阶段范围与建表策略

当前 Integration 已向 ERP 提供以下已归一化对象：

| Integration 字段 | ERP 含义 |
|---|---|
| `sourceId` | 订货宝商品来源主键；为空时由 Integration/ERP 回退使用 `code` |
| `code` | 商品/SPU业务编码，来自订货宝 `coding` |
| `name` | 商品名称，来自订货宝 `name` |
| `putaway` | 订货宝来源上下架值，原样保留 |
| `skus` | `getGoodsList.multi` 的可销售规格组合，包含 SKU 编码、条码和规格值 ID |
| `category/brand` | `getSite/getBrands` 归一化后的分类、品牌 |
| `specification/values` | `getMultiOptionsList` 归一化后的规格维度和规格值 |
| `tag` | `getGoodsTag` 标签；当前字段别名是兼容映射，需真实账号回执验证 |
| `sourceFields` | Integration Raw Landing 保存协议原文；ERP 商品/SPU、SKU 保存完整业务字段到 `attributes_json` |

## 3. 历史对照：单表最小方案（未采用）

该表是 ERP 商品/SPU 本地业务模型，ERP 是唯一写者。下面的字段包含来源字段，是“只做订货宝商品
列表”的最小落地方案；如果采用本文件第 9 节最终方案，应使用 SQL 草案中的规范 SPU 表，并把来源
字段放入 `erp_master_source_binding`，不要同时落两套商品主表。

### 3.1 DDL

```sql
CREATE TABLE erp_product_spu (
    id                   CHAR(36)      NOT NULL,
    tenant_id             VARCHAR(64)   NOT NULL,
    source_system         VARCHAR(32)   NOT NULL,
    source_product_id     VARCHAR(128)  NOT NULL,
    spu_code              VARCHAR(128)  NULL,
    product_name          VARCHAR(200)  NOT NULL,
    barcode               VARCHAR(160)  NULL,
    base_unit             VARCHAR(40)   NULL,
    source_putaway        VARCHAR(16)   NULL,
    internal_status       VARCHAR(24)   NOT NULL,
    source_updated_at     DATETIME(6)   NULL,
    source_payload_hash   CHAR(64)      NOT NULL,
    last_sync_run_id      CHAR(36)      NULL,
    synced_at             DATETIME(6)   NOT NULL,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_source
        UNIQUE (tenant_id, source_system, source_product_id),
    CONSTRAINT ck_erp_product_spu_status
        CHECK (internal_status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    KEY idx_erp_product_spu_code (tenant_id, spu_code),
    KEY idx_erp_product_spu_name (tenant_id, product_name),
    KEY idx_erp_product_spu_status (tenant_id, internal_status, updated_at),
    KEY idx_erp_product_spu_putaway (tenant_id, source_putaway),
    KEY idx_erp_product_spu_sync (tenant_id, synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP 商品/SPU 本地业务模型';
```

### 3.2 字段说明

| 字段 | 类型 | 说明 | 写入规则 |
|---|---|---|---|
| `id` | `CHAR(36)` | ERP 内部商品/SPU主键 | ERP 生成 UUID，不使用订货宝 ID 作为内部主键 |
| `tenant_id` | `VARCHAR(64)` | 租户隔离键 | 只取 Gateway 签名上下文，禁止从请求体读取 |
| `source_system` | `VARCHAR(32)` | 来源系统编码 | 第一阶段固定为 `DINGHUOBAO` |
| `source_product_id` | `VARCHAR(128)` | 订货宝商品来源主键 | 由 `sourceId` 提供；为空时回退 `code`；不能为空 |
| `spu_code` | `VARCHAR(128)` | 商品/SPU业务编码 | 当前来自 `getGoodsList.coding`；不作为幂等键 |
| `product_name` | `VARCHAR(200)` | 商品名称 | 首次导入必填；来源为空时拒绝该行并计入 rejected |
| `barcode` | `VARCHAR(160)` | 条码 | 仅在 Integration 已归一化并提供时写入 |
| `base_unit` | `VARCHAR(40)` | 基础单位 | 仅在 Integration 已归一化并提供时写入 |
| `source_putaway` | `VARCHAR(16)` | 订货宝上下架原值 | 原样保留，例如 `T`/`F`，不当作 ERP 内部状态 |
| `internal_status` | `VARCHAR(24)` | ERP 内部商品状态 | 首次按来源上下架初始化；后续同步不得覆盖 ERP 已维护状态 |
| `source_updated_at` | `DATETIME(6)` | 来源更新时间 | 来源接口明确提供时写入，未确认时允许为空 |
| `source_payload_hash` | `CHAR(64)` | 归一化来源字段摘要 | 用于判断新增、变化和重复；不包含凭据 |
| `last_sync_run_id` | `CHAR(36)` | 最近一次导入批次 | 只作追踪，不跨库建立 Integration 外键 |
| `synced_at` | `DATETIME(6)` | 最近一次成功处理时间 | 每次成功处理都更新 |
| `version` | `BIGINT` | ERP 乐观锁版本 | 业务字段变化时递增，支持后续人工维护接口 |
| `created_at` | `DATETIME(6)` | ERP 记录创建时间 | ERP 首次插入时写入 |
| `updated_at` | `DATETIME(6)` | ERP 记录更新时间 | ERP 任何本地变更时写入 |

### 3.3 内部状态规则

| 场景 | `internal_status` |
|---|---|
| 首次导入且来源 `putaway=T` | `ACTIVE` |
| 首次导入且来源为 `F` 或空值 | `INACTIVE` |
| 已存在商品再次同步 | 保留 ERP 当前状态，不被订货宝覆盖 |
| ERP 后续人工归档 | `ARCHIVED`；普通同步不能自动恢复 |

这里必须区分“来源状态”和“内部状态”：订货宝只能提供来源事实，ERP 负责商品业务状态。

## 4. 同步批次表：`erp_master_data_sync_run`

列表页的“同步”按钮不是一次没有记录的远程调用，应保留批次和统计，便于排查分页中断、重复导入
和真实账号异常。

```sql
CREATE TABLE erp_master_data_sync_run (
    id                   CHAR(36)      NOT NULL,
    tenant_id             VARCHAR(64)   NOT NULL,
    connector_id          CHAR(36)      NOT NULL,
    source_system         VARCHAR(32)   NOT NULL,
    object_type           VARCHAR(32)   NOT NULL DEFAULT 'PRODUCT',
    trigger_type          VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',
    status                VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    window_from           DATETIME(6)   NULL,
    window_to             DATETIME(6)   NULL,
    max_pages             INT UNSIGNED  NOT NULL DEFAULT 100,
    page_size             INT UNSIGNED  NOT NULL DEFAULT 100,
    fetched_count         BIGINT        NOT NULL DEFAULT 0,
    created_count         BIGINT        NOT NULL DEFAULT 0,
    changed_count         BIGINT        NOT NULL DEFAULT 0,
    duplicate_count       BIGINT        NOT NULL DEFAULT 0,
    rejected_count        BIGINT        NOT NULL DEFAULT 0,
    error_code            VARCHAR(64)   NULL,
    error_message         VARCHAR(2000) NULL,
    started_at             DATETIME(6)   NOT NULL,
    finished_at            DATETIME(6)   NULL,
    created_by             CHAR(36)      NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_erp_master_data_sync_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_erp_master_data_sync_run_trigger
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'RETRY')),
    KEY idx_erp_master_data_sync_run_tenant_time (tenant_id, started_at),
    KEY idx_erp_master_data_sync_run_status (tenant_id, status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP 商品主数据同步批次';
```

| 字段 | 说明 |
|---|---|
| `connector_id` | Integration 返回的目标连接器 ID；ERP 只保存引用，不读取 Integration 表 |
| `trigger_type` | 第一阶段为 `MANUAL`，对应 Portal 列表页同步按钮 |
| `status` | 批次状态；只有完整分页和本地落库完成才是 `SUCCEEDED` |
| `window_from/window_to` | 给后续增量同步使用；手动首次全量可为空 |
| `max_pages/page_size` | 防止一次按钮操作无限拉取供应商数据 |
| `fetched_count` | Integration 返回的商品行数 |
| `created_count` | ERP 首次创建的商品数 |
| `changed_count` | 来源摘要变化并更新的商品数 |
| `duplicate_count` | 来源摘要未变化的重复行数 |
| `rejected_count` | 缺少来源主键或商品名称等必要字段而拒绝的行数 |
| `error_code/error_message` | 稳定错误码和脱敏错误信息，不允许写入 Token、密码或完整原始报文 |
| `created_by` | 用户触发时记录用户 ID；服务定时触发时为空 |

## 5. 增量游标表：`erp_master_data_sync_checkpoint`

这张表是后续自动同步的准备项。若当前只实现“手动全量同步”，可以先不启用调度，但建议随第一版
迁移创建，避免后续重新设计同步状态。

```sql
CREATE TABLE erp_master_data_sync_checkpoint (
    id                   CHAR(36)      NOT NULL,
    tenant_id             VARCHAR(64)   NOT NULL,
    connector_id          CHAR(36)      NOT NULL,
    source_system         VARCHAR(32)   NOT NULL,
    object_type           VARCHAR(32)   NOT NULL DEFAULT 'PRODUCT',
    cursor_type           VARCHAR(24)   NOT NULL DEFAULT 'TIME_WINDOW',
    cursor_value          VARCHAR(1024) NULL,
    source_updated_at     DATETIME(6)   NULL,
    last_success_run_id   CHAR(36)      NULL,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_data_sync_checkpoint
        UNIQUE (tenant_id, connector_id, source_system, object_type),
    CONSTRAINT ck_erp_master_data_sync_checkpoint_cursor
        CHECK (cursor_type IN ('TIME_WINDOW', 'PAGE_TOKEN', 'SOURCE_VERSION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP 商品主数据增量同步游标';
```

游标只能在以下条件同时满足时推进：

1. Integration 所有分页请求成功；
2. ERP 所有商品行完成校验和幂等落库；
3. 同步批次最终状态为 `SUCCEEDED`。

## 6. 幂等与并发规则

1. 幂等键：`tenant_id + source_system + source_product_id`。
2. `spu_code` 不是幂等键，避免订货宝改编码或不同商品编码重复导致误合并。
3. `source_payload_hash` 未变化时计为 duplicate，不递增业务版本。
4. 来源字段变化时只更新来源字段、摘要和 `synced_at`；不覆盖 `internal_status`。
5. 同一租户同一商品的导入应在 ERP 事务内执行；后续可用唯一键和版本号处理并发同步。
6. `connector_id` 是跨服务引用，不在 ERP 建立 Integration 数据库外键。
7. ERP 不建立 `erp_raw_landing`，避免重复持有订货宝原始报文。

## 7. SKU 实现边界

`erp_product_sku` 已创建并由 `getGoodsList.multi` 随 SPU 同步。订货宝文档中的
`options_goods_num` 映射 SKU 编码，`options_id` 优先作为来源 SKU ID，
`multiFirst/multiSecond` 映射规格值来源 ID。规格值字典仍由 `getMultiOptionsList` 单独同步。

订货宝 V1.1 文档没有独立 SKU 列表接口，因此 Portal 的 SKU 页查询 ERP 本地
`erp_product_sku`，点击同步时使用 `PRODUCT_SPU`，同一次 `getGoodsList` 刷新 SPU 和 SKU。

## 8. 面向后期自研 ERP 的推荐调整

上一节的 `erp_product_spu` 是“最快接通订货宝”的最小方案，但不建议作为 ERP 的最终模型。
如果后期要由 ERP 自己创建商品、维护 SKU、品牌、分类、规格和上下架，推荐现在就把“业务主数据”和
“外部来源身份”拆开：

```text
erp_product_spu                 ERP规范SPU，ERP自己拥有主数据主权
        └── erp_product_sku     ERP规范SKU，后续支持规格组合和库存单位

erp_master_source_binding       外部来源绑定：订货宝只是其中一种来源
        erp_master_data_sync_run            同步批次
        erp_master_data_sync_checkpoint     来源同步游标
```

### 8.1 推荐的职责拆分

| 表 | 责任 | 是否允许订货宝同步直接覆盖 |
|---|---|---|
| `erp_product_spu` | SPU名称、内部编码、品牌、分类、内部状态等 ERP 规范字段 | 否；只按明确字段映射更新来源建议值 |
| `erp_product_sku` | SKU编码、条码、规格、单位、启用状态 | 否；仅当 `ownership_state=EXTERNAL_PRIMARY` 时更新允许的来源字段 |
| `erp_master_source_binding` | `DINGHUOBAO + source_object_id` 与 ERP 主数据对象的绑定、来源状态、来源摘要 | 是，只覆盖来源侧字段 |
| `erp_master_data_sync_run` | 手动/定时同步批次及统计 | 由 ERP 同步用例写入 |
| `erp_master_data_sync_checkpoint` | 每租户、每连接器、每对象的增量游标 | 只在批次成功后推进 |

### 8.2 `erp_master_source_binding` 建议字段

完整字段和约束见第 10 节，以及配套的
[`ERP_PRODUCT_MASTER_DATA_SCHEMA.sql`](./ERP_PRODUCT_MASTER_DATA_SCHEMA.sql)。这里不再维护一份
仅支持 SPU/SKU 的重复 DDL，避免后续分类、品牌、规格和标签接入时出现两个来源绑定模型。

这个表的关键点是：

1. 幂等键变为 `tenant_id + source_system + source_object_type + source_object_id`，不再把订货宝 ID
   当成 ERP 主键。
2. `source_system` 可以扩展为 `DINGHUOBAO`、`SELF_BUILT`、其他 ERP 或供应商系统。
3. 自研商品可以只有 ERP 主数据，没有来源绑定；订货宝导入商品则通过绑定表关联到 ERP SPU。
4. `source_putaway` 只表示订货宝事实，ERP 的 `internal_status` 仍在规范商品表中维护。
5. `target_id` 不跨服务建外键；它是统一来源绑定表中的多态目标，ERP 应用事务必须校验目标类型和记录存在。

### 8.3 推荐的接口边界调整

当前 `integration-migration-api` 已输出归一化商品导入模型，核心字段如下：

```text
ProductImportView
  sourceObjectId
  sourceObjectType       PRODUCT / SKU
  sourceCode
  sourceName
  sourcePutaway
  spuCode
  skuCode
  barcode
  unit
  specifications
  sourceUpdatedAt
  sourcePayloadHash
```

Integration 负责把订货宝字段映射到这个模型；ERP 负责把模型导入自己的 SPU/SKU 和来源绑定。
这样未来接入自研商品或另一套 ERP 时，只新增 Integration Adapter 和映射，不修改 ERP 核心商品模型。

### 8.4 我的建议

- 如果目标只是近期展示订货宝商品：使用第 3 节单表方案，交付快，但后续需要迁移。
- 如果确认 ERP 会继续自研：直接采用本节“规范模型 + 来源绑定”方案，即使第一版多两张表，也能避免
  把订货宝字段和内部商品事实耦合在一起。
- 不建议使用万能 EAV 表承载所有商品字段。可检索、排序、参与业务规则的字段应使用类型明确的列；
  暂未确认的扩展字段可以阶段性放 JSON，但不能替代 SPU、SKU、状态和来源绑定模型。

## 9. 商品主数据全量模型：分类、品牌、规格、标签

如果分类、品牌、规格和标签都会先从订货宝导入，再逐步转为 ERP 自研，推荐最终采用下面的表族。
这套模型把“可被业务查询和维护的 ERP 主数据”与“外部系统来源事实”分开。

### 9.1 表清单

| 表 | 作用 | 第一阶段是否建议创建 |
|---|---|---:|
| `erp_product_spu` | ERP 规范 SPU 主表 | 是 |
| `erp_product_sku` | ERP 规范 SKU 主表 | 是，字段允许逐步补齐 |
| `erp_category` | 商品分类树 | 是 |
| `erp_brand` | 品牌主表 | 是 |
| `erp_specification` | 规格维度，例如颜色、容量 | 是 |
| `erp_specification_value` | 规格值，例如红色、500ml | 是 |
| `erp_product_spu_category` | SPU 与分类关系，支持多分类和主分类 | 是 |
| `erp_product_spu_specification` | SPU 可用规格维度关系 | 是 |
| `erp_product_sku_specification_value` | SKU 与规格值关系 | 是 |
| `erp_tag` | 标签主表 | 是；来源不存在时也可由 ERP 自建 |
| `erp_product_spu_tag` | SPU 与标签关系 | 是 |
| `erp_master_source_binding` | 订货宝等外部来源与 ERP 对象绑定 | 是 |
| `erp_master_data_sync_run` | 商品主数据同步批次 | 是 |
| `erp_master_data_sync_checkpoint` | 商品主数据同步游标 | 建议创建 |

### 9.2 规范主数据表字段

以下字段是 ERP 自己的字段，不应直接使用订货宝字段名。

#### `erp_product_spu`

| 字段 | 说明 |
|---|---|
| `id` | ERP 内部 SPU UUID |
| `tenant_id` | 租户隔离键 |
| `spu_code` | ERP 内部 SPU 编码；租户内唯一 |
| `name` | ERP 商品名称 |
| `brand_id` | ERP 品牌 ID，可为空 |
| `base_unit` | ERP 基础单位 |
| `ownership_state` | `EXTERNAL_PRIMARY`、`SHADOW_VALIDATING`、`INTERNAL_PRIMARY_SYNC_BACK`、`INTERNAL_ONLY` |
| `internal_status` | `DRAFT`、`ACTIVE`、`INACTIVE`、`ARCHIVED` |
| `record_origin` | `SELF_BUILT`、`IMPORTED`、`MIXED` |
| `version` | 乐观锁版本 |
| `created_by/updated_by` | ERP 用户或服务身份 |
| `created_at/updated_at` | 审计时间 |

推荐唯一约束：`UNIQUE (tenant_id, spu_code)`。

#### `erp_product_sku`

| 字段 | 说明 |
|---|---|
| `id` | ERP 内部 SKU UUID |
| `tenant_id` | 租户隔离键 |
| `spu_id` | 所属 ERP SPU |
| `sku_code` | ERP 内部 SKU 编码；租户内唯一 |
| `barcode` | 条码，可为空；不能假设一定唯一 |
| `unit` | SKU 销售/库存单位 |
| `specification_summary` | 展示用规格组合名称，例如“红色,L”；不能替代规格关系表 |
| `ownership_state` | SKU 的数据主权状态 |
| `internal_status` | SKU 内部状态 |
| `record_origin` | 自建、导入或混合 |
| `version/created_at/updated_at` | 版本和审计字段 |

推荐唯一约束：`UNIQUE (tenant_id, sku_code)`；SPU 删除前必须处理其 SKU。

#### `erp_category`

| 字段 | 说明 |
|---|---|
| `id` | ERP 分类 UUID |
| `tenant_id` | 租户隔离键 |
| `parent_id` | 父分类；根分类为空 |
| `category_code` | ERP 分类编码 |
| `name` | 分类名称 |
| `level` | 冗余层级，便于查询；由 ERP 维护 |
| `sort_order` | 同级排序 |
| `status` | `ACTIVE`、`INACTIVE`、`ARCHIVED` |
| `ownership_state/record_origin` | 主权状态和来源 |
| `version/created_at/updated_at` | 版本和审计字段 |

推荐唯一约束：`UNIQUE (tenant_id, category_code)`。分类关系建议使用 `parent_id` 形成树，禁止通过
订货宝来源 ID 作为 ERP 分类父子关系的唯一依据。

#### `erp_brand`

| 字段 | 说明 |
|---|---|
| `id` | ERP 品牌 UUID |
| `tenant_id` | 租户隔离键 |
| `brand_code` | ERP 品牌编码 |
| `name` | 品牌名称 |
| `english_name` | 英文名称，可为空 |
| `logo_object_key` | 我方 COS 私桶对象 key；接口按请求生成短时 URL，不保存订货宝或永久公开地址 |
| `status` | 品牌内部状态 |
| `ownership_state/record_origin` | 主权状态和来源 |
| `version/created_at/updated_at` | 版本和审计字段 |

推荐唯一约束：`UNIQUE (tenant_id, brand_code)`；名称是否唯一不在数据库强制，避免历史脏数据阻塞导入。

#### `erp_specification`

| 字段 | 说明 |
|---|---|
| `id` | 规格维度 UUID，例如“颜色”“容量” |
| `tenant_id` | 租户隔离键 |
| `specification_code` | ERP 规格维度编码 |
| `name` | 规格维度名称 |
| `status` | 规格维度状态 |
| `ownership_state/record_origin` | 主权状态和来源 |
| `version/created_at/updated_at` | 版本和审计字段 |

#### `erp_specification_value`

| 字段 | 说明 |
|---|---|
| `id` | 规格值 UUID |
| `tenant_id` | 租户隔离键 |
| `specification_id` | 所属规格维度 |
| `value_code` | ERP 规格值编码 |
| `value_name` | 规格值名称，例如“红色” |
| `sort_order` | 展示排序 |
| `status` | 规格值状态 |
| `ownership_state/record_origin` | 主权状态和来源 |
| `version/created_at/updated_at` | 版本和审计字段 |

推荐唯一约束：`UNIQUE (tenant_id, specification_id, value_code)`。

### 9.3 关系表字段

#### `erp_product_spu_category`

```text
id              关系 UUID
tenant_id       租户隔离键
spu_id          ERP SPU ID
category_id     ERP 分类 ID
is_primary      是否主分类
sort_order      多分类展示顺序
created_at      创建时间
updated_at      更新时间
```

唯一约束：`UNIQUE (tenant_id, spu_id, category_id)`。是否只能存在一个 `is_primary=1` 由 ERP 应用层
事务保证；不要仅依赖 MySQL 对 NULL/布尔值的唯一索引行为。

#### `erp_product_spu_specification`

```text
id              关系 UUID
tenant_id       租户隔离键
spu_id          ERP SPU ID
specification_id ERP 规格维度 ID
sort_order      规格维度排序
created_at      创建时间
updated_at      更新时间
```

唯一约束：`UNIQUE (tenant_id, spu_id, specification_id)`。

#### `erp_product_sku_specification_value`

```text
id              关系 UUID
tenant_id       租户隔离键
sku_id          ERP SKU ID
specification_id ERP 规格维度 ID
value_id        ERP 规格值 ID
sort_order      展示顺序
created_at      创建时间
updated_at      更新时间
```

推荐同时建立：

```text
UNIQUE (tenant_id, sku_id, specification_id)
UNIQUE (tenant_id, sku_id, value_id)
```

这样可以保证一个 SKU 在同一个规格维度下只有一个值，例如不能同时有“颜色=红”和“颜色=蓝”。

#### `erp_tag`

```text
id              标签 UUID
tenant_id       租户隔离键
tag_code        ERP 标签编码
name            标签名称
color            展示颜色，可为空
status          ACTIVE / INACTIVE / ARCHIVED
ownership_state 主权状态
record_origin   SELF_BUILT / IMPORTED / MIXED
version         乐观锁版本
created_at      创建时间
updated_at      更新时间
```

唯一约束：`UNIQUE (tenant_id, tag_code)`。标签名称不强制唯一，允许不同业务分类使用同名标签。

#### `erp_product_spu_tag`

```text
id              关系 UUID
tenant_id       租户隔离键
spu_id          ERP SPU ID
tag_id          ERP 标签 ID
assigned_by     维护人；同步导入时为空或使用服务身份
created_at      创建时间
updated_at      更新时间
```

唯一约束：`UNIQUE (tenant_id, spu_id, tag_id)`。第一阶段建议标签挂在 SPU，而不是直接挂在订货宝
商品来源记录上；以后确实需要 SKU 级标签时，再增加 `erp_product_sku_tag`，不要把关系表设计成
无法校验的多态大表。

## 10. 外部来源绑定表

前述 SPU、SKU、分类、品牌、规格、规格值、标签都通过统一的
`erp_master_source_binding` 绑定外部来源。推荐字段如下：

| 字段 | 说明 |
|---|---|
| `id` | ERP 绑定记录 UUID |
| `tenant_id` | 租户隔离键 |
| `source_system` | `DINGHUOBAO`、`SELF_BUILT` 或其他来源 |
| `source_object_type` | `PRODUCT_SPU`、`PRODUCT_SKU`、`CATEGORY`、`BRAND`、`SPECIFICATION`、`SPECIFICATION_VALUE`、`TAG` |
| `source_object_id` | 外部对象 ID，不能为空 |
| `target_type` | `SPU`、`SKU`、`CATEGORY`、`BRAND`、`SPECIFICATION`、`SPECIFICATION_VALUE`、`TAG` |
| `target_id` | 对应 ERP 对象 ID；因为 `target_type` 可变，不建立数据库多态外键 |
| `source_code/source_name` | 来源编码和名称快照 |
| `source_status/source_putaway` | 来源状态原值 |
| `source_updated_at` | 来源更新时间 |
| `source_payload_hash` | 来源规范字段摘要，用于幂等判断 |
| `last_sync_run_id` | 最近同步批次 ID |
| `synced_at` | 最近成功处理时间 |
| `version/created_at/updated_at` | 版本和审计字段 |

核心唯一约束：

```text
UNIQUE (tenant_id, source_system, source_object_type, source_object_id)
UNIQUE (tenant_id, source_system, target_type, target_id)
```

第二个唯一约束表示同一来源系统的一个 ERP 对象只能有一个主绑定；如果未来允许同一来源对象映射
多个 ERP 对象，则需要改成显式的关系表，不能悄悄放宽唯一键。

多态绑定没有数据库外键是有意的：它支持多个 ERP 主数据对象共用一套来源协议。ERP 应用层必须在
同一事务中校验 `target_type` 与目标表记录存在，并为每个类型编写导入测试；如果团队更重视数据库
级别的强外键约束，也可以拆成 `erp_category_source_binding`、`erp_brand_source_binding` 等
多个专用绑定表，代价是表和代码重复增加。

## 11. 订货宝接口、同步参数和限制

| 同步参数 | Integration 订货宝函数 | ERP 落库对象 | 已知限制 |
|---|---|---|---|
| `PRODUCT_SPU` | `getGoodsList` | SPU + `multi` 中的 SKU + 来源绑定 | 只使用文档已确认的 `status/putaway/goodsCode`；一期手动全量 |
| `CATEGORY` | `getSite` | 分类 + 来源绑定 | 返回不含父 ID，不能伪造分类树；首期按平铺分类落库 |
| `BRAND` | `getBrands` | 品牌 + 来源绑定 | 全量接口 |
| `SPECIFICATION` | `getMultiOptionsList` | 规格、规格值、来源绑定 | 最大每页 1000；商品 SKU 关系在来源规格值已绑定时建立 |
| `TAG` | `getGoodsTag` | 标签 + 来源绑定 | 由项目需求指定；本地 V1.1 文档未给字段表，适配器保留容错别名和 Raw Landing |

Portal 只调用一个 ERP 同步入口：

```text
POST /api/v1/erp/sync
body: { "objectType": "PRODUCT_SPU|CATEGORY|BRAND|SPECIFICATION|TAG", "maxPages": 100 }
```

查询入口只读 ERP 本地表：

```text
GET /api/v1/erp/products
GET /api/v1/erp/skus
GET /api/v1/erp/categories
GET /api/v1/erp/brands
GET /api/v1/erp/specifications
GET /api/v1/erp/tags
```

一期没有开放订货宝商品增量时间窗口，因为当前 V1.1 `getGoodsList` 参数表未列出
更新时间筛选。后续只有在订货宝正式文档和真实账号同时验证后，才启用
`erp_master_data_sync_checkpoint`。

当 `ownership_state` 从 `EXTERNAL_PRIMARY` 切换为内部主数据后，同步只更新来源绑定和差异信息，
不得覆盖 ERP 人工维护的名称、内部状态和业务关系。
