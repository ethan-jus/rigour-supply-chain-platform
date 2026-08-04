# 订货宝 Integration 接口边界

本文档记录当前代码依据的订货宝资料和未确认项。资料来源：
`05_公共资源/API文档/订货宝API标准对接接口V1-1.docx`。这份资料是 ERP 对接说明，
不是 OpenAPI 文件；因此没有在代码中臆造 URL、签名算法或供应商限流值。

## 当前已实现的协议

请求使用统一 JSON 信封：

```json
{
  "f": "getGoodsList",
  "v": {
    "sKey": "<运行时 token>",
    "begin": 0,
    "step": 100
  }
}
```

首次请求调用 `getTokenValue`：`v` 中使用 `SerialNumber` 和 `Password`，回执中的
`rData.token` 与 `rData.expires_in` 用于后续业务请求。运行时只缓存 token，不写数据库，
日志只记录租户、连接器、函数、分页、状态和耗时。

当前端口和适配器支持：

| 能力 | 订货宝函数 | 分页/增量 |
| --- | --- | --- |
| 商品 | `getGoodsList` | `begin + step`，`step` 最大 1000；资料没有商品更新时间过滤 |
| 客户 | `getDealersList` | `begin + step`，可按 `create_date` 或 `update_date` 传时间窗口 |
| 订单摘要 | `getOrderList` | `begin + step`，可按创建时间或修改时间传窗口 |
| 订单明细 | `getOrderContent` | 单订单号查询；可控制是否自动标记已下载/审核 |

适配器还提供：

- 连接超时、读取超时；
- 仅对网络错误、HTTP 429、HTTP 5xx 做有限次指数退避重试；订货宝业务 `rStatus != 100`
  不自动重试；
- 每个租户连接器独立的进程内限流；多实例部署前需改成共享限流器或按供应商合同配置；
- 用 `rTotal` 和返回行数推进偏移页，并提供 `hasNext/nextRequest`；
- provider message 截断和敏感字段脱敏，绝不输出请求体、`Password`、`sKey`、token 或 API Key。

Integration 暴露 `POST /api/v1/integration/dinghuobao/connectors/{id}/test` 作为连接测试入口，
仅返回成功/稳定错误码和 token 到期时间，不返回 token 或 Secret；该动作需要
`integration:dinghuobao:write` 权限。

## Secret 和连接器配置

数据库 `integration_dinghuobao_connector.auth_secret_ref` 只保存引用，例如：

```text
env://RIGOUR_DHB_DEV
```

开发环境的默认 Secret 适配器随后从进程 Secret 环境读取：

```text
RIGOUR_DHB_DEV_SERIAL_NUMBER=<订货宝接口账号>
RIGOUR_DHB_DEV_PASSWORD=<订货宝接口密码>
```

这两个值不应写入 Git、Nacos、数据库、聊天记录或日志。生产环境应替换
`DinghuobaoSecretResolver` 为 Vault/KMS 等实现。账号、密码暂时留空时，连接测试会返回
`DINGHUOBAO_SECRET_NOT_CONFIGURED`，不会发起外部请求。

连接器的 `base_url` 必须由订货宝提供正式 API 基础地址；`https://pc.dhb168.com` 是后台入口，
不能直接当作 API 地址。代码不会默认拼接或猜测接口路径。

## 仍需向订货宝确认

1. 正式 API 基础 URL、环境（测试/正式）和网络白名单；
2. 账号密码之外是否存在 API Key、签名、IP 白名单或其他认证要求；
3. 每个接口的官方错误码含义、是否存在临时错误，以及 429/Retry-After 规则；
4. 每租户/每账号的 QPS、并发和每日额度；当前 YAML 的限流值只是保守本地默认值；
5. `begin/step` 在数据变化期间的稳定性、订单状态可配置范围，以及是否有官方增量游标/Webhook；
6. `rData` 在异常或大数据量场景是否始终为 JSON 数组/对象，还是可能返回 JSON 字符串。

完成确认后，再实现 Worker：从 `integration_sync_checkpoint` 读取订单/客户时间窗口，按页拉取，
写入 Raw Landing，幂等更新订单镜像并发布 Integration Outbox 事件。商品没有文档化增量字段，
在确认前只允许全量分页任务，不能假装有游标。
