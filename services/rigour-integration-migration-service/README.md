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
| 第三方协议 | [`docs/DINGHUOBAO_API_CONTRACT.md`](../../docs/DINGHUOBAO_API_CONTRACT.md) |

## 负责什么

- 外部系统连接器和供应商协议适配。
- Secret 引用解析、认证、超时、重试、限流、分页和错误码转换。
- Raw Landing、同步任务/批次、增量游标、死信、对账和归一化事件。
- 订货宝、飞书等外部系统的调用隔离；下游领域服务只接收内部 API/事件。

订货宝唯一外部适配器是 `DinghuobaoClientAdapter`。账号、密码、API Key 和 Token 不能进入业务服务、Portal、数据库明文或日志。

## 不负责什么

- 不拥有订单中心的内部订单主状态、履约状态或库存状态。
- 不提供员工浏览器登录，不把第三方账号密码当作 Portal 登录凭据。
- 不让 Portal 直接调用订货宝，也不把第三方管理端卡片当作同步接口。
- 不读取或写入 IAM、订单、ERP 等其他 Schema。

## 入口与契约

| 接口 | 用途 |
|---|---|
| `GET/POST/PUT /api/v1/integration/dinghuobao/connectors` | 租户级连接配置 |
| `POST /api/v1/integration/dinghuobao/connectors/{id}/test` | 使用 Secret 做连接测试，不返回 Token |
| `GET/POST/PUT /api/v1/integration/dinghuobao/sync-tasks` | 同步任务控制面 |
| `GET /api/v1/integration/dinghuobao/order-mirrors` | 订货宝订单镜像查询 |
| `GET /api/v1/integration/dinghuobao/sync-logs` | 同步诊断日志 |

这些接口必须经过 Gateway，并使用可信 `tenantId` 与 Integration 权限。同步完成后，订单中心通过内部导入契约或事件接收数据；该契约不得携带外部凭据。

## 数据与 Secret

- Schema：`rigour_integration`；Flyway 由 `integration-migrator` 账号执行，应用使用 `integration-app` 账号。
- 主要表：`integration_dinghuobao_connector`、`integration_sync_task`、`integration_raw_landing`、`integration_sync_run`、`integration_sync_checkpoint`、`integration_dead_letter`、`integration_outbox_event`。
- `auth_secret_ref` 只保存如 `env://RIGOUR_DHB_DEV` 的引用；Secret Resolver 只存在于 Integration。
- 连接测试和同步日志只返回稳定错误码、批次、耗时和脱敏消息。

## 代码位置

```text
integration-migration-api/       # 可供跨服务依赖的版本化契约
integration-migration-service/
├── api/controller/              # 入站 HTTP
├── application/                 # 同步用例和出站端口
└── infrastructure/
    ├── dinghuobao/              # 订货宝唯一外部适配器
    └── persistence/             # rigour_integration 持久化
```

新增供应商时先搜索现有连接器和 Secret Resolver；不能在订单、ERP 或 Portal 中复制 Provider。

## 启动与验证

```bash
SPRING_PROFILES_ACTIVE=dev,local \
./mvnw -f services/rigour-integration-migration-service/integration-migration-service/pom.xml \
  spring-boot:run
```

健康检查：`http://localhost:26882/actuator/health`。完整数据库、Nacos 和共享 DEV 步骤见 [`INTEGRATION_DATABASE_RUNTIME.md`](../../docs/INTEGRATION_DATABASE_RUNTIME.md)。

当前已实现客户端适配器和控制面；正式同步 Worker 必须等待订货宝正式 API 合同、限流规则和增量方案确认，不得用测试账号或猜测 URL 冒充真实联调。
