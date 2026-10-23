# 销售 App 自动识别员工身份

2026-10-23 接续开发。用户明确要求：账号登录后自动判断本人身份，不要求指定验收员工或在 App 手动绑定。本文描述源码行为，尚未部署共享 DEV 或生产。

## 登录与业务流程

1. 原生登录仍由 IAM 完成。App 在公司会话恢复/登录成功后，自动调用 `POST /api/v1/sales/me/profile`；不提交用户、员工、租户、销售编号或权限参数。
2. Sales 将原操作人的租户、用户、会话及版本签名透传到 IAM `GET /internal/v1/iam/supply/authorization?action=sales:context:read`，要求当前授权有效。被撤销的销售权限不会继续沿用旧会话快照。
3. 使用 IAM 返回的工号，经 HR `GET /api/v1/hr/employee-identities/{code}` 核验真实 Long ID 与 `usable`。HR 查询使用仅有 `hr:employee:identity-read` 的服务身份；不按姓名匹配、不使用客户端传入工号。
4. 首次核验通过，在 Sales 自己的 Schema 创建本人销售档案。重复或并发调用返回同一档案；已有停用/过期档案不被重新激活。管理员没有有效员工信息时返回错误。
5. 正式本人拜访、门店、签到等业务每次解析新档案时重新核验 IAM/HR。HR 停用、IAM 撤权/会话失效、员工改绑或上游不可用时拒绝业务请求，不使用旧的成功结果降级放行。

App 首次拜访读取等待同一次身份同步，失败明确展示原因；账号切换清除页面身份状态及待发起请求。身份初始化不创建签到、拜访、门店归属或公司权限。`GET /api/v1/sales/me/profile` 仅核验并读取状态，不创建记录；主要供查询/诊断。

## 数据与兼容

新增 `V28__sales_verified_hr_profile.sql`，历史 V/B 文件保持不变：

- `sales_profile` 新增 `platform_user_id / hr_employee_id / hr_employee_code`；每租户的用户、HR ID、工号唯一，历史 `employee_id UUID` 保留。
- CHECK 约束要求历史 UUID 引用与真实 HR 引用二选一。随机 UUID 仅用于 Sales 自有档案主键，不充当 HR 员工 ID。
- `sales_work_day` 保存真实 `hr_employee_id`，按租户/员工/业务日唯一；原工作日规则与显式签到动作保留。
- Context 返回新增 `hrEmployeeId`（十进制字符串）、`employeeCode`；WorkDay 返回新增 `hrEmployeeId`。新档案的旧 `employeeId` 为 null；旧档案保持旧 UUID。签到 Outbox 新身份使用 `hrEmployeeId` 键，旧身份保留 `employeeId` 键。
- 历史 UUID 身份投影不自动猜测迁移。存在旧绑定或新旧身份冲突时，自动识别返回 `409 SALES_EMPLOYEE_BINDING_CONFLICT`，保留旧记录供正式核对。既有 H5/旧档案业务路径继续保留。
- 自动初始化使用现有 `sales:context:read` 权限；不授予权限或重新启用被停用档案。每次成功同步有审计事件，含真实员工引用及 HR 版本；不记录密码或 Token。

未增加跨 Schema 写入。CRM 客户 Long 与 Sales 门店 UUID 的主数据/归属投影、主管部门范围、坐标系与 200 米规则、长录音上传仍需继续实现或验收；本次身份可用不代表这些能力完成。旧管理列表中的 `employeeId` 仍是历史 UUID 字段，新 HR 档案该字段为空，后续管理端应消费明确的 HR 引用合同。

## 运行配置

默认依照注册中心服务名寻址，使用注入的 `RestClient.Builder`：

- IAM：`rigour.sales.identity.iam-base-url`，默认 `http://rigour-tenant-iam-service`（可由 `IAM_BASE_URL` 覆盖）。
- HR：`rigour.sales.identity.hr-base-url`，默认 `http://rigour-hr-payroll-service`（可由 `HR_PAYROLL_BASE_URL` 覆盖）。

隔离 App DEV 应显式把 IAM 指向隔离 IAM，HR 指向已确认的 DEV HR；不向共享 Nacos 注册个人进程。此次只引用 `iam-api`、`hr-api`，未引入全局 IAM client filter，临时打卡路径不变。

## 验证边界

- 新身份的真实 MySQL 集成测试覆盖：纯读取无副作用、自动同步命令幂等/并发、同员工不能被另一账号认领、停用不可自助复活、改绑拒绝、上游失败、权限缺失、旧绑定保留、两个账号各自门店范围，以及签到持久化真实 Long ID。
- HTTP 协议测试验证 IAM 原会话签名、HR 最小服务权限、管理员无员工、停用/撤权即时拒绝、异租户/错员工响应和故障处理。
- V27→V28 升级测试单独检查旧员工引用、历史 checksum、不重复迁移和新列排序规则。
- App 单元回归、Android 原生编译与真实普通销售/手机验收分别报告。当前没有连接手机，不把编译当作实机通过。

当前只读 DEV 快照：Sales 到 V27，正式档案 1、身份投影 1、门店投影 11；IAM 到 V141、HR 到 V26，均高于此次开始时 `origin/dev` 的 IAM V140、HR V22。禁止据此直接重启旧源码 IAM/HR；本次未执行 DEV V28，也未修改业务身份、权限或临时打卡数据。


## 2026-10-23 最终本地检查

- Sales 全模块执行 323 项测试、0 跳过。初次仅迁移测试的 Spring 异常类型断言不符；改为校验 DataAccessException 与约束名后，单独重跑该测试通过，其余 322 项首次通过。新身份 HTTP、数据库、并发和旧临时打卡回归均包含在内。
- Sales 及依赖 `package -DskipTests` 成功；历史基线检查通过（10 个冻结基线、464 个历史迁移未修改）。
- App 141/141 回归通过、0 跳过；Android 编译、H5 typecheck/lint 通过。
- 根 `./mvnw verify` 在未修改的 IAM 测试停止：刷新令牌断言、重复 analytics:dashboard:read，以及 V120 的排序规则冲突。
- 单独架构模块执行 21 项，其中 15 项通过；DatabaseCollationMySqlTest 的 6 项失败/错误来自冻结 B 基线空库初始化或存量排序规则（含 Sales B27），发生在新增 V28 之前。未改写历史基线以绕过检查。全仓 verify 尚未通过。
- 未重启本地服务，未向共享 DEV 执行 V28，未完成真机验收。

## IDEA 本地重启交接

后端和 App 已于 2026-10-23 合并回用户日常项目。以后默认在以下目录开发；使用其他工作树必须先说明并征得用户同意。服务由用户通过 IDEA 重启，代理不自行操作。

- 后端：`/Users/ethan/myspance/rigour/B2B供应链/自动化系统构建/06_代码工程/rigour-supply-chain-digital-platform`，分支 `feature/hr-target-settings`，已包含功能提交 `69ea6ed4`。
- App：同级 `rigour-sales-workbench`，分支 `dev`，已包含功能提交 `c21a9e5`；HBuilderX 打开其中的 `app/`。
- 合并采用快进，未切换分支。原有 BOM 和 AppDelegate.swift 未提交修改的文件哈希在合并前后相同。

在现有后端 IDEA 工程的 `.run/` 中已准备两个本机运行项：

| IDEA 运行项 | 入口 / 模块 | 用途 |
|---|---|---|
| `SalesApplication - App 本地 26886` | `com.rigour.sales.SalesApplication` / `sales-server` | 加载主工程的新 Sales 代码，核验 IAM 27681、HR 26882 |
| `GatewayApplication - App 本地 27680` | `com.rigour.gateway.GatewayApplication` / `gateway-server` | 保持 App 入口 27680，将 Sales 路由改到主工程 26886 |

这些是当前主工程的运行配置，不是另外的项目或分支。文件名为 `.run/sales-app-sales-local.run.xml`、`.run/sales-app-gateway-local.run.xml`，复用现有 IDEA 签名密钥，权限 600，已用本机 `.git/info/exclude` 排除，不提交 Git。两者禁用 Nacos 配置/发现/注册，Sales 禁用临时打卡/AI，以免个人联调进程影响共享服务。Gateway 保留原生 App 现有 IAM、CRM、ERP、Order 目标和令牌校验设置，仅将 Sales 下游改为 26886。

重启顺序：

1. 在 IDEA 执行 Reload All Maven Projects，以加载新增的 iam-api / hr-api 依赖。
2. 停止现有占用 26886 的 Sales，再运行 **SalesApplication - App 本地 26886**。
3. 由用户停止旧 App Gateway（27680，创建配置时 PID 39074，停止前需重新核对），再运行 **GatewayApplication - App 本地 27680**。旧进程来自之前的命令行，不一定显示在 IDEA；新配置不能与它同时占用端口。
4. 主开发 Gateway 26880、IAM、HR、CRM、Order 无需因本次改动重启。原先的旧 Sales 27686 不再是新 Gateway 的目标；验收新路由后再清理旧进程，不能先按旧 PID 盲目停止。

只重启 Sales 不会改变旧 Gateway 的内存路由，因此本次需同时替换 App Gateway。代理尚未替用户重启任何进程，新增运行项也尚未做真实启动验收。

DEV Sales 启动会通过既有 Flyway 执行 V28，修改共享 DEV Sales Schema。一次性 MySQL 的 V27→V28 升级已通过；最近真实 DEV 查询仍为 V27。启动后核实 26886/27680 health、V28 与历史 checksum、实际 Sales 路由、真实 IAM 登录及 HR 身份识别。手机连接后再同步主目录中的 App，健康检查不代替真实登录验收。

合并回主目录后重新验证：App 141/141、Android 编译通过；后端身份与升级 18/18、0 跳过，Sales/Gateway 及依赖打包通过。原完整 Sales 323 项与全仓失败边界见上文，不把定向复跑写成全仓通过。
