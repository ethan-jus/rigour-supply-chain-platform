# 订单中心与订货宝接入说明

## 1. 接口边界

订货宝官方接口、本平台 Integration 接口和订单中心查询接口是三层边界。第三方协议、凭据、重试和限流只允许存在于 Integration；订单中心不直接访问订货宝。

| 类型 | 接口 | 作用 |
|---|---|---|
| Integration 官方适配器 | `getTokenValue`、`getOrderList`、`getOrderContent` | 认证并同步订货宝原始数据，凭据只从Secret引用读取 |
| 订单中心平台接口 | `GET /api/v1/orders/dhb` | 查询订单中心本地订单投影 |
| 订单中心平台接口 | `GET /api/v1/orders/dhb/{orderSn}` | 查询本地订单明细投影 |

Portal只调用订单中心的本地查询接口。订货宝同步由 Integration 的同步任务负责，完成后通过内部导入端口或事件写入订单中心；当前提交不再在订单中心暴露手工同步接口，避免同一第三方能力出现两套实现。

## 2. 分层职责

```text
Integration
  -> DhbClientAdapter   订货宝HTTP协议、Token、超时、重试、限流和脱敏
  -> Raw Landing / 同步批次
  -> 订单中心内部导入契约或事件
       -> InternalOrderRepository  MyBatis-Plus事务落库
       -> order_outbox_event        事务内记录内部领域事件

Portal -> Gateway -> order-center-service -> 本地订单投影
```

- `DhbClientAdapter` 是订货宝唯一的外部适配器，位于 Integration。
- 订单中心只拥有内部订单模型和查询/导入持久化边界，不读取第三方凭据。
- `Order` 是平台内部订单模型，内部流程只使用 `internalStatus`。
- `sourceStatus` 保留订货宝原始状态，便于追溯和重新映射。
- 订单中心是 `order_order` 的唯一写入服务；ERP、库存、客户和BI通过内部事件或服务接口消费。

## 3. 内部数据表

| 表 | 说明 |
|---|---|
| `order_order` | 内部订单主表；保存内部状态、来源标识、金额、客户和收货快照 |
| `order_order_line` | 内部订单明细；保存来源明细、SKU、商品和数量 |
| `order_order_shipment` | 内部订单发货信息；一期只保存订货宝来源快照 |
| `order_source_record` | 不可变的订货宝列表/明细原始JSON及SHA-256 |
| `order_sync_run` | 历史同步批次表；新同步批次由Integration作为主记录 |
| `order_outbox_event` | 与订单写入同事务的领域事件，供后续投递器使用 |

内部订单幂等键为：

```text
tenant_id + source_system + source_order_no
```

明细幂等键为：

```text
order_id + source_line_id
```

订货宝原始报文不再放在内部订单主表中，而是进入 `order_source_record`。V2迁移会把V1已有的 `dhb_*` 历史数据迁移到新模型，历史数据不会重复发布Outbox事件。

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

后续订货宝同步只更新 `source_*` 字段，不覆盖已经由自研订单流程维护的 `internal_status`。

## 5. 领域事件

当前定义事件：

- `ORDER_IMPORTED`：外部订单首次转换为内部订单；
- `ORDER_SOURCE_UPDATED`：外部订单来源事实发生变化。

事件载荷只包含订单ID、订单号、来源系统、来源订单号和状态，不包含手机号、地址、Secret、Token或原始报文。ERP、库存、客户和BI收到事件后，应通过 `orderId` 获取允许使用的订单数据并建立自己的投影。

## 6. 后续扩展约束

1. 新增自研下单流程时，直接创建 `order_order`，不伪造订货宝报文。
2. 新增其他外部来源时，只新增对应的 Translator 和 Provider Adapter，不修改内部订单流程。
3. 订货宝写操作暂不开放；新增、修改、审核、发货需要另行定义内部订单状态机和权限。
4. 外部凭据只通过Secret注入，不能写入数据库、日志和API响应。

## 7. Integration与订单中心的后续契约

Integration到订单中心的导入契约需要在服务间可信上下文和幂等策略确定后单独实现。命令只能携带租户、连接器、批次和分页/游标信息，绝不能携带订货宝账号、密码、API Key或Token；Secret Resolver只能存在于 Integration。该契约不应被Gateway作为浏览器菜单接口暴露。
