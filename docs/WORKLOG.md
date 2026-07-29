# 工作日志

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
- 认证授权、可信租户头、Flyway/Schema、领域持久化、消息、缓存、文件和第三方适配尚未实现。
- IdempotencyStore 和 OutboxStore 只有端口，原子性、同事务写入、重试和清理必须由具体服务实现并做集成测试。
- Compose 仅供本地开发，未启动容器做运行验收。

### 状态

架构收敛完成，等待主 Agent 验收。
