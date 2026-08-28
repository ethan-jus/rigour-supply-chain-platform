# 订单中心与订货宝接入说明

## 1. 接口边界

订货宝官方接口、本平台 Integration 接口和订单中心查询接口是三层边界。第三方协议、凭据、重试和限流只允许存在于 Integration；订单中心不直接访问订货宝。

| 类型 | 接口 | 作用 |
|---|---|---|
| Integration 官方适配器 | `getTokenValue`、`getOrderList`、`getOrderContent`、`getShipsList/getShipsContent`、`getWaitShips` | 认证并同步订货宝原始数据，凭据只从Secret引用读取 |
| 订单中心平台接口 | `GET /api/v1/orders/sales-orders` | 查询我方销售订单列表 |
| 订单中心平台接口 | `GET /api/v1/orders/sales-orders/{id}` | 查询我方销售订单详情 |
| 订单中心内部接口 | `POST /internal/v1/orders/dhb/sync` | 接收 Integration 已采集、已映射的订货宝销售订单批次 |

Portal 只调用订单中心的销售订单业务接口。手动同步、定时同步和修复同步统一进入 Integration 订货宝同步中心；Order Center 不再编排订货宝同步，也不暴露浏览器可调用的模块级同步接口。

## 2. 分层职责

```text
Integration
  -> DhbClientAdapter   订货宝HTTP协议、Token、超时、重试、限流和脱敏
  -> Raw Landing / 同步批次
  -> 订单中心内部导入契约
       -> OrderSalesOrderService / MybatisPlusSalesOrderRepository
       -> order_sales_order / order_sales_order_line / order_sales_order_payment

Portal -> Gateway -> order-center-service -> 本地订单投影
```

- `DhbClientAdapter` 是订货宝唯一的外部适配器，位于 Integration。
- 物流查询使用订货宝 `getWaitShips`，入参为订单号 `orders_num`；返回 `shipped` 已出库/已发货记录和
  `wait_stock` 待出库明细。该调用发生在 Integration，订单中心只接收已归一化的物流数据。
- 订单中心只拥有内部订单模型和查询/导入持久化边界，不读取第三方凭据。
- `SalesOrder` 是平台内部销售订单模型，内部流程只使用我方订单状态、收款状态、出库状态和发货状态。
- 订货宝状态作为 `source_status_code` 展示、筛选和对账，不直接作为我方人工流程动作。
- 订单中心是 `order_sales_order`、`order_sales_order_line`、`order_sales_order_payment` 的唯一写入服务；ERP、库存、客户和 BI 通过服务接口消费业务数据。

## 3. 内部数据表

| 表 | 说明 |
|---|---|
| `order_sales_order` | 我方销售订单主表；保存订单号、客户快照、归属销售人员编码、金额和状态 |
| `order_sales_order_line` | 我方销售订单商品明细；保存商品编码、规格、数量和价格快照 |
| `order_sales_order_payment` | 我方销售订单收款记录 |
| Integration Raw / 外部映射 / 编排运行记录 | 保存订货宝来源报文、外部 ID 绑定、同步批次和对账证据 |

销售订单业务幂等键为：

```text
tenant_id + order_no
```

明细幂等键为：

```text
sales_order_id + line_no
```

订货宝原始报文不再放在 Order Center；来源报文、来源覆盖、外部 ID 绑定和同步对账证据由 Integration 统一保存。Order V17 会删除旧 Order 内部订货宝镜像、导入和审计表。

## 4. 订单状态边界

Order Center 不把订货宝订单状态直接折叠成我方业务状态。当前销售订单有四条状态线：

| 状态线 | 字段/枚举 | 用途 |
|---|---|---|
| 我方订单主状态 | `order_status_code` / `SalesOrderStatus` | 只表达草稿、提交、取消等我方人工流程节点 |
| 我方出库状态 | `outbound_status_code` / `SalesOrderOutboundStatus` | 表达待出库、部分出库、已出库 |
| 我方发货状态 | `order_sales_shipment.status_code` / `SalesShipmentStatus` | 表达发货单创建、已发货、已签收、取消 |
| 订货宝来源状态 | `source_status_code` / `DHB_ORDER_STATUS` | 保留订货宝原始状态，用于展示、筛选和对账 |

订货宝同步只能按来源状态设置 `source_status_code`，以及在创建新单时根据来源状态决定是否直接生成已提交订单。同步不能把 `待发货`、`待收货`、`已收货`、`已完成`、`强制完成` 直接覆盖到我方订单主状态，否则会绕过我方出库、发货、签收等人工动作。

订货宝来源状态为取消时，Integration 通过 `POST /api/v1/orders/sales/{id}/source-cancellations` 做来源取消投影；普通用户仍只能走 `POST /api/v1/orders/sales/{id}/cancellations`，并继续受我方人工取消规则约束。取消订单会把 `payment_status_code` 置为 `CANCELLED`，并关闭 `unpaid_amount`，避免取消单继续进入待收款口径。

后续如果要让我方销售订单形成“完成/强制完成”闭环，应在我方状态机里新增明确动作和触发条件，例如“出库已完成 + 发货已签收 + 收款已结清”后自动完成，或由有权限人员强制完成。这个规则属于我方业务流程，不由订货宝来源状态单独决定。

## 5. 领域事件

当前订单到 ERP 的协作通过受控服务接口完成：销售订单确认出库时，Order 调用 ERP 销售出库能力生成 `erp_stock_out_order`。如后续需要领域事件，事件载荷只允许包含业务 ID、业务编码和状态，不包含手机号、地址、Secret、Token 或原始报文。

## 6. 后续扩展约束

1. 新增自研下单流程时，直接创建 `order_sales_order`，不伪造订货宝报文。
2. 新增其他外部来源时，只新增对应的 Translator 和 Provider Adapter，不修改内部订单流程。
3. 订货宝写操作暂不开放；新增、修改、审核、发货需要另行定义内部订单状态机和权限。
4. 外部凭据只通过Secret注入，不能写入数据库、日志和API响应。

## 7. Integration与订单中心的后续契约

Integration 到订单中心的导入契约只允许服务间可信上下文调用。命令携带租户、连接器、来源任务和已映射业务数据，绝不能携带订货宝账号、密码、API Key 或 Token；Secret Resolver 只能存在于 Integration。该契约不得被 Gateway 作为浏览器菜单接口暴露。
