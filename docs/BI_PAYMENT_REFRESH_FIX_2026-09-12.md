# BI 回款刷新 SQL 修复（2026-09-12）

## 根因与影响

- 用户提供的 14:25、14:55 日志显示，`ORDER_PAYMENT_RECORD` 定时刷新在 `biSalesPaymentFactBackfillHealth` 处失败：`Unknown column 'payment_record_id'`。
- 正式 V2 迁移及 `upsertSalesPaymentFactFromSource` 均使用 `payment_id`；本次为校验 SQL 字段名错误，不应新增数据库列，也不应改写已执行迁移。
- 错误发生在回款事实增量刷新之前，会阻止本次回款来源刷新和成功水位推进。其他来源采用独立异常收集，不能由此推断所有看板数据均错误。健康检查 UP 不能证明刷新成功。
- 同链路另发现：源回款签名未采用实际写入时的回款人兜底归属。在订单、客户归属为空时，已一致的事实也会被误判为需全量补齐。

## 修复与回归

- 目标校验签名改用 `payment_id`，不使用 BI 自增 `id`。
- 源校验签名按现有写入规则依次采用订单销售、客户销售、回款人，不改变业务归属或看板回款统计口径。
- 新增 `SupplyDashboardPaymentBackfillRepositoryTest`：在隔离 H2 中直接执行正式 V2 建表迁移和真实 Mapper，适配 MySQL CRC32、FORMAT 函数；不连接共享 DEV。
- 修复前 6 项测试均复现缺列错误；仅修正字段后，回款人兜底案例仍失败；两处修正后 6 项全通过。覆盖租户隔离、逻辑删除、空数据、销售归属优先级及同金额不同来源 ID 的错配检测。
- `./mvnw verify` 于 15:02 完成，全 51 模块成功。752 项中 599 通过、153 因 Docker 不可用跳过，0 失败、0 错误；BI 模块 85 项全部通过。
- 本机验证日志：`/tmp/bi-payment-backfill-red.log`、`/tmp/bi-payment-backfill-id-fix.log`、`/tmp/bi-payment-backfill-green.log`、`/tmp/bi-payment-backfill-verify.log`。

## 运行复验边界

用户于 15:04 重新启动服务后，已通过真实登录页面的只读请求复验：数据同步状态接口返回 HTTP 200、overallStatus=OK，运行 177 于 15:05:14 至 15:05:20 完成，八个来源均 SUCCESS，回款来源无失败信息。原缺列错误已在该运行中消除。运行总量 pulled=3285、upserted=6570，是整次任务计数，不是回款单项数量。

城市商品报表和经营分析接口均恢复 HTTP 200。没有代替用户重启 IDEA、主动触发 DEV 同步或手工修改数据库；本记录证明本次刷新恢复，不代表源业务金额对账或最终业务验收已通过。
