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
- 对 Portal 和其他领域服务提供版本化的本地销售订单查询/导入契约。
- 接受 Integration 统一订货宝编排器的内部调用，并在本地业务表中完成幂等落库。
- 保存导入后的来源标识和必要来源快照，但不负责获取外部报文。

## 不负责什么

- 不调用订货宝、飞书或其他第三方 API。
- 不保存第三方账号、密码、API Key、Token，也不实现第三方重试、限流和分页。
- 不直接读取 `rigour_integration` 或其他 Schema。
- 不让 Portal 直接调用 Order Center 的订货宝内部同步接口；手动同步统一进入 Integration 的订货宝同步中心。
- 不保留模块内部定时入口；定时、手动、修复均由 Integration 统一编排。

## 订货宝边界

```text
Portal 查询 -> Gateway -> Order Center销售订单API -> 我方销售订单业务表 -> Portal
Portal 手动同步/系统定时 -> Gateway/Integration订货宝同步中心
                         -> Integration目标发现/Raw Landing/映射
                         -> Order Center内部同步接口
                         -> 我方销售订单业务表和同步审计
```

当前对外接口：

- `GET /api/v1/orders/sales-orders`：查询我方销售订单列表；
- `GET /api/v1/orders/sales-orders/{id}`：查询我方销售订单详情；
- `POST /api/v1/orders/sales-orders`：创建或更新我方销售订单；
- `POST /api/v1/orders/sales-orders/{id}/stock-out`：发起销售出库。

当前内部同步接口：

- `POST /internal/v1/orders/dhb/sync`：仅供 Integration 订货宝同步编排器调用；请求必须携带可信上下文、`connectorId` 和 `sourceTaskId`。Order Center 只负责把 Integration 已采集并映射后的来源批次幂等写入我方订单业务表和同步审计。

`mode=REPAIR`按`scope`执行无时间窗的完整来源重放并强制补拉详情；本地强制重建
来源管理的主表字段与子表，因此可修复“行数相同但字段被错改”；重建对象记入`changed`
（即repaired），且REPAIR永不推进增量checkpoint。
订货宝列表没有删除墓碑，本地不物理删除未重现的聚合。只有FULL/REPAIR完成整轮拉取、Raw Landing、
详情和本地持久化核对后，才将本轮未见单据标记为`SOURCE_ABSENT`；INCREMENTAL、失败页或核对失败均不得标记缺失。
后续同步再次见到该单据时恢复为`PRESENT`，所有状态变化与本轮成功核对账同事务提交。

Order Center 不实现订货宝 `f/v` 协议，不保存外部 Secret/Token；通过 `integration-migration-api` 的版本化查询契约调用 Integration。当前工作区已确认可调用订单列表和订单详情；同步结果使用 `tenantId + sourceSystem + sourceOrderNo` 做幂等。

## 同步窗口配置

Order Center 不再持有定时配置、订货宝游标或订货宝镜像表。定时频率、手动触发、修复触发、最大页数、Raw Landing、来源覆盖和对账证据统一由 Integration 订货宝同步编排器控制。Order Center 只接收 Integration 已映射好的销售订单业务批次。

```yaml
rigour:
  integration:
    dhb:
      orchestration:
        enabled: true
```

首次同步、增量窗口、重叠区间、FULL 对账和失败重试都在 Integration 侧记录和推进；Order Center 不再维护本地订货宝游标。

订单同步通过 Integration 读取订货宝订单、发货、退货、收付款和物流快照；每个请求经由 Integration 限流、重试并完成 Raw Landing。Order Center 收到领域批次后只做本地事务性幂等落库、销售订单状态映射和销售出库联动，Portal 不再展示订货宝镜像列表。

Order Center 的接口访问日志记录方法、路径、查询参数、JSON请求体、租户、requestId、响应状态和耗时；token、sKey、签名、密码等敏感值统一脱敏，便于按 requestId 定位问题而不把凭据写入日志。

历史演示迁移只作为既有 Flyway 历史保留；新的业务验收以我方销售订单页面和订货宝同步中心核对账为准。

## 数据与代码位置

- Schema：`rigour_order`；订单中心是 `order_sales_order`、`order_sales_order_line`、`order_sales_order_payment` 的唯一写者。
- 来源映射和同步对账：由 Integration 的 Raw、外部对象映射和编排运行记录保存，Order Center 不再维护旧订货宝 checkpoint/run/reconciliation 表。
- 出库联动：Order 通过 `OrderSalesOrderService` 调用 ERP 销售出库接口，ERP 在 `erp_stock_out_order` 和 `erp_stock_out_order_line` 保存我方出库单。
- 主要设施：订货宝同步由 Integration 编排器拉取、Raw 落库和映射，Order 仅通过 `OrderSalesOrderService` / `MybatisPlusSalesOrderRepository` 维护我方销售订单业务表。

## 依赖与调用方向

- 允许依赖：自己的 `order-center-api`、平台 shared 模块和经过评审的消息/API 客户端。
- 禁止依赖：`integration-migration-service` 实现模块、其他领域服务实现模块、跨库 SQL。
- 跨服务协作使用 `order-center-api` 的版本化契约、内部事件或受控本地投影。

## 启动与验证

```bash
./mvnw -B -pl services/rigour-order-center-service/order-center-service -am test
```

完整后端验证使用 `./mvnw -B verify`。数据库和 DEV 配置在根 `docs/DOMAIN_DATABASE_RUNTIME.md` 以及本服务 `application-*.yml` 中维护；不要复制 Integration 的第三方配置。
