# BI 数据范围契约

## 调用约定

- 注入 `com.rigour.analytics.application.service.BiDataScopeService`。
- 所有按城市/销售查询的入口先执行 `var scope = scopes.resolve(regionCode, ownerStaffCode)`。
- 查询只传 `scope.tenantId()`、`scope.regionCode()`、`scope.ownerStaffCode()`；不得再传原始城市/销售参数。
- `scope.fullTenant()` 只表示已通过当前签名身份的租户管理员授权，不能从请求参数设置。
- 跨主体汇总、刷新、来源对账、档案与全租户治理入口执行 `scopes.requireGlobalGovernance()`。
- 读取已有任务、修改目标等按对象操作，执行 `scopes.requireObjectScope(objectRegionCode, objectOwnerStaffCode)`；对象必须先按当前租户查询。不能把请求参数当作对象归属。
- `GET /api/v1/analytics/supply-dashboard/effective-scope` 返回 `accessLevel` (`TENANT` / `CITY` / `SELF` / `DENIED`)、原因、授权城市、本人销售及默认范围。
- 多城市授权不隐式扩大为全部城市；缺省采用已授权城市排序后的第一个。显式查询未授权城市或其他销售返回拒绝。

## 信任边界

已存在的 IAM `TENANT_SUPER_ADMIN` 角色且拥有 `analytics:dashboard:read` 可读全租户。其他账号必须有未过期的本地范围投影，签名身份的安全版本及租户策略版本必须一致。投影只能来源于 IAM 的 DataScope 决定、HR 员工主档与 IAM 账号绑定、CRM 的精确员工编码关联，禁止姓名猜测。

新增 IAM 只读入口 `GET /api/v1/iam/bi-identity?userId=...` 使用 IAM 已验证 JWT。普通账号仅能查本人；跨账号查询必须是 `TENANT_SUPER_ADMIN` 且已有 `iam:data-scope:write`。返回 IAM 本库精确账号绑定、有效 BI 策略、安全版本和同源外部员工 ID，不返回姓名、手机或凭据。IAM 应用层返回自身模型，由 controller 显式转换 API DTO。

BI 先校验 `/api/v1/me` 的租户、账号与当前签名身份一致，再读取上述身份入口，按 IAM 员工编码精确查询 HR。历史编码不同，仅允许 IAM 外部绑定与 HR `/internal/v1/hr/employees/source-resolve` 的同源 ID 精确匹配，传入 `employeeNames=[]`；无唯一关联时拒绝，不猜测姓名。HR 必须在职。CRM `ownerEmployeeCode` 只作为精确业务关联契约，零客户合法，不要求城市总、HR 或新销售本人有成交/客户。

缺少已验证投影时返回 `DENIED`，不自动授予用户/角色、不把 BI 读取权限当全租户权限。授权城市由管理员从真实 CRM 城市字典选择，不根据客户、成交或同名城市推断。

`SELF` 固定本人销售和已授权城市；`CITY` 固定到已授权城市，可再按城市内销售缩小。无法可靠按销售/城市分区的库存、采购、来源水位不向受限账号返回；这些数据后续要依赖可信仓库/城市关联再开放。城市总成本只对城市范围开放，不能当作员工个人成本。

## 配置与续期闭环

- `POST /api/v1/analytics/supply-dashboard/data-scopes/synchronize`：`{userId, iamPolicyIds: UUID[], regionCodes: string[]}`。需既有租户超管与 `iam:data-scope:write`。先使旧范围失效，再从 IAM、HR、CRM 源 API 核验并建立 V12 本地投影；失败不恢复旧授权。
- `DELETE /api/v1/analytics/supply-dashboard/data-scopes/{userId}`：同一管理权限，撤销投影并写审计。
- 不写 IAM 角色、用户授权、HR 员工或 CRM 客户，不能直接提交员工编码或伪造客户引用。
- 有效期八小时；使用时距离上次核验达到十五分钟便自动重验。仅重验已有策略、城市和员工，集合必须完全一致，采用旧核验时间及安全版本的 CAS 更新续期，不自动增加范围。源不可用或关联改变时拒绝并失效；安全版本改变需管理员重新核验。
- HR/CRM 目录访问为 BI 专用签名 SERVICE 身份，仅有 `hr:employee:read` 和 `crm:customer:read`，租户来自先前通过 IAM 验证的账号。不伪装登录用户，不给账号新增权限；JWT 仅发往固定配置的 IAM 地址。
- 部署需启用 IAM 新只读入口、BI V12 迁移，并配置 `rigour.analytics.scope.iam-base-url`、`hr-base-url`、`crm-base-url` 为可信服务地址。默认本地端口分别为 26881、26889、26883；连接/读取超时为三秒/十秒。

Portal 使用 `<BiScopeSettings v-model="visible" @changed="reload" />`，所有账号、策略和城市选项来自现有 IAM/CRM API，不提供内部 ID 文本输入。主页面用 `src/api/core/bi-access.ts` 的 `getBiEffectiveScope()`，先解析默认范围，再取筛选和数据。

## 跨主体边界

SELF 的 `unavailableSubjects` 包含 `INVENTORY`、`PROCUREMENT`、`CITY_COST`、`RECONCILIATION`、`SOURCE_GOVERNANCE`、`LEGACY_PERIOD_RECEIPTS`。CITY 则包含 `INVENTORY`、`PROCUREMENT`、`RECONCILIATION`、`SOURCE_GOVERNANCE`。客户历史与合法目标已按以下 SQL 规则开放，不再永久禁用。此字段描述账号能力；具体查询仍可因筛选粒度不兼容返回不可用，前端不能把隐藏值补成全租户数据、样例或零。

逐项 SQL 审计及修复（`SupplyDashboardQueryMapper.java`）：

- `cityCostSummary`、`cityCostTrend`、`cityCostRanking`：租户和 `region_code` 都有严格谓词。已对白名单 CITY 开放真实成本汇总、趋势、排行；要求没有 owner、客户类型、分类、来源细分，否则总成本除以部分销售会失真。SELF 继续禁用。
- `cityTargetCompletions`：事实子查询有城市过滤，目标外层也有 `t.dimension_code=regionCode`，同一完整城市业务范围可返回；员工/客户/商品/来源细分不套用城市总目标。
- `salesTargetCompletions`：已按 `OperatingWorkspaceMapper` 的真实 BI 关联规则补外层目标范围。须在本城客户或订单中有该员工关联，且客户和订单中均没有外城、空城市关联；owner 参数同时限定目标主体。完整本城目标对 CITY/SELF 开放，不按比例分摊跨城目标；没有业务城市证据的目标不能推定属于当前城。全租户无城市筛选保留所有目标；客户类型、商品分类或来源细分时，不把整人目标与部分业绩硬比较。
- `customerSegmentSummary`、`customerActivityRanking`、`customerChurnRiskRanking`：已在各自的 `period_orders`、`history_orders`、`period_payments` 九段 CTE 中加入事实自己的 `region_code` 和 `owner_staff_code` 谓词。当前客户归属与历史事实归属必须同时在请求范围内，客户转归属不再带入外城、前销售金额及最后成交/回款时间。无城市和员工筛选时仍保留全租户真实历史。三类分析及活跃/流失指标已对 CITY/SELF 开放；商品分类不在这些客户查询的粒度内，该筛选下不返回这些图表。
- `collectionSummary`、`collectionTrend`：有城市谓词，销售条件却是 owner 或 collector。CITY 未选销售且未选商品分类时可返回真实期间回款，SELF 隐藏这项。经营分析按 owner 分组再过滤后仅返回本人数据。
- 库存及补货聚合包含全租户共享库存，没有可靠城市仓库关联。`AnalyticsCityProductSupplyController` 强制 `requireGlobalGovernance()`，已知 warehouseId 不能绕过；没有使用销售城市伪造库存归属。

以上是字段级可用边界，不代表城市总、销售全部工作均已闭环。仍未支持：跨城整人目标的单城归属、缺少业务关联的目标城市推定、按商品分类分析客户活跃、按客户类型/商品/来源分拆个人目标、SELF 的旧收款人混合口径期间回款、没有真实仓库城市关系的受限库存。不会通过假数据、分摊或姓名猜测补齐这些缺口。

## 验证边界

回归测试涵盖超管、普通销售、缺失身份、多城市默认、参数篡改、报表绕过、零客户合法性、同源 ID、自动续期与并发撤销。新增 `BiCustomerTargetScopeRepositoryTest` 使用独立 H2 实际执行 MyBatis 动态 SQL（仅适配 DATEDIFF/DATE_FORMAT 方言），验证两城市客户转归属、同城前销售、收款人不能替代归属销售、跨租户隔离、未筛选完整历史、跨城/未知城市目标排除及零成交但已有客户关联的合法目标。

客户/目标 SQL 修改晚于主任务上一轮 verify，需要主任务重新编译并执行 `BiCustomerTargetScopeRepositoryTest`、`SupplyDashboardRestrictedOverviewTest`、`BiDataScopeServiceTest` 后再验收。部署、真实用户授权配置与登录浏览器尚需主任务统一验收；源码及测试存在不代表生产投影已建立。按并行构建协调约定，本工作单元不运行 Maven，也不修改 IAM 代理修复或已应用的历史迁移。
