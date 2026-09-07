# 订货宝同步稳定方案

更新时间：2026-08-26

## 目标

订货宝同步不能以“接口调成功”为完成标准。稳定口径是：

1. 订货宝原始数据完整落 Raw；
2. 我方领域服务按自己的业务规则落主表/明细表；
3. 领域服务把“订货宝来源对象 -> 我方业务对象”登记到 Integration 全局映射表；
4. 下游同步只消费全局映射，不跨库猜、不临时造主数据；
5. 无法确认的数据进入对账/死信，原因可追溯，可重跑修复。

## 总链路顺序

统一同步顺序固定为：

1. 字典 baseline
2. IAM 人员
3. ERP 商品主数据：分类、品牌、规格、标签、SPU/SKU
4. CRM 客户主数据
5. ERP 供应链数据：供应商、仓库、采购单、采购退货、入库单、库存余额
6. Order 订单域：销售订单、发货、收款单、付款单、调拨证据投影

这个顺序的原因是 Order 依赖客户、商品、SKU、仓库、人员和字典映射。缺上游映射时，Order 不能自己补一个临时对象，否则后续对账会出现同一个订货宝对象映射到多个我方对象的问题。

## 全局映射原则

`integration_external_object_mapping` 是跨域依赖的唯一稳定入口。

| 来源对象 | 领域 | 我方对象 | 生产方 | 消费方 |
| --- | --- | --- | --- | --- |
| CUSTOMER | CRM | CUSTOMER | CRM 同步完成后发布 | Order 订单/资金 |
| PRODUCT_SPU | ERP | PRODUCT | ERP 商品同步完成后发布 | Order/ERP 单据 |
| PRODUCT_SKU | ERP | PRODUCT_VARIANT | ERP 商品同步完成后发布 | Order/ERP 单据 |
| SUPPLIER | ERP | SUPPLIER | ERP 供应商同步完成后发布 | 采购/对账 |
| WAREHOUSE | ERP | WAREHOUSE | ERP 仓库同步完成后发布 | 出库/入库/调拨 |
| PURCHASE_ORDER | ERP | PROCUREMENT_ORDER | ERP 采购同步完成后发布 | 对账 |
| PURCHASE_RETURN | ERP | PURCHASE_RETURN_ORDER | ERP 采购退货同步完成后发布 | 对账 |
| WAREHOUSING_RECEIPT | ERP | STOCK_IN_ORDER | ERP 入库同步完成后发布 | 对账 |
| INVENTORY_BALANCE | ERP | STOCK_BALANCE | ERP 库存同步完成后发布 | 库存对账 |
| SALES_ORDER | Order | SALES_ORDER | Order 订单同步发布 | 发货/资金 |
| SALES_SHIPMENT | Order | SALES_SHIPMENT | Order 发货同步发布 | 对账 |
| FINANCIAL_RECEIPT | Order | FUND_DOCUMENT | Order 资金同步发布 | 资金查询/对账 |
| FINANCIAL_PAYMENT | Order | FUND_DOCUMENT | Order 资金同步发布 | 资金查询/对账 |
| TRANSFER_ORDER | ERP | TRANSFER_ORDER | ERP 按我方规则生成；订货宝 `DB...` 只作来源证据 | 调拨出库/入库凭证 |

## 字典方案

字典分两层：

1. 编排开始时先跑订货宝 baseline 字典，补齐已知来源枚举；
2. 每个领域同步时继续把实际遇到的来源值提交字典审计。

字典未映射不应静默吞掉：已知值自动落 revision，未知值返回 `SUCCEEDED_WITH_WARNINGS`，在编排步骤里展示具体 `dict.field=value x count`。这样后续补字典后可以重跑，不需要改业务代码。

## ERP 主数据和供应链

ERP 自己负责订货宝供应商、仓库、商品、SKU、采购、入库、库存余额的业务落库。

同步完成后，ERP 从本地 `MasterSourceBinding` 读取已解析、未删除、来源仍存在的数据，批量发布到 Integration 全局映射。Order 不再直接理解 ERP 内部来源绑定，也不跨库查 ERP 表。

DHB 拉取的库存类单据只作为来源证据和对账，不直接改当前库存余额；库存余额以库存同步对象为基线，我方 ERP 人工业务才影响实时库存。

同步生成内部编码时按来源业务时间优先：采购单按订货宝创建时间，入库单按入库时间，采购退货按退货出库/来源创建时间，出库和调拨按来源出入库/调拨业务时间。供应商、仓库、商品分类、品牌、标签、规格、规格值这类主数据只有在来源明确给出创建时间时才用来源创建时间生成编码，否则回退本系统当前生成规则。人工新增数据始终走本系统当前生成规则。

入库单必须按订货宝来源入库类型落库，不能默认当采购入库：

1. `type_id=1` 或类型名为采购入库时，落 `stock_in_type_code=PURCHASE`；
2. `type_id=8` 或类型名包含调拨时，落 `stock_in_type_code=TRANSFER`；
3. `type_id=-1` 或类型名包含退货时，落 `stock_in_type_code=RETURN`，作为退货入库凭证展示；
4. 调拨入库、退货入库不挂供应商、不挂采购单；如果调拨来源字段能拿到 `DB...`，只作为来源调拨单号展示；
5. 订货宝来源采购单、入库单要保存 `connector_id/source_system_code/source_document_no`，用于页面展示、去重、对账和禁止人工误操作；
6. 订货宝来源入库单编号按来源入库时间 `storageAt` 生成 `SIyyyyMMdd****`；我方人工入库继续按本系统当前业务时间生成；
7. 历史数据补偿不能只看 payload hash；即使来源 hash 未变，只要编号日期、类型、来源单号、采购/调拨关联、仓库、供应商等投影字段不一致，也要按 changed 重新投影。

ERP 单据列表筛选按业务含义执行，不能被同步补偿时间误导：

1. 采购、入库、出库、调拨列表按各自业务时间或来源创建时间倒序，不按 `updated_time` 倒序；
2. 单号搜索框同时匹配我方内部号、订货宝来源号、关联采购/销售/调拨号和备注；
3. 入库列表支持类型筛选，`PURCHASE`、`TRANSFER`、`RETURN` 分开统计；
4. Portal 查询按钮先回到第 1 页，并把时间、仓库、供应商、单据类型等筛选条件传给后端。

## CRM

CRM 客户同步后发布 `CUSTOMER -> CRM CUSTOMER` 全局映射。订单里的 `ClientGUID` 优先按 source id 匹配，`ClientNO` 作为 source no fallback。

大批量映射登记必须分批提交，避免客户 1000+ 条时 Integration 实际写入成功但调用端读响应超时，导致 CRM run 被误标失败。

## Order

Order 同步只做订单域业务对象投影：

1. 销售订单：必须解析客户、商品、SKU；
2. 发货单：必须解析父销售订单；
3. 收款单/付款单：必须保留资金方向、来源单号、关联订单、业务类型、支付方式、流水号、银行账户、开户名、开户行、提交/审核时间，附件下载后上传我方 COS 并保留来源引用；
4. 调拨：读取调拨来源证据后投影到 ERP，不在 Order 内生成调拨业务主单。

缺客户、缺父单、缺商品、缺仓库时进入死信和对账，不做猜测。修复上游映射后重跑即可补偿。

Order 里订货宝来源销售订单保留同步投影能力，但不开放人工业务动作：

1. 同步服务身份 `SERVICE` 可以创建/更新来源投影，也可以投影来源取消状态；
2. 普通租户用户不能伪造 `sourceSystemCode` 创建外部来源销售订单；
3. 普通租户用户不能对外部来源销售订单执行提交、编辑、取消、确认出库、一键出库或删除；
4. 我方自建销售订单 `sourceSystemCode` 为空，继续完整走人工提交、出库、删除等业务状态机。

## 各域稳定验收

| 同步域 | Raw 完整性 | 业务落库 | 全局映射 | 不一致处理 |
| --- | --- | --- | --- | --- |
| 字典 | baseline 记录同步批次和实际来源值 | 已知枚举自动落项，未知枚举保留审计 | 不需要跨域映射 | `SUCCEEDED_WITH_WARNINGS` 展示具体字段和值 |
| ERP 商品/供应链 | 商品、SKU、供应商、仓库、采购、入库、库存余额来源对象先落 Raw | ERP 按本地商品、仓库、采购、库存规则落主表/明细 | ERP 同步完成后发布 PRODUCT/SUPPLIER/WAREHOUSE/PURCHASE/STOCK 映射 | 缺单位、缺仓库、缺商品时进入 ERP 同步异常，修复后从 Raw 重放 |
| CRM | 客户、类型、区域、地址来源对象先落 Raw | CRM 按客户、联系人、地址、政策等本地模型落库 | CRM 发布 CUSTOMER/CUSTOMER_TYPE/CUSTOMER_AREA 映射 | 重号或字段不完整进入 CRM 对账，不由 Order 临时造客户 |
| Order | 订单、发货、收款、付款来源对象先落 Raw | Order 只落销售订单、回款记录、发货、资金单据 | Order 发布 SALES_ORDER/SALES_SHIPMENT/FINANCIAL_RECEIPT/FINANCIAL_PAYMENT 映射 | 缺父单或上游映射进入死信，补上游后补偿重放 |
| 调拨 | 订货宝后台调拨页、调拨出库、调拨入库都只作为来源证据落 Raw | ERP 按我方规则生成 `TR...` 调拨主单，出入库凭证挂同一主单 | ERP 发布 TRANSFER_ORDER/STOCK_OUT/STOCK_IN 映射，`DB...` 只是 source no | 后台调拨页不可读时标记 `PARTIAL`，不能用 FH/RK 假装完整 |

## 收款单和付款单

收款单和付款单建议在存储和详情语义上保持分开方向：

| 类型 | 方向 | 典型含义 | 不能混淆的点 |
| --- | --- | --- | --- |
| 收款单 | RECEIPT | 客户向我方付款、充值、订单收款 | 增加我方资金流入或客户预存款 |
| 付款单 | PAYMENT | 退款、退货退款、预存款扣款等资金支出/余额消耗 | 普通付款不是默认销售退款 |

可以提供一个统一资金流水查询视图，但必须显式展示 `direction`、`businessType`、`sourceDocumentNo`、`linkedOrderNo`、`paymentMethod`、`tradeSerialNo`、`bankAccountNo`、`bankAccountName`、`bankName`、`submittedAt`、`approvedAt`、`attachments`。用户查看一笔款时，要能直接知道“这笔款是什么、怎么来的、关联哪张单、钱走到哪个账户”。

## 收支明细对账

订货宝系统里的“收支明细”建议作为资金对账账本，而不是替代收款单/付款单成为唯一业务来源。

原因是收款单和付款单提供单据详情、审核状态、银行账户、流水号、附件等业务证据；收支明细提供最终账本视角，能直接看到每条资金行是收入还是支出。两者需要相互校验，不能只保留其中一个。

当前可执行实现：

1. 按订货宝现有接口文档，以 `getReceiptsList`/`getPaymentList` 作为正式同步来源，先落 Raw，再投影到 `order_fund_document`；
2. `order_fund_document` 按字段分开保存：我方资金单号、订货宝收付款单号、关联订单号、支付流水号、客户编号、客户名称、收支类型、支付方式、收入/支出方向、金额、银行账号、开户名称、开户行、提交/审核时间、附件 COS key/来源引用；
3. Portal 资金页按订货宝后台“收支明细”样式展示统一列表，但查询条件保持业务字段分开：收付款单号、关联单号、支付流水号、我方单号、客户名称、收支类型、支付方式、收支方向、状态、收支时间；
4. 订货宝后台“收支明细”作为验收账本人工核对，不作为自动同步主数据源，不通过页面抓取替代接口文档；
5. 对账比较字段固定为：单号、收支时间、客户编号、客户名称、收支类型、支付方式、收入金额、支出金额、关联单号；
6. `FR...` 对应收款单，`FP...` 对应付款单，`DH...` 对应销售订单；收入列映射 `RECEIPT`，支出列映射 `PAYMENT`；
7. 出现金额不一致、方向不一致、收支类型不一致、缺收款/付款单详情时，进入资金对账异常，不静默覆盖业务单据；
8. 后续如果订货宝接口文档新增正式收支明细接口，只新增 Raw 对象 `FUND_LEDGER_ENTRY` 和对账，不改变已落库的收款单/付款单业务口径。

## 调拨

订货宝正式开放接口没有调拨主单接口时，不能只靠调拨出库/调拨入库反推全部状态；但我方也不能把订货宝 `DB...` 当成业务主单。稳定方案是：

1. ERP 调拨业务仍按我方状态机、编号规则和库存规则生成自己的 `TR...` 调拨主单；
2. 订货宝后台调拨页只用于读取来源证据和缺口审计：`DB...`、明细、调出仓、调入仓、经办人、审核/状态；
3. `DB...` 只作为来源证据映射到我方 `TR...`，不能反向成为我方调拨主键或状态来源；
4. 如果后台调拨页可读，就用它覆盖“待出库”等没有 FH/RK 凭证的来源证据缺口；
5. `FH...` 是这张调拨的出库凭证，`RK...` 是这张调拨的入库凭证；
6. 出入库凭证补偿确认到同一张 `TR...`，不再各自生成调拨主单；
7. 订货宝来源 `TR...` 调拨单、对应调拨出库/入库凭证只展示来源和详情，不在 ERP 页面开放确认出库、确认入库、编辑或删除；
8. 如果后台调拨页不可读或登录态失效，同步状态必须是 `PARTIAL`，并记录 `DHB_TRANSFER_MASTER_SOURCE_UNAVAILABLE`；这时只能保证已有 FH/RK 凭证不漏，不能承诺覆盖订货宝所有待调拨主单。

## 验收 SQL 口径

每次同步后至少检查：

Integration Raw/Mapping/Dead Letter 使用 `DHB`；Order/ERP/CRM 等领域表展示/业务来源使用 `DINGHUOBAO`。

```sql
-- Raw 已落但没有全局映射
SELECT source_object_type, COUNT(*)
FROM rigour_integration.integration_raw_landing r
LEFT JOIN rigour_integration.integration_external_object_mapping m
  ON m.tenant_id = r.tenant_id
 AND m.connector_id = r.connector_id
 AND m.source_system = r.source_system
 AND m.source_object_type = r.source_object_type
 AND m.source_object_id = r.source_id
 AND m.mapping_status = 'ACTIVE'
WHERE r.source_system = 'DHB'
  AND m.id IS NULL
GROUP BY source_object_type;

-- 订单死信按原因分布
SELECT source_object_type, last_error_code, COUNT(*)
FROM rigour_integration.integration_dead_letter
WHERE source_system = 'DHB'
GROUP BY source_object_type, last_error_code
ORDER BY COUNT(*) DESC;

-- 全局映射重复
SELECT source_object_type, source_object_id, internal_domain, internal_object_type, COUNT(*)
FROM rigour_integration.integration_external_object_mapping
WHERE source_system = 'DHB'
  AND mapping_status = 'ACTIVE'
GROUP BY source_object_type, source_object_id, internal_domain, internal_object_type
HAVING COUNT(*) > 1;
```

## 失败处理

| 问题 | 处理 |
| --- | --- |
| 字典未映射 | 编排警告，展示具体来源值，补字典后重跑 |
| CRM/ERP 映射缺失 | 上游域重跑并重新发布映射，Order 不猜 |
| Integration mapping upsert 超时 | 客户端分批提交 |
| 订货宝调拨后台源不可用 | 编排 `PARTIAL`，保留死信和对账原因 |
| Raw 已落但投影失败 | 修复映射/字典/规则后按 Raw 补偿重放 |
| 订货宝来源删除 | 保留 Raw 和历史映射，领域对象按业务规则软删除或标记不可用 |
