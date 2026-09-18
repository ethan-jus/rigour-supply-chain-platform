# 工程命名与公共能力边界

本次调整工程目录、Maven 坐标和相关构建引用。根目录及根 artifactId 统一为 `rigour-supply-chain-digital-platform`，groupId 保持 `com.rigour`。

## 服务工程

`services/` 是分类目录，不额外增加 POM。下表每个 `rg-scdp-*` 工程使用 `packaging=pom`，子模块目录与各自的 artifactId 一致。

| 聚合工程 | 子模块 | 保留的运行注册名 |
|---|---|---|
| rg-scdp-gateway | gateway-server | rigour-api-gateway |
| rg-scdp-iam | iam-api、iam-client、iam-server | rigour-tenant-iam-service |
| rg-scdp-integration | integration-api、integration-client、integration-server | rigour-integration-migration-service |
| rg-scdp-crm | crm-api、crm-server | rigour-merchant-crm-service |
| rg-scdp-erp | erp-api、erp-server | rigour-erp-core-service |
| rg-scdp-order | order-api、order-server | rigour-order-center-service |
| rg-scdp-sales | sales-api、sales-server | rigour-sales-work-service |
| rg-scdp-ai | ai-api、ai-server | rigour-ai-agent-service |
| rg-scdp-bi | bi-api、bi-server | rigour-analytics-bi-service |
| rg-scdp-hr | hr-api、hr-server | rigour-hr-payroll-service |
| rg-scdp-foundation | foundation-api、foundation-client、foundation-server | rigour-business-settings-service |

`*-api` 放接口契约，`*-server` 放业务实现和启动入口。已有的 `*-client` 保留已被调用方使用的 HTTP 调用策略，不依赖服务端实现。Gateway 不增加空 API 模块。

## 启动类与可执行 JAR

启动类统一为业务域的 PascalCase 名称加 `Application`；可执行 JAR 与服务端模块 artifactId 一致，由各 `*-server` POM 的 `finalName` 明确配置。版本号仍由 Maven 坐标和发布记录维护，API/client/shared 依赖包沿用 Maven 默认版本命名。

| 服务 | 启动类 | 可执行 JAR |
|---|---|---|
| gateway | `GatewayApplication` | `gateway-server.jar` |
| iam | `IamApplication` | `iam-server.jar` |
| integration | `IntegrationApplication` | `integration-server.jar` |
| crm | `CrmApplication` | `crm-server.jar` |
| erp | `ErpApplication` | `erp-server.jar` |
| order | `OrderApplication` | `order-server.jar` |
| sales | `SalesApplication` | `sales-server.jar` |
| ai | `AiApplication` | `ai-server.jar` |
| bi | `BiApplication` | `bi-server.jar` |
| hr | `HrApplication` | `hr-server.jar` |
| foundation | `FoundationApplication` | `foundation-server.jar` |

构建目录、发布包和容器内使用相同 JAR 文件名。修改启动类后执行 `./mvnw clean verify` 清理旧 class/JAR，避免 Spring Boot 检测到多个启动入口。IDEA 的主类与对应测试类同步命名。

## 共享组件

```text
shared/
├── shared-core/          # 通用基础类型与工具
├── shared-context/       # 请求、租户与可信调用上下文
├── shared-logging/       # 日志接入与通用封装
├── shared-audit/         # 审计事件与公共端口
├── shared-cache/         # 缓存访问契约
├── shared-file/          # 文件访问契约
├── shared-idempotency/   # 幂等处理契约
└── shared-outbox/        # Outbox 公共契约
```

这些模块由原 `rigour-shared-*` 改名，供服务通过 Maven 引用，不作为独立应用启动。现有可选组件多为接口契约；目录改名不意味着已经实现 Redis 适配、文件托管或消息投递。`platform/` 下现有 BOM、Starter、架构检查模块保留原名称。

## Foundation 职责

当前已实现的是公共业务字典及其相关审计、可用性检查，沿用 `rigour_settings` Schema。下面是公共能力的后续演进建议，不属于本次新增功能：

| 能力 | 建议归属 | 边界 |
|---|---|---|
| 字典 | foundation | 集中维护字典定义、字典项和租户覆盖规则 |
| 文件 | shared-file + foundation 内部 file 模块 | shared 提供访问契约；需要集中附件管理时由 foundation 管元数据和访问凭证，业务归属与业务授权仍由所属服务决定 |
| 通知 | foundation 内部 notification 模块 | 模板、发送渠道、发送记录和重试；是否通知及通知内容由业务服务决定 |
| 缓存 cache | shared-cache + 各服务适配 | 各服务直接访问 Redis，管理自己的键、TTL 和失效策略，不统一经 foundation HTTP 转发 |
| 调度 scheduler | 调度基础设施 + 各服务任务执行器 | 可以集中管理触发、执行记录和失败告警；订单关单、库存处理等任务仍在对应业务服务执行，不跨库改业务数据 |

文件和通知在规模、故障隔离要求允许时可以先在一个 foundation 进程内分模块实现，无需为每种公共能力创建一个微服务。以后是否拆分，应依据独立扩容、可用性和发布要求决定。

## 兼容与开发入口

- Java 包名、运行注册名、HTTP 路径、权限码、端口和数据库名称保留。工程名不要求与部署中的服务注册名同步变更。
- DEV 仍使用各服务 `application-dev.yml`，启动时执行 Flyway，保留校验和禁用 clean 的规则。
- IDEA 打开新目录的根 `pom.xml`，重新加载 Maven；启动配置的 module 改为相应 `*-server`。旧目录已打开的 IDEA 窗口需要重新打开新工程。
- 现有 Git 远程仓库 URL 与台式机 `/srv/rigour-dev/src/rigour-supply-chain-platform` 检出路径保留；部署脚本中的模块路径、Jar 名称使用新工程结构。
- 内部协同、城市运营、渠道代理服务退出当前构建、路由、部署清单及工作台入口。历史迁移保存在 `docs/retired-services/collaboration/`，数据库数据保留。IAM 通过新增迁移停用这三个服务对应的能力和导航，已执行迁移不改写。

## 验证入口

```bash
./mvnw clean verify
python3 -m unittest discover -s scripts/desktop -p 'test_*.py'
```

架构检查验证所有 POM 纳入 reactor、目录与 artifactId 一致、服务依赖边界及 11 个启动应用。构建通过不等于共享 DEV 已完成迁移或浏览器验收。

## 前端命名

Web 工程目录及 package 名为 `rigour-supply-chain-digital-web`，以 `-web` 区分后端 `rigour-supply-chain-digital-platform`。前端只承载 SCDP。
