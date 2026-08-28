# 订货宝同步稳定化 Handoff

更新时间：2026-08-27 14:36 CST

## 接手先读

- 仓库：`/Users/guorongrong/IdeaProjects/瑞盖/rigour-supply-chain-platform`
- 分支：`feature/sync-reconciliation-repair`，本地领先远端 9 个提交。
- 相关前端仓库：`/Users/guorongrong/IdeaProjects/瑞盖/rigour-supply-chain-portal`
- 工作树很脏，包含 BI、CRM、ERP、Order、Integration、IAM、Portal 多批变更；接手先看 `git status --short --branch`，不要 `reset/checkout/clean`。
- 不要在日志、回复、handoff 中打印 `/tmp/rigour-dev-restart/*.env` 里的密钥。

## 用户核心诉求

- 订货宝同步要稳定，不能每次同步出一堆问题再临时修。
- 我方系统要能清楚看出“这笔款是什么、怎么来的、关联哪张单、进出哪个账号”。
- 从订货宝同步来的单据只展示来源事实和详情，不展示我方人工确认/出入库/删除等操作。
- 我方人工单据仍按我方业务流程走，不能因为订货宝同步投影而绕开人工动作。
- 订货宝没有调拨主单正式接口，只能从调拨出库/入库反推；不能用时间硬猜造成错误数据。

## 本对话已完成

- 资金单据字段落库和展示：来源单号、关联订单号、支付流水号、付款日期、提交/确认时间、收款账号、开户名称、开户银行、附件来源等都结构化展示；附件按上传我方 COS 的方向实现，下载失败不阻断同步。
- 销售订单状态收口：我方主状态保留内部流程；订货宝状态单独落 `source_status_code`，用于展示、筛选、对账，不混进人工流程。
- 销售订单人员收口：`归属销售人员` 映射订货宝业务员；`制单人` 映射订货宝员工账号并匹配我方员工；`createdBy/updatedBy` 统一写 `SYSTEM`，Portal 展示“系统同步”。
- DEV 抽样验证：`DH.20260818.0045`、`DH.20260818.0056` 的制单人为 `RY202608239133 / 王桐`，归属销售为 `RY202608238087 / 胡毅然`，审计字段均为 `SYSTEM`。
- DEV 聚合验证：1932 张订货宝销售订单中，`created_by/updated_by` 全部为 `SYSTEM`；1929 张匹配到制单人姓名和员工编码，3 张需继续查 Raw。
- ERP 入库和编码规则收口：采购入库、调拨入库、退货入库按订货宝来源类型拆分；同步单据编号按来源业务时间生成，人工单据仍按系统当前规则生成。
- CRM/Integration 稳定性修复：CRM mapping 分批写入；兼容 `application/octet-stream` 响应；Raw Landing 写入前查重，降低 REPAIR 重复 Raw 唯一键冲突。
- 方案文档已补：`docs/DHB_SYNC_STABILITY_PLAN.md`、`docs/DHB_API_CONTRACT.md`、`docs/ORDER_CENTER_DHB_INTEGRATION.md`。

## 共享 DEV 已执行

- 已获用户确认后执行共享 DEV 迁移、服务重启、销售订单 REPAIR。
- 最终确认健康：Integration `26882`、Order `26885`、CRM `26883`、Settings `26892` 均为 `UP`；Portal dev server `5100` 此前在跑，接手如需页面验证先复查。
- Schema：Order V28、Integration V15、CRM V12、Settings V25。
- CRM 最新主数据同步成功：`CUSTOMER` fetched 1576 / created 146 / changed 381 / duplicate 1049；`ADDRESS` fetched 1556 / created 235 / changed 16 / duplicate 1305。

## 最新 Order REPAIR

- runId：`92c800fd-703d-4d6c-b559-726ca2d43a08`
- 窗口：`2026-07-26T16:00:00Z` 到 `2026-08-27T06:09:18Z`
- 结果：`PARTIAL`
- 计数：fetched 4020，accepted 45，duplicate 3921，rejected 55。
- 错误码：`DHB_ORDER_PROJECTION_PARTIAL`
- 判断：客户映射和状态/制单人问题已明显收敛，剩余大头是调拨反推歧义。

## 当前未闭环

- 调拨反推歧义 36：`ERP_STOCK_OUT / DHB_TRANSFER_INBOUND_AMBIGUOUS`。一个出库匹配多个入库候选，不能按时间猜；保留死信/对账。
- 调拨目标仓缺映射 2：`ERP_STOCK_OUT / DHB_TRANSFER_TARGET_WAREHOUSE_MAPPING_MISSING`。补仓库映射后重跑。
- 销售订单客户映射缺失 9：`SALES_ORDER / DHB_ORDER_MAPPING_MISSING`。查 Raw 客户编号、CRM source binding、Integration mapping。
- 销售订单投影失败 4、单位未映射 2：查死信样例和 Raw，优先补单位字典/商品 SKU 映射。
- 发货父订单缺失 3、发货单缺订单号 1：父订单补齐后可随 REPAIR 自愈；缺订单号需查订货宝源字段。
- 制单人仍有 3 张未匹配：查 Raw 是否缺 `AccountsId/LoginName/AdminUser/OperationName`，源有值再补员工账号映射规则。

## 下一步计划

1. 查最新 run 的 55 条死信样例，按错误码分组做最小修复清单。
2. 先处理确定性映射缺口：客户 9、单位 2、仓库 2、制单人 3。
3. 再处理调拨歧义：只能基于订货宝系统页面/接口文档找稳定关联字段；找不到时维持死信，不按时间猜。
4. 资金侧继续补“收支明细”对账源：有正式接口则作为账本行接入；没有接口时只能用订货宝系统页面人工核对。
5. 同一窗口重跑 Order REPAIR，目标是除调拨歧义外其他错误降到 0。
6. Portal 页面回归：销售订单列表/详情、资金单详情、ERP 入库/调拨/出库列表，重点看来源字段、人员展示、操作按钮隐藏。

## 已验证

- 后端编译/安装通过：
  - `./mvnw -pl services/rigour-integration-migration-service/integration-migration-client,services/rigour-integration-migration-service/integration-migration-service,services/rigour-merchant-crm-service/merchant-crm-service -am -DskipTests compile`
  - `./mvnw -pl services/rigour-integration-migration-service/integration-migration-client,services/rigour-integration-migration-service/integration-migration-service,services/rigour-merchant-crm-service/merchant-crm-service -am -DskipTests install`
  - `./mvnw -pl services/rigour-integration-migration-service/integration-migration-service,services/rigour-order-center-service/order-center-service -am -DskipTests compile`
  - `./mvnw -pl services/rigour-integration-migration-service/integration-migration-service,services/rigour-order-center-service/order-center-service -am -DskipTests install`
- Portal `pnpm build` 通过。
- 注意：`-DskipTests` 未执行完整单测，只验证编译/testCompile/install 链路。

## 关键文件

- `services/rigour-order-center-service/order-center-api/src/main/java/com/rigour/order/api/v1/model/SalesOrderSourceProjectionCommand.java`
- `services/rigour-order-center-service/order-center-service/src/main/java/com/rigour/order/application/service/sales/OrderAuditActors.java`
- `services/rigour-order-center-service/order-center-service/src/main/resources/db/migration/V28__sales_order_source_creator_and_sync_audit.sql`
- `services/rigour-integration-migration-service/integration-migration-service/src/main/java/com/rigour/integration/application/service/dhb/DhbOrderSyncService.java`
- `services/rigour-integration-migration-service/integration-migration-service/src/main/java/com/rigour/integration/infrastructure/persistence/repository/MybatisPlusDhbSyncStore.java`
- `services/rigour-integration-migration-service/integration-migration-client/src/main/java/com/rigour/integration/client/ExternalObjectMappingClient.java`
- `services/rigour-merchant-crm-service/merchant-crm-service/src/main/java/com/rigour/merchant/api/InternalCrmDhbSyncController.java`
- `services/rigour-erp-core-service/erp-core-service/src/main/resources/db/migration/V29__erp_procurement_and_stock_in_source_identity.sql`
- Portal：`src/views/supply-chain/order/SalesOrderView.vue`、`src/views/supply-chain/order/FundDocumentView.vue`、`src/utils/audit-actor.ts`

## 注意事项

- 订货宝来源单据不能展示我方人工确认入库/出库/取消等操作；我方人工单据照常走人工流程。
- 调拨待出库无法靠出入库凭证反推出来，这是数据源不完整，不是代码技巧问题。
- 订货宝收支明细适合作为资金对账账本，不建议替代收款单/付款单业务单据。
- 下一对话如需访问订货宝系统页面，可以直接用页面核对 Raw 字段，但不要做改变订货宝状态的操作。
