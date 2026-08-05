# 跨服务协同开发规范

版本：v1.0
状态：生效
适用范围：`rigour-supply-chain-platform`、统一 Portal、飞书销售 Workbench 以及后续接入本平台的内部应用。

本文件解决一个实际问题：同一能力只能有一个事实来源和一个代码所有者。以后开发前先判断“谁拥有这项能力”，再写代码；不能因为某个服务当前需要数据，就把第三方调用、数据库写入和业务流程复制到这个服务里。

## 1. 文档与事实的权威顺序

发生冲突时按以下顺序判断，不以聊天记录、旧截图或历史工作日志作为当前设计依据：

1. 本文件：跨服务协同规则和变更门禁。
2. [`SERVICE_BOUNDARIES.md`](SERVICE_BOUNDARIES.md)：服务职责、数据主权和 Schema 所有权。
3. 各服务目录下的 `README.md`：该服务的运行、接口和当前实现事实。
4. API、事件和第三方协议文档：调用方可依赖的具体契约。
5. `docs/architecture.md` 和产品/架构 ADR：总体设计和决策背景。
6. `docs/WORKLOG.md`：只记录历史，不代表当前实现。

如果需要改变服务职责，先更新边界文档和设计记录，再改代码；不能先复制一套实现，等以后再整理。

## 2. 平台服务地图

| 组件 | 唯一职责 | 明确禁止 |
|---|---|---|
| Gateway | 路由、JWT 校验、可信调用人上下文、统一入口安全策略 | 业务状态机、第三方 SDK、跨库查询、拼装业务数据 |
| IAM | 租户、组织、用户、角色、资源、授权和会话 | 订单、库存、第三方账号、业务统计 |
| Integration | 外部系统连接器、Secret 引用、认证、分页、限流、重试、Raw Landing、同步批次和归一化事件 | 内部订单主状态、员工浏览器登录、Portal 页面、跨领域业务规则 |
| 领域服务 | 自己领域的主数据、状态机、事务和 Schema | 直接访问第三方、读取其他 Schema、复制其他服务的实现 |
| Portal/Workbench | 页面、交互、路由体验和 API 调用 | 业务状态机、直接访问数据库、保存第三方凭据、直接调用第三方 API |

当前每个领域服务的具体所有权见 [`SERVICE_BOUNDARIES.md`](SERVICE_BOUNDARIES.md)。一个能力只能有一个“主写者”；其他服务只能通过 API、事件或只读本地投影使用它。

## 3. 先判断代码应该放在哪里

### 3.1 决策表

| 需求内容 | 放置位置 | 例子 |
|---|---|---|
| 第三方协议、认证、API Key/账号密码、HTTP 超时、重试、限流、分页、Webhook | Integration 的 `infrastructure` | `DhbClientAdapter` |
| 外部原始响应、同步游标、同步批次、死信、对账和连接探活 | Integration Schema | `integration_raw_landing`、`integration_sync_checkpoint` |
| 内部订单、库存、客户或销售的业务状态机 | 对应领域服务 | 订单中心维护 `internalStatus` |
| 外部数据导入后的内部事实 | 对应领域服务的导入用例 | 订单中心接收 `OrderImported`，自己事务落库 |
| 统一认证、租户、角色、菜单、按钮和 API 权限 | IAM/Gateway | Portal 只展示后端返回的权限结果 |
| 页面、表单、卡片和交互状态 | Portal/Workbench | 不复制后端授权和业务计算 |

### 3.2 订货宝示例（强制遵循）

订货宝只能有一套外部实现：

```text
Portal -> Gateway -> Integration
                    ├─ DhbClientAdapter
                    ├─ SecretResolver
                    ├─ 同步批次 / Raw Landing / 订单镜像
                    └─ 内部导入契约或事件
                                      -> Order Center
                                         └─ 内部订单主表和状态机
```

因此：

- `DhbClient`、订货宝 URL、账号、密码、API Key、签名、重试和限流只能出现在 Integration。
- Order Center 不得新增 `DhbClient`、订货宝登录配置、第三方重试或“手工同步”接口。
- Order Center 只接收内部导入契约/事件，负责内部订单幂等、事务和状态机。
- Portal 只调用本平台的 Integration/Order API，不把 Token 或第三方密码转发给外部系统。
- 新增商品、客户、订单等外部来源时，先复用 Integration 的连接器模式，不在业务服务中新增 Provider。

详细实现见 [`INTEGRATION_DATABASE_RUNTIME.md`](INTEGRATION_DATABASE_RUNTIME.md)、[`DHB_API_CONTRACT.md`](DHB_API_CONTRACT.md) 和 [`ORDER_CENTER_DHB_INTEGRATION.md`](ORDER_CENTER_DHB_INTEGRATION.md)。

## 4. 开发前的五分钟边界检查

每个任务开始前，开发者必须完成以下检查，并把结果写入任务描述或 PR：

1. **搜已有实现**：使用 `rg` 搜索供应商名、外部域名、表名、接口路径、类名和权限码；不要只搜当前服务。
2. **确认唯一所有者**：在 `SERVICE_BOUNDARIES.md` 和服务 README 中确认代码、Schema 和 Secret 的所有者。
3. **确认调用方向**：写清楚 `调用方 -> 契约 -> 所有者`，禁止“为了方便”直接依赖实现模块或跨库 SQL。
4. **确认数据主权**：说明谁写主表、谁只读投影、幂等键是什么，以及失败后如何重试。
5. **确认安全边界**：说明 `tenantId`、`principalId`、权限、Secret 和日志脱敏如何处理。

如果搜索发现已有同类 Provider、接口或配置，默认是复用或重构，不是复制；确需两套实现时，必须在设计记录中说明不同供应商、不同协议或不同生命周期。

## 5. 跨服务变更流程

跨服务功能按以下顺序推进：

### A. 先写变更摘要

至少包含：

```text
功能：
唯一所有者：
调用方：
API/事件契约：
写入 Schema/表：
只读投影：
租户与权限：
Secret 来源：
失败、重试和幂等：
需要评审的仓库：
```

### B. 先冻结契约，再写实现

- HTTP 使用版本化 API；Java 调用契约放在提供方的 `<domain>-api` 模块。
- 新增事件必须包含 `eventId`、`schemaVersion`、`tenantId`、`correlationId`、`occurredAt` 和 `producer`。
- 事件只传下游必需的业务字段，不传密码、API Key、Token、原始敏感报文或浏览器会话。
- 跨服务命令不允许让调用方指定另一个租户；租户来自可信 Gateway 上下文或受验证的内部消息。
- 契约变更优先新增版本或兼容字段；不得在未迁移调用方时修改现有字段语义。

### C. 再分别实现

- 提供方先实现契约、授权、幂等和持久化。
- 调用方只依赖 API/事件，不依赖提供方 `*-service` 实现模块。
- Portal 只在后端契约稳定后接入页面；不要用前端 Mock 掩盖后端未完成。
- 数据库迁移由 Schema 所有者维护；其他服务不得改写该 Schema 的 Flyway。

## 6. 服务 README 规则

不是每个服务都再写一份重复的“大架构文档”。采用“一份平台规范 + 每服务一份事实卡片”的方式：

- 平台规则集中在本文件和 `SERVICE_BOUNDARIES.md`。
- 每个进入业务开发或承担跨服务契约的服务目录必须有 `README.md`，只写该服务的职责、禁止事项、入口、契约、Schema、Secret、启动和验收链接；仍是空骨架的服务在首次进入业务开发前补齐。
- 服务 README 不复制完整 API 文档；接口细节链接到 `docs/` 或服务 API 模块。
- 任何新增服务在进入业务开发前，必须补齐 README；职责变化必须同 PR 更新 README 和边界表。
- 模板见 [`SERVICE_README_TEMPLATE.md`](SERVICE_README_TEMPLATE.md)。

当前已补齐：

- [`Integration README`](../services/rigour-integration-migration-service/README.md)
- [`Order Center README`](../services/rigour-order-center-service/README.md)

## 7. Git 与评审门禁

```text
main       稳定、可发布基线
dev        共享 DEV 基线
feature/*  单一目标的功能分支
fix/*      缺陷修复分支
```

每个 PR 必须回答：

- 是否新增了第三方调用？若是，为什么不在 Integration？
- 是否新增/修改了跨服务 API、事件、权限、表或 Secret？
- 是否出现跨 Schema SQL、实现模块依赖、重复 Provider 或直接前端外调？
- 是否删除了被替代的旧实现、配置、权限、路由、测试和文档引用？
- 是否完成 lint/typecheck/test/build 或 `./mvnw verify`，以及必要的共享 DEV/外部联调验收？

合并前至少需要提供：

1. 代码 owner 评审；
2. 架构边界检查；
3. 数据库和安全影响检查（适用时）；
4. CI 通过；
5. 明确区分“构建通过”“接口集成测试通过”“真实 DEV/第三方验收通过”。

## 8. 日志与排障要求

关键跨服务链路必须记录可关联但不敏感的字段：`requestId`、`correlationId`、`tenantId`、`principalId`、`connectorId`、`syncRunId`、服务名、路由、耗时和稳定错误码。

禁止记录：密码、API Key、Access/Refresh Token、Authorization Code、Cookie、完整请求体、收货地址和不必要的个人信息。第三方错误消息需要截断和脱敏。

排障顺序固定为：

```text
Portal -> Gateway -> 所属服务 -> 外部系统/消息 -> 数据库投影
```

先用 `requestId` 对齐各服务日志，再判断是认证、路由、权限、服务不可用、外部协议还是数据投影问题；不要通过在多个服务复制日志和调用代码来“试错”。

## 9. 完成定义（Definition of Done）

任务只有同时满足以下条件才能标记完成：

- 代码放在唯一所有者服务，调用方向和数据主权已写清楚；
- API/事件、权限、tenant 隔离、幂等和错误码已评审；
- Secret 只使用引用，未进入 Git、Nacos、数据库明文、前端或日志；
- 数据库迁移、索引、回滚/兼容策略和账号权限已确认；
- 重复实现、旧入口、旧配置和孤儿引用已清理；
- 自动化验证通过；
- 共享 DEV 和第三方真实验收状态单独记录，未验证项明确标记；
- 服务 README、边界文档和必要的 API/事件文档同步更新。

## 10. 违规处理原则

发现重复实现时，先暂停新增代码，保留已有调用方可用的那一套作为基线，迁移另一套调用方，验证后删除重复实现、配置、权限和测试。不得通过增加“暂时禁用”开关、复制目录或注释代码把问题推迟到以后。
