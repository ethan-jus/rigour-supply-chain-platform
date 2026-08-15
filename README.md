# Rigour Supply Chain Platform

Java 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2 的领域化服务骨架。

本仓库已实现 IAM OIDC、Gateway 资源服务器、配置化基础管理、Integration 连接器基础能力、订单中心本地投影，以及 Sales Work V1 领域库表骨架；尚未进入业务实现的领域服务仍是可编译骨架。自动构建通过不表示共享 DEV、浏览器或生产环境已验收。

协同开发先阅读 [`docs/TEAM_DEVELOPMENT_GUIDE.md`](docs/TEAM_DEVELOPMENT_GUIDE.md)；服务职责和 Schema 所有权以 [`docs/SERVICE_BOUNDARIES.md`](docs/SERVICE_BOUNDARIES.md) 为准。Integration 与订单中心的具体边界见各自的 [`README.md`](services/rigour-integration-migration-service/README.md) 和 [`README.md`](services/rigour-order-center-service/README.md)。

## 工程结构

```text
platform/
├── rigour-platform-bom/          # 内部库版本清单
├── rigour-platform-starter/      # 所有 HTTP 服务的最小公共基线
└── rigour-architecture-tests/    # reactor 与服务依赖边界测试
shared/
├── rigour-shared-context/        # request、签名调用人、tenant上下文与清理
├── rigour-shared-core/           # 统一响应、错误和分页
├── rigour-shared-logging/        # HTTP 访问日志
├── rigour-shared-audit/          # 可选：审计事件与端口
├── rigour-shared-idempotency/    # 可选：幂等状态与存储端口
├── rigour-shared-outbox/         # 可选：Outbox 消息与事务内写入端口
├── rigour-shared-cache/          # 可选：缓存端口
└── rigour-shared-file/           # 可选：文件元数据与存储端口
services/
├── rigour-api-gateway/           # 统一 API 入口，端口 26880
└── rigour-<domain>-service/      # 12个领域服务聚合工程
    ├── <domain>-api/             # 版本化接口和请求/响应模型
    ├── <domain>-client/          # 仅在确有跨服务公共调用策略时提供，无领域实现
    └── <domain>-service/         # 业务实现和启动应用，端口26881-26892
```

`rigour-platform-starter` 只聚合 `core/context/logging`、Spring Web、Validation 和 Actuator。`audit/idempotency/outbox/cache/file` 不会被强制带入服务，领域服务必须按实际需求显式依赖并提供基础设施实现。

## 构建与测试

```bash
./mvnw verify
```

本项目仍然使用 Maven，根目录 `pom.xml` 是唯一构建配置。`mvnw` 是 Maven Wrapper 的 macOS/Linux 启动脚本，会按 `.mvn/wrapper/maven-wrapper.properties` 自动使用项目锁定的 Maven 3.9.9；Windows 使用 `mvnw.cmd`。它避免每位开发者自行安装不同 Maven 版本，不需要改成全局 `mvn`，也不代表项目换了构建工具。

开发环境Nacos Namespace名称为`dev`，实际ID为`3aa03547-8948-4254-bd94-47c630db128b`。各服务的`application-dev.yml`默认使用该ID，需要临时切换时可通过`NACOS_NAMESPACE`覆盖。

CI 执行同一命令。`verify` 会同时检查：

- Java 21 与 Maven Wrapper 版本；
- 51个reactor项目的编译和测试；
- 所有 `pom.xml` 都属于根 reactor；
- artifactId 唯一；
- 应用集合为一个 Gateway 加 12 个领域服务；
- 领域服务之间没有直接 Maven 依赖。

领域 API 模块只保存已确认的跨服务契约，未确认的接口和 DTO 不提前创建。IAM、Integration、订单中心和 Sales Work 已分别具备已提交的 Schema/迁移；其余领域服务的空 DEV Schema/账号初始化见[`docs/DOMAIN_DATABASE_RUNTIME.md`](docs/DOMAIN_DATABASE_RUNTIME.md)，在各自字段级设计确认前不接入业务表、MyBatis-Plus 或 Flyway。Sales Work 的当前边界见 [`services/rigour-sales-work-service/README.md`](services/rigour-sales-work-service/README.md)。

启动单个服务示例：

```bash
./mvnw -pl services/rigour-tenant-iam-service/iam-service -am spring-boot:run
```

## 本地基础设施

Compose 仅供开发者本机使用。先根据 `.env.example` 创建未提交的 `.env`，并替换所有占位凭据：

```bash
docker compose --env-file .env -f docker/compose/docker-compose.yml up -d
```

| 组件 | 固定镜像 | 本地端口 |
|---|---|---|
| MySQL | `mysql:8.4.7` | 3306 |
| Redis | `redis:7.4.10-alpine` | 6379 |
| RocketMQ NameServer | `apache/rocketmq:5.3.3` | 9876 |
| RocketMQ Broker | `apache/rocketmq:5.3.3` | 10909、10911 |
| RocketMQ Proxy | `apache/rocketmq:5.3.3` | **18081 → 容器 8081** |

RocketMQ Proxy 继续使用独立宿主机端口 `18081`；表中的 `8081` 是容器内部端口。

文件、图片和录音统一使用腾讯云 COS。项目只定义厂商无关的文件存储端口，COS Bucket、地域和密钥通过所属环境安全配置，不在本地 Compose 或 Git 中保存。

## HTTP 契约

- 业务前缀：`/api/v1`
- 浏览器请求头：`Authorization`、`X-Request-Id`、`Accept-Language`；客户端不得发送可信租户身份头
- 下游身份：Gateway验签JWT并向IAM在线确认后，使用HMAC签名`X-Rigour-*`最小身份/角色/权限上下文；领域服务拒绝未签名、篡改或过期上下文
- requestId：缺失时由请求上下文过滤器生成，并同时写入响应头和响应体
- 错误码：稳定机器码；领域错误使用 `DOMAIN_REASON`

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "requestId": "request-123",
  "timestamp": "2026-07-29T11:30:00+08:00"
}
```

## 当前未实现或未验收

- Gateway限流、熔断和规模化的IAM安全版本事件投影；当前逐请求在线确认能即时失效，但与IAM延迟/可用性耦合；
- 其余领域服务的数据库驱动、迁移和仓储实现；Sales Work 当前只有 V1 DDL 和基础依赖，Repository、命令处理及共享 DEV Flyway 仍未验收；
- 幂等存储、Outbox投递器、审计应用实现、缓存和对象存储适配器；IAM的Outbox和审计表仅完成DDL；
- OpenAPI、领域 API、消息契约和跨服务集成测试；
- Docker Compose 的生产部署、安全加固、备份和可观测性。

项目 POM 不声明自定义 Maven 仓库，依赖默认从 Maven Central 解析；开发者本机 `settings.xml` 仍可能覆盖镜像来源。

## IAM数据库迁移

IAM 当前检出代码的迁移目录为 V1～V22；V22新增租户菜单展示覆盖和无路由自定义分组，平台资源目录仍是唯一功能主数据。共享 DEV 实际已执行版本、表数量和源码差异必须以[`docs/IAM_DATABASE_RUNTIME.md`](docs/IAM_DATABASE_RUNTIME.md)的最新只读核验为准，不能从代码目录推断运行时已发布。Integration 为独立的`rigour_integration` Schema，代码迁移为 V1～V2；Sales Work 代码迁移为 V1。其余领域库的 Schema 和账号初始化见[`docs/DOMAIN_DATABASE_RUNTIME.md`](docs/DOMAIN_DATABASE_RUNTIME.md)。

代码完成边界见[`docs/IAM_OIDC_REMAINING_ROADMAP.md`](docs/IAM_OIDC_REMAINING_ROADMAP.md)，多人共享DEV配置/数据库且各自本机运行服务见[`docs/SHARED_DEV_LOCAL_RUNTIME.md`](docs/SHARED_DEV_LOCAL_RUNTIME.md)，登录验收步骤见[`docs/IAM_MANAGEMENT_ACCEPTANCE.md`](docs/IAM_MANAGEMENT_ACCEPTANCE.md)，业务服务的用户上下文和授权接入见[`docs/DOMAIN_AUTHORIZATION_GUIDE.md`](docs/DOMAIN_AUTHORIZATION_GUIDE.md)。

IAM实现模块已加入MyBatis-Plus、JDBC、MySQL Driver和Spring Boot 4 Flyway Starter。Nacos只保存数据源地址、用户名和密码环境变量引用；真实密码由本机或部署平台Secret注入，不能直接用root账号启动迁移。
