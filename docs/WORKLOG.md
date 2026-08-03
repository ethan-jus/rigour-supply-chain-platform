# 工作日志

## 2026-08-01 - OIDC退出会话收口

- IAM退出处理器兼容OIDC退出令牌及原登录主体两种会话来源，成功撤销IAM会话、授权记录和Refresh Token，并输出不含凭据的中文诊断日志。
- OIDC退出响应显式清除`RIGOUR_IAM_SESSION`浏览器Cookie，避免Portal重新登录时静默恢复上一个平台账号。
- Portal退出后的授权请求使用`prompt=login`；IAM在授权端点强制清理当前浏览器会话、保存原始授权请求并进入登录页，登录成功后只放行这一笔授权，避免静默跳回门户首页。
- 未强行向ID Token增加未经服务端校验支持的`sid`声明，保持现有OIDC退出协议兼容；安全测试验证退出后旧Access Token返回401。

## 2026-08-01 - IAM 登录页与门户视觉基线

- IAM 登录页使用品牌目录中的 Web logo 和统一的墨色/蓝色设计基线，去掉“平台管理员/普通用户”身份选择器。
- 登录页只收集企业编码、用户名和密码；企业编码为空推断 `PLATFORM`，填写企业编码推断 `TENANT`，服务端强制校验两者一致并兼容旧客户端显式 scope。
- Portal 不再承载无功能的欢迎中间页；受保护路由未认证时发起 OIDC，正式账号表单只由 IAM 提供。

## 2026-08-01 - IAM 登录成功后的错误请求恢复

- 登录成功只恢复会话中真正的 `/oauth2/authorize` 请求；遗留的 `/error?continue`、登录页或其他错误请求会被清理并回到 Portal，避免重复进入 IAM 403。
- 授权链与浏览器登录链共用请求缓存，并增加不记录凭据、Token和Cookie的中文跳转诊断日志。
- 新增成功处理器单元测试；IAM OIDC 安全测试4项通过。

## 2026-08-01 - 废弃骨架清理

- 删除未注册的 Gateway 审计、限流、路由目录空类，以及 IAM/Integration 的旧 SSO、外部身份、缓存、Outbox、令牌签名和空领域模型骨架。
- 删除未实现的 IAM 管理 Controller 和未落地的内部访问快照契约；当前入口以实际 Controller、Service 和已验证 API 为准。
- 已执行 Flyway 迁移 V1～V11 保留不改，数据库演进继续使用新迁移。

## 2026-07-31 - 全服务启动成功标识

### 实施计划

1. 在平台Starter新增统一启动完成日志工具，输出服务名称、应用名、端口、Profile和醒目成功符号。
2. 12个可运行微服务入口统一调用该工具，不复制日志格式和环境解析逻辑。
3. 不在项目配置中强制ANSI颜色；IDEA普通Application控制台的颜色由本机运行配置处理。
4. 执行全量Maven验证和差异检查。

### 完成结果

- 12个微服务入口均在Spring完全就绪后输出`✅✅✅`启动成功标识，以及服务名、`spring.application.name`、端口和活动Profile。
- 项目YAML未增加ANSI颜色配置；IDEA Community普通Application控制台通过本机VM option选择是否强制ANSI。
- `./mvnw -B verify`全量46个Reactor项目全部成功；IAM 23项、Gateway 12项及架构门禁5项通过。

## 2026-07-31 - Gateway分层配置去重

### 实施计划

1. 基础`application.yml`只保留路由、通用运行配置和HMAC环境变量绑定。
2. `application-local.yml`只保留本机服务发现禁注册与localhost拓扑覆盖，不硬编码安全开关。
3. Nacos模板只保留共享DEV安全开关和策略，不重复HMAC或本机地址。
4. 执行Gateway定向测试、YAML解析与`git diff --check`。

### 完成结果

- HMAC环境变量绑定只保留在基础配置；`local`只负责禁止本机注册和覆盖IAM localhost地址。
- Gateway安全开关和在线Token确认开关只由Nacos模板中的环境变量引用控制，默认关闭。
- 默认Profile与单独`local` Profile各执行12项Gateway测试，均失败0、错误0；4个相关YAML解析及`git diff --check`通过。

## 2026-07-31 - 多开发者共享DEV、本机服务模式固化

- 明确Portal是Vue前端项目，不是微服务；每位开发者本机启动Portal、Gateway、IAM和按需业务服务。
- `dev`负责远端DEV Nacos与数据库，`local`只覆盖localhost地址和本机Cookie/HTTP边界，统一使用`dev,local`。
- 为Gateway、IAM及全部领域服务增加local覆盖层，默认`spring.cloud.nacos.discovery.register-enabled=false`：读取共享配置但不把开发者电脑注册进共享服务发现。
- Nacos IAM/Gateway模板改为localhost loopback默认值，移除当前不存在的DEV域名占位默认值。
- Gateway Nacos模板移除不完整routes列表，防止远端列表覆盖仓库内完整业务路由。
- RSA私钥解析新增受限`home-file:`引用，允许共享数据库保存统一相对路径、每位开发者在自己的user.home放置同一权限600私钥；新增路径越界和密钥读取测试。
- 新增`docs/SHARED_DEV_LOCAL_RUNTIME.md`，固化共享AES/RSA/HMAC密钥、一次性V7/V8/客户端初始化和IDEA启动配置。

## 2026-07-31 - IAM、Portal与Gateway业务开发基线收口

- Gateway在JWT验签后强制调用IAM内部`/token/current`；安全开启时禁止关闭在线确认，并配置2秒连接/3秒读取超时。
- 删除浏览器可访问的`/token/current`路由；Gateway清除所有客户端`X-Rigour-*`，以独立HMAC密钥签名身份、租户、会话版本、角色、权限、方法、路径和查询。
- 所有领域服务验证签名、时效和头部上限后建立`CallerIdentity`；未签名或篡改返回401，缺少后端权限返回403。
- IAM补齐密码重置及全会话撤销、租户用户上限、最后管理员保护、系统角色保护、套餐预约/替换和历史查询。
- 登录页和Portal统一入口补齐当前用户/租户信息；业务服务授权与显示资料边界记录在`docs/DOMAIN_AUTHORIZATION_GUIDE.md`。
- 全工作区已将误写的“电话宝”更正为“订货宝”。
- `./mvnw -B verify`全量通过：46个Reactor项目、56项测试；其中IAM 21项、Gateway 12项、架构门禁5项。
- 共享DEV的V7/V8、密钥、初始账号、客户端初始化和真实浏览器验收仍需负责人授权执行，不把自动测试误报为运行态验收。

## 2026-07-31 - IAM基础管理、数据库导航与即时授权收口

- 新增V8：租户设置、UI导航元数据和管理资源种子；空库迁移后共32张IAM表、74个资源、43条UI导航记录。
- 实现平台级租户、套餐与不可变版本、订阅、应用、公开PKCE客户端、MENU/PAGE/BUTTON/API资源和审计管理。
- 实现租户级组织、用户、角色、套餐资源边界、DataScope、租户设置和审计；写操作更新安全/策略版本。
- Gateway新增`/token/current`逐请求在线确认，并注入当前角色/权限可信上下文；IAM拒绝是401，IAM不可用是503。
- 本地HTTP仅在显式local profile的loopback地址放行；一次性bootstrap和安全开关默认全部关闭。共享DEV授权密文禁止使用进程级随机AES密钥。
- `./mvnw verify -B`串行全量验证通过：46个Reactor项目全部SUCCESS；IAM 21项、Gateway 10项、架构门禁5项均通过。
- V7/V8共享DEV迁移和真实浏览器跨进程验收尚未执行，见`docs/IAM_MANAGEMENT_ACCEPTANCE.md`。

## 2026-07-31 - IAM、Gateway、Portal单点登录代码链路完成

- 完成Argon2id认证、锁定和IAM会话，RS256/JWKS，Access/ID签发，Refresh哈希轮换与重放撤销。
- 完成Authorization Code + PKCE、CORS精确白名单、OIDC RP-Initiated Logout及退出后旧Token拒绝。
- 新增`/api/v1/me`、`/api/v1/portal/apps`；IAM实时比对session/security/policy version并从数据库计算权限和应用卡片。
- Gateway完成issuer/audience/签名/时间/Access用途和版本声明校验，删除客户端伪造`X-Rigour-*`并注入可信上下文。
- Portal完成PKCE、state、内存Token、真实接口和授权应用目录；公开SPA不接收Refresh Token。
- Gateway对其他领域请求的最新安全版本事件投影尚未实现；共享DEV V7和跨进程验收尚未执行。

## 2026-07-31 - OIDC授权Store运行时安全装配

- 新增受控IAM认证details，固定传递`sessionId`、稳定`principalId`、可选`tenantId`和`securityVersion`，不把凭据或数据库实体放入OAuth授权上下文。
- 新增会话解析器，要求OAuth `principalName`与details中的主体UUID一致；Store同时校验`iam_auth_session.principal_id`、ACTIVE状态和有效期。
- 新增`OidcAuthorizationProperties`，授权上下文Store默认关闭；只有显式启用且提供活动密钥版本和32字节Base64密钥时才注册运行时`OAuth2AuthorizationService`。
- Nacos模板只保存环境变量引用，V7通过评审并应用共享DEV前保持`IAM_OIDC_AUTHORIZATION_STORE_ENABLED=false`。
- MySQL 8.4定向测试共11个，失败0、错误0、跳过0；新增验证运行时Bean装配及主体/会话不一致拒绝。
- `./mvnw verify -B`全量46个Reactor项目全部成功。
- 完整浏览器单点登录按一期交付口径仍差7步，详见`docs/IAM_OIDC_REMAINING_ROADMAP.md`。

## 2026-07-31 - OAuth授权上下文哈希与加密存储

- 实现Authorization Code阶段的自定义`OAuth2AuthorizationService`：State和Authorization Code仅保存SHA-256哈希，支持按调用方提交的原值计算哈希查询。
- 授权请求和已擦除凭据的认证主体使用AES-256-GCM加密；授权ID作为AAD防止跨记录替换密文，`attributes_key_version`支持后续密钥轮换。
- OAuth授权强制绑定仍处于有效期内的`iam_auth_session`；会话失效后不再允许用State或Code恢复授权。
- Authorization Code消费时间和软撤销状态已落库，重复兑换时能够恢复`invalidated`元数据，不保存Code原文。
- 当前实现主动拒绝Access Token、ID Token、Refresh Token、Device Code和User Code，避免Token轮换链完成前误启用半成品端点。
- MySQL 8.4定向测试共10个，失败0、错误0、跳过0；覆盖加密上下文往返、State/Code哈希查询、Code一次性消费、软撤销以及此前客户端、Consent、迁移和架构门禁。
- `./mvnw verify -B`全量46个Reactor项目全部成功。
- 本阶段尚未注册运行时Bean；后续已完成登录会话解析、环境Secret密钥配置和条件化装配，见上方更新。
- 仍未启用Authorization Server端点，尚未实现RS256/JWKS和Token签发/刷新轮换，共享DEV未执行V7。

## 2026-07-31 - OIDC客户端与同意持久化

- 接入由Spring Boot 4 BOM管理的Spring Security Authorization Server 7.0.6，但尚未启用Authorization Server端点。
- 实现自定义`RegisteredClientRepository`，将公开PKCE客户端和已做Argon2id编码的机密客户端映射到V7规范化表；拒绝明文客户端Secret、可复用Refresh Token、非RS256和非自包含JWT配置。
- 实现自定义`OAuth2AuthorizationConsentService`，授权同意可保存、查询和软撤销，不使用Spring默认原始Token JDBC表。
- 统一抽取UUID与MySQL `BINARY(16)`转换，供MyBatis TypeHandler和OIDC JDBC适配器共同使用。
- MySQL 8.4定向测试共8个，失败0、错误0、跳过0；覆盖V1～V7迁移、客户端插入与更新往返、同意软撤销、明文Secret拒绝和包架构门禁。
- `./mvnw verify -B`全量46个Reactor项目全部成功。
- `private_key_jwt`仍只在V7数据约束中预留；JWK Set与认证签名算法字段尚未设计，因此当前适配器明确拒绝该方式，不能把预留DDL误报成已支持能力。
- 本阶段尚未实现授权上下文、Authorization Code哈希、登录认证、RS256密钥加载、JWKS、Token签发/刷新/撤销及浏览器SSO；后续进度见上方更新，共享DEV仍未执行V7。

## 2026-07-30 - IAM OIDC V7实施计划

### 已确认输入

- IAM已在共享DEV完成Nacos配置加载、Flyway V6识别、健康检查和服务注册组合验收。
- 统一认证升级为OAuth 2.x Authorization Server与OpenID Connect Provider；Portal使用Authorization Code + PKCE。
- 已在DEV执行的V1～V6禁止修改；OIDC持久化只能新增V7。
- 数据库不保存Authorization Code、Access Token、ID Token和Refresh Token原文；现有`iam_auth_session`与`iam_refresh_token`继续作为唯一会话和轮换事实。

### 本分支实施顺序

1. 新增V7客户端、Authorization、Consent、签名密钥元数据及Refresh Token关联DDL。
2. 扩展一次性MySQL 8.4集成测试，验证30张IAM表、约束、索引和V1～V7顺序迁移。
3. 接入Spring Boot 4管理的Authorization Server依赖，并实现自定义持久化映射，禁止采用保存原始Token的默认JDBC表。
4. 实现Argon2id密码哈希、RS256/JWKS、Authorization Code + PKCE、刷新、撤销和退出。
5. 实现`/api/v1/me`和`/api/v1/portal/apps`最小契约。
6. 接入Gateway Resource Server验签和可信上下文。
7. 分阶段执行模块测试与`./mvnw verify -B`，共享DEV迁移必须在代码和迁移评审后单独执行。

### 当前边界

- 本分支不修改Portal和Workbench仓库。
- 不在Flyway中写入真实域名、密码、客户端Secret、私钥或开发用户密码。
- 不在本阶段铺开Integration、CRM、Order、Sales Work或BI业务实现。
- 构建和Testcontainers通过不等于共享DEV、浏览器SSO或真实外部系统验收通过。

### V7数据库阶段结果

- 新增8张OIDC表，将空库顺序迁移后的IAM业务表从22张扩展为30张。
- 现有`iam_refresh_token`增加Authorization关联，仍只保存Token哈希和唯一轮换链。
- 扩展`iam_application`启动约束和`LaunchMode`，为独立内部OIDC客户端预留`OIDC_CLIENT`。
- 一次性MySQL 8.4真实执行V1～V7成功；OIDC外键、唯一约束和无原始Token字段断言通过。
- 定向Maven测试共5个，失败0、错误0、跳过0。
- 后端`./mvnw verify -B`全量46个Reactor项目全部成功。
- V7尚未应用共享DEV，必须完成代码与迁移评审后单独执行。

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
