# AGENTS.md — Platform 实施约束

## 范围

只修改本仓库。不得 commit/push，不得把密钥、生产租户值或构建产物纳入 Git。

## 模块边界

- `platform/rigour-platform-bom`：内部库版本清单，不携带运行时依赖。
- `platform/rigour-platform-starter`：HTTP 服务最小基线，只聚合 core/context/logging/Web/Validation/Actuator。
- `platform/rigour-architecture-tests`：reactor 和服务依赖边界门禁。
- `shared/`：横切契约或最小自动配置，禁止领域逻辑、万能数据服务和共享业务表。
- `services/`：`rigour-api-gateway` 加 11 个领域服务；每个领域服务是其 Schema 的单一写者。

`audit/idempotency/outbox/cache/file` 是可选库，不得加入 platform-starter。具体持久化和中间件适配归使用它们的领域服务所有。

## 服务内分层

- `interfaces` 依赖 `application`；
- `application` 依赖 `domain`；
- `infrastructure` 实现出站端口，可依赖 `application/domain`；
- `domain` 不依赖 Spring、数据库或其他服务实现。

## 强制规则

- 服务之间不得直接或传递依赖实现模块。
- 跨服务协作使用版本化 API、领域事件或本地投影。
- 所有领域数据、事件、缓存键和对象路径从第一天携带 tenantId。
- AOP 只用于真实横切能力，不得用空切面伪装幂等、审计或事务能力。
- TODO 格式：`TODO(owner/issue): 原因与完成条件`。
- Java 架构类型和关键 package 使用中文 Javadoc/package-info，解释职责、边界、原因和风险。

### 代码生命周期（断舍离）

- 废弃代码和配置在替代实现验证后直接删除，不保留注释代码、禁用死配置、旧路由、重复权限、孤儿模块或占位适配器。
- 删除前在三个仓库检索引用，删除后完成构建、静态检查和相关测试，并再次检索确认没有孤儿引用或旧入口。
- 仅为未来可能使用而保留的实现一律删除；正在生效的兼容层必须有明确职责和移除条件。
- 已执行的 Flyway 迁移文件不得删除或改写，只能新增迁移推进数据库演进。

统一规则见：`../共享DEV研发规范_v1.0.md` 的“代码与配置生命周期（断舍离）”。

## 验证

```bash
./mvnw verify
```

该命令必须在交付前通过。新增或删除模块时同步根 POM、BOM、README 和 `docs/architecture.md`；任何新增 `pom.xml` 都必须纳入根 reactor。
