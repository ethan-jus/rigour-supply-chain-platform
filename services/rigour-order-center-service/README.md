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
- 由 Order Center 内部定时任务编排 Integration 查询，并在本地业务表中完成幂等落库。
- 保存导入后的来源标识和必要来源快照，但不负责获取外部报文。

## 不负责什么

- 不调用订货宝、飞书或其他第三方 API。
- 不保存第三方账号、密码、API Key、Token，也不实现第三方重试、限流和分页。
- 不直接读取 `rigour_integration` 或其他 Schema。
- 不让 Portal 直接调用 Integration 执行接口；Portal 的立即同步按钮只能调用 Order Center，
  另有 Order Center 内部定时任务作为自动同步入口。

## 订货宝边界

```text
Portal 查询/立即同步 -> Gateway -> Order Center -> 本地查询表/业务落库 -> Portal
Order Center 定时任务 -> Integration内部目标发现
                  -> Integration查询/转换/Raw Landing -> Order Center业务幂等落库
                                                                  -> order_order等前端查询表
```

当前订货宝接口：

- `GET /api/v1/orders/dhb`：查询订单中心本地投影；
- `GET /api/v1/orders/dhb/shipments`：查询本地出库/发货投影；支持status、typeId、orderNo和时间筛选；
- `GET /api/v1/orders/dhb/shipment-logistics`：查询`getWaitShips`按订单落库的出库/发货物流快照；
- `GET /api/v1/orders/dhb/shipment-logistics/{orderNo}`：按订货宝订单号查询出库/发货物流详情；
- `GET /api/v1/orders/dhb/{orderSn}`：查询本地订单明细。
- `GET /api/v1/orders/dhb/shipments/{shipmentNo}`：查询本地出库/发货单及明细；
- `GET /api/v1/orders/dhb/returns`、`GET /api/v1/orders/dhb/returns/{returnNo}`：查询本地退货单及明细；
- `GET /api/v1/orders/dhb/receipts`、`GET /api/v1/orders/dhb/payments`：查询本地收款单、付款单。
- `POST /api/v1/orders/dhb/sync`：Portal无需传connectorId，Order Center按当前登录租户从Integration sync-targets自动解析唯一启用连接器，再调Integration后落本地库；请求体的`scope=ORDER`只拉订货单列表和详情，`scope=RETURN`只拉退货单列表和详情，`scope=SHIPMENT`只拉出库/发货单及详情，`scope=SHIPMENT_LOGISTICS`只落库物流快照，`scope=RECEIPT`只拉收款单，`scope=PAYMENT`只拉付款单，省略或`ALL`保持历史聚合同步行为；若当前租户没有或有多个启用连接器，则拒绝执行，避免误同步。
- `POST /api/v1/orders/dhb/sync/{connectorId}`：兼容旧调用方；Order Center仍会校验connectorId属于当前租户的启用任务，再调Integration后落本地库。

Order Center 不实现订货宝 `f/v` 协议，不保存外部 Secret/Token；通过 `integration-migration-api` 的版本化查询契约调用 Integration。当前工作区已确认可调用订单列表和订单详情；同步结果使用 `tenantId + sourceSystem + sourceOrderNo` 做幂等。

## 定时同步配置

默认关闭定时任务。由 DEV/Nacos 或进程配置注入全局策略；调度服务使用进程内稳定身份，租户、连接器和任务清单由 Integration 每次调度动态发现，不能填写订货宝账号密码：

```yaml
rigour:
  order:
    dhb:
      sync:
        enabled: true
        cron: "0 0/30 * * * ?"
        max-pages: 100
        overlap-minutes: 5
```

Order Center 通过未配置到 Gateway 的 `/internal/v1/integration/dhb/sync-targets` 发现
`enabled=1`、`object_type=ORDER` 且连接器为 `ACTIVE` 的目标。发现请求使用可信 `SERVICE`
身份；实际订单查询继续携带 Integration 返回的 `tenantId`，不模拟租户操作人，也不从 Nacos
维护租户 UUID 清单。

首次同步不带更新时间窗口；后续以 `order_dhb_sync_checkpoint.last_success_at - overlap-minutes`
作为起点，只有 Integration 查询成功且本地业务表全部落库成功后才推进游标。失败时保留上一次成功游标，下一次会重新读取重叠区间。

订单同步通过 Integration 调用订货宝 `getShipsList` 分页读取独立出库/发货单，并按 `includeDetails` 调用 `getShipsContent(ships_num)` 补齐主单和商品明细，交回 Order Center 后由本地幂等导入写入 `order_dhb_shipment` 及明细表；同时按订单调用 `getWaitShips(orders_num)`，将`shipped`和`wait_stock`按订单域批次交回并写入`order_dhb_shipment_logistics`及明细表。由于订货宝没有独立物流列表接口，物流页同步会先读取订单号作为索引，再逐单调用`getWaitShips`，但本批次只导入物流快照，不重复导入订单。Portal 的出库/发货、物流和订单结算页面只查询本地接口，手动同步分别使用专用范围；拉取成功后在同一轮导入事务中幂等落库。

Order Center 的接口访问日志记录方法、路径、查询参数、JSON请求体、租户、requestId、响应状态和耗时；token、sKey、签名、密码等敏感值统一脱敏，便于按 requestId 定位问题而不把凭据写入日志。

本地开发可通过 `V8__dhb_document_demo_data.sql` 查看出库单、发货单、退货单、收款单和付款单页面；演示数据使用固定开发租户，不写出站事件。

## 数据与代码位置

- Schema：`rigour_order`；订单中心是 `order_order`、`order_order_line`、`order_order_shipment` 的唯一写者。
- 增量游标：`order_dhb_sync_checkpoint`，状态为 `IDLE`、`SUCCEEDED` 或 `FAILED`，仅成功业务落库后推进。
- 出库/发货物流：`order_dhb_shipment_logistics`、`order_dhb_shipment_logistics_line`，按`tenant_id + source_system + order_no`幂等保存`getWaitShips`快照；明细类型为`SHIPPED`或`WAIT_STOCK`。
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
