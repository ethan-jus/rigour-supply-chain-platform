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
└── rigour-*-service/             # 11 个领域服务，端口 26881-26891
```

`rigour-platform-starter` 只聚合 `core/context/logging`、Spring Web、Validation 和 Actuator。`audit/idempotency/outbox/cache/file` 不会被强制带入服务，领域服务必须按实际需求显式依赖并提供基础设施实现。

## 构建与测试

```bash
./mvnw verify
```

CI 执行同一命令。`verify` 会同时检查：

- Java 21 与 Maven Wrapper 版本；
- 24 个 reactor 项目的编译和测试；
- 所有 `pom.xml` 都属于根 reactor；
- artifactId 唯一；
- 应用集合为一个 Gateway 加 11 个领域服务；
- 领域服务之间没有直接 Maven 依赖。

启动单个服务示例：

```bash
./mvnw -pl services/rigour-tenant-iam-service -am spring-boot:run
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
| MinIO | `minio/minio:RELEASE.2025-04-22T22-12-26Z` | 9000、9001 |

RocketMQ Proxy 继续使用独立宿主机端口 `18081`；表中的 `8081` 是容器内部端口。

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
- 数据库驱动、连接池、Flyway、领域表和仓储实现；
- 幂等存储、Outbox 表/投递器、审计落库、缓存和对象存储适配器；
- OpenAPI、领域 API、消息契约和跨服务集成测试；
- Docker Compose 的生产部署、安全加固、备份和可观测性。

项目 POM 不声明自定义 Maven 仓库，依赖默认从 Maven Central 解析；开发者本机 `settings.xml` 仍可能覆盖镜像来源。
