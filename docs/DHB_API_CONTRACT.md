# 订货宝 Integration 接口边界

本文档记录当前代码依据的订货宝资料和未确认项。资料来源包括：
`05_公共资源/API文档/订货宝API标准对接接口V1-1.docx`，以及订货宝官方
[ERP 接口文档目录](https://docs.dhb168.com/books/erp/)、[基础说明](https://docs.dhb168.com/books/erp/page/cb70a)、
[getTokenValue](https://docs.dhb168.com/books/erp/page/gettokenvalue)、
[getGoodsList](https://docs.dhb168.com/books/erp/page/getgoodslist)、
[getDealersList](https://docs.dhb168.com/books/erp/page/getdealerslist)、
[getOrderList](https://docs.dhb168.com/books/erp/page/getorderlist) 和
[getOrderContent](https://docs.dhb168.com/books/erp/page/getordercontent)。这份资料是 ERP
对接说明，不是 OpenAPI 文件；未确认的 URL、字段、签名算法和租户配额不在代码中臆造。

## 官方在线基础页已确认的协议

- 正式环境根地址为 `https://erp.dhb168.com/`；推荐 JSON 请求完整地址为
  `https://erp.dhb168.com/home/index/erpIndex`。
- 所有接口使用 POST；请求体为 `{ "f": "业务方法", "v": { ... } }`，`v.sKey` 为请求密钥。
- `rStatus=100` 表示成功；常见认证/参数/接口状态错误包括 201～213，203 或 token 过期时必须重新取 token。
- 单次批量获取上限 1000，批量操作上限 100；官方基础页说明单客户端 QPS 上限为 20，429 需要按供应商规则处理。

本次实现使用每个租户连接器保存的完整 `base_url`，不把后台地址
`https://pc.dhb168.com` 当作 API 地址，也不在代码里自动拼接未确认的路径。

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
| 商品 | `getGoodsList` | `begin + step`，`step` 最大 1000；支持更新时间、商品编号和条码筛选 |
| 客户 | `getDealersList` | `begin + step`；支持创建/更新时间、客户编号、地区和客户类型筛选 |
| 订单摘要 | `getOrderList` | `begin + step`；支持创建/更新时间、订单状态、下载状态、异常状态、付款状态和拆单类型筛选 |
| 订单明细 | `getOrderContent` | 单订单号查询；`isAutoSign`/`isAutoAudit` 显式控制外部标记和审核副作用 |

适配器还提供：

- 连接超时、读取超时；
- 仅对网络错误、HTTP 429、HTTP 5xx 做有限次指数退避重试；订货宝业务 `rStatus != 100`
  不自动重试；
- 每个租户连接器独立的进程内限流；多实例部署前需改成共享限流器或按供应商合同配置；
- 用 `rTotal` 和返回行数推进偏移页，并提供 `hasNext/nextRequest`；
- provider message 截断和敏感字段脱敏，绝不输出请求体、`Password`、`sKey`、token 或 API Key。

Integration 的版本化跨服务契约位于
`services/rigour-integration-migration-service/integration-migration-api` 的
`DhbIntegrationApi`、`DhbProductApi`、`DhbOrderApi` 和 `DhbApiModels`；Integration
Controller 分别实现这些契约。
其他微服务依赖 API 模块，不依赖 `DhbClient` 或订货宝原始 `f/v` 报文。

其他微服务的调用方向固定为：

```text
ERP/Order Center -> integration-migration-api 契约 -> Gateway/Integration -> 订货宝
```

调用方使用 `integration-migration-api` 中的 DTO 组织 HTTP 请求，例如商品查询调用
`POST /api/v1/integration/dhb/products/{connectorId}/query`；`tenantId`、权限和
身份来自 Gateway 签名上下文，不能放在请求体里伪造。当前已完成的是 Integration 的 HTTP 边界和
版本化契约；调用方自己的 HTTP 客户端、服务编排和领域落库仍由 ERP/Order Center 实现，不能依赖
Integration 的 JDBC 表。定时任务使用独立服务身份的内部调用契约尚未实现，在该契约完成前不应让
各领域服务自行复制订货宝认证逻辑。

Integration 暴露 `POST /api/v1/integration/dhb/connectors/{id}/test` 作为连接测试入口，
仅返回成功/稳定错误码和 token 到期时间，不返回 token 或 Secret；该动作需要
`integration:dhb:write` 权限。

当前还提供手动订单同步入口：

```text
POST /api/v1/integration/dhb/orders/sync-tasks/{taskId}/run
```

第一阶段只支持 `objectType=ORDER`，按供应商更新时间窗口分页拉取订单列表，写入 Raw Landing、
订单镜像和 Integration Outbox；成功后推进 checkpoint。暂不自动调度、不调用订货宝写接口，
也不在本阶段拉取订单明细或直接写 Order Center。

商品和订单域的跨服务查询入口使用 POST 请求体承载查询条件，避免调用方直接拼接订货宝字段：

```text
POST /api/v1/integration/dhb/products/{connectorId}/query
POST /api/v1/integration/dhb/orders/{connectorId}/query
POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/content
POST /api/v1/integration/dhb/orders/sync-tasks/{taskId}/run
GET  /api/v1/integration/dhb/orders/mirrors
```

商品和订单列表查询只执行对应的订货宝读取接口；订单明细查询需要
`integration:dhb:write` 权限，并显式传入 `autoMarkDownloaded`、`autoAudit`，因为官方文档说明
`getOrderContent` 可能改变订单获取/审核状态。返回中的 `sourceFields` 只包含业务字段，不包含账号、密码或 Token。

当前业务域边界：商品和订单已公开 V1 API；仓库、客户、员工目录暂不公开 API，待确认官方接口
和内部领域落库责任后分别新增 `DhbWarehouseApi`、`DhbCustomerApi`、`DhbEmployeeApi`，不复用
一个总接口承载所有领域。

## Secret 和连接器配置

数据库 `integration_dhb_connector.auth_secret_ref` 只保存引用，例如：

```text
env://RIGOUR_DHB_DEV
```

开发环境不在 `application-local.yml` 声明凭据；在 IDEA Run Configuration 或进程环境中配置：

```text
RIGOUR_DHB_DEV_SERIAL_NUMBER=<订货宝接口账号>
RIGOUR_DHB_DEV_PASSWORD=<订货宝接口密码>
```

这两个值不应写入 Git、Nacos、数据库、聊天记录或日志。生产环境应替换
`DhbSecretResolver` 为 Vault/KMS 等实现。账号、密码暂时留空时，连接测试会返回
`DHB_SECRET_NOT_CONFIGURED`，不会发起外部请求。

连接器的 `base_url` 必须由订货宝提供正式 API 基础地址；`https://pc.dhb168.com` 是后台入口，
不能直接当作 API 地址。代码不会默认拼接或猜测接口路径。

## 命名兼容

活动代码、Java 包、API 路径、权限和运行时连接表统一使用 `Dhb`/`DHB`；Integration V3
将连接表统一为 `integration_dhb_connector`。V1/V2 迁移文件及 IAM 的历史迁移文件保留原文件名，
仅作为 Flyway 历史，不代表新的代码命名。订单中心已有的来源系统值 `DINGHUOBAO` 也是稳定数据编码，
本次不直接改写既有订单事实。

## 仍需通过真实账号确认

1. 账号密码之外是否存在 API Key、签名、IP 白名单或其他认证要求；
2. 每个接口的官方错误码含义、是否存在临时错误，以及 429/Retry-After 规则；
3. 每租户/每账号的并发和每日额度；官方基础页已给出单客户端 QPS=20，但仍需确认账号级限制；
4. `begin/step` 在数据变化期间的稳定性、订单状态可配置范围，以及是否有官方增量游标/Webhook；
5. `rData` 在异常或大数据量场景是否始终为 JSON 数组/对象，还是可能返回 JSON 字符串；
6. 真实账号返回字段是否存在租户级扩展字段，尤其是订单明细中的 `Invoice`、`Ships`、`Payment` 和 `body` 子结构。

当前 Worker 已先实现订单手动拉取：从 `integration_sync_checkpoint` 读取订单更新时间窗口，按页拉取，
写入 Raw Landing，幂等更新订单镜像并发布 Integration Outbox 事件。商品同步、客户/仓库/员工目录、定时调度、
死信重放以及下游消费仍待后续实现；商品接口虽有 `updateGe/updateLe`，仍需真实账号确认时间窗口稳定性。
