# Platform 架构边界

## 定位

本工程是 12 个粗粒度领域服务和一个 API Gateway 的可编译骨架。当前目标是建立稳定的代码所有权、依赖方向和跨仓库 HTTP 契约，不以空实现冒充生产基础设施。

多人协同开发入口是 [`TEAM_DEVELOPMENT_GUIDE.md`](TEAM_DEVELOPMENT_GUIDE.md)。它规定了服务唯一所有者、第三方集成归属、跨服务契约、README、评审和断舍离门禁；本文件负责解释架构结构，不替代协同规则。

## Reactor

根reactor共51个项目：根聚合项目1个、platform 3个、shared 8个、Gateway 1个、领域聚合父模块12个、领域API模块12个、领域启动应用12个，以及2个明确复用跨服务调用策略的client模块。

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
├── 12 domain service parents
│   ├── <domain>-api     # 版本化接口和DTO，不包含实现
│   ├── <domain>-client  # 可选；只封装已确认的跨服务调用策略
│   └── <domain>-service # 启动应用、业务、领域和持久化
└── architecture-tests
```

领域服务之间禁止 Maven 依赖。跨服务协作只能通过版本化 API、领域事件或本地投影完成；禁止跨库 SQL、跨服务写表和共享业务表。当前 `business-settings-client` 只封装字典批量补齐与降级审计，`integration-migration-client` 只封装连接器同步任务租约、心跳和精确释放；二者不包含所属服务的领域实现或数据访问。

## Shared 边界

| 模块 | 默认进入服务 | 职责 | 明确不负责 |
|---|---:|---|---|
| context | 是 | requestId、HMAC签名调用人、tenant/角色/权限上下文与请求后清理 | JWT认证、IAM事实计算、领域数据范围、异步隐式传播 |
| core | 是 | ApiResponse、错误格式、分页、DataScope 名称 | 领域实体、数据访问、万能数据服务 |
| logging | 是 | 不含敏感内容的 HTTP 访问日志 | 业务审计、请求体复制 |
| audit | 否 | AuditEvent、AuditSink | AOP、共享表、默认空实现 |
| idempotency | 否 | IdempotencyKey/Record/Store | 无效切面、内存伪实现、共享实体表 |
| outbox | 否 | OutboxMessage、OutboxStore | JPA 实体、共享 Outbox 表、投递器 |
| cache | 否 | 租户隔离 CacheStore | Redis 绑定、自动缓存、唯一事实 |
| file | 否 | FileMetadata、FileStorage | 具体 COS SDK 绑定、业务授权 |

### 幂等落地要求

`IdempotencyStore.reserve` 必须在具体基础设施中实现原子占位。跨实例并发、TTL、失败释放、结果重放和敏感响应保留策略必须由使用它的领域服务决定并做集成测试。仅引入 `rigour-shared-idempotency` 不会自动获得幂等能力。

### Outbox 落地要求

`OutboxStore.append` 必须与领域业务写入使用同一服务、同一数据库和同一本地事务。表结构、索引、抢占锁、重试、死信、清理和消息中间件适配器归领域服务所有；shared 不提供 JPA 实体。

## 服务内部依赖

所有领域服务都采用业务聚合目录，让接口契约和实现相邻，避免按技术类型集中到顶层`contracts`目录。API模型不再拆出独立DTO模块：请求、响应和错误语义都是接口契约的一部分。Gateway只承担入口能力，不发布领域调用契约，因此保持单模块。

领域实现模块统一采用四层package边界：

```text
api             -> application -> domain
infrastructure  -> application -> domain
domain          -> 不依赖 Spring、数据库或其他服务实现
```

- `api`：Controller等入站协议适配；可供其他服务依赖的Java契约只放在同级`iam-api` Maven模块；
- `application`：用例编排、事务边界和端口调用；
- `domain`：聚合、值对象、领域服务和业务不变量；
- `infrastructure`：数据库、消息、缓存、文件和第三方系统出站适配。

标准包名为`api.controller`、`application.service`、`domain.model/repository`和`infrastructure.persistence`。只在存在实现或已确认的跨服务契约时创建类型，不保留空Controller、空适配器、空领域模型或重复的占位目录。

持久层统一选型为 MyBatis-Plus 加显式 XML，但运行依赖按服务渐进接入：只有 Schema、Flyway 脚本、Nacos 数据源和集成测试同时具备时，服务实现模块才能加入 MyBatis-Plus/Flyway/MySQL。当前 IAM、Integration 和订单中心满足条件；其余领域服务虽然已经完成共享 DEV 空 Schema 和账号初始化，但仍要在字段级设计、迁移和集成测试完成后再接入数据库依赖。业务层不继承 `IService/ServiceImpl`；复杂查询保留 XML；多租户 SQL 插件必须经过平台表豁免和越权测试后才能启用。

## 架构门禁

根 POM 的 Maven Enforcer 禁止任何领域服务 artifact 成为依赖。`rigour-architecture-tests` 进一步验证：

1. 所有POM都由根reactor直接或递归聚合；
2. artifactId 不重复；
3. 只有 `rigour-api-gateway` 和 12 个领域服务；
4. 服务 POM 不直接依赖其他服务 artifact。

这些静态门禁不能证明数据库账号隔离、运行时调用方向或事件兼容性，后续仍需部署和集成测试。

## 请求与错误契约

`RequestContextFilter` 解析或生成 `X-Request-Id`；存在身份头时校验Gateway生成的HMAC签名、时间窗口、请求方法、路径和查询，再建立`CallerIdentity`/tenant上下文，并在`finally`中清理ThreadLocal。查询参数按参数名稳定排序并统一等价URL编码，同名参数值顺序仍参与签名，避免Gateway重组查询串造成误判，同时继续拒绝真实参数篡改。未签名、篡改和过期上下文返回401；`AuthorizationContext.requirePermission`失败返回403。`ApiResponse`创建时读取同一requestId；未知异常只返回稳定通用文案，完整堆栈留在服务端日志。

## 本地基础设施边界

唯一 Compose 文件是 `docker/compose/docker-compose.yml`，固定 MySQL、Redis 和 RocketMQ 镜像版本。它只用于本地开发，不包含生产密钥管理、高可用、备份、TLS、监控或容量配置。文件能力通过对象存储端口适配腾讯云 COS，不在 Compose 中运行本地替代服务。

RocketMQ Proxy 映射为宿主机 `18081` 到容器 `8081`，与微服务使用的 `26880-26892` 端口段分离。

## 尚未生产就绪

IAM OIDC、平台/租户管理、数据库导航、Portal卡片与权限导航、Gateway资源服务器和签名上下文已完成代码。Gateway对每个受保护请求调用IAM内部`/token/current`，会话撤销和安全/租户策略版本变化可立即生效；代价是当前请求链路与IAM延迟和可用性耦合，后续需以安全版本事件投影扩展。V1～V6已应用共享DEV，V7/V8、共享密钥、客户端和跨进程浏览器链路尚未发布验收。构建和Testcontainers通过不代表共享DEV或生产验收完成。
