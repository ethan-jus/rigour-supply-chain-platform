# 飞书在线只读对账采集

## API

- `GET /api/v1/integration/feishu/reconciliation-sources`
- `POST /api/v1/integration/feishu/reconciliation-captures`，请求体仅 `{ "sourceId": "sales-source" }`。
- 两个入口均要求 Gateway 签名的 `TENANT` 用户身份、非空 tenantId/userId，以及 `analytics:reconciliation:write` 与 `integration:feishu:import` 或 `integration:feishu:write`。服务身份和平台身份不允许。
- 同租户来源由服务器白名单解析；请求不能指定 appToken/tableId/viewId 或租户。其他租户及未知 sourceId 返回同一种不可访问错误。

响应外层沿用 `ApiResponse`：`code/message/data/requestId/timestamp`。来源列表 `data`：

```json
[{"id":"sales-source","name":"销售对账来源","filtered":false,"tables":[
  {"tableCode":"FEISHU_SALES_ORDER","name":"销售订单","filtered":false},
  {"tableCode":"FEISHU_SALES_ORDER_LINE","name":"销售订单明细","filtered":false},
  {"tableCode":"FEISHU_PRODUCT","name":"商品库","filtered":false}
]}]
```

采集响应 `data` 仅包含 `id`（UUID）、`sourceId/sourceName/sourceUrl`、`startedAt/completedAt`（ISO UTC）、`complete/filtered`、`checksum`（小写 SHA-256）、`recordCount/pageCount`。不返回原始记录、连接凭据或租户 ID。

## 配置

绑定 `rigour.integration.feishu.reconciliation.sources`。无配置返回空列表，无默认租户/Base。示例值仅为格式示范，不能当真实配置：

```yaml
rigour:
  integration:
    feishu:
      reconciliation:
        sources:
          - id: sales-source
            name: 销售对账来源
            tenant-id: 00000000-0000-0000-0000-000000000001
            app-token: fixtureAppToken
            source-url: https://tenant-fixture.feishu.cn/base/fixtureAppToken
            tables:
              - table-id: tblOrderFixture
                table-code: FEISHU_SALES_ORDER
              - table-id: tblLineFixture
                table-code: FEISHU_SALES_ORDER_LINE
              - table-id: tblProductFixture
                table-code: FEISHU_PRODUCT
```

同一来源必须包含 `FEISHU_SALES_ORDER` 和 `FEISHU_SALES_ORDER_LINE`，可加 `FEISHU_PRODUCT` 解析商品链接。tableCode/tableId 不允许重复。可选 view-id 会使表及整次采集 `filtered=true`，不能被下游当作全 Base 对账。表代码来自现有 FeishuImportTableCatalog，不自行转换字段业务含义。

建议显式配置可选 `source-url` 为实际租户 Base 地址。它只能来自服务端来源配置，必须是 HTTPS `*.feishu.cn`，路径严格为 `/base/{同一appToken}`，不允许用户信息、非443端口或 fragment。可以保留原地址的查询参数，但仅作来源回看链接，不参与采集筛选；真正 table/view 范围仍只来自 tables 配置。未配置时保留历史 `https://feishu.cn/base/{appToken}` fallback，该通用地址不保证能直接回到租户 Base。旧五参 Source 构造保留兼容，API/DDL、证据哈希和权限不变。

复用现有 `rigour.integration.feishu.app-id/app-secret` 客户端鉴权；Secret 只通过环境/Secret 注入，不写入 Git、Nacos、响应或日志。来源配置不改变现有导入 records 调用行为。

个人来源白名单直接按上述YAML结构放在本服务 `application-local.yml` 的第二段（`---` 之后），启动时选择 `local`。它自动继承台式机DEV连接信息，不再读取个人目录的额外properties文件，也不读取旧DEV Nacos配置。未配置来源时列表为空，不影响服务启动；个人真实来源值不要提交团队分支。

日常开发无需来源覆盖时直接选择 `dev`。台式机部署仍由现有部署配置绑定来源列表，不应使用个人 `local` 配置；App Secret继续使用已有环境变量。真实租户、Base和表绑定值由部署方维护，不提交仓库。

## 不可变证据

新增 V22 `integration_feishu_online_capture`，字段：

| 字段 | 类型/约定 |
|---|---|
| id, tenant_id, actor_id | BINARY(16)，UUID 大端字节序 |
| source_id, source_name, source_url | VARCHAR(128/255/2000)，采集时来源副本 |
| started_at, completed_at | DATETIME(6)，UTC，不用 JVM 默认时区 |
| complete, filtered | BOOLEAN |
| checksum | CHAR(64)，实际 payload_json UTF-8 字节的 SHA-256 |
| record_count, page_count | INT，所有表总行数/记录请求总页数，授权请求不计页数 |
| payload_json | LONGTEXT，受 JSON_VALID 和 32MiB 大小约束 |

`payload_json`：

```json
{"tables":[{"tableId":"tblOrderFixture","tableCode":"FEISHU_SALES_ORDER","viewId":null,
  "rows":[{"recordId":"recFixture","fields":{"原始字段":"原始值"},
    "createdTime":1770000000000,"lastModifiedTime":1770000001000}]}]}
```

对象键排序，tables 按 tableCode/tableId 排序，rows 按 recordId 排序。fields 保留原始 JSON 值、嵌套对象、null、richtext/formula/lookup/link 结构，数组内顺序不变；BigDecimal 去除数值无意义的尾零。哈希可直接从库中 payload_json 原字符串复算，无需二次格式化。

外层 createdTime/lastModifiedTime 为可选 epoch 毫秒元数据，不表示订单业务日期。不复制外层 created_by/modified_by 身份；fields 内已有原始字段不删改。

每次采集使用新 UUID，仅单条 INSERT；没有修改、覆盖、删除或导入 API。全部表成功后才保存，无部分成功证据。不会调用导入 run、附件下载、业务投影或修改飞书记录。

## 完整性与预算

跨表共享上限：20,000 行、200 页、32MiB HTTP 响应（含鉴权响应）、90 秒单调时钟预算。请求逐块读取并计数，网络超时不超过当时剩余预算（且至多 5 秒）；读取和最终写入前检查预算。payload 自身也限制 32MiB。

重复 recordId、重复/缺失 cursor、has_more 不明确、空页仍宣称有后续、缺失 fields、失败/中断/截断的 HTTP/JSON、声明 total 与实际记录数不符或分页期间 total 改变，均失败关闭。客户端不回退旧 records 方法，不静默重试或截取到上限后标成功。

`complete=true` 只表示配置中各表分页完成。跨表/跨页不是原子一致性快照，期间字段仍可能被修改；BI 必须保留采集开始/结束时间及非原子说明，不能声称实时对账绝对一致。

## 部署顺序与 BI 领域接口

1. 先部署 Integration 并确认 V22 创建在线证据表，再验证租户来源配置、飞书授权与只读采集。HTTP 200、完整分页、业务对账是不同验收项。
2. BI 通过签名 SERVICE 接口 `/api/v1/integration/analytics-source/INTEGRATION_FEISHU_ONLINE_CAPTURE` 取得固定字段和来源版本，在 BI 本地发布证据镜像。接口拒绝普通浏览器身份及跨租户读取；BI 不直接读取 Integration Schema。
3. 部署匹配的 BI 镜像迁移和客户端，核验实际域服务地址及签名配置；源版本完整同步后，以已完成且未过滤的 captureId 生成复核，检查返回的版本证据和差异明细。

当前源数据接口或版本不可用时，明确记录“采集成功、BI 复核未完成”。本次代码合并未变更线上数据库授权，也未完成真实飞书/BI 联调。迁移编号和接口依赖见仓库 `docs/DEV_SYNC_SUPPLY_SETTINGS_2026-09-16.md`。

## 验证责任

新增 FeishuBitableCaptureTest、FeishuReconciliationServiceTest、FeishuReconciliationPropertiesTest、JdbcFeishuOnlineCaptureStoreTest。覆盖身份权限、租户隔离、配置绑定、分页边界、预算、哈希、纯证据写入及 CGLIB 仓库代理。

此独立写集不运行 Maven，不改 BI/Portal/IAM，不写真实来源配置。主线程统一执行构建、迁移、真实只读采集和 BI/Portal 联调；源码实现不等同已上线验收。
