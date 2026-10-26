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

首次只读快照为 Sales V27；随后用户启动主项目 Sales 已执行 V28。IAM/HR 迁移差异需按下方最新说明处理；未修改业务身份、权限或临时打卡数据。


## 2026-10-23 最终本地检查

- Sales 全模块执行 323 项测试、0 跳过。初次仅迁移测试的 Spring 异常类型断言不符；改为校验 DataAccessException 与约束名后，单独重跑该测试通过，其余 322 项首次通过。新身份 HTTP、数据库、并发和旧临时打卡回归均包含在内。
- Sales 及依赖 `package -DskipTests` 成功；历史基线检查通过（10 个冻结基线、464 个历史迁移未修改）。
- App 141/141 回归通过、0 跳过；Android 编译、H5 typecheck/lint 通过。
- 根 `./mvnw verify` 在未修改的 IAM 测试停止：刷新令牌断言、重复 analytics:dashboard:read，以及 V120 的排序规则冲突。
- 单独架构模块执行 21 项，其中 15 项通过；DatabaseCollationMySqlTest 的 6 项失败/错误来自冻结 B 基线空库初始化或存量排序规则（含 Sales B27），发生在新增 V28 之前。未改写历史基线以绕过检查。全仓 verify 尚未通过。
- 以上测试完成时未执行共享 DEV 迁移；随后用户已启动主工程 Sales 并执行 V28，真实身份验收仍未完成。

## 主项目 IDEA 环境（2026-10-23）

后端位于 `/Users/ethan/myspance/rigour/B2B供应链/自动化系统构建/06_代码工程/rigour-supply-chain-digital-platform`，分支 `feature/hr-target-settings`；App 位于同级 `rigour-sales-workbench`，分支 `dev`。所有开发默认在上述日常目录，其他工作树需事先获得用户同意。

仅使用用户原有 IDEA 运行项：GatewayApplication 26880、IamApplication 26881、SalesApplication 26886。Gateway 的 Sales 路由继续用 `lb://rigour-sales-work-service`，不为 App 另建端口或启动配置。现有 Nacos 环境按服务名发现依赖；共享注册中心实例可能来自其他机器，应在真实登录验收时检查解析目标，不能把所有发现地址都称为本机。

App 的 Gateway / issuer 已统一为 `http://localhost:26880` / `http://localhost:26881`，原 HTTP 精确白名单同步更新；Android 使用 ADB reverse 26880/26881。issuer 改变后重新登录，不迁移旧 refresh token。

IAM DEV 原生登录已在源码启用，仅允许 `rigour-sales-app-android-dev`；PUBLIC 客户端已存在且 ACTIVE，bootstrap 明确关闭。生产默认仍关闭。源码修改需要 IAM 重启才会生效。

**当前不能直接重启 IAM**：真实 DEV 只读 Flyway validate 返回 V139 CHECKSUM_MISMATCH。库中的 V139 为 `cost_dashboard_permissions`、checksum -812060856；主代码的 V139 为 `iam_payment_risk_page_read_permission`、checksum 466362980。DEV 还存在 V141 `iam_converge_cost_and_payment_risk_permissions`（477565154），其源文件也未进入主项目或 origin/dev。需要取得原始脚本并正确处理环境历史；禁止 repair、忽略 mismatch、关闭 Flyway 或改写已执行生产迁移来绕过。当前 IAM 老进程仍健康，但不能据此宣称新配置已加载。

已按用户授权停止之前独立启动的 5 个 App JAR；旧监听端口全部释放。删除了为那套环境新增的两份 `.run/sales-app-*.run.xml` 及其专用 Git 排除条目，原有 IDEA 运行项和密钥保持原状。没有停止或重启任何主项目 IDEA 服务，没有写业务数据。后续只在确有必要时告知用户重启具体服务，由用户操作。

Sales V28 已由用户的主工程 Sales 启动于 17:05:44 成功执行；新列存在，现存 HR 身份档案仍为 0。新身份链路真实登录待验收。

统一端口后重新验证：App 141/141、Android 编译通过；IAM 原生登录与客户端 bootstrap 定向测试 8/8。完整 Sales 323 项与全仓既有失败边界见上文；不能把定向通过写成全仓或新环境登录通过。
