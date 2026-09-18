# AGENTS.md — Platform 开发指引

## 工作方式

- 围绕用户目标自主完成必要的实现、重构和验证；涉及前后端契约时，可以联动本工作区的相关仓库。
- 任务所需的常规 commit/push 无需再次请求许可；执行前检查目标分支、远程状态和提交范围，保留其他任务的未提交改动，不自动 force-push。
- 不把密钥、生产租户值、飞书导出数据或构建产物纳入 Git。

## 现有架构

- `platform/rigour-platform-bom`：内部库版本清单，不携带运行时依赖。
- `platform/rigour-platform-starter`：HTTP 服务最小基线，只聚合 core/context/logging/Web/Validation/Actuator。
- `platform/rigour-architecture-tests`：reactor 和服务依赖边界门禁。
- `shared/`：横切契约或最小自动配置；领域逻辑与业务数据放在对应服务。
- `services/`：`rg-scdp-gateway/gateway-server` 加 10 个领域服务；每个领域服务是其 Schema 的单一写者。

`audit/idempotency/outbox/cache/file` 是按需引入的可选库。具体持久化和中间件适配归使用它们的领域服务所有。

## 服务内分层

- `interfaces` 依赖 `application`；
- `application` 依赖 `domain`；
- `infrastructure` 实现出站端口，可依赖 `application/domain`；
- `domain` 不依赖 Spring、数据库或其他服务实现。

## 工程命名

根工程为 `rigour-supply-chain-digital-platform`；服务聚合目录与 artifactId 为 `rg-scdp-<domain>`，子模块为 `<domain>-api`、`<domain>-server`，实际复用调用策略时保留 `<domain>-client`。网关只有 `gateway-server`，不创建空 API。目录必须与 artifactId 一致。启动类使用 `<Domain>Application`，可执行包为 `<domain>-server.jar`，构建、发布包、容器命令保持一致；类改名后清理旧编译产物。Java 包名、运行服务名、Schema 和 HTTP 路径不随工程改名自动变化。协同、城市运营和渠道代理服务代码已移除；协同历史 SQL 保留在 `docs/retired-services/collaboration/`。

## 实现约定

- 跨服务协作优先使用版本化 API、领域事件或本地投影，保持实现模块独立。
- 所有领域数据、事件、缓存键和对象路径从第一天携带 tenantId。
- 幂等、审计和事务按实际行为验证，不能仅凭注解或空切面声明已实现。
- TODO 说明未完成原因和完成条件；注释用于解释必要的职责、边界和设计原因，不要求固定格式或每个 package 都补文档。

### 代码生命周期（断舍离）

- 替代实现验证后清理本次改动涉及的废弃代码和配置，避免扩大到无关清理。
- 按影响范围检查引用和验证；涉及跨仓库接口时再检查相关仓库。
- 有实际使用方的兼容层可以保留，并说明用途。
- 已执行的 Flyway 迁移文件不得删除或改写，只能新增迁移推进数据库演进。
- DEV/local 按用户 2026-09-16 确认的方式在服务启动时执行 Flyway；DEV 统一使用 root，专用开发密码直接维护在 application-dev.yml，Flyway 复用数据源，不另设迁移账号或环境变量。保留历史校验并禁用 clean、自动 baseline 和乱序迁移；不得为绕过缺表或冲突而关闭迁移或执行 repair。此约定仅限 DEV，生产配置与隔离测试分别处理。

共享环境和部署参考 `../共享DEV研发规范_v1.0.md`；本地开发按实际影响选择流程。

## 验证

```bash
./mvnw verify
```

按改动影响选择编译、定向测试或全量验证；文档和低影响修改无需运行全套测试。跨模块、依赖或架构变化使用上述全量验证。报告实际执行结果与未验证部分。

新增或删除构建模块时更新相关 POM 和必要文档，确保预期模块参与构建。
