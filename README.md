# Rigour Supply Chain Digital Platform

Java 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2 的领域化服务骨架。

本仓库已实现 IAM OIDC、Gateway 资源服务器、配置化基础管理、Integration 连接器基础能力、订单中心本地投影、Sales Work V1 领域库表骨架；尚未进入业务实现的领域服务仍是可编译骨架。自动构建通过不表示共享 DEV、浏览器或生产环境已验收。

协同开发先阅读 [`docs/TEAM_DEVELOPMENT_GUIDE.md`](docs/TEAM_DEVELOPMENT_GUIDE.md)；服务职责和 Schema 所有权以 [`docs/SERVICE_BOUNDARIES.md`](docs/SERVICE_BOUNDARIES.md) 为准。Integration 与订单中心的具体边界见各自的 [`README.md`](services/rg-scdp-integration/README.md) 和 [`README.md`](services/rg-scdp-order/README.md)。

工程命名规则与旧名称映射见 [工程命名约定](docs/PROJECT_NAMING.md)。内部协同、城市运营和渠道代理代码已移除，协同历史迁移见 [协同退出说明](docs/retired-services/collaboration/README.md)。

## SCDP 单产品入口

Web 工程为 `rigour-supply-chain-digital-web`，登录后直接进入供应链。用户、角色和菜单在供应链系统设置维护。升级及验证记录见 [单产品入口改造](docs/SCDP_SINGLE_PRODUCT_MIGRATION.md)。

## 工程结构

```text
platform/
├── rigour-platform-bom/          # 内部库版本清单
├── rigour-platform-starter/      # 所有 HTTP 服务的最小公共基线
└── rigour-architecture-tests/    # reactor 与服务依赖边界测试
shared/
├── shared-context/        # request、签名调用人、tenant上下文与清理
├── shared-core/           # 统一响应、错误和分页
├── shared-logging/        # HTTP 访问日志
├── shared-audit/          # 可选：审计事件与端口
├── shared-idempotency/    # 可选：幂等状态与存储端口
├── shared-outbox/         # 可选：Outbox 消息与事务内写入端口
├── shared-cache/          # 可选：缓存端口
└── shared-file/           # 可选：文件元数据与存储端口
services/
├── rg-scdp-gateway/             # 网关聚合工程
│   └── gateway-server/          # 网关启动模块，端口 26880
└── rg-scdp-<domain>/            # 10 个领域服务聚合工程
    ├── <domain>-api/             # 版本化接口和请求/响应模型
    ├── <domain>-client/          # 仅在确有跨服务公共调用策略时提供，无领域实现
    └── <domain>-server/         # 业务实现和启动应用，端口26881-26892
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
- 全部 reactor 项目的编译和测试；
- 所有 `pom.xml` 都属于根 reactor；
- artifactId 唯一；
- 应用集合为一个 Gateway 加 10 个领域服务；
- 领域服务之间没有直接 Maven 依赖。

领域 API 模块只保存已确认的跨服务契约，未确认的接口和 DTO 不提前创建。IAM、Integration、订单中心和 Sales 已分别具备已提交的 Schema/迁移；其余领域服务的空 DEV Schema/账号初始化见[`docs/DOMAIN_DATABASE_RUNTIME.md`](docs/DOMAIN_DATABASE_RUNTIME.md)，在各自字段级设计确认前不接入业务表、MyBatis-Plus 或 Flyway。Sales Work 的当前边界见 [`services/rg-scdp-sales/README.md`](services/rg-scdp-sales/README.md)。

日常开发在 IDEA 选择对应的 `<Domain>Application`（例如 `IamApplication`、`CrmApplication`），使用 `dev` 启动；个人覆盖选择 `local`，生产选择 `prod`。启动类和 JAR 对照见 [工程命名](docs/PROJECT_NAMING.md)。
三个环境配置都在各启动模块的 `src/main/resources/application-{dev,local,prod}.yml`。
完整用法见[本地开发指南](docs/SHARED_DEV_LOCAL_RUNTIME.md)。

已打包服务的命令行启动示例（签名密钥需与IDEA一样通过环境变量提供）：

```bash
java -jar services/rg-scdp-iam/iam-server/target/iam-server.jar --spring.profiles.active=dev
```

## 开发基础设施

日常开发统一使用台式机 `192.168.12.7`：MySQL `13306`、Redis `16379`、Nacos `18848`（控制台 `18080`）、RocketMQ NameServer `19876`。
DEV YAML已填写连接信息；Nacos 配置读取关闭，本机不注册；有迁移的服务在启动时由 Flyway 校验并执行增量迁移。无需启动本机中间件。

以下Compose仅在明确需要独立测试环境时使用，不是日常开发前置步骤。根据 `.env.example` 创建 `.env`，并替换占位凭据：

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
- 其余领域服务的数据库驱动、迁移和仓储实现；Sales 已有代码迁移和基础实现，具体环境迁移与移动端真机验收仍需分别核验；
- 幂等存储、Outbox投递器、审计应用实现、缓存和对象存储适配器；IAM的Outbox和审计表仅完成DDL；
- OpenAPI、领域 API、消息契约和跨服务集成测试；
- Docker Compose 的生产部署、安全加固、备份和可观测性。

项目 POM 不声明自定义 Maven 仓库，依赖默认从 Maven Central 解析；开发者本机 `settings.xml` 仍可能覆盖镜像来源。

## IAM数据库迁移

IAM 迁移版本以当前 `services/rg-scdp-iam/iam-server/src/main/resources/db/migration` 目录为准。当前代码包含 V104–V111，用于停用已退出的协同、城市运营和渠道代理能力并调整供应链单入口；已有迁移内容保留。共享 DEV 实际已执行版本须以环境核验为准，不能由源码目录推断。Integration、Sales 和其余领域服务继续拥有各自 Schema，数据库运行约定见 [`docs/DOMAIN_DATABASE_RUNTIME.md`](docs/DOMAIN_DATABASE_RUNTIME.md)。

代码完成边界见[`docs/IAM_OIDC_REMAINING_ROADMAP.md`](docs/IAM_OIDC_REMAINING_ROADMAP.md)，多人共享DEV配置/数据库且各自本机运行服务见[`docs/SHARED_DEV_LOCAL_RUNTIME.md`](docs/SHARED_DEV_LOCAL_RUNTIME.md)，登录验收步骤见[`docs/IAM_MANAGEMENT_ACCEPTANCE.md`](docs/IAM_MANAGEMENT_ACCEPTANCE.md)，业务服务的用户上下文和授权接入见[`docs/DOMAIN_AUTHORIZATION_GUIDE.md`](docs/DOMAIN_AUTHORIZATION_GUIDE.md)。

IAM实现模块已加入MyBatis-Plus、JDBC、MySQL Driver和Spring Boot 4 Flyway Starter。按当前日常 DEV 约定，数据源在 `application-dev.yml`，应用使用共享 DEV 开发账号并在启动时由 Flyway 校验、执行增量迁移；本机不注册服务发现。生产配置保持独立，实际已执行版本仍以环境核验为准。

供应链内部授权客户端：`services/rg-scdp-iam/iam-client`。它只提供当前授权读取与请求上下文，配置入口为 `rigour.supply-authorization.iam-base-url`，不得加入平台通用 starter。
