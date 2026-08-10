# 工作日志

## 2026-08-09 - 真机回归后的拜访权限与执行页状态机修复

### 问题与根因

- 真机日志确认拜访详情和结果保存为 200，但录音会话与当日轨迹查询为 403；工作日签退 409 是服务端正确阻止“存在进行中拜访时结束工作日”。
- IAM V30/V31 已登记轨迹与录音权限，却只自动授予租户超级管理员；普通销售角色虽然已有 `sales:visit:own:write`，仍没有新增能力，导致拜访无法录音并进一步无法离店。
- H5 将录音会话加载成功作为按钮启用条件，却没有可恢复权限错误；拜访结果保存后按钮文案不变；到店签到与离店签退同时以禁用按钮呈现，无法表达真实状态和下一步。

### 修复结果

- 新增 IAM V32：以既有 `sales:visit:own:write` 授权作为 H5 销售角色判据，补授 `sales:track:own:read`、`sales:recording:own:read`、`sales:recording:own:write`，不依赖角色名称且不扩散管理端权限；同时递增租户策略版本令旧会话失效。
- 拜访执行页重构为“已到店 → 拜访记录 → 完成离店”状态流；到店只显示为事实，不再同时出现两个灰色签到/签退按钮。
- 底部只保留一个上下文主操作：重新加载录音权限、开始/停止录音、填写或保存记录、完成拜访并离店；录音加载失败提供明确错误和重试。
- KP、联系电话、合作意向与沟通纪要保存成功后显示保存时间和摘要；仅再次修改时出现“保存修改”，未保存修改会阻止离店，避免用户误以为新内容已归档。
- 录音显示已上传时长、规则要求、剩余秒数和片段列表；录音/上传进行中阻止离开页面。390×844 Mock 视觉 QA 修复了卡片按钮与固定操作栏重复、遮挡以及小字号问题。

### 验证边界

- Workbench 新增 3 项拜访页状态测试，覆盖进行中动作互斥、材料齐全后的离店主操作、权限失败恢复；ESLint、vue-tsc、70 项 Vitest 与 Vite 生产构建通过。
- Platform 全量 46 模块 `./mvnw verify` 与 5 项架构门禁通过；本机无 Docker，MySQL 顺序迁移和普通销售角色实际授权仍需部署 IAM 后验证。
- V32 生效后必须重启 IAM/Flyway，并让销售重新进入飞书工作台获取新会话；只重启 Sales Work 或刷新旧页面不能消除 403。

## 2026-08-09 - 销售拜访执行、录音与轨迹恢复闭环

### 实施计划

1. 复核 H5 → Gateway → Sales Work 的创建拜访、录音、结果、签退和轨迹链路，修复页面仍停留在能力演示/骨架的入口。
2. 拜访结果由服务端主写，KP 称呼、电话和合作意向必填；签退前由服务端校验结果、停留时长、位置和固化拜访规则要求的录音时长。
3. 录音采用飞书 `RecorderManager` 单段10分钟自动切片续录；H5通过`FileSystemManager.readFile`读取临时音频后走同源HTTPS multipart，片段携带客户端幂等标识；元数据落Sales Work，开发使用文件系统，生产通过同一`FileStorage`端口切换腾讯云COS。
4. 定位采样从考勤页面局部生命周期提升为应用级 Store 会话；免登后若服务端工作日仍为 ACTIVE 自动恢复，页面跳转不停止，工作日签退/登出才停止。
5. 当日轨迹从服务端定位点和签到/签退 Punch 生成高德 GCJ-02 轨迹图；地图配置不可用时保留时间线降级。
6. 增加拜访单一进行中约束、录音重试幂等、上传时序校验和前后端回归测试；完成 Maven、ESLint、TypeScript、Vitest 和生产构建门禁。

### 完成结果

- H5 拜访页已支持真实飞书现场录音、停止后自动上传、失败重试、片段列表与规则最低时长提示；不再以 Mock 模式作为录音按钮启用条件。
- 新增 KP 称呼、电话号码、合作意向和结果备注表单；前三项由前后端共同校验，服务端拒绝未保存结果或未满足录音规则的拜访签退。
- 录音上传新增 `clientClipId` 幂等键和 Flyway V4；同键同内容重放返回原片段，同键不同内容拒绝。客户端时长与起止时间只记为上传事实，`verified_total_duration_ms` 保持 0，等待后续媒体验证/人工复核。
- 同一销售只能存在一个 `CHECKED_IN` 拜访；创建时锁定销售画像并检查进行中拜访，避免并发重复到店；存在进行中拜访时服务端同时拒绝工作日签退，防止考勤先结束而拜访悬挂。
- 新增腾讯云 COS `FileStorage` 实现，支持启动时提供临时凭据、连接/读取超时、服务端加密和租户对象键隔离；当前未实现 STS 自动续期，生产先使用最小权限专用 CAM 长期密钥。默认文件系统用于本地/共享持久卷，生产必须显式切换 COS。
- COS 配置已按安全属性拆分：存储类型、地域、Bucket、大小限制、超时和 SSE 开关保存在 application/Nacos；只有 `SecretId`、`SecretKey` 和可选临时 `SessionToken` 由部署 Secret 注入，并新增创建、最小权限和验收指引。
- 定位采样已改为应用级会话：签到启动、应用刷新自动恢复、页面跳转保持、工作日签退和登出停止。首页展示真实进行中拜访，不再显示“接口待接入”占位。
- 当日轨迹接口与地图展示保持服务端事实驱动；飞书定位固定使用 GCJ-02，与高德底图一致。

### 验证与边界

- Sales Work 及依赖模块编译、可运行单元/上下文测试通过；本机无 Docker，新增 MySQL V3/V4 迁移、录音幂等和完整拜访集成测试仍按 `disabledWithoutDocker` 跳过，不能记为数据库验收通过。
- Workbench ESLint、vue-tsc、67 项 Vitest 测试和 Vite 生产构建通过；Platform 46 个 reactor 模块全量 `./mvnw verify` 与 5 项架构门禁通过。
- 真实飞书`FileSystemManager.readFile`、10分钟自动续段、前后台切换、移动网络重试、高德Key、共享DEV Gateway/IAM V29～V31、COS Bucket/CAM/SSE配置仍需部署环境与真机验收；当前H5经后端上传COS，不需要开放浏览器CORS。自动化测试不替代这些外部验收。
- 录音 AI 真实性/服务端媒体时长验证、ASR、主管复核和“最终有效拜访”结论不在本切片伪造；当前只完成可上线采集与待复核事实链路。

## 2026-08-08 - 销售工作阶段 3 前置维护 API、重新签到与当日轨迹

### 实施计划

1. 前置开发：merchant-crm-service 尚为空壳，Sales Work 的身份绑定、销售画像、外勤/拜访规则、CRM 门店与归属投影没有任何写入方，H5 无法联调。按 V18 已登记的“销售管理”权限模型，在 Sales Work 内新增 `/api/v1/sales/admin` 维护 API（身份绑定、画像、规则发布、门店投影、归属），门店/归属投影写入标注为 CRM 事件消费者上线前的临时前置，消费者投产后下线。
2. 缺陷修复：签退后同一业务日禁止再次签到导致“考勤一直已签退、无法签到、无法拜访”。改为签退（FINISHED）后允许重新签到：重开同一工作日聚合、新建定位会话、Punch 追加 CHECK_IN，可验证工作时长按最近一次签到起算并累加，日结 summary 版本递增，不改写历史。
3. 功能补齐：新增本人当日轨迹查询 API（定位点 + 签到/签退 Punch 打点），Workbench 轨迹页接入高德 JS API 地图展示轨迹线与打点，无 Key 时降级为时间序列表。
4. Workbench 修复：考勤页 FINISHED 状态展示“重新签到”；发起拜访前显式校验工作日 ACTIVE，否则引导先签到。
5. IAM 新增 V30 登记 `sales:track:own:read`（H5）与 `sales:profile:write`、`sales:identity:bind`、`sales:store-projection:write`、`sales:assignment:write`（销售管理），沿用 V29 模式授予租户超管联调；不改写历史迁移。
6. 每个切片完成后执行定向 Maven 测试与 Workbench 门禁（lint/typecheck/test/build），全部通过后停止并汇报残余风险。

### KPI 边界

- 本阶段仍不计算绩效指标；重新签到只追加可追溯事实（Punch、会话、summary 版本），verifiedWorkMinutes 是事实累计，不是考勤结论。

### 完成结果

- 新增 `/api/v1/sales/admin` 维护 API：身份绑定（`sales:identity:bind`）、销售画像（`sales:profile:write`）、外勤/拜访规则版本与发布（`sales:policy:write`+`sales:policy:publish`，含适用范围）、门店投影与归属（`sales:store-projection:write`/`sales:assignment:write`，标注临时前置）。所有写入幂等覆盖或版本递增，审计留痕且不含经纬度。
- 签退（FINISHED）后允许重新签到：`sales_work_day` 条件更新重开为 ACTIVE，保留首次签到时间与累计工时，新建定位会话，追加 CHECK_IN Punch，Outbox 追加 `SalesWorkDayReopened`；非 FINISHED（如复核中）仍拒绝。签退工时按最近一次签到起算并与历史段累计，日结 `summary_version` 递增不改写历史。
- 新增本人当日轨迹 API `GET /api/v1/sales/me/work-days/{date}/track`（`sales:track:own:read`），返回定位点与签到/签退打点，仅限本人工作日。
- IAM 新增 V30 登记 5 项权限并沿用 V29 模式补授租户超管；未改写历史迁移。
- Workbench：考勤页 FINISHED 展示“重新签到”；发起拜访前显式校验工作日 ACTIVE，否则提示并引导至考勤页；轨迹页接入高德 JS API 地图（轨迹线+打卡打点，`VITE_AMAP_JS_KEY`/`VITE_AMAP_SECURITY_CODE` 注入），未配置 Key 或加载失败时降级为时间线视图；新增 `loadTrack` store 与契约类型。
- 运行时缺陷修复：`JdbcSalesOutboxStore.append` 聚合版本误固定查询 `sales_work_day`，`SALES_VISIT` 聚合（创建/签退拜访）查不到行抛 `EmptyResultDataAccessException` 导致 500。改为按聚合类型路由版本表（SALES_WORK_DAY/SALES_VISIT），聚合行缺失记 0 不升级为业务异常；拜访集成测试补充 Outbox 写入断言。

### 验证记录

- 后端：`sales-work-service -am` 编译与测试通过；Spring 上下文测试（含新增 Admin Service/Repository/Controller 装配）通过；`RestAmapPoiClientTest` 3 项通过。
- 本机无 Docker，Testcontainers 集成测试（含新增重开/轨迹 2 项、Admin 3 项）按 `disabledWithoutDocker` 跳过，未执行真实 MySQL 闭环；IAM V30 未做空库顺序迁移验证。
- Workbench：ESLint、vue-tsc、62 项测试、Vite build 通过。

### 已知边界

- 集成测试需在有 Docker 的环境执行 `./mvnw -pl services/rigour-sales-work-service/sales-work-service -am test` 后方可视为数据库闭环验收。
- 地图坐标按服务端保存值原样渲染；飞书定位坐标系与高德底图（GCJ-02）若不一致，需服务端统一转换（当前阶段未做）。
- 门店/归属投影维护 API 是临时前置；CRM 服务与事件消费者投产后应下线并删除对应权限资源。
- Portal 销售管理页面仍是骨架，本阶段只交付 API 层；页面接入另行排期。

## 2026-08-06 - 销售工作阶段 0 至阶段 2 纵向闭环实施

### 实施计划

1. 以销售专项设计 V1.1 和跨仓库接口约定为基线，冻结阶段 1、阶段 2 的上下文、客户目标和外勤考勤 API。
2. 明确工作日、定位会话、幂等、审计、Outbox、租户隔离和 DataScope 约束；不改写已存在的 Sales Work Flyway V1。
3. 阶段 1 实现真实销售上下文、当前规则和 CRM 最小只读目标投影查询，并接入 Workbench。
4. 阶段 2 实现签到、定位批量上报、签退和本人工作日查询，并接入 Workbench。
5. 每个阶段完成后执行代码评审、架构边界检查、自动化测试和静态检查；当前阶段自审通过后才进入下一阶段。
6. 阶段 2 自审通过后停止，不进入拜访、录音、COS、AI、HR、BI 或 KPI 实现，等待负责人检查。

### KPI 边界

- 阶段 0 至阶段 2 不创建绩效 KPI，不在 Sales Work 或前端计算经营指标、排名、提成或绩效结论。
- 只沉淀可追溯的销售工作事实和运行摘要；完整指标字典、公式、版本、负责人和数据截至时间由 Analytics BI 后续维护。
- 所有后续 KPI 必须从领域标准事实计算，不能由 Portal 或 Workbench 临时跨服务拼装。

### 当前阶段

- 阶段 0：已完成并通过自审；未改写 Flyway V1，未引入第二套 KPI 或业务事实来源。
- 阶段 1：已完成并通过自审；真实上下文、规则、CRM门店目标和H5权限已接通，Workbench 已消费服务端结果。
- 阶段 2：已完成并通过自审；已停止继续进入拜访、录音、COS、AI、HR、BI 或 KPI 阶段，等待负责人验收。

### 阶段 1 自审记录

- 后端新增版本化 `SalesWorkApi` 只读契约、`sales_identity_projection` V2 迁移、身份/销售画像/外勤规则/CRM归属投影 JDBC 查询和标准 `ApiResponse` Controller。
- IAM 新增 V23，只登记 `sales:context:read`、`sales:visit-target:read`、`sales:work-day:write`、`sales:location:write` 四项最小权限；未修改 V18 及之前历史迁移。
- Workbench 的 `X-Tenant-Id` 已优先读取当前登录会话，首页和客户门店页通过 API core 读取真实销售上下文与目标；没有新增销售业务 mock 或可编辑门店主档。
- 评审结果：接口路径、权限码、租户边界、数据主权和 KPI 边界一致；修复了 `JdbcSalesWorkQueryRepository` 被 Spring 代理时的 final 类问题；无 `git diff --check` 问题。
- 验证结果：Workbench `pnpm typecheck`、53 项测试、`pnpm build` 通过；Sales Work Flyway 2 迁移/31 张表 Testcontainers 通过；IAM Flyway 23 迁移、15 项集成测试通过。
- 未验证项：当前未连接共享 DEV、真实飞书容器或 Gateway，以上通过不等于跨服务运行时验收。

### 阶段 2 自审记录

- 后端实现了签到、定位批量上报、定位中断证据、签退和本人工作日查询；状态变更由服务端事务和数据库条件更新控制，规则版本从签到时固化，后续定位与签退使用固化版本。
- 签到成功后才创建定位会话；签退成功后关闭定位会话；页面隐藏、权限关闭或定位失败只追加 `sales_work_interruption`，不把定位中断直接判定为旷工或绩效扣减。
- 所有移动端写命令均有幂等键或设备事件号；服务端保存请求哈希，支持同请求重放，拒绝同键不同请求，并对定位设备事件做租户内去重。跨非法工作日状态和跨租户查询由服务端拒绝。
- 工作日、Punch、定位点、中断、日结候选、Outbox 和审计写入同一 Sales Work 数据库事务；日结状态为 `PENDING_REVIEW` 候选，不写 HR 正式考勤。审计不保存经纬度、Token 或录音正文。
- Workbench 已改为“服务端签到成功后启动持续定位、服务端签退成功后停止定位”，首页、考勤页和轨迹页读取服务端工作日事实；浏览器 Mock 只模拟飞书客户端能力，不模拟 Sales Work 业务写入。
- KPI 自审通过：阶段 0～2 没有销售额、排名、提成、有效拜访率、转订单率或绩效结论计算；`verifiedWorkMinutes`、定位点数、中断数和证据质量仅是可追溯事实/运行摘要，后续指标仍必须由 BI 按指标字典计算。
- 验证结果：Sales Work 定向 Maven 测试 3 项通过（迁移、Spring 上下文、隔离 MySQL 8.4 真实闭环）；Workbench `pnpm typecheck`、56 项测试和 `pnpm build` 通过；阶段 1 已验证的 IAM Flyway V23/15 项测试保持通过；`git diff --check` 和架构边界静态检查通过。
- 自审结论：阶段 2 退出条件满足，当前停止开发并等待负责人明日验收。
- 明确残余：尚未做共享 DEV、Gateway、真实飞书真机、COS、HR/BI 跨服务验收；普通销售角色仍需由 IAM 业务配置显式授予新增 H5 权限；当前目标查询已落地 CRM 门店纵向切片，客户总部/无门店目标需要 CRM 客户投影契约后再扩展；拜访、录音、AI、复核管理端和 KPI 尚未实现。

## 2026-08-06 - 租户菜单配置四层权限模型落地

### 实施计划

1. 保留平台`iam_resource`/`iam_resource_ui`作为唯一功能资源事实，不开放租户修改路由、权限编码和资源类型。
2. 新增租户菜单展示覆盖与无路由自定义分组，以套餐资源作为租户可配置上限。
3. 将导航计算收口为有效资源、有效订阅、租户启用配置和用户角色授权的交集。
4. 增加`iam:menu:read`/`iam:menu:write`及系统设置菜单入口，补齐租户越权、分组和覆盖配置测试。
5. Portal增加菜单管理页面、路由白名单和角色树父级授权修复，并同步V2.1与工程文档。

### 完成结果

- 新增IAM V22，建立`iam_tenant_menu_config`和`iam_tenant_menu_group`；历史V1～V21未改写。
- 租户可以配置套餐内菜单的名称、图标、排序、显示状态和自定义分组，不能创建可执行页面、按钮或API资源。
- 平台停用资源时租户不能强制开启；隐藏父菜单或分组时不会把子菜单提升到导航根级继续显示。
- 普通角色仍需独立授权；角色树保存半选父节点，避免只授权子页面造成菜单层级丢失。
- IAM V1～V22在MySQL 8.4顺序迁移成功，平台`./mvnw verify -q`通过；Portal lint、typecheck、42项测试和生产构建通过。

### 当前边界

- V22仅完成本地代码和隔离数据库验证，尚未发布共享DEV，也未重启现有服务。
- 平台资源发布版本、发布前routeKey/Capability校验和回退仍属于V2.1后续能力。
- 本轮不提交或推送，保留三个仓库既有未提交工作。

## 2026-08-06 - 飞书销售工作台入口与H5骨架纠正

- 新增IAM V21，不改写V18～V20，将`FEISHU_SALES`从错误的供应链销售管理内部路由纠正为外部飞书应用，受控启动页恢复为`/sales-workbench`。
- Portal恢复独立启动页，仅负责校验并打开飞书H5地址；供应链`/supply-chain/sales`继续只承载主管销售管理后台。
- `rigour-sales-workbench`按销售一天的工作流补齐今日总览、外勤考勤、客户门店、四阶段拜访、录音证据、轨迹/拜访/证据记录、补卡申诉、隐私授权和个人中心页面骨架。
- 缺少后端API的区域不伪造业务数据；浏览器Mock只验证飞书定位和录音能力，正式考勤与拜访事实仍由Sales Work主写。
- Portal 40项测试、Workbench 53项测试及两端lint/typecheck/build通过；IAM V1～V21在隔离MySQL连续迁移成功，平台全量验证结果见本次交付记录。
- 共享DEV备份后由V20升级至V21，未重启现有服务；备份位于`/opt/rigour-dev/backups/iam-v21-feishu-entry-20260806T1850/rigour_iam_before_v21.sql.gz`，权限`0600`。

## 2026-08-06 - 供应链V2.1二三级菜单与飞书入口收口

- 新增IAM V20，不改写V18/V19，补齐ERP、CRM、订单管理、城市运营、BI数据看板、人事与绩效、渠道代理、外部集成与数据同步、业务设置的101个菜单/页面资源。
- ERP按商品主数据、供应商、采购、仓库、库存、成本结算分组；CRM只保留客户商家、门店、客户归属和信用政策，品牌主数据归ERP单一维护。
- Portal新增90个领域分组/页面的完整routeKey注册和受控页面挂载；缺失后端API的页面只展示职责边界，不制造模拟业务数据。
- 侧栏宽度调整为280px，一级、二级和三级菜单统一使用白色加粗文本并提高悬停、选中与分隔层级对比度。
- V20当时将`FEISHU_SALES`门户卡片改为内部路由`/supply-chain/sales`；该入口判断随后被确认不合理，并已由上方V21恢复为独立飞书H5启动页。
- Portal lint、typecheck、40项测试和生产构建通过；平台`./mvnw -B verify`全量46个Reactor项目通过，IAM V1～V20在隔离MySQL连续迁移成功。
- 共享DEV备份后由V19升级至V20，核验为260个有效资源、212条UI资源和233条套餐资源关系；未重启现有服务。备份位于`/opt/rigour-dev/backups/iam-v20-complete-navigation-20260806T1820/rigour_iam_before_v20.sql.gz`，权限`0600`。

## 2026-08-06 - 供应链V2.1菜单与导航视觉收口

- 新增IAM V19，在不改写V18和稳定资源ID的前提下，将供应链一级域对齐为供应链首页、ERP、CRM、订单管理、销售管理、城市运营、BI数据看板、人事与绩效、渠道代理、外部集成与数据同步、业务设置。
- 订单域从早期“订货宝菜单镜像”收口为订单工作台、订单接入、订单中心、履约编排和售后管理；旧镜像统计页退出正式侧栏但保留原资源与直达路由兼容。
- Portal侧栏改为数据库驱动的清晰业务域导航：提高对比度和点击面积，只展开当前路由分支，统一使用Element Plus图标并强化活动层级。
- Portal lint、typecheck、37项测试和生产构建通过；IAM V1～V19在隔离MySQL连续迁移成功，IAM 28项测试通过。
- 共享DEV在备份后从正确V18升级至V19，未重启现有服务；备份位于`/opt/rigour-dev/backups/iam-v19-navigation-20260806T1730/rigour_iam_before_v19.sql.gz`，权限`0600`。

## 2026-08-06 - 共享DEV IAM V18冲突处置

- 共享DEV曾被误执行同号迁移`V18__rename_outbound_logistics_menus.sql`，与当前销售管理V18发生校验和冲突。
- 经授权先备份完整`rigour_iam`，确认错误迁移只修改2个订单菜单和1个租户权限版本；随后精确回退3条数据并删除对应的单条Flyway历史，未使用`repair`掩盖差异。
- 使用当前完整迁移目录重新执行Flyway，`V18__iam_sales_management_and_feishu_app.sql`已成功成为共享DEV的V18，校验和为`811188194`。
- 共享DEV验证结果：46个销售新增资源、39个销售routeKey、`FEISHU_SALES`为`ACTIVE`且目标为`/sales-workbench`；原“出库发货”和“发货单”菜单已恢复。
- 操作前备份位于服务器`/opt/rigour-dev/backups/iam-v18-conflict-20260806T1700/rigour_iam_before_v18_replacement.sql.gz`，权限`0600`。

## 2026-08-06 - 飞书销售工作台与销售管控架构落地

### 实施计划

1. Sales Work 接入独立数据库基础依赖，并以新增 Flyway V1 建立规则、销售组织投影、H5打卡、定位、拜访、录音、复核和日结表。
2. IAM 通过新增迁移把“销售监管”演进为数据库驱动的销售管理三级菜单，登记页面和敏感操作 Capability。
3. 启用 `FEISHU_SALES` 门户应用卡片，目标只进入受控H5引导页，不在PC端复制现场作业。
4. Portal 注册与IAM routeKey严格对应的销售页面骨架；缺失API不生成模拟业务数据。
5. 飞书H5改为签到后开始定位、签退后停止，并增加CRM客户门店和当前规则入口。
6. 分别执行平台 Maven、Portal 和 Workbench 质量门禁；初始架构落地阶段不发布共享DEV，后续IAM V18经单独授权发布，真实飞书、COS和跨服务接口仍另行验收。

### 当前边界

- 初始架构落地阶段不连接或修改共享DEV数据库、Nacos和运行服务；后续IAM V18发布见上方独立处置记录，源码存在仍不等于其他领域迁移已执行。
- 不创建可编辑的Sales Work门店主档，CRM投影只读，历史拜访只保存不可变快照。
- Sales Work只生成销售工作日结候选；HR正式考勤和薪酬仍由HR/Payroll主写。
- 本轮不提交或推送三个仓库，保留平台仓库既有未跟踪`handoff.md`。

### 完成结果

- Sales Work V1 在一次性 MySQL 8.4 中迁移成功，共30张领域表；测试同时断言不存在可编辑的`sales_store`门店主表。
- IAM V18 在V1～V18空库顺序迁移中成功，登记销售管理7个分组、30个页面、9个API Capability，并启用`FEISHU_SALES`受控门户卡片。
- Portal完成销售管理三级路由、数据库routeKey白名单、页面职责骨架、飞书应用卡片和H5引导页；36项测试通过。
- Workbench增加客户门店和当前规则入口，考勤页改为签到后定位、签退后停止；53项测试通过。
- `./mvnw -B verify`全量46个Reactor项目全部成功；IAM 28项、Sales Work 2项和架构门禁5项均通过。
- Portal和Workbench的lint、typecheck、test、build全部通过；初始质量门禁未启动任何共享服务，IAM V18后续通过一次性Flyway容器发布，未重启服务器现有服务。

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
