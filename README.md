# Rigour Supply Chain Platform

Java 21、Spring Boot 4.0.7、Spring Cloud 2025.1.2 的领域化服务骨架。

本仓库当前证明模块边界、统一 HTTP 契约、自动配置和构建门禁可工作，不表示认证、数据持久化、消息投递、缓存、文件存储或任何领域业务已达到生产就绪。

## 工程结构

```text
platform/
├── rigour-platform-bom/          # 内部库版本清单
├── rigour-platform-starter/      # 所有 HTTP 服务的最小公共基线
└── rigour-architecture-tests/    # reactor 与服务依赖边界测试
shared/
├── rigour-shared-context/        # request/tenant 上下文与清理
├── rigour-shared-core/           # 统一响应、错误和分页
├── rigour-shared-logging/        # HTTP 访问日志
├── rigour-shared-audit/          # 可选：审计事件与端口
├── rigour-shared-idempotency/    # 可选：幂等状态与存储端口
├── rigour-shared-outbox/         # 可选：Outbox 消息与事务内写入端口
├── rigour-shared-cache/          # 可选：缓存端口
└── rigour-shared-file/           # 可选：文件元数据与存储端口
services/
├── rigour-api-gateway/           # 统一 API 入口，端口 26880
└── rigour-<domain>-service/      # 11个领域服务聚合工程
    ├── <domain>-api/             # 版本化接口和请求/响应模型
    └── <domain>-service/         # 业务实现和启动应用，端口26881-26891
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
- 46个reactor项目的编译和测试；
- 所有 `pom.xml` 都属于根 reactor；
- artifactId 唯一；
- 应用集合为一个 Gateway 加 11 个领域服务；
- 领域服务之间没有直接 Maven 依赖。

领域API模块当前只固化依赖边界，未确认的接口和DTO不会提前创建。数据库运行依赖也不机械复制：只有已经完成Schema、Flyway和数据源设计的IAM接入MyBatis-Plus/Flyway/MySQL，其余领域服务在各自库表设计确认后按同一标准接入。

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
- 请求头：`Authorization`、`X-Tenant-Id`、`X-Request-Id`、`Accept-Language`
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

## 当前未实现

- Gateway 生产级认证、可信租户头、限流、熔断和服务发现；
- 其余领域服务的数据库驱动、迁移和仓储实现；IAM当前只接入首个应用目录Mapper骨架，尚未实现完整仓储；
- 幂等存储、Outbox投递器、审计应用实现、缓存和对象存储适配器；IAM的Outbox和审计表仅完成DDL；
- OpenAPI、领域 API、消息契约和跨服务集成测试；
- Docker Compose 的生产部署、安全加固、备份和可观测性。

项目 POM 不声明自定义 Maven 仓库，依赖默认从 Maven Central 解析；开发者本机 `settings.xml` 仍可能覆盖镜像来源。

## IAM数据库迁移

`services/rigour-tenant-iam-service/iam-service/src/main/resources/db/migration`已包含V1～V6，共22张表、5个应用、70个资源、24个权限码和一期标准套餐。集成测试会在一次性MySQL 8.4容器中通过Flyway执行全部迁移，并验证MyBatis-Plus BaseMapper和自定义XML查询；尚未连接或修改开发服务器数据库。

IAM实现模块已加入MyBatis-Plus、JDBC、MySQL Driver和Spring Boot 4 Flyway Starter。Nacos只保存数据源地址、用户名和密码环境变量引用；真实密码由本机或部署平台Secret注入，不能直接用root账号启动迁移。
