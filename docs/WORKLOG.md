# 工作日志

## 2026-07-30 - 全领域服务工程结构统一

- 11个领域服务统一为“聚合父模块 + `<domain>-api` + `<domain>-service`”，Gateway保持单模块。
- API模块只保存跨服务调用契约和DTO，业务实现模块依赖自己的API；未确认接口不提前生成。
- 所有开发配置统一使用Nacos Namespace名称`dev`对应的ID `3aa03547-8948-4254-bd94-47c630db128b`。
- 实现模块统一向`api.controller`、`application.service`、`domain.model/repository`、`infrastructure.persistence`演进，不为未设计业务创建空类。
- 数据库依赖不批量复制；当前只有具备22表迁移和集成测试的IAM接入MyBatis-Plus、Flyway和MySQL。
- 删除Integration中一期未确认的OIDC、SAML空适配器，保留已确认的飞书身份和外部启动边界。

## 2026-07-30 - IAM工程与持久层骨架重构

- IAM改为一个业务聚合工程，内部包含`iam-api`和`iam-service`两个Maven模块；不再使用顶层`contracts`目录，也不额外拆分DTO模块。
- `iam-api`只保存版本化接口和请求/响应模型；`iam-service`保存Controller、应用服务、领域模型、Mapper、Flyway和启动配置。
- 持久层采用MyBatis-Plus 3.5.16处理标准单表操作，复杂查询继续使用XML；不使用`IService/ServiceImpl`，暂不启用全局租户SQL改写。
- Spring Boot 4使用专用`spring-boot-starter-flyway`，MySQL数据库支持由`flyway-mysql`提供。
- 架构测试改为识别递归Maven模块，并继续禁止跨服务实现依赖。
- Nacos只保存数据源等环境配置，Git中仅保留无真实凭据的复制模板。

## 2026-07-30 - IAM数据库迁移初版

- 依据已评审的IAM 22表字段模型生成V1～V5 DDL。
- 依据首批种子清单生成V6：5个应用、70个资源、24个权限码和`STANDARD`一期标准套餐。
- 使用一次性本地MySQL 8.4容器按V1～V6顺序执行，验证22表、5个应用、70个资源、47个标准套餐资源且平台资源未进入租户套餐。
- `./mvnw verify -B -T 1C`通过，24个Reactor模块全部成功。
- 未连接或修改开发服务器；IAM模块的JDBC、MySQL Driver、Flyway运行时、Nacos数据源和数据库账号权限仍待单独接入。

## 2026-07-29 - 后端脚手架架构收敛

### 验收背景

初版虽然 `mvn verify` 通过，但存在重复 Gateway、孤儿 shared POM、可选能力被 starter 强制传递、幂等/Outbox 伪实现、投机依赖和重复 Compose，因此不能视为达到架构标准。

### 实施计划

1. 统一 Gateway 为 `rigour-api-gateway`，删除重复目录和孤儿 POM。
2. 将八个 shared 库全部纳入 reactor，区分强制基线与可选扩展。
3. 将幂等、Outbox、审计、缓存和文件能力改为显式端口/契约。
4. 精简根 dependencyManagement，恢复 Maven Central 默认解析。
5. 统一本地 Compose，固定镜像版本并避免默认弱口令和端口冲突。
6. 补齐服务根包及四层 `package-info.java`。
7. 新增 Maven Enforcer 和 reactor 结构测试。
8. 修正 ApiResponse requestId 契约，补响应、异常和上下文清理测试。
9. 同步 README、AGENTS 和架构文档。
10. 执行 `./mvnw verify` 并统计项目、测试和剩余边界。

### 完成结果

- Gateway 只保留 `services/rigour-api-gateway`。
- shared 只保留并聚合 `core/context/logging/audit/idempotency/outbox/cache/file` 八个模块。
- `rigour-platform-starter` 只传递 context、core、logging、Web、Validation 和 Actuator。
- audit/idempotency/outbox/cache/file 均为按需契约；无共享 JPA 实体、默认空实现或自动启用的无效切面。
- 根 POM 只管理 Spring Boot、Spring Cloud、内部模块和实际使用的 Maven 插件，不声明自定义仓库。
- 12 个应用均有根包说明；11 个领域服务补齐 interfaces/application/domain/infrastructure 分层说明。
- Maven Enforcer 和 `rigour-architecture-tests` 共同阻止领域服务直接依赖、重复 artifactId 和孤儿 POM。
- ApiResponse 从 RequestContext 读取 requestId；请求过滤器在正常/异常路径均清理 request/tenant ThreadLocal。
- Compose 只保留 `docker/compose/docker-compose.yml`；RocketMQ Proxy 使用 `18081:8081`，不再占用 IAM 的 8081。

### 验证记录

- Reactor：24 个项目（根 1 + platform 3 + shared 8 + applications 12）。
- 测试：23 个（应用上下文 12 + context 3 + core 3 + architecture 5）。
- `./mvnw verify -B -T 1C`：通过。

### 已知边界

- 当前是领域化服务骨架，不是生产就绪平台。
- 认证授权、可信租户头、Flyway运行时、领域持久化、消息、缓存、文件和第三方适配尚未实现；IAM仅完成V1～V6 SQL和本地MySQL语法/约束验证。
- IdempotencyStore 和 OutboxStore 只有端口，原子性、同事务写入、重试和清理必须由具体服务实现并做集成测试。
- Compose 仅供本地开发，未启动容器做运行验收。

### 状态

架构收敛完成，等待主 Agent 验收。
