# Prototype Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.

## 已确认的产品方向

- 2026-11-09：以物流运输记录为主，同步货拉拉列表和详情后直接保存、展示。未关联订单和员工不得阻止查看。
- 平台可取得的信息只读展示，业务核心操作为关联本系统业务订单、关联本系统员工。技术配置不得出现在业务页面。
- 原型仅用于方案确认，不调用实际业务API或修改真实数据。

- 2026-11-09最新修正：主列表必须保留“物流运输记录”，同一列表支持货拉拉自动同步与手动录入；不另设货拉拉订单主入口。数据来源、平台订单状态和本系统单据状态分别表达。

- 2026-11-09 V2.2：本期不新增岗位角色和权限矩阵；沿用既有登录和菜单。货拉拉账户授权仍是外部接口前提，页面只显示连接状态和业务操作。官方APP详情与企业详情不能混用；本期企业接入方案不承诺全量隐私数据。

- 2026-11-10 V2.3：货拉拉同步记录的物流公司固定为“货拉拉”，编辑只读，列表／详情／导出口径一致。手动新增仍选择物流公司。新增、编辑、详情共用“基本信息、关联业务订单、发货信息、收货信息、费用信息、备注”顺序；平台源字段只读，编辑不改变来源或覆盖原始费用、配送信息。

- 2026-11-10 V2.4：发货信息必须在新增、编辑、详情的共用区域展示。手动记录优先业务单仓库、可选仓库或填实际发货方，保存单据快照；不能用固定武汉仓兜底。货拉拉发货点按平台路线只读展示，缺失不伪造。
