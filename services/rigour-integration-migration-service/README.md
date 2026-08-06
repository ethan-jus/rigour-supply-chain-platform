# Integration 集成迁移服务

> 平台级协同规则见 [`../../docs/TEAM_DEVELOPMENT_GUIDE.md`](../../docs/TEAM_DEVELOPMENT_GUIDE.md)，完整边界见 [`../../docs/SERVICE_BOUNDARIES.md`](../../docs/SERVICE_BOUNDARIES.md)。

## 服务卡片

| 项目 | 内容 |
|---|---|
| Spring 应用名 | `rigour-integration-migration-service` |
| 端口 | `26882` |
| Schema | `rigour_integration` |
| 数据主写者 | Integration |
| 运行手册 | [`docs/INTEGRATION_DATABASE_RUNTIME.md`](../../docs/INTEGRATION_DATABASE_RUNTIME.md) |
| 第三方协议 | [`docs/DHB_API_CONTRACT.md`](../../docs/DHB_API_CONTRACT.md) |

## 负责什么

- 外部系统连接器和供应商协议适配。
- Secret 引用解析、认证、超时、重试、限流、分页和错误码转换。
- Raw Landing、同步任务/批次、增量游标、死信、对账和归一化事件。
- 订货宝、飞书等外部系统的调用隔离；下游领域服务只接收内部 API/事件。

订货宝唯一外部适配器是 `DhbClientAdapter`。账号、密码、API Key 和 Token 不能进入业务服务、Portal、数据库明文或日志。

## 不负责什么

- 不拥有订单中心的内部订单主状态、履约状态或库存状态。
- 不提供员工浏览器登录，不把第三方账号密码当作 Portal 登录凭据。
- 不让 Portal 直接调用订货宝，也不把第三方管理端卡片当作同步接口。
- 不读取或写入 IAM、订单、ERP 等其他 Schema。

## 入口与契约

| 接口 | 用途 |
|---|---|
| `GET/POST/PUT /api/v1/integration/dhb/connectors` | 租户级连接配置 |
| `POST /api/v1/integration/dhb/connectors/{id}/test` | 使用 Secret 做连接测试，不返回 Token |
| `GET/POST/PUT /api/v1/integration/dhb/sync-tasks` | 同步任务控制面 |
| `POST /api/v1/integration/dhb/products/{connectorId}/query` | 商品域：查询订货宝商品 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/query` | 订单域：查询订货宝订单列表 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/content` | 订单域：查询订单明细 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/shipments/query` | 订单域：调用 `getShipsList` 查询出库/发货单列表 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/shipments/{shipmentNumber}/content` | 订单域：调用 `getShipsContent` 查询出库/发货单详情 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/wait-ships` | 订单域：调用 `getWaitShips` 查询指定订单物流快照 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/returns/query` | 订单域：调用 `getReturnsList` 查询退货单列表 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/returns/{returnNumber}/content` | 订单域：调用 `getReturnsContent` 查询退货单明细 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/receipts/query` | 订单域：调用 `getReceiptsList` 查询收款单列表 |
| `POST /api/v1/integration/dhb/orders/{connectorId}/payments/query` | 订单域：调用 `getPaymentList` 查询付款单列表 |
| `POST /api/v1/integration/dhb/orders/sync-tasks/{taskId}/run` | 订单域：手动执行订单拉取 |
| `GET /api/v1/integration/dhb/orders/mirrors` | 订单域：查询订单镜像 |
| `GET /api/v1/integration/dhb/sync-logs` | 同步诊断日志 |

这些接口必须经过 Gateway，并使用可信 `tenantId` 与 Integration 权限。同步完成后，订单中心通过内部导入契约或事件接收数据；该契约不得携带外部凭据。

## 数据与 Secret

- Schema：`rigour_integration`；Flyway 由 `integration-migrator` 账号执行，应用使用 `integration-app` 账号。
- 主要表：`integration_dhb_connector`、`integration_sync_task`、`integration_raw_landing`、`integration_sync_run`、`integration_sync_checkpoint`、`integration_dead_letter`、`integration_outbox_event`。
- `auth_secret_ref` 只保存如 `env://RIGOUR_DHB_DEV` 的引用；Secret Resolver 只存在于 Integration。
- 连接测试和同步日志只返回稳定错误码、批次、耗时和脱敏消息。

## 代码位置

```text
integration-migration-api/       # 可供跨服务依赖的版本化契约
integration-migration-service/
├── api/controller/              # 入站 HTTP
├── application/                 # 同步用例和出站端口
└── infrastructure/
    ├── dhb/              # 订货宝唯一外部适配器
    └── persistence/             # rigour_integration 持久化
```

新增供应商时先搜索现有连接器和 Secret Resolver；不能在订单、ERP 或 Portal 中复制 Provider。

跨服务调用方依赖 `integration-migration-api` 中的
`com.rigour.integration.api.v1.DhbIntegrationApi`、`DhbProductApi`、`DhbOrderApi` 和
`com.rigour.integration.api.v1.model.DhbApiModels`。该 API 模块只包含本平台契约，
不包含账号、密码、Token、Secret Resolver 或第三方 HTTP 实现。

调用方向为 `ERP/Order Center -> Gateway/Integration -> 订货宝`；调用方只使用 API 模块的 DTO
和 HTTP 路径，不读取 `rigour_integration` 表。当前契约支持带可信租户/用户上下文的查询和手动任务
触发；面向定时任务的独立服务身份及 Order Center/ERP 自动落库编排仍属于后续跨服务契约，不在
Integration 中复制业务主表。

## 启动与验证

```bash
SPRING_PROFILES_ACTIVE=dev,local \
./mvnw -f services/rigour-integration-migration-service/integration-migration-service/pom.xml \
  spring-boot:run
```

健康检查：`http://localhost:26882/actuator/health`。完整数据库、Nacos 和共享 DEV 步骤见 [`INTEGRATION_DATABASE_RUNTIME.md`](../../docs/INTEGRATION_DATABASE_RUNTIME.md)。

当前已实现客户端适配器、连接测试控制面和手动 `ORDER_PULL` Worker。Worker 使用连接器配置的
完整 API URL，读取 `env://` Secret 引用，按供应商更新时间窗口分页拉取订单，先写 Raw Landing，
再写订单镜像和 Outbox；重复 payload 幂等跳过，成功后推进 checkpoint。

本机不在 `application-local.yml` 声明凭据；真实值只通过 IDEA Run Configuration 或进程环境注入：

```text
RIGOUR_DHB_DEV_SERIAL_NUMBER=<订货宝接口账号>
RIGOUR_DHB_DEV_PASSWORD=<订货宝接口密码>
```

手动执行入口：

```text
POST /api/v1/integration/dhb/orders/sync-tasks/{taskId}/run
Body: {"from":"2026-08-04T00:00:00Z","to":"2026-08-04T01:00:00Z","pageSize":100}
```

定时 Worker、商品落库同步、客户/仓库/员工目录、订单明细自动拉取、死信重放、Outbox 消费和 Order Center 内部导入契约仍未完成；不得用测试账号
或猜测字段冒充真实联调。
