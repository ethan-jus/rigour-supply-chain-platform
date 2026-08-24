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
- `SalesOrder` 是平台内部销售订单模型，内部流程只使用我方订单状态、收款状态和出库状态。
- 订货宝状态只在 Integration/内部同步中映射为我方状态，不作为业务页面字段展示。
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

## 4. 内部订单状态

| 状态 | 含义 |
|---|---|
| `RECEIVED` | 已接收外部订单，尚未进入内部流程 |
| `PENDING_CONFIRMATION` | 待确认 |
| `ALLOCATING` | 库存分配中 |
| `SHIPPED` | 已发货 |
| `COMPLETED` | 已完成 |
| `CANCELLED` | 已取消 |
| `EXCEPTION` | 来源状态无法识别或数据异常 |

订货宝状态只做初始映射：

| 订货宝状态 | 内部初始状态 |
|---|---|
| `pricing`、`pending` | `PENDING_CONFIRMATION` |
| `stock_up` | `ALLOCATING` |
| `shipped` | `SHIPPED` |
| `received`、`finished`、`forcedone` | `COMPLETED` |
| `cancelled` | `CANCELLED` |
| 其他或空值 | `EXCEPTION` 或 `RECEIVED` |

后续订货宝同步只通过映射规则更新我方允许来源接管的字段，不覆盖已经由自研订单流程维护的人工字段和状态机。

## 5. 领域事件

当前订单到 ERP 的协作通过受控服务接口完成：销售订单确认出库时，Order 调用 ERP 销售出库能力生成 `erp_stock_out_order`。如后续需要领域事件，事件载荷只允许包含业务 ID、业务编码和状态，不包含手机号、地址、Secret、Token 或原始报文。

## 6. 后续扩展约束

1. 新增自研下单流程时，直接创建 `order_sales_order`，不伪造订货宝报文。
2. 新增其他外部来源时，只新增对应的 Translator 和 Provider Adapter，不修改内部订单流程。
3. 订货宝写操作暂不开放；新增、修改、审核、发货需要另行定义内部订单状态机和权限。
4. 外部凭据只通过Secret注入，不能写入数据库、日志和API响应。

## 7. Integration与订单中心的后续契约

Integration 到订单中心的导入契约只允许服务间可信上下文调用。命令携带租户、连接器、来源任务和已映射业务数据，绝不能携带订货宝账号、密码、API Key 或 Token；Secret Resolver 只能存在于 Integration。该契约不得被 Gateway 作为浏览器菜单接口暴露。
