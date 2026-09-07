# 订货宝只读查询接口审计

审计日期：2026-08-25

## 结论

本轮订货宝只作为外部来源系统，Integration 只调用查询接口做 Raw Landing、来源映射和对账。
CRM、ERP、Order、Settings 的业务主权仍在本平台，自研新增、编辑、作废、审核等能力继续保留，
但不得反向写订货宝。

`DhbClientAdapter` 已设置只读业务函数白名单。未纳入白名单的订货宝业务函数会被拒绝，尤其是
`write...DownloadStatus`、`add...`、`approve...`、`cancel...`、`confirm...`、`sync...`
这类可能改变订货宝数据或状态的接口。

## 查询白名单

| 业务域 | 订货宝函数 | 分页方式 | 业务主键 | 详情接口 | 同步注意点 |
| --- | --- | --- | --- | --- | --- |
| 商品 | `getGoodsList` | `begin/step` | `guid`、`coding` | 无 | 全量必须显式传 `status=C`、`putaway=A`，否则会漏停用或下架商品；SKU 在 `multi` 中 |
| 商品分类 | `getSite` | 全量 | `SiteID` | 无 | 不伪造父子树，先按平铺分类落 ERP |
| 商品品牌 | `getBrands` | 全量 | `brandID` | 无 | 结构化品牌编码/名称，原始扩展字段保留 Raw |
| 商品规格 | `getMultiOptionsList` | `begin/step` | `multiID` | 无 | 父规格和规格值都要来源映射，供 SKU 关系复用 |
| 商品标签 | `getGoodsTag` | `page/page_size` | `tag_id` | 无 | 字段版本差异较大，结构化常用字段，其余保留 Raw |
| 仓库 | `getStockInfo` | `page/page_size` | `stock_id`、`stock_guid`、`stock_num` | 无 | ERP 出入库和库存必须先查仓库来源映射 |
| 库存 | `batchGetStock` | 非全量分页 | `goods_num + stock_num` | 无 | 只能基于已落库商品编码分批查，不作为全量发现入口 |
| 客户类型 | `getClientTypeList` | 全量 | 类型 ID | 无 | 落 Settings/CRM 字典或 CRM 类型表，Portal 筛选走业务值 |
| 归属地区 | `getArea` | 全量 | 地区 ID | 无 | 落 CRM 地区表，客户归属地区用映射关联 |
| 客户 | `getDealersList` | `begin/step` | 客户编号、GUID | 无 | 结构化客户编号、名称、联系人、电话、状态、类型、地区、归属员工 |
| 收货地址 | `getShippingAddressList` | `begin/step` | 地址 ID、客户 GUID | 无 | 默认地址和地址簿落 CRM 地址业务模型，列表不展示来源编码 |
| 员工 | `getStaffList` | `begin/step` | `accounts_id` | `getStaffInfo` | 归属销售、采购员、制单人先查员工映射；未命中进对账 |
| 订单 | `getOrderList` | `begin/step` | `OrderSN` | `getOrderContent` | 详情固定 `isAutoSign=2/isAutoAudit=2`，不改下载或审核状态 |
| 出库 | `getShipsList` | `page/page_size` | `ships_num` | `getShipsContent` | 销售出库关联 Order 发货；调拨出库才反推调拨单；其他类型只落 ERP 出库或待确认 |
| 待发货 | `getWaitShips` | 无分页 | `orders_num` | 无 | 只作订单发货快照和对账证据，不替代 ERP 出库主表 |
| 退货 | `getReturnsList` | `begin/step` | `ReturnsSN` | `getReturnsContent` | 退货状态原值进入字典映射，金额/明细落 Order 退货/退款业务模型 |
| 收款 | `getReceiptsList` | `begin/step` | `ReceiptsNum` | 无 | 支持 `updateDateGe`，可做重叠窗口增量 |
| 付款 | `getPaymentList` | `begin/step` | `PaymentNum` | 无 | 官方无更新时间筛选，必须做周期性全量或重叠交易时间对账 |
| 供应商 | `getSupplierList` | `begin/step` | 供应商 GUID、编码 | 无 | 采购、入库、采购退货必须先查供应商映射 |
| 采购 | `getPurchaseList` | `page/page_size` | `purchase_num` | `getPurchaseContent` | 供应商、仓库、员工、商品/SKU 未映射时进死信 |
| 采购退货 | `getPurchaseReturnList` | `page/page_size` | `purchase_num` | `getPurchaseReturnContent` | 不把采购退货误投为销售退货 |
| 入库 | `getWarehousingList` | `page/page_size` | 入库单号 | `getWarehousingContent` | 采购关联和仓库映射缺失必须对账 |

## 字段落库口径

- 结构化业务字段：客户、商品、SKU、仓库、供应商、订单、出入库、资金等业务人员直接使用的字段。
- 来源映射字段：统一进入 `integration_external_object_mapping`、`erp_master_source_binding`、`crm_source_binding` 等来源绑定表，不在业务列表展示。
- Raw/JSON 证据字段：接口扩展字段、暂不展示字段和字段版本差异全部保留 Raw Landing 或领域 `attributes_json`。
- 暂不使用字段：营销副标题、`field_1..field_6`、联营扩展等先不驱动业务逻辑，等待真实 Raw 对账和业务确认。

## 重同步前必须优先修复

- 单位原始值必须映射到本平台单位字典；未知单位进入死信/对账，不写脏主表。
- 客户、商品、SKU、仓库、供应商、员工、客户类型、归属地区必须先查来源映射；未命中不静默创建错误关系。
- 销售出库和调拨出库按出库类型分开投影；只有调拨出库反推调拨单。
- 来源单号唯一性要覆盖租户、连接器、来源系统和来源单号，避免多连接器或重跑时误合并或重复。
- Portal 列表只展示业务友好字段；来源编码、Raw 字段和对账细节放到详情或同步诊断入口。
