# Platform 架构边界

## 定位

本工程是 11 个粗粒度领域服务和一个 API Gateway 的可编译骨架。当前目标是建立稳定的代码所有权、依赖方向和跨仓库 HTTP 契约，不以空实现冒充生产基础设施。

## Reactor

根 reactor 共 24 个项目：根聚合项目 1 个、platform 3 个、shared 8 个、启动应用 12 个。

```text
root
├── platform-bom
├── shared-context
├── shared-core        -> shared-context
├── shared-logging     -> shared-context
├── shared-audit       # 可选纯契约
├── shared-idempotency # 可选纯契约
├── shared-outbox      # 可选纯契约
├── shared-cache       # 可选纯契约
├── shared-file        # 可选纯契约
├── platform-starter   -> context + core + logging + web + validation + actuator
├── api-gateway        -> platform-starter + Spring Cloud Gateway WebMVC
├── 11 domain services -> platform-starter
└── architecture-tests
```

领域服务之间禁止 Maven 依赖。跨服务协作只能通过版本化 API、领域事件或本地投影完成；禁止跨库 SQL、跨服务写表和共享业务表。

## Shared 边界

| 模块 | 默认进入服务 | 职责 | 明确不负责 |
|---|---:|---|---|
| context | 是 | requestId、tenantId、语言上下文与请求后清理 | 认证、授权、异步隐式传播 |
| core | 是 | ApiResponse、错误格式、分页、DataScope 名称 | 领域实体、数据访问、万能数据服务 |
| logging | 是 | 不含敏感内容的 HTTP 访问日志 | 业务审计、请求体复制 |
| audit | 否 | AuditEvent、AuditSink | AOP、共享表、默认空实现 |
| idempotency | 否 | IdempotencyKey/Record/Store | 无效切面、内存伪实现、共享实体表 |
| outbox | 否 | OutboxMessage、OutboxStore | JPA 实体、共享 Outbox 表、投递器 |
| cache | 否 | 租户隔离 CacheStore | Redis 绑定、自动缓存、唯一事实 |
| file | 否 | FileMetadata、FileStorage | MinIO/OSS 绑定、业务授权 |

### 幂等落地要求

`IdempotencyStore.reserve` 必须在具体基础设施中实现原子占位。跨实例并发、TTL、失败释放、结果重放和敏感响应保留策略必须由使用它的领域服务决定并做集成测试。仅引入 `rigour-shared-idempotency` 不会自动获得幂等能力。

### Outbox 落地要求

`OutboxStore.append` 必须与领域业务写入使用同一服务、同一数据库和同一本地事务。表结构、索引、抢占锁、重试、死信、清理和消息中间件适配器归领域服务所有；shared 不提供 JPA 实体。

## 服务内部依赖

每个领域服务预留四层 package 边界：

```text
interfaces      -> application -> domain
infrastructure  -> application -> domain
domain          -> 不依赖 Spring、数据库或其他服务实现
```

- `interfaces`：HTTP、消息、批处理等入站协议适配；
- `application`：用例编排、事务边界和端口调用；
- `domain`：聚合、值对象、领域服务和业务不变量；
- `infrastructure`：数据库、消息、缓存、文件和第三方系统出站适配。

当前仅使用 `package-info.java` 固化边界，尚未实现领域类型。

## 架构门禁

根 POM 的 Maven Enforcer 禁止任何领域服务 artifact 成为依赖。`rigour-architecture-tests` 进一步验证：

1. 所有 POM 都由根 reactor 聚合；
2. artifactId 不重复；
3. 只有 `rigour-api-gateway` 和 11 个领域服务；
4. 服务 POM 不直接依赖其他服务 artifact。

这些静态门禁不能证明数据库账号隔离、运行时调用方向或事件兼容性，后续仍需部署和集成测试。

## 请求与错误契约

`RequestContextFilter` 解析或生成 `X-Request-Id`，将其写入响应头，并在 `finally` 中清理 request/tenant ThreadLocal。`ApiResponse` 创建时从 `RequestContext` 读取同一个 requestId。`GlobalExceptionHandler` 对未知异常只返回稳定通用文案，完整堆栈留在服务端日志。

## 本地基础设施边界

唯一 Compose 文件是 `docker/compose/docker-compose.yml`，固定 MySQL、Redis、RocketMQ 和 MinIO 镜像版本。它只用于本地开发，不包含生产密钥管理、高可用、备份、TLS、监控或容量配置。

RocketMQ Proxy 映射为宿主机 `18081` 到容器 `8081`，与微服务使用的 `26880-26891` 端口段分离。

## 尚未生产就绪

认证授权、可信租户头、数据库迁移、领域持久化、幂等实现、Outbox 投递、审计落库、缓存/文件适配器、OpenAPI、消息契约、集成测试和生产部署均未完成。`mvn verify` 通过只证明当前静态结构、编译和最小上下文测试通过。
