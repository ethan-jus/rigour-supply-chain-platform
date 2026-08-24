# ERP 供应链数据一期设计

## 1. 范围与边界

一期实现供应商、采购单、采购退货单、入库单、仓库和库存的订货宝同步映射，统一写入 ERP 自研新业务表。页面链路固定为：

`Portal -> Gateway -> ERP Core -> Integration -> 订货宝`

- Portal 只访问 ERP Core，不持有订货宝 Connector、Token 或 Secret。
- ERP Core 是规范业务表的唯一写者，负责内部状态、业务编码、幂等落库、本地查询和同步批次。
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
| `id` | `BIGINT` | ERP 内部自增主键，不以外部 ID 作为主键 |
| `tenant_id` | `VARCHAR(64)` | 租户隔离键 |
| `*_code` / `*_no` | `VARCHAR(50)` | 我方业务编码或单号，由 ERP 编码规则生成 |
| `status_code` | `VARCHAR(64)` | 我方业务状态，关联业务字典，不直接使用订货宝状态 |
| `revision` | `INT` | MyBatis-Plus 乐观锁版本 |
| `created_by/created_time` | `VARCHAR(50)` / `DATETIME(6)` | 创建人和创建时间 |
| `updated_by/updated_time` | `VARCHAR(50)` / `DATETIME(6)` | 更新人和更新时间 |
| `deleted` | `INT` | 删除标识：0 未删除，1 已删除 |

订货宝来源 ID、来源编码、来源状态、payload hash、最后出现时间统一保存在 `erp_master_source_binding`。业务主表只保存我方业务字段和必要的业务快照，避免旧方案继续以订货宝字段为中心。

## 4. 表设计

### 4.1 `erp_supplier_profile` 供应商档案

| 字段组 | 关键字段 | 说明 |
|---|---|---|
| 身份 | `supplier_code`, `supplier_name` | 供应商编码由 ERP 编码规则生成 |
| 地址联系人 | `address`, `contact_name`, `contact_phone` | 当前内部使用场景同步并展示完整地址和联系方式 |
| 财务信息 | `bank_name`, `bank_account_no` | 当前内部使用场景同步银行信息 |
| 状态 | `status_code`, `revision`, `deleted` | 供应商状态关联 `SUPPLIER_STATUS` 字典项 |

幂等键：业务侧 `(tenant_id, supplier_code)`；来源侧 `(tenant_id, source_system, source_object_type, source_object_id)` 走 `erp_master_source_binding`。

### 4.2 `erp_inventory_warehouse` 仓库档案

主要字段：`warehouse_code`、`warehouse_name`、`region_code`、`warehouse_type_code`、`default_flag`、`address`、`contact_name`、`contact_phone`、`remark`。
幂等键：业务侧 `(tenant_id, warehouse_code)`；来源侧走 `erp_master_source_binding`。

### 4.3 `erp_procurement_order` / `erp_procurement_order_line` 采购单

采购单头保存我方采购订单号、供应商、目标仓库、采购状态、预计到货时间、采购数量和采购金额。供应商、仓库、商品、规格在同步时优先通过来源绑定定位；未命中时创建我方占位业务档案并使用我方编码。

采购明细保存：

- 商品、规格内部外键；
- 商品编码、规格编码、商品名称快照；
- 单位、采购数量、采购单价、采购金额、已入库数量。

幂等键：来源侧走 `erp_master_source_binding`；业务侧采购单 `(tenant_id, procurement_no)`；明细按 `(tenant_id, procurement_order_id, line_no)` 重建。

### 4.4 `erp_purchase_return_order` / `erp_purchase_return_order_line` 采购退货

采购退货是采购域业务单据，单独落自研采购退货表，不混入通用出库单。主表保存我方退货单号、供应商、退货仓库、经办员工编码/名称快照、退货状态、退货数量、退货金额、折扣、原因、发送日期和联系人信息。明细保存申请/确认数量、退货/确认价格、成本、采购单引用及商品快照。

订货宝来源 ID、来源单号、来源状态和 payload hash 不写入业务主表，由 `erp_master_source_binding` 统一保存，避免业务表继续以订货宝协议字段为中心。接口返回的操作日志不写 ERP 业务表，由 Integration Raw Landing 保存，避免把供应商协议日志耦合进未来自研模型。

幂等键：来源侧 `(tenant_id, source_system, source_object_type, source_object_id)` 走 `erp_master_source_binding`；业务侧退货单 `(tenant_id, purchase_return_no)`；明细按 `(tenant_id, purchase_return_order_id, line_no)` 重建。

### 4.5 `erp_stock_in_order` / `erp_stock_in_order_line` 入库单

主表保存我方入库单号、入库类型、采购订单引用、入库仓库、供应商、入库状态和确认入库时间。明细保存商品、规格、商品快照、单位、入库数量、入库单价和入库金额。

订货宝入库单来源 ID、来源单号、来源状态和 payload hash 由 `erp_master_source_binding` 保存；入库业务表不再保留旧订货宝投影字段。

### 4.6 `erp_stock_balance` 库存余额

库存按以下复合键幂等覆盖：

`tenant_id + warehouse_id + product_id + product_variant_id`

- `warehouse_id`：通过订货宝仓库来源绑定映射到 ERP 仓库。
- `product_id/product_variant_id`：通过订货宝商品/规格来源绑定映射到 ERP 商品与规格。
- `available_quantity` 来自 `batchGetStock` 的可用库存。
- `locked_quantity`、`in_transit_quantity` 一期固定为 0；后续自研库存引擎接管后再改变计算来源，不能把两者误称为订货宝返回字段。

## 5. 同步接口与顺序

ERP 不再向 Portal 暴露模块级订货宝同步入口。手动同步、定时同步和修复同步统一进入 Integration 订货宝同步编排器；Integration 再调用 ERP 内部入口 `POST /internal/v1/erp/dhb/sync`，仅通过 `objectType` 区分商品、分类、品牌、规格、标签与本文的供应链对象。

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

下表是 Flyway `V19__erp_internal_product_inventory_baseline.sql`、`V22__erp_purchase_return_order_baseline.sql` 及后续扩展迁移的业务字段字典。第 3 节已说明的通用字段在各表不重复解释；下列所有业务字段与迁移脚本一致。

### 7.1 `erp_supplier_profile`

| 字段 | 类型 | 说明 |
|---|---|---|
| `supplier_code` | `VARCHAR(50)` | ERP 供应商编码，由 ERP 编码规则生成 |
| `supplier_name` | `VARCHAR(200)` | 供应商名称 |
| `contact_name` | `VARCHAR(100)` | 联系人 |
| `contact_phone` | `VARCHAR(50)` | 联系电话 |
| `address` | `VARCHAR(1000)` | 供应商地址 |
| `bank_name` | `VARCHAR(100)` | 开户银行 |
| `bank_account_no` | `VARCHAR(100)` | 银行账号 |
| `status_code` | `VARCHAR(64)` | 供应商状态，关联 `SUPPLIER_STATUS` 字典项 |
| `remark` | `VARCHAR(1000)` | 供应商备注 |

### 7.2 `erp_inventory_warehouse`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehouse_code` | `VARCHAR(50)` | 仓库编码，由 ERP 编码规则生成 |
| `warehouse_name` | `VARCHAR(120)` | 仓库名称 |
| `region_code` | `VARCHAR(64)` | 仓库归属地区，关联 `REGION` 字典项 |
| `warehouse_type_code` | `VARCHAR(64)` | 仓库类型：`DEFAULT` / `CITY` / `OTHER` |
| `default_flag` | `TINYINT(1)` | 是否默认仓库：0 否，1 是 |
| `address` | `VARCHAR(1000)` | 仓库地址 |
| `contact_name` | `VARCHAR(100)` | 联系人 |
| `contact_phone` | `VARCHAR(50)` | 联系电话 |
| `remark` | `VARCHAR(1000)` | 仓库备注 |

### 7.3 `erp_procurement_order`

| 字段 | 类型 | 说明 |
|---|---|---|
| `procurement_no` | `VARCHAR(50)` | 采购订单号，由 ERP 编码规则生成 |
| `supplier_id` | `BIGINT` | 供应商ID |
| `target_warehouse_id` | `BIGINT` | 计划入库仓库ID |
| `status_code` | `VARCHAR(64)` | 采购状态，关联 `PURCHASE_STATUS` 字典项 |
| `expected_arrival_time` | `DATETIME(6)` | 预计到货时间 |
| `total_quantity` | `DECIMAL(24,6)` | 采购总数量，由明细汇总 |
| `total_amount` | `DECIMAL(24,6)` | 采购总金额，由明细汇总 |
| `remark` | `VARCHAR(1000)` | 备注 |

### 7.4 `erp_procurement_order_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `procurement_order_id` | `BIGINT` | 采购订单ID |
| `line_no` | `INT` | 行号 |
| `product_id` / `product_variant_id` | `BIGINT` | 商品/规格外键 |
| `product_code_snapshot` / `variant_code_snapshot` | `VARCHAR(50)` | 商品编码与规格编码快照 |
| `product_name_snapshot` | `VARCHAR(200)` | 商品名称快照 |
| `unit_code` | `VARCHAR(64)` | 采购单位，关联 `PRODUCT_UNIT` 字典项 |
| `quantity` | `DECIMAL(24,6)` | 采购数量 |
| `unit_price` | `DECIMAL(24,6)` | 采购单价 |
| `line_amount` | `DECIMAL(24,6)` | 采购金额 |
| `received_quantity` | `DECIMAL(24,6)` | 已入库数量 |
| `remark` | `VARCHAR(1000)` | 备注 |

### 7.5 `erp_purchase_return_order`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_return_no` | `VARCHAR(50)` | ERP 采购退货单号，由 ERP 编码规则生成 |
| `supplier_id` / `warehouse_id` | `BIGINT` | ERP 自研供应商/退货仓库外键 |
| `operator_staff_code` | `VARCHAR(50)` | 经办员工编码，关联 IAM 员工中心员工编码 |
| `operator_staff_name_snapshot` | `VARCHAR(100)` | 经办员工名称快照 |
| `status_code` | `VARCHAR(64)` | 采购退货状态，关联 `PURCHASE_RETURN_STATUS` 字典项 |
| `total_quantity` | `DECIMAL(24,6)` | 退货总数量 |
| `total_amount` | `DECIMAL(24,6)` | 退货总金额 |
| `discount_amount` | `DECIMAL(24,6)` | 优惠/折让金额 |
| `return_time` | `DATETIME(6)` | 退货发出时间 |
| `contact_name` | `VARCHAR(100)` | 退货联系人 |
| `contact_phone` | `VARCHAR(50)` | 退货联系电话 |
| `contact_address` | `VARCHAR(1000)` | 退货联系地址 |
| `reason` | `VARCHAR(1000)` | 退货原因 |
| `remark` | `VARCHAR(1000)` | 单据备注 |
| `revision` | `INT` | 乐观锁 |
| `created_by` / `created_time` | `VARCHAR` / `DATETIME(6)` | 创建人和创建时间 |
| `updated_by` / `updated_time` | `VARCHAR` / `DATETIME(6)` | 更新人和更新时间 |
| `deleted` | `INT` | 删除标识 |

### 7.6 `erp_purchase_return_order_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `purchase_return_order_id` | `BIGINT` | ERP 采购退货单外键 |
| `line_no` | `INT` | 行号 |
| `procurement_order_id` | `BIGINT` | 关联采购订单ID，可由来源采购单号映射到 ERP 自研采购单 |
| `procurement_no_snapshot` | `VARCHAR(50)` | 关联采购订单号快照 |
| `product_id` / `product_variant_id` | `BIGINT` | ERP 商品/规格外键 |
| `product_code_snapshot` / `variant_code_snapshot` | `VARCHAR(50)` | 商品编码与规格编码快照 |
| `product_name_snapshot` | `VARCHAR(200)` | 商品名称快照 |
| `unit_code` | `VARCHAR(64)` | 退货单位，关联 `PRODUCT_UNIT` 字典项 |
| `requested_quantity` | `DECIMAL(24,6)` | 申请退货数量 |
| `returned_quantity` | `DECIMAL(24,6)` | 确认退货数量 |
| `unit_price` | `DECIMAL(24,6)` | 退货单价 |
| `confirmed_unit_price` | `DECIMAL(24,6)` | 确认退货单价 |
| `line_amount` | `DECIMAL(24,6)` | 明细金额 |
| `cost_price` | `DECIMAL(24,6)` | 成本价快照 |
| `remark` | `VARCHAR(1000)` | 明细备注 |

### 7.7 `erp_stock_in_order`

| 字段 | 类型 | 说明 |
|---|---|---|
| `stock_in_no` | `VARCHAR(50)` | 入库单号，由 ERP 编码规则生成 |
| `stock_in_type_code` | `VARCHAR(64)` | 入库类型，关联 `STOCK_IN_TYPE` 字典项 |
| `procurement_order_id` | `BIGINT` | 采购订单ID |
| `procurement_no` | `VARCHAR(50)` | 采购订单号快照 |
| `transfer_order_id` / `transfer_order_no` | `BIGINT` / `VARCHAR(50)` | 调拨入库来源调拨单 |
| `warehouse_id` | `BIGINT` | 入库仓库ID |
| `supplier_id` | `BIGINT` | 供应商ID |
| `status_code` | `VARCHAR(64)` | 入库单状态，关联 `STOCK_IN_STATUS` 字典项 |
| `stock_in_time` | `DATETIME(6)` | 确认入库时间 |
| `remark` | `VARCHAR(1000)` | 备注 |

### 7.8 `erp_stock_in_order_line`

| 字段 | 类型 | 说明 |
|---|---|---|
| `stock_in_order_id` | `BIGINT` | 入库单ID |
| `line_no` | `INT` | 行号 |
| `procurement_order_line_id` | `BIGINT` | 采购订单明细ID |
| `transfer_order_line_id` | `BIGINT` | 调拨单明细ID |
| `product_id` / `product_variant_id` | `BIGINT` | 商品/规格外键 |
| `product_code_snapshot` / `variant_code_snapshot` | `VARCHAR(50)` | 商品编码与规格编码快照 |
| `product_name_snapshot` | `VARCHAR(200)` | 商品名称快照 |
| `unit_code` | `VARCHAR(64)` | 单位，关联 `PRODUCT_UNIT` 字典项 |
| `quantity` | `DECIMAL(24,6)` | 入库数量 |
| `unit_price` | `DECIMAL(24,6)` | 入库单价 |
| `amount` | `DECIMAL(24,6)` | 入库金额 |
| `remark` | `VARCHAR(1000)` | 备注 |

### 7.9 `erp_stock_balance`

| 字段 | 类型 | 说明 |
|---|---|---|
| `warehouse_id` | `BIGINT` | 仓库ID |
| `product_id` | `BIGINT` | 商品ID |
| `product_variant_id` | `BIGINT` | 商品规格ID；无规格商品也应创建默认规格 |
| `available_quantity` | `DECIMAL(24,6)` | 可用库存 |
| `locked_quantity` | `DECIMAL(24,6)` | 锁定库存 |
| `in_transit_quantity` | `DECIMAL(24,6)` | 在途库存 |
