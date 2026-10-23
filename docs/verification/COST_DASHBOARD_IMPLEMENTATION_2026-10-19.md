# 成本数据看板 V5 实施与验收记录

核对日期：2026-10-19。需求基线为 [V5 产品需求规格](../requirements/COST_DASHBOARD_PRD_V5_2026-10-16.md)。本轮为正式系统代码开发及本地验证，尚未执行共享 DEV/生产迁移、部署、真实账号权限验收或真实业务数据核平。

开发基线：Platform `6ffd6151f4d86a05b7a95c2c7890a23860cf28b1`；Portal `5387e0b06cf5fe140e4ff1bbc3a60e646fe27df0`；均在 `feature/cost-management`。本轮不创建提交或推送，既有需求、Word、原型暂存内容保留。

## 正式数据来源

正式看板只使用现有业务系统录入的真实费用事实。前端没有默认演示金额，没有缺数据补值，没有读取独立原型。源金额为空时保留为空；来源失败时保留最后完整批次并提示更新延迟，无可用批次时返回错误。

| 原业务 | 数据所有者及表 | 新增内部数据集 | BI 分类 |
|---|---|---|---|
| 物流费用 | Order / `order_logistics_cost` | `ORDER_LOGISTICS_COST` | 仅公司承担运费 |
| 成本台账 | ERP / `erp_cost_entry` | `ERP_COST_ENTRY` | 采购、损耗、仓储、办公、营销、待分类场地 |
| 员工报销 | HR / `hr_expense` | `HR_EXPENSE` | 员工报销 |

源系统字段仍由原模块写入。BI 不写原业务表、不跨库查询，不将历史 `bi_city_cost_record`、前端 `cockpit-costs.ts` 或库存估算值纳入新看板。经营费用包含报销，采购和损耗分别统计，工资和押金不计入。

调用链：Portal → Gateway → BI 查询接口 → BI 已发布费用快照。BI 刷新通过现有签名 `analytics-source` API 读取 Order、ERP、HR，另读取 HR 部门、员工及 CRM 城市映射参考。服务地址使用既有服务名配置及注入的 `RestClient.Builder`。

所有新增费用持久化通过 MyBatis-Plus `BaseMapper` / `QueryWrapper` / `UpdateWrapper` 及 Mapper 批量插入、事务行锁实现。旧通用 JDBC 源适配器只增加费用分流，费用读取委托新的 MyBatis-Plus 适配器。

## 已实现的产品功能

| V5 范围 | 正式实现 |
|---|---|
| 费用总览 | 已确认经营费用、笔数、实际两期、变动额/增幅、增加/减少前三项、累计趋势、类别占比 |
| 费用状态 | 未付款、待确认、缺金额、待归属的金额或笔数；进入对应明细 |
| 城市分析 | 全量城市×类别矩阵，金额/城内占比/变动额切换，纵向分组柱图，城市环形占比 |
| 费用构成 | 环形图、纵向瀑布图、全量构成明细；增减颜色与正负号一起表达 |
| 支出对象 | 运输环节、承运方、运输方式、场地、合同、费用类型、渠道、员工、部门、供应商、商品规格、损耗原因；TOP 8 与完整对象表、六个月趋势 |
| 下钻追溯 | 分析弹层、金额最高 8 笔预览、本期和可比期完整明细、当前原单权限与版本核验、原业务详情跳转 |
| 明细与导出 | 搜索、确认/付款状态、分页、全量汇总；异步导出全部匹配记录、进度、取消和受控下载 |
| 页面状态 | 加载、错误、旧快照、无可比数据、权限排除提示、口径说明、全屏、窄屏布局、弹层焦点约束 |

采用业务术语、环形图、折线、纵向柱图、矩阵及纵向瀑布；没有横向条形图。金额计算集中在后端 `CostAnalysis`，使用 `BigDecimal`；接口金额为两位十进制字符串。前端只在绘图坐标使用 Number，精确金额展示使用字符串。

当前月按快照业务截止日计算同日范围，短月裁剪；历史月按完整自然月，全年强制同比。无任何可比期确认记录时不生成增幅、变动排序或假零线。零金额确认记录与缺金额分开。重点变动贡献以同方向增减总额为分母。

全局条件变化清除下钻与搜索，保留明细状态/付款选项并展示；图表发起的明细钻取默认已确认、全部付款状态，避免混入旧明细筛选。读取过程中清空旧金额，并丢弃迟到响应。所有图表、明细、分析弹层、导出固定使用同一 snapshotId。

## 接口、权限和更新机制

正式前缀为 `/api/v1/analytics/supply/cost-dashboard`，沿用 Gateway 已有 analytics 路由。与 PRD 的拟定接口相比，changes/breakdown/trend 合并在 overview 响应中，一次返回同批次报告，避免独立图表请求混用批次。

- `GET overview`：指标、类别/城市/对象分组、变动、矩阵、趋势、主要支出预览及统一元数据。
- `GET records`：全量汇总及分页结果；`periodSide` 控制本期/可比期。
- `GET records/{source}/{id}`：先校验当前可见范围，再调用源详情核验原单权限、状态及 revision。
- `POST exports`、`GET exports/{id}`、`DELETE exports/{id}`、`GET exports/{id}/download`：异步导出、状态、取消及下载。

页面沿用 `/supply-chain/bi/city-cost`、`supply.bi.city-cost`。按系统动作规范，新增读权限 `analytics:cost-dashboard:read` 和独立导出权限 `analytics:cost-dashboard:export`。已有页面授权迁移至新读动作，导出动作不自动授予既有角色。

统计取看板范围与原业务读范围的交集。报销按原员工/部门规则，营销按当前员工归属，仓库来源按原业务双仓约束，手工无关联费用按原创建人和城市范围。未录部门但已明确映射城市的费用，可使用经营城市部门核验 BI 部门范围；原业务范围继续独立核验。未知城市不作为全租户放行条件。

导出还必须满足独立导出数据范围；超出时拒绝任务，不静默导出部分记录。任务属于租户和操作者，查询状态及下载重新校验授权摘要与可见记录集合，权限变化要求重新导出。CSV 单元格防公式注入；取消后的任务不能被迟到工作线程写回 READY；任务及内容 24 小时清理。

新增、编辑、确认、回草稿、作废在原事务中推进版本。文本 UUID 分页每页 1000 笔，校验单调游标、字段、租户和源版本；三域与组织参考均完成后才事务发布。源版本前后不一致会重试，失败不会替换完整批次。

2026-10-19 按用户要求对齐销售业绩看板：成本旧定时入口共用 `rigour.analytics.supply-dashboard.refresh.enabled`、`fixed-delay-ms`、`initial-delay-ms`，默认每轮完成后间隔 1 小时，启动后 60 秒首轮。DEV/生产配置中的旧入口默认关闭；部署有效值还可能被环境变量/Nacos 覆盖。已由同步中心接管的租户（包括停用状态）跳过旧入口，统一由 `BiScheduledRefreshWorker` 在同一次 `BI_REFRESH` 计划认领和心跳下执行经营、费用刷新，频率和启停以该计划为准；费用失败不能回报整体成功。移除成本独立的 5 分钟、90 秒配置。

源版本未变、截止日未变且批次未临近过期时复用完整批次，避免重复复制历史事实。有变化时全量重建，包含更正与作废。旧批次保留 24 小时，过期统一返回 `SNAPSHOT_EXPIRED`。这是异步读取现有真实数据的实现，并非浏览器每次直接查询各业务数据库；不再承诺原 10 分钟内可查目标，也不将正常小时周期误报为超过 10 分钟的更新延迟。失败或未成功状态仍提示延迟，页面保留批次时间。

前端与 `BiSalesMeetingPresentation.vue` 一致，在进入、切换条件、点击刷新时重新读取，无独立定时轮询。`IndexView.vue` 的可选 60 秒报表轮询在销售大屏打开时暂停，不属于销售业绩大屏的自动刷新规则。首次无费用批次时仍可初始化一次；已有批次的页面刷新不强制重读业务源。实际租户计划和线上执行频率未在本轮连接环境核验。

日志覆盖源读取批次/版本/行数/耗时、刷新提交与失败、查询范围、明细定位、导出创建/完成/取消/下载/失败。记录租户、操作者、快照及必要状态，不记录敏感备注、附件正文或整份导出。

## 迁移、发布与开关

新增迁移：

1. HR `V23__hr_expense_analytics_version.sql`：独立报销版本锁。
2. BI `V27__cost_dashboard_snapshots.sql`：批次、费用事实、刷新状态及导出任务表。
3. IAM `V139__cost_dashboard_permissions.sql`：升级既有入口和新增导出动作，推进授权版本。

新表使用 `utf8mb4_general_ci`。本轮只完成迁移静态检查，没有执行共享环境 SQL。发布应先核对最终 profile/Nacos、实例、schema、Flyway 历史，再按源服务兼容接口 → BI/IAM → Portal 顺序验证。不得修改已执行迁移或通过 repair、clean、自动 baseline、乱序执行规避问题。

后端开关 `rigour.analytics.cost-dashboard.enabled=false` 停止新看板查询、刷新及导出清理；前端 `VITE_COST_DASHBOARD_ENABLED=false` 显示尚未开放，停止费用查询。关闭开关不会回退为样例金额。入口路径保留，旧接口不删除；应用版本回退与数据库向前修复按既有发布流程操作。

## 验证边界与证据

测试夹具仅在 Platform `src/test/resources/cost-dashboard` 和 Portal `tests/fixtures/cost-dashboard*`。Portal 正式入口不引用夹具，生产构建产物已检查不含对应固定样例值/标识。隔离视觉页有明确虚构数据提示并拒绝写操作；它不能证明真实账号或真实费用已联调。

[正式组件隔离测试截图](cost-dashboard-component-2026-10-19.jpg) 使用 V5 固定测试数据，已检查 1440、1920 桌面和 390 窄屏布局、城市矩阵及结构图。未将截图中的数值作为公司真实支出。

本地定向测试包含：V5 主指标与城市分类金额核平、两期明细、全部匹配汇总、金额/空值/状态、押金与非公司运费排除、城市映射冲突、最早可选月份、跨年/闰日/短月、原业务权限排除、独立导出范围、CSV 防注入、跨租户拒绝、过期缓存拒绝、取消与到期清理、1,001 笔 UUID 分页和事务回滚。H2 测试使用实际 MyBatis-Plus Mapper 和 Spring 事务；不等同于 MySQL 迁移验收。

未完成的上线验收：共享 DEV/生产数据库迁移、服务发现及真实接口联调、真实账号权限矩阵、三域真实业务样本核平、10 万记录 20 并发性能、10 万记录导出时限、业务代表验收、发布回退演练与运维告警。条件开放的“城市费用规模参照”继续关闭，预算、利润、工资、现金流不在本版范围。

## 本轮实际执行结果

| 检查 | 2026-10-19 结果 |
|---|---|
| Portal `pnpm test:run` | 156 个测试文件、1,089 项测试通过 |
| Portal `pnpm build` | TypeScript 检查和生产构建通过；保留既有大型依赖分包提示 |
| Portal 改动文件 ESLint | 通过 |
| Platform 常规 `./mvnw verify` | 因本机无可用 Docker，必需容器的 IAM 集成测试报错；未宣称常规全量验证通过 |
| Platform 排除必需 Docker 测试后的 `verify` | 全部 reactor 模块构建通过；汇总 1,986 项，其中 1,698 项执行通过、288 项跳过，0 失败、0 错误；另有 18 个必需 Docker 测试文件被显式排除 |
| 成本专项 | BI 22 项领域/服务/权限/持久化测试及 ERP、Order、HR 各 1 项源适配器测试通过，已包含在上述执行结果中 |
| 迁移静态检查 | 10 份冻结基线、464 份历史迁移未改变；新增迁移符合 `utf8mb4_general_ci` 要求 |
| 正式构建数据隔离 | `dist` 未检出本轮测试样例固定值或标识；正式入口不导入测试夹具 |
| 浏览器视觉验证 | 实际 Vue 组件在隔离测试入口完成桌面及窄屏检查；截图金额是虚构测试数据 |

后端排除项对应 17 个唯一类名（`AnalyticsSourceSnapshotContractTest` 分布在两个模块）。可在具备 Docker 的环境直接执行常规 `./mvnw verify` 补齐；本轮在无 Docker 环境采用以下命令，不应将其等同于常规全量验证：

```sh
./mvnw verify '-Dtest=*,!JdbcDhbIncrementalWorkStoreTest,!JdbcCustomerSyncJobStoreTest,!JdbcDhbPageSyncJobStoreTest,!JdbcSyncScheduleStoreTest,!RecoveredProjectionIssuesMapperTest,!JdbcOrderRegisterStoreMySqlTest,!AnalyticsSourceSnapshotContractTest,!TenantDictionaryIntegrationTest,!ErpOrderExecutionIntegrationTest,!ScdpSingleProductMigrationTest,!HrPlaceholderMigrationTest,!HrTargetNavigationMigrationTest,!JdbcSelfPasswordStoreTest,!OrderAttributionAndScopeIntegrationTest,!HistoryOrderSyncMySqlTest,!ScdpAdministratorAuthorizationTest,!BiSupplyScopeIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
python3 scripts/database/prepare-general-ci-baselines.py --check
```

Portal 验证在 Portal 仓库执行：

```sh
pnpm test:run
pnpm build
```

新增及修改的本轮实现文件已加入各自仓库暂存区；未创建 Git 提交、未推送、未执行环境迁移。已有暂存设计资料保留。

### 刷新规则对齐后的补充验证

2026-10-19 在上述开发验证之后，按用户要求调整刷新规则。定向运行 7 个测试类，共 57 项全部通过、0 跳过：包括既有供应链刷新回归、集中任务认领失败不执行、回执丢失只重发不重跑、费用失败报告 PARTIAL、销售占锁跳过、集中接管/停用不绕过、协调服务不可用不自行执行、旧入口开关与集中派发独立控制、租户失败隔离，以及正常 45 分钟旧批次不误报延迟、实际刷新失败仍提示延迟。上述全量执行数字是此次调整前的记录；本次未重复全量构建或前端测试，前端代码未改变。

```sh
./mvnw -pl services/rg-scdp-bi/bi-server -am test -Dtest=CostDashboardRefreshServiceTest,BiScheduledRefreshWorkerTest,CostDashboardServiceTest,CostPeriodTest,CostSourceNormalizerTest,CostSnapshotPersistenceTest,SupplyDashboardQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

本次没有修改数据库或远程调度设置；仍需部署后核验实际租户计划、有效 Nacos 配置、执行日志与真实数据更新时间。
