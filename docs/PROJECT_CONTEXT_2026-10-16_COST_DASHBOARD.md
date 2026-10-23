# 项目核对补充与成本看板设计基线

日期：2026-10-16。配合 `PROJECT_CONTEXT.md` 使用；本文件记录当前源码差异，不将旧快照或文档视为运行事实。

## 当前基线

- Platform `feature/cost-management`：`6ffd6151f4d86a05b7a95c2c7890a23860cf28b1`；Portal 同分支：`5387e0b06cf5fe140e4ff1bbc3a60e646fe27df0`。任务开始均干净，未 fetch 远程。
- Workbench `main`：`819b726eb647f9d3f5bcd58e8cd082d7e2dd5ff3`，相对本地缓存 origin/main 落后1；未改动。
- 递归 POM 仍47工程、Gateway加10领域启动应用；AI无业务 Controller。
- 源码迁移上限：IAM138、Integration27、CRM27、ERP40、Order57、Sales27、HR22、BI26、Foundation40。不是实际执行历史。
- 旧索引中“ORDER_PAYMENT_STATUS、HR目标完成尚未接入”的历史描述不应继续当现状；当前 `BiSourceDatasets` 已有 ORDER_PAYMENT_STATUS。本轮未对所有目标功能重新验收。

## 费用事实与设计约束

- 费用唯一写者：ERP `erp_cost_entry`（PURCHASE、LOSS、MARKETING、RENTAL）；Order `order_logistics_cost`；HR `hr_expense`。
- 当前费用为手工台账；office-rentals 在 Portal 复用 ERP warehouse-rentals，按 OFFICE 类型筛选。新增 source_system/premises_type 见 ERP V40、Order V57。
- DRAFT/CONFIRMED/VOID；编辑已确认后回待确认。ERP/Order草稿允许金额空，HR必填；空、零不能混淆。付款仅 PAID/UNPAID，不可推导实付流水。
- 现有 BI 尚未采集上述三个成本数据集，旧 `bi_city_cost_record` 独立保留，`cockpit-costs.ts` 样例不作实际数据。
- 接入前置：源仓储及 BI projector 只支持数值/二进制UUID游标，而费用ID为VARCHAR UUID；需新增TEXT_UUID排序和校验。guard.version当前仅见加锁读取，不可假设它已经是可靠变更版本。
- 经营城市由 `OperatingCitySql` 以 HR 销售部下级部门代码构成，CRM地区唯一名称匹配生成 source_region_code。费用必须使用已确定映射；多义/缺失不能猜。
- 报销 OFFICE_RENT 与 ERP办公场地费用可能重复；此为模型风险，不是已查出的真实重复金额。未经核实只提示不自动扣款。

## 本轮交付与持续规则

首次交付为需求与原型 V1，需求见 `requirements/COST_DASHBOARD_PRD_2026-10-16.md`，Word同目录；交互原型在独立仓库 `../../cost-management-prototype/cost-dashboard/`。

用户随后认可按方案开发，并强调数据全部由系统已有成本及报销录入，看板只需读取计算，图形应明确表达对比、占比和支出。最新请求“重新给我出一版”对应 V2 需求与原型修订，本轮尚未实施正式系统。最新版见 `requirements/COST_DASHBOARD_PRD_V2_2026-10-16.md`、同目录 V2 Word 和 `../../cost-management-prototype/cost-dashboard-v2/`。V1 保留；后续页面、指标、交互以 V2 为准。

V2 首页以累计/发生额趋势、费用构成、城市双条对比、付款状态和类别增减五组图为主；城市结构与报销类型/员工分析支持明细追溯。数据仍由 ERP、Order、HR 原台账经既有内部读取模式进入 BI，不增加业务录入或外部数据采集。付款图为台账标记，不是实际现金流。原型全为虚构数据，8 项计算测试与浏览器交互通过；V2 Word 12 页已逐页检查，正式接入和真实数据验收未执行。

随后用户要求结合成熟市场方案与现有功能重新设计，并明确主要用户为经营负责人、CEO、老板。当前最新版升级为 V3：`requirements/COST_DASHBOARD_PRD_V3_2026-10-16.md`、同目录《成本数据看板产品需求与设计方案_V3.0_2026-10-16.docx》和 `../../cost-management-prototype/cost-dashboard-v3/`。V1/V2保留作历史，后续页面、指标、交互以V3为准，本轮仍为设计交付。

V3通过Microsoft Power BI/Dynamics365、SAP Concur及合思官方公开材料借鉴类别/地区/对象分析、总览与明细分层、多维钻取。四章为经营总览、城市与结构、七类成本专题、单据追溯；新增城市×类别热力矩阵、增减瀑布、集中度、按业务现有字段的专题拆解，以及每100元交易额对应已登记经营费用。后者复用当前 `SupplyDashboardQueryMapper` 的 `bi_sales_order_fact.payable_amount`、order_date及取消单排除规则；必须同城市、同期间、同权限，不代表财务费用率或利润。工资功能存在但暂不纳入。17项计算测试和代表性交互通过，V3 Word13页已逐页检查，截图及验证记录在原型目录。

建议升级原数据看板里的城市成本入口，保留旧URL；经营费用、商品采购、商品损耗分别展示，统一费用日期，报销为经营费用子集。权限、批次、来源需覆盖汇总到导出全链路。

最新用户要求“站在产品经理的维度重新设计”。当前设计升级V4，文件为 `requirements/COST_DASHBOARD_PRD_V4_2026-10-16.md`、同目录《成本数据看板产品方案_V4_2026-10-16.docx》及 `../../cost-management-prototype/cost-dashboard-v4/`。产品主线收敛为经营简报→费用分析→支出明细；首页默认经营费用，城市×类别变化排序提前，抽屉分开已知事实与待核实问题，提供两期完整证据；采购和损耗作为独立辅助入口。增加与减少贡献分别用各自总额作分母，不除以净变化。V4覆盖V3的页面与产品定义，历史版本保留；数据写入边界不变。

V4为设计原型，未实施正式系统。21项计算测试通过，代表性交互和530宽度检查通过；Word11页逐页检查。月度复盘场景、30秒理解和3分钟取证属于设计假设及试用目标，未声称经过用户验证。无自动派单、预算、利润、现金流或原因归因。

架构、服务功能及调用、实现规范、数据库操作详见 V1 需求第02、03、12、14节、V2 第09、10节与原项目索引。DEV默认仍导入Nacos并开启Flyway，local启动可能写共享库；执行前核对最终配置。遵守各仓库AGENTS，精确暂存新建文件，保留其他任务改动。

本轮核对了源码及销售/城市看板浏览器样式，未连接数据库、启动后端、迁移、部署或验收真实成本数据。

## V5 业务术语与正式建设规格

用户要求业务话术、移除横向柱状图，并明确需求和原型用于真实开发上线。最新设计为 `requirements/COST_DASHBOARD_PRD_V5_2026-10-16.md`、同目录《成本数据看板产品需求规格_V5_2026-10-16.docx》以及 `../../cost-management-prototype/cost-dashboard-v5/`。V5替代V1至V4的页面与交互定义，仍为需求与原型交付，未实施正式业务代码或数据库。

V5采用费用总览、费用分析、支出明细；结构使用环形图并保留精确金额占比，城市和对象使用纵向柱图，费用规模参照改数值表；累计折线、城市矩阵、纵向瀑布继续保留。支持城市、类别、对象及合同快照维度下钻。明细付款筛选与全局条件隔离，全年固定同比，未来月份禁用。

规格包含15节，明确P0与条件开放范围、页面区块、图表、字段、权限、拟新增查询契约、同步与数据库要求、15项业务验收及8项技术验收。2026-10-19 用户要求刷新规则与销售业绩看板一致，已替代原每5分钟更新、10分钟内可查的目标：默认固定间隔1小时、启动后60秒首轮，集中接管后复用同一 BI_REFRESH 计划的频率和启停；以有效配置为准。性能时限尚未完成环境验收。规模参照对齐未通过时关闭，不阻断P0费用分析上线。

21项原型计算测试、代表性浏览器下钻/期间/限权状态/全量CSV核平、1440/1920/530宽度检查完成；Word15页渲染检查完成。全部数据虚构。生产服务端权限、跨域接入、数据库迁移、实际性能与业务验收仍须按需求实施验证。新增文件已精确暂存，未提交推送；其他任务改动保留。
