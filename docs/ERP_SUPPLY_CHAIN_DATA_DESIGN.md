# ERP 供应链数据一期设计

## 1. 范围与边界

一期实现供应商、采购单、采购退货单、入库单、仓库和库存的订货宝同步落库与 ERP 本地分页查询。页面链路固定为：

`Portal -> Gateway -> ERP Core -> Integration -> 订货宝`

- Portal 只访问 ERP Core，不持有订货宝 Connector、Token 或 Secret。
- ERP Core 是规范业务表的唯一写者，负责内部状态、幂等落库、本地查询和同步批次。
- Integration 独占订货宝协议、鉴权、Raw Landing、重试、限流和字段归一化。
- 原始响应完整保存在 Integration Raw Landing；ERP 不复制接口日志和操作日志。
- 所有规范表必须携带 `tenant_id`，所有唯一键必须包含租户边界。

## 2. 已核对的订货宝接口

| ERP 对象 | 订货宝接口 | 同步策略 |
|---|---|---|
| 供应商 | [`getSupplierList`](https://docs.dhb168.com/books/erp/page/getsupplierlist) | 按 `begin/step` 分页，单页最多 1000 |
| 采购单 | [`getPurchaseList`](https://docs.dhb168.com/books/erp/page/getpurchaselist) + [`getPurchaseContent`](https://docs.dhb168.com/books/erp/page/getpurchasecontent) | 列表分页后按采购单号逐单获取完整明细 |
| 采购退货 | [`getPurchaseReturnList`](https://docs.dhb168.com/books/erp/page/getpurchasereturnlist) + [`getPurchaseReturnContent`](https://docs.dhb168.com/books/erp/page/getpurchasereturncontent) | 列表分页后按退货单号逐单获取完整明细；详情接口依官方文档传 `purchase_num` |
| 入库单 | [`getWarehousingList`](https://docs.dhb168.com/books/erp/page/getwarehousinglist) + [`getWarehousingContent`](https://docs.dhb168.com/books/erp/page/getwarehousingcontent) | 列表分页后按入库单号逐单获取完整明细 |
| 仓库 | [`getStockInfo`](https://docs.dhb168.com/books/erp/page/getstockinfo) | 按 `page/page_size` 分页，单页最多 1000 |
| 库存 | [`batchGetStock`](https://docs.dhb168.com/books/erp/page/batchgetstock) | 不是全量分页接口；以 ERP 已落库的订货宝商品编码分批查询 |

官方文档入口：<https://docs.dhb168.com/books/erp>。

## 3. 通用字段约定

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `CHAR(36)` | ERP 内部 UUID，后续自研继续使用，不以外部 ID 作为主键 |
| `tenant_id` | `VARCHAR(64)` | 租户隔离键 |
| `source_*` | 见各表 | 订货宝来源标识或来源状态，仅用于同步、追溯和快照 |
| `internal_status` | `VARCHAR(24)` | ERP 内部状态；同步不得覆盖未来由 ERP 自研维护的状态 |
| `ownership_state` | `VARCHAR(32)` | `EXTERNAL_PRIMARY` 表示一期以订货宝为主；后续可切换 `INTERNAL_PRIMARY` |
| `record_origin` | `VARCHAR(16)` | `IMPORTED`、`MANUAL` 等记录来源 |
| `source_synced_at` | `DATETIME(6)` | 本次从订货宝同步完成时间 |
| `attributes_json` | `JSON` | 已归一化但尚未提升为业务列的扩展字段，不替代核心查询列 |
| `version` | `BIGINT` | MyBatis-Plus 乐观锁版本 |
| `created_at/updated_at` | `DATETIME(6)` | ERP 记录创建、更新时间 |

## 4. 表设计

### 4.1 `erp_supplier` 供应商档案

| 字段组 | 关键字段 | 说明 |
|---|---|---|
| 身份 | `supplier_code`, `name`, `source_supplier_id`, `source_supplier_guid` | 内部编码唯一；保留订货宝 ID/Guid |
| 地址联系人 | `area_name`, `address`, `contact_name` | 当前内部使用场景同步并展示完整地址 |
| 联系方式 | `mobile`, `phone`, `email` | 当前内部使用场景同步并展示完整联系方式 |
| 财务信息 | `account_name`, `bank_name`, `bank_account`, `invoice_title`, `taxpayer_number` | 当前内部使用场景同步并展示完整银行账号、纳税人识别号 |
| 状态与扩展 | `internal_status`, `ownership_state`, `attributes_json`, `version` | 为后续 ERP 自研预留内部控制面 |

幂等键：`(tenant_id, supplier_code)`、`(tenant_id, source_supplier_id)`。

### 4.2 `erp_warehouse` 仓库档案

主要字段：`warehouse_code`、`name`、`source_warehouse_id/guid`、`source_status`、`source_default_flag`、`acreage`、`phone_masked`、`address`、`collaborator_source_id`、`remark`。
幂等键：`(tenant_id, warehouse_code)`、`(tenant_id, source_warehouse_id)`。

### 4.3 `erp_purchase_order` / `erp_purchase_order_line` 采购单

采购单头保存单号、供应商/仓库内部外键、来源快照、来源状态、ERP 内部状态、交货日期、金额、已付金额、商品数量和下载状态。供应商或仓库尚未同步时允许内部外键为空，名称与编码快照仍可展示。

采购明细保存：

- 订货宝商品、规格组合及明细 ID；
- 可解析时关联 `spu_id/sku_id`，无法解析时保留来源快照；
- 基础数量、采购单位数量、单价、已入库数量、已退货数量；
- `attributes_json` 保留订货宝新增但未建模字段。

幂等键：采购单 `(tenant_id, purchase_order_no)`；明细 `(tenant_id, purchase_order_id, source_line_id)`。

### 4.4 `erp_purchase_return` / `erp_purchase_return_line` 采购退货

主表保存退货单号、供应商/仓库、状态、退货金额、折扣、原因、发送日期和脱敏联系人信息。明细保存申请/确认数量、退货/确认价格、单位换算、成本、采购单引用及商品快照。

接口返回的操作日志不写 ERP 业务表，由 Integration Raw Landing 保存，避免把供应商协议日志耦合进未来自研模型。

幂等键：退货单 `(tenant_id, purchase_return_no)`；明细 `(tenant_id, purchase_return_id, source_line_id)`。

### 4.5 `erp_warehousing_receipt` / `erp_warehousing_receipt_line` 入库单

主表保存入库单号、仓库/供应商、入库类型、来源状态、ERP 内部状态、经办人、协作方、物流、入库日期、运费及成本。明细保存商品、规格组合、数量、单位换算、成本/采购/批发价格、库位、条码、型号，以及订货宝返回的实时库存快照。

`erp_warehousing_purchase_link` 单独保存一个入库单与多个采购单的关系，避免在入库单主表中保存逗号分隔 ID。

### 4.6 `erp_inventory_balance` 库存余额

库存按以下复合键幂等覆盖：

`tenant_id + source_warehouse_key + source_product_key + source_variant_key`

- `source_warehouse_key`：优先 Guid，其次仓库编码。
- `source_product_key`：优先商品 Guid，其次商品编码。
- `source_variant_key`：一级与二级规格 Guid/编码组合；无规格时使用固定值 `BASE`。
- `real_quantity`、`available_quantity` 来自 `batchGetStock`。
- `reserved_quantity`、`in_transit_quantity` 一期固定为 0，`calculation_origin=DHB_SNAPSHOT`；后续自研库存引擎接管后再改变计算来源，不能把两者误称为订货宝返回字段。

## 5. 同步接口与顺序

ERP 统一入口：`POST /api/v1/erp/sync`。商品、分类、品牌、规格、标签与本文的供应链对象共用该入口，仅通过 `objectType` 区分。

请求 `objectType` 支持：

- `PRODUCT_SPU`
- `CATEGORY`
- `BRAND`
- `SPECIFICATION`
- `TAG`
- `SUPPLIER`
- `PURCHASE_ORDER`
- `PURCHASE_RETURN`
- `WAREHOUSING_RECEIPT`
- `WAREHOUSE`
- `INVENTORY`

手动同步接口每次只执行一个 `objectType`；建议首次手动同步顺序：

1. 供应商；
2. 仓库；
3. 商品；
4. 采购单；
5. 采购退货；
6. 入库单；
7. 库存。

商品编码是库存 `batchGetStock` 的输入；供应商、仓库先同步可提高采购及入库单内部外键的命中率。单个列表页面的“同步”按钮只传当前对象类型，不提供伪全量 `ALL`。

ERP 已增加内部定时同步任务，默认关闭，启用配置如下：

```yaml
rigour:
  erp:
    sync:
      enabled: true
      cron: 0 0/30 * * * ?
      max-pages: 100
```

定时任务每次先从 Integration 发现已启用的 `PRODUCT_MASTER_DATA` 和
`SUPPLY_CHAIN_DATA` 目标，再按 `CATEGORY -> BRAND -> SPECIFICATION -> TAG -> PRODUCT_SPU`
以及 `SUPPLIER -> WAREHOUSE -> PURCHASE_ORDER -> PURCHASE_RETURN -> WAREHOUSING_RECEIPT -> INVENTORY`
顺序调用同一个 ERP 统一同步应用服务。手动入口和定时入口不重复实现拉取、校验或落库逻辑；定时批次的
`trigger_type` 记录为 `SCHEDULED`，并继续使用租户/对象类型互斥锁。

### 5.1 日志与问题追踪

- 统一入口记录 `objectType`、`maxPages` 和分派子域。
- ERP 同步批次记录 `tenantId`、`createdBy`、`connectorId`、`runId`、触发方式及新增、变更、重复、拒绝数量。
- Integration 记录订货宝函数、分页位置、返回数量和耗时；Raw Landing 失败会中断本次同步。
- 失败日志记录异常类型和单行原因，但不记录 Token、Secret、完整供应商联系方式、税号或银行账号；这些字段只作为供应商同步字段落库，不写入日志。

## 6. 后续自研演进

- 自研写入使用内部 UUID 和 `internal_status`，不要依赖订货宝枚举驱动状态机。
- 当 `ownership_state=INTERNAL_PRIMARY` 时，订货宝同步只更新 `source_*`、来源快照与同步时间，不覆盖内部状态和自研字段。
- 当前内部使用场景新增了供应商完整地址、联系方式、税号和 `bank_account` 明文字段；如果开放给外部租户或扩大访问范围，应先引入 ERP 独立的 KMS/信封加密端口、密钥版本和审计授权，并迁移为密文列。
- 库存流水、批次效期、预占、在途属于未来自研库存子域，本期订货宝接口只足以建立余额快照，不应据此虚构库存流水。

## 7. 完整字段字典

下表是 Flyway `V3__erp_supply_chain_data.sql` 及后续供应链扩展迁移的业务字段字典。第 3 节已说明的 `id`、`tenant_id`、`internal_status`、`ownership_state`、`record_origin`、`source_synced_at`、`attributes_json`、`version`、`created_by`、`updated_by`、`created_at`、`updated_at` 等通用字段在各表不重复解释；下列所有业务字段与迁移脚本一致。

### 7.1 `erp_supplier`

| 字段 | 类型 | 说明 |
|---|---|---|
| `supplier_code` | `VARCHAR(128)` | ERP 供应商编码，一期由订货宝编码初始化 |
| `name` | `VARCHAR(200)` | 供应商名称 |
| `source_supplier_id` | `VARCHAR(128)` | 订货宝供应商主标识 |
| `source_supplier_guid` | `VARCHAR(128)` | 订货宝供应商 Guid |
| `area_name` | `VARCHAR(200)` | 所在地区名称 |
| `address` | `VARCHAR(500)` | 当前内部使用场景同步的完整地址 |
| `contact_name` | `VARCHAR(120)` | 联系人名称 |
| `mobile` | `VARCHAR(80)` | 当前内部使用场景同步的完整手机号 |
| `phone` | `VARCHAR(80)` | 当前内部使用场景同步的完整固定电话 |
| `email` | `VARCHAR(200)` | 当前内部使用场景同步的完整邮箱 |
| `account_name` | `VARCHAR(200)` | 开户名 |
| `bank_name` | `VARCHAR(200)` | 开户行 |
| `bank_account` | `VARCHAR(200)` | 当前内部使用场景同步的完整银行账号 |
| `invoice_title` | `VARCHAR(240)` | 发票抬头 |
| `taxpayer_number` | `VARCHAR(80)` | 当前内部使用场景同步的完整纳税人识别号 |
| `remark` | `VARCHAR(1000)` | 供应商备注 |
| `source_updated_at` | `DATETIME(6)` | 订货宝记录更新时间 |

### 7.2 `erp_warehouse`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehouse_code` | `VARCHAR(128)` | ERP 仓库编码 |
| `name` | `VARCHAR(200)` | 仓库名称 |
| `source_warehouse_id` | `VARCHAR(128)` | 订货宝仓库主标识 |
| `source_warehouse_guid` | `VARCHAR(128)` | 订货宝仓库 Guid |
| `source_status` | `VARCHAR(24)` | 订货宝仓库状态 |
| `source_default_flag` | `TINYINT(1)` | 订货宝默认仓标识 |
| `acreage` | `DECIMAL(20,6)` | 仓库面积 |
| `phone_masked` | `VARCHAR(80)` | 脱敏联系电话 |
| `address` | `VARCHAR(500)` | 仓库地址 |
| `collaborator_source_id` | `VARCHAR(128)` | 订货宝协作方标识 |
| `remark` | `VARCHAR(1000)` | 仓库备注 |

### 7.3 `erp_purchase_order`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_order_no` | `VARCHAR(128)` | ERP 采购单号，租户内唯一 |
| `source_purchase_id` | `VARCHAR(128)` | 订货宝采购单主标识 |
| `supplier_id` | `CHAR(36)` | ERP 供应商内部外键，可空 |
| `warehouse_id` | `CHAR(36)` | ERP 仓库内部外键，可空 |
| `supplier_code_snapshot` | `VARCHAR(128)` | 同步时供应商编码快照 |
| `supplier_name_snapshot` | `VARCHAR(200)` | 同步时供应商名称快照 |
| `warehouse_code_snapshot` | `VARCHAR(128)` | 同步时仓库编码快照 |
| `warehouse_name_snapshot` | `VARCHAR(200)` | 同步时仓库名称快照 |
| `staff_source_id` | `VARCHAR(128)` | 订货宝经办人标识 |
| `staff_name` | `VARCHAR(120)` | 经办人名称 |
| `source_status` | `VARCHAR(32)` | 订货宝单据状态值 |
| `source_status_name` | `VARCHAR(80)` | 订货宝单据状态名称 |
| `source_payment_status` | `VARCHAR(32)` | 订货宝付款状态值 |
| `source_payment_name` | `VARCHAR(80)` | 订货宝付款状态名称 |
| `delivery_at` | `DATETIME(6)` | 预计交货时间 |
| `source_created_at` | `DATETIME(6)` | 订货宝单据创建时间 |
| `source_updated_at` | `DATETIME(6)` | 订货宝单据更新时间 |
| `total_amount` | `DECIMAL(20,6)` | 采购总金额 |
| `paid_amount` | `DECIMAL(20,6)` | 已付金额 |
| `goods_count` | `DECIMAL(20,6)` | 商品总数量 |
| `source_downloaded` | `TINYINT(1)` | 订货宝 ERP/API 下载标识 |
| `remark` | `VARCHAR(1000)` | 外部备注 |
| `internal_communication` | `VARCHAR(2000)` | 内部沟通备注快照 |

### 7.4 `erp_purchase_order_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_order_id` | `CHAR(36)` | ERP 采购单主表外键 |
| `source_line_id` | `VARCHAR(128)` | 订货宝采购明细标识 |
| `spu_id` | `CHAR(36)` | ERP SPU 外键，未命中时可空 |
| `sku_id` | `CHAR(36)` | ERP SKU 外键，未命中时可空 |
| `source_goods_id` | `VARCHAR(128)` | 订货宝商品标识 |
| `source_goods_guid` | `VARCHAR(128)` | 订货宝商品 Guid |
| `source_goods_code` | `VARCHAR(128)` | 订货宝商品编码 |
| `source_goods_name` | `VARCHAR(240)` | 订货宝商品名称快照 |
| `source_options_id` | `VARCHAR(128)` | 订货宝规格组合标识 |
| `source_options_goods_code` | `VARCHAR(128)` | 订货宝规格商品编码 |
| `options_summary` | `VARCHAR(500)` | 规格组合展示文本 |
| `base_quantity` | `DECIMAL(20,6)` | 基础单位采购数量 |
| `unit_price` | `DECIMAL(20,6)` | 采购单价 |
| `purchase_unit_code` | `VARCHAR(40)` | 采购单位编码 |
| `purchase_unit_name` | `VARCHAR(80)` | 采购单位名称 |
| `purchase_unit_quantity` | `DECIMAL(20,6)` | 采购单位数量 |
| `warehoused_quantity` | `DECIMAL(20,6)` | 已入库数量 |
| `returned_quantity` | `DECIMAL(20,6)` | 已退货数量 |
| `remark` | `VARCHAR(1000)` | 明细备注 |

### 7.5 `erp_purchase_return`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_return_no` | `VARCHAR(128)` | ERP 采购退货单号 |
| `source_return_id` | `VARCHAR(128)` | 订货宝退货单主标识 |
| `supplier_id` / `warehouse_id` | `CHAR(36)` | ERP 供应商/仓库外键，可空 |
| `supplier_code_snapshot` / `supplier_name_snapshot` | `VARCHAR` | 供应商编码与名称快照 |
| `warehouse_code_snapshot` / `warehouse_name_snapshot` | `VARCHAR` | 仓库编码与名称快照 |
| `staff_source_id` / `staff_name` | `VARCHAR` | 订货宝经办人标识与名称 |
| `source_status` / `source_status_name` | `VARCHAR` | 订货宝状态值与名称 |
| `return_amount` | `DECIMAL(20,6)` | 退货金额 |
| `discount_amount` | `DECIMAL(20,6)` | 折扣金额 |
| `return_reason` | `VARCHAR(1000)` | 退货原因 |
| `source_created_at` | `DATETIME(6)` | 订货宝单据创建时间 |
| `return_send_at` | `DATETIME(6)` | 退货发送时间 |
| `internal_communication` | `VARCHAR(2000)` | 内部沟通快照 |
| `remark` | `VARCHAR(1000)` | 单据备注 |
| `detail_count` | `INT` | 订货宝明细数量 |
| `contact_name` | `VARCHAR(120)` | 退货联系人 |
| `contact_phone_masked` | `VARCHAR(80)` | 脱敏退货联系电话 |
| `contact_address_masked` | `VARCHAR(500)` | 脱敏退货地址 |
| `city_ids_json` | `JSON` | 退货城市标识数组 |
| `city_names_json` | `JSON` | 退货城市名称数组 |
| `source_device` | `VARCHAR(80)` | 订货宝单据来源设备 |
| `parent_return_source_id` | `VARCHAR(128)` | 父退货单来源标识 |
| `parent_company_source_id` | `VARCHAR(128)` | 父公司来源标识 |
| `source_downloaded` | `TINYINT(1)` | 订货宝 ERP/API 下载标识 |

### 7.6 `erp_purchase_return_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_return_id` | `CHAR(36)` | ERP 采购退货主表外键 |
| `source_line_id` | `VARCHAR(128)` | 订货宝退货明细标识 |
| `spu_id` / `sku_id` | `CHAR(36)` | ERP SPU/SKU 外键，可空 |
| `purchase_order_line_id` | `CHAR(36)` | ERP 原采购明细外键，可空 |
| `source_goods_id` | `VARCHAR(128)` | 订货宝商品标识 |
| `source_goods_code` / `source_goods_name` | `VARCHAR` | 商品编码与名称快照 |
| `source_options_id` / `source_options_goods_code` | `VARCHAR(128)` | 规格标识与规格商品编码 |
| `options_summary` | `VARCHAR(500)` | 规格展示文本 |
| `requested_quantity` | `DECIMAL(20,6)` | 申请退货数量 |
| `confirmed_quantity` | `DECIMAL(20,6)` | 确认退货数量 |
| `return_price` | `DECIMAL(20,6)` | 申请退货价格 |
| `confirmed_price` | `DECIMAL(20,6)` | 确认退货价格 |
| `return_unit_code` / `return_unit_name` | `VARCHAR` | 退货单位编码与名称 |
| `return_unit_quantity` | `DECIMAL(20,6)` | 退货单位数量 |
| `confirmed_unit_quantity` | `DECIMAL(20,6)` | 确认单位数量 |
| `conversion_number` | `DECIMAL(20,6)` | 单位换算系数 |
| `amount` | `DECIMAL(20,6)` | 明细金额 |
| `cost_price` | `DECIMAL(20,6)` | 成本价 |
| `purchase_order_no` | `VARCHAR(128)` | 原采购单号快照 |
| `category_name_snapshot` | `VARCHAR(160)` | 商品分类名称快照 |
| `brand_name_snapshot` | `VARCHAR(160)` | 商品品牌名称快照 |
| `remark` | `VARCHAR(1000)` | 明细备注 |

### 7.7 `erp_warehousing_receipt`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehousing_no` | `VARCHAR(128)` | ERP 入库单号 |
| `source_warehousing_id` | `VARCHAR(128)` | 订货宝入库单主标识 |
| `warehouse_id` / `supplier_id` | `CHAR(36)` | ERP 仓库/供应商外键，可空 |
| `warehouse_name_snapshot` / `supplier_name_snapshot` | `VARCHAR(200)` | 仓库/供应商名称快照 |
| `source_type_id` / `source_type_name` | `VARCHAR` | 订货宝入库类型值与名称 |
| `source_status` / `source_status_name` | `VARCHAR` | 订货宝状态值与名称 |
| `staff_name` | `VARCHAR(120)` | 经办人名称 |
| `client_source_id` | `VARCHAR(128)` | 订货宝客户/供应商来源标识 |
| `account_source_id` | `VARCHAR(128)` | 订货宝账户来源标识 |
| `collaborator_source_id` / `collaborator_name` | `VARCHAR` | 协作方标识与名称 |
| `logistics_source_id` | `VARCHAR(128)` | 物流公司来源标识 |
| `express_number` | `VARCHAR(160)` | 快递/物流单号 |
| `storage_at` | `DATETIME(6)` | 入库时间 |
| `source_created_at` / `source_updated_at` | `DATETIME(6)` | 订货宝单据创建/更新时间 |
| `freight_amount` | `DECIMAL(20,6)` | 运费 |
| `total_amount` | `DECIMAL(20,6)` | 入库总金额 |
| `cost_amount` | `DECIMAL(20,6)` | 入库成本总额 |
| `source_api_flag` | `TINYINT(1)` | 订货宝 API 标识 |
| `split_type` | `VARCHAR(32)` | 订货宝拆单类型 |
| `remark` | `VARCHAR(1000)` | 单据备注 |

### 7.8 `erp_warehousing_receipt_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehousing_receipt_id` | `CHAR(36)` | ERP 入库单主表外键 |
| `source_line_id` | `VARCHAR(128)` | 订货宝入库明细标识 |
| `spu_id` / `sku_id` | `CHAR(36)` | ERP SPU/SKU 外键，可空 |
| `source_goods_id` | `VARCHAR(128)` | 订货宝商品标识 |
| `source_goods_code` / `source_goods_name` | `VARCHAR` | 商品编码与名称快照 |
| `source_options_id` / `source_options_goods_code` | `VARCHAR(128)` | 规格标识与规格商品编码 |
| `options_summary` | `VARCHAR(500)` | 规格展示文本 |
| `base_quantity` | `DECIMAL(20,6)` | 基础单位入库数量 |
| `unit_quantity` | `DECIMAL(20,6)` | 入库单位数量 |
| `unit_code` / `unit_name` | `VARCHAR` | 入库单位编码与名称 |
| `conversion_number` | `DECIMAL(20,6)` | 单位换算系数 |
| `cost_price` | `DECIMAL(20,6)` | 基础单位成本价 |
| `unit_cost_price` | `DECIMAL(20,6)` | 入库单位成本价 |
| `purchase_price` | `DECIMAL(20,6)` | 采购价 |
| `wholesale_price` | `DECIMAL(20,6)` | 批发价 |
| `allocation` | `VARCHAR(200)` | 库位 |
| `barcode` | `VARCHAR(160)` | 条形码 |
| `goods_model` | `VARCHAR(160)` | 商品型号 |
| `source_real_quantity` | `DECIMAL(20,6)` | 订货宝返回的实际库存快照 |
| `source_available_quantity` | `DECIMAL(20,6)` | 订货宝返回的可用库存快照 |
| `collaborator_source_id` / `collaborator_name` | `VARCHAR` | 协作方标识与名称 |
| `remark` | `VARCHAR(1000)` | 明细备注 |

### 7.9 `erp_warehousing_purchase_link`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehousing_receipt_id` | `CHAR(36)` | ERP 入库单外键 |
| `purchase_order_id` | `CHAR(36)` | ERP 采购单外键，未命中时可空 |
| `source_purchase_id` | `VARCHAR(128)` | 订货宝采购单标识 |
| `purchase_order_no` | `VARCHAR(128)` | 采购单号快照 |

### 7.10 `erp_inventory_balance`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehouse_id` / `spu_id` / `sku_id` | `CHAR(36)` | ERP 仓库、SPU、SKU 内部外键，可空 |
| `source_warehouse_key` | `VARCHAR(160)` | 库存幂等键中的仓库键 |
| `source_warehouse_guid` | `VARCHAR(128)` | 订货宝仓库 Guid |
| `source_warehouse_code` / `source_warehouse_name` | `VARCHAR` | 仓库编码与名称快照 |
| `source_product_key` | `VARCHAR(160)` | 库存幂等键中的商品键 |
| `source_goods_guid` | `VARCHAR(128)` | 订货宝商品 Guid |
| `source_goods_code` / `source_goods_name` | `VARCHAR` | 商品编码与名称快照 |
| `source_variant_key` | `VARCHAR(320)` | 库存幂等键中的规格组合键 |
| `first_option_guid` / `first_option_code` / `first_option_name` | `VARCHAR` | 一级规格 Guid、编码、名称 |
| `second_option_guid` / `second_option_code` / `second_option_name` | `VARCHAR` | 二级规格 Guid、编码、名称 |
| `real_quantity` | `DECIMAL(20,6)` | 订货宝实际库存 |
| `available_quantity` | `DECIMAL(20,6)` | 订货宝可用库存 |
| `reserved_quantity` | `DECIMAL(20,6)` | ERP 预占数量，一期固定为 0 |
| `in_transit_quantity` | `DECIMAL(20,6)` | ERP 在途数量，一期固定为 0 |
| `calculation_origin` | `VARCHAR(32)` | 库存计算来源，一期为 `DHB_SNAPSHOT` |
