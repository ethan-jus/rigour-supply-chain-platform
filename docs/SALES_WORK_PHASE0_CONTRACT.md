# Sales Work 阶段 0 契约与 KPI 边界 V1

日期：2026-08-06

状态：阶段 0 实施基线。本文只冻结阶段 1、阶段 2 所需的上下文、客户目标和外勤考勤契约，不提前实现拜访、录音、AI、HR 正式考勤或 BI 经营指标。

## 1. 适用基线

- 产品与数据主权以《飞书销售工作台与销售管控后台架构设计 V1.1》为准。
- HTTP、请求头、响应体、分页和 OpenAPI 生成规则以《跨仓库接口与环境约定 V1.0》为准。
- Platform 是 OpenAPI 契约来源；Workbench 的生成代码只能放在 `src/api/generated/`，不能手写生成目录。
- Sales Work 是销售打卡事实、工作日、定位、拜访和证据关系的唯一主写服务。
- CRM 是客户、门店和销售归属的唯一主写服务；Sales Work 只保存最小只读投影和不可变快照。
- HR/Payroll 是正式考勤结果和薪酬结果的唯一主写服务。
- Analytics BI 是经营指标、指标字典、口径版本和完整看板的唯一主写服务。

## 2. 阶段 1、阶段 2 API

### 2.1 销售上下文

| 方法 | 路径 | 用途 | 数据主权 |
|---|---|---|---|
| GET | `/api/v1/sales/me/context` | 当前用户、员工、销售画像、城市范围、能力和当前生效规则 | IAM、HR、Sales Work |
| GET | `/api/v1/sales/me/visit-targets` | 当前用户可拜访的客户、门店和有效归属摘要 | CRM；Sales Work 只读投影 |

上下文和客户目标查询必须从签名调用人取得 `tenantId/userId`，不能信任浏览器提交的用户 ID、角色或 DataScope。

### 2.2 外勤考勤

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/sales/work-days/check-in` | 创建当日销售工作日、追加签到事件并创建定位会话 |
| POST | `/api/v1/sales/work-days/{workDayId}/location-points:batch` | 批量接收前台定位点，重复点必须幂等 |
| POST | `/api/v1/sales/work-days/{workDayId}/interruptions` | 记录页面隐藏、权限关闭或定位失败等证据中断；不会关闭定位会话 |
| POST | `/api/v1/sales/work-days/{workDayId}/check-out` | 追加签退事件、关闭定位会话并形成日结候选 |
| GET | `/api/v1/sales/me/work-days/{date}` | 查询本人某业务日的工作状态、定位摘要和异常 |

所有移动端写命令都必须包含 `idempotencyKey` 或等价的 `deviceEventId`。服务端同时保存客户端发生时间和服务端接收时间，业务状态判定使用服务端时间。

## 3. 请求和响应约束

### 3.1 请求头

- `Authorization: Bearer <token>`：由 Gateway 验证的访问令牌。
- `X-Request-Id`：调用链 ID，缺失时由平台生成。
- `Accept-Language`：默认 `zh-CN`。
- `X-Tenant-Id` 只能作为客户端选择提示，不能覆盖令牌和 Gateway 签名上下文中的租户。

### 3.2 标准响应

成功和错误均使用平台 `ApiResponse`，包含 `code`、`message`、`data`、`requestId` 和 `timestamp`。领域错误使用 `DOMAIN_REASON` 命名，不把 SQL、Token、飞书 code、精确位置或第三方原始响应写入响应体。

### 3.3 阶段 2 最小请求字段

签到和签退：

- `clientOccurredAt`：客户端观测时间，只作为证据；
- `idempotencyKey`：客户端实例内唯一且可重试；
- `location`：经纬度、精度、来源和采集时间；
- `clientInstanceId`：设备或 WebView 实例标识，不作为身份认证依据。

位置批量上报：

- `points[]`：`deviceEventId`、经纬度、精度、来源、客户端时间；
- `idempotencyKey`：本批次幂等键；
- 单批数量和请求体大小由服务端限制。

## 4. 外勤状态机

### 4.1 工作日

```text
NOT_STARTED
  -> ACTIVE              签到事务成功
ACTIVE
  -> FINISHED            签退事务成功
  -> PENDING_REVIEW      产生需要人工处理的异常
```

禁止重复创建同一租户、同一员工、同一业务日期的有效工作日；禁止页面打开、定位成功或客户端时间自行创建工作日。

### 4.2 定位会话

```text
ACTIVE  -> CLOSED       签退成功
```

页面隐藏、权限关闭或定位失败记录为 `sales_work_interruption` 证据事件，会话仍保持 `ACTIVE`，直到签退后变为 `CLOSED`。定位中断不单独判定旷工、不自动扣减绩效。重复位置点返回幂等结果，不产生第二条业务事实。

### 4.3 规则版本

阶段 1 只能读取当前有效规则并固化 `fieldPolicyVersionId`；规则参数不得散落在页面或 Controller 常量中。规则完整的草稿、审批、发布和历史查询属于后续规则管理切片，但不能以硬编码替代服务端规则解析。

## 5. 权限和数据范围

| 主体 | 阶段 1、2 范围 |
|---|---|
| 销售人员 | 只能读取和写入自己的上下文、工作日和定位证据 |
| 销售主管/城市负责人 | 阶段 2 只预留管理查询权限，不在 H5 复用销售本人写接口 |
| 系统管理员 | 不因系统管理员身份自动获得精确位置或录音敏感能力 |

领域服务必须同时执行租户隔离和业务 DataScope；仅有 Gateway 身份验证不等于允许访问目标数据。

## 6. KPI 和指标边界

阶段 0、1、2 不新增绩效 KPI，不在 Workbench、Sales Work 或 Portal 页面自行计算销售额、排名、提成、有效拜访率、转订单率等经营指标。

阶段 2 只保存可供后续 BI 使用的事实和运行摘要：

- 签到、签退的服务端时间；
- 工作日和定位会话状态；
- 定位点数量、中断数量和证据质量；
- 使用的规则版本；
- 幂等、审计和 Outbox 事实。

这些字段是业务事实或操作摘要，不是绩效结论。后续 KPI 必须在 BI 指标字典中定义指标编码、名称、公式、时间范围、状态口径、数据来源、版本、负责人和数据截至时间，并由 BI 从领域标准事实计算。Sales Work 只能发布领域事件和日结候选，不能跨库现算 KPI。

## 7. 阶段退出条件

阶段 0：接口路径、请求/响应、状态机、权限边界、数据主权和 KPI 边界已记录，未引入第二套事实来源。

阶段 1：真实实现能够返回当前销售上下文、当前规则和本人有效客户/门店目标；无浏览器伪造用户或门店主档写入。

阶段 2：真实实现能够完成签到、定位批量上报、签退和本人工作日查询；重复请求不产生重复事实，跨租户和非法状态被拒绝；日结候选可追溯到规则版本。

构建、单元测试或 Testcontainers 通过只证明代码和测试环境，不等于共享 DEV、真实飞书、COS 或 HR/BI 外部验收通过。
