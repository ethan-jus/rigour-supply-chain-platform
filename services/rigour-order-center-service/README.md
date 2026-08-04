# 订单中心服务

> 平台级协同规则见 [`../../docs/TEAM_DEVELOPMENT_GUIDE.md`](../../docs/TEAM_DEVELOPMENT_GUIDE.md)，完整边界见 [`../../docs/SERVICE_BOUNDARIES.md`](../../docs/SERVICE_BOUNDARIES.md)。

## 服务卡片

| 项目 | 内容 |
|---|---|
| Spring 应用名 | `rigour-order-center-service` |
| 端口 | `26885` |
| Schema | `rigour_order` |
| 数据主写者 | Order Center |
| 接入说明 | [`docs/ORDER_CENTER_DHB_INTEGRATION.md`](../../docs/ORDER_CENTER_DHB_INTEGRATION.md) |

## 负责什么

- 内部订单、订单明细、发货、售后、应收和回款核销的业务主权。
- 内部订单状态机、订单幂等、事务落库和订单领域事件。
- 对 Portal 和其他领域服务提供版本化的本地订单查询/导入契约。
- 保存导入后的来源标识和必要来源快照，但不负责获取外部报文。

## 不负责什么

- 不调用订货宝、飞书或其他第三方 API。
- 不保存第三方账号、密码、API Key、Token，也不实现第三方重试、限流和分页。
- 不直接读取 `rigour_integration` 或其他 Schema。
- 不向 Portal 暴露“直连第三方”的同步按钮；同步由 Integration 的任务负责。

## 订货宝边界

```text
Integration -> 内部导入契约/事件 -> Order Center -> order_order 本地投影
Portal -> Gateway -> Order Center -> 只读订单接口
```

当前订货宝接口：

- `GET /api/v1/orders/dhb`：查询订单中心本地投影；
- `GET /api/v1/orders/dhb/{orderSn}`：查询本地订单明细。

Order Center 不得新增 `DhbClient` 或同步实现。外部协议、Secret、Raw Landing 和同步批次都属于 Integration；内部导入时使用 `tenantId + sourceSystem + sourceOrderNo` 做幂等。

## 数据与代码位置

- Schema：`rigour_order`；订单中心是 `order_order`、`order_order_line`、`order_order_shipment` 的唯一写者。
- 主要设施：`DhbOrderService` 只查询本地投影；`MybatisPlusOrderRepository` 负责本服务持久化。
- `order_source_record` 只保留导入后的来源快照，不替代 Integration 的 Raw Landing。
- `order_outbox_event` 与订单写入使用同一服务、同一数据库和同一本地事务。

## 依赖与调用方向

- 允许依赖：自己的 `order-center-api`、平台 shared 模块和经过评审的消息/API 客户端。
- 禁止依赖：`integration-migration-service` 实现模块、其他领域服务实现模块、跨库 SQL。
- 跨服务协作使用 `order-center-api` 的版本化契约、内部事件或受控本地投影。

## 启动与验证

```bash
./mvnw -B -pl services/rigour-order-center-service/order-center-service -am test
```

完整后端验证使用 `./mvnw -B verify`。数据库和 DEV 配置在根 `docs/DOMAIN_DATABASE_RUNTIME.md` 以及本服务 `application-*.yml` 中维护；不要复制 Integration 的第三方配置。
