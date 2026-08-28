# 订货宝 Integration 接口边界

本文档记录当前代码依据的订货宝资料和未确认项。本地资料为
`../../docs/订货宝API标准对接接口V1-1.docx`，以及订货宝官方
[ERP 接口文档目录](https://docs.dhb168.com/books/erp/)、[基础说明](https://docs.dhb168.com/books/erp/page/cb70a)、
[getTokenValue](https://docs.dhb168.com/books/erp/page/gettokenvalue)、
[getGoodsList](https://docs.dhb168.com/books/erp/page/getgoodslist)、
[getDealersList](https://docs.dhb168.com/books/erp/page/getdealerslist)、
[getOrderList](https://docs.dhb168.com/books/erp/page/getorderlist) 和
[getOrderContent](https://docs.dhb168.com/books/erp/page/getordercontent)、
[getShipsList](https://docs.dhb168.com/books/erp/page/getshipslist)、
[getShipsContent](https://docs.dhb168.com/books/erp/page/getshipscontent)、
[getWaitShips](https://docs.dhb168.com/books/erp/page/getwaitships)、
[getReturnsList](https://docs.dhb168.com/books/erp/page/getreturnslist)、
[getReturnsContent](https://docs.dhb168.com/books/erp/page/getreturnscontent)、
[getReceiptsList](https://docs.dhb168.com/books/erp/page/getreceiptslist) 和
[getPaymentList](https://docs.dhb168.com/books/erp/page/getpaymentlist)。这份资料是 ERP
对接说明，不是 OpenAPI 文件；未确认的 URL、字段、签名算法和租户配额不在代码中臆造。

## 官方在线基础页已确认的协议

- 正式环境根地址为 `https://erp.dhb168.com/`；推荐 JSON 请求完整地址为
  `https://erp.dhb168.com/home/index/erpIndex`。
- 所有接口使用 POST；请求体为 `{ "f": "业务方法", "v": { ... } }`，`v.sKey` 为请求密钥。
- `rStatus=100` 表示成功；常见认证/参数/接口状态错误包括 201～213，203 或 token 过期时必须重新取 token。
- 单次批量获取上限 1000，批量操作上限 100；官方基础页说明单客户端 QPS 上限为 20，429 需要按供应商规则处理。

本次实现使用每个租户连接器保存的完整 `base_url`，不把后台地址
`https://pc.dhb168.com` 当作 API 地址，也不在代码里自动拼接未确认的路径。
订货宝商品图片相对地址不复用 API `base_url`，由
`rigour.integration.dhb.client.image-base-url` 独立配置；当前 DEV 默认使用
`https://img.dhb168.com/`。接口文档中的 `file_name` 示例是完整 URL，当前租户回执
也可能返回相对路径；解析器兼容两种格式，绝对图片 URL 保持原样。

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
| 商品/SPU + SKU | `getGoodsList` | `begin + step`，`step` 最大 1000；支持 `status`、`putaway`、`goodsCode`；`multi` 返回 SKU 组合 |
| 商品分类 | `getSite` | 全量；V1.1 返回 `SiteID/SiteName/ERPID`，不返回父分类 |
| 商品品牌 | `getBrands` | 全量；返回 `brandID/brandName/erpID` |
| 规格/规格值 | `getMultiOptionsList` | `begin + step`，`step` 最大 1000；父规格通过 `children` 返回规格值 |
| 商品标签 | `getGoodsTag` | 由项目对接要求确认；当前 V1.1 本地文档未提供字段表，需真实账号验证 |
| 客户 | `getDealersList` | `begin + step`；支持创建/更新时间、客户编号、地区和客户类型筛选 |
| 订单摘要 | `getOrderList` | `begin + step`；支持创建/更新时间、订单状态、下载状态、异常状态、付款状态和拆单类型筛选 |
| 订单明细 | `getOrderContent` | 单订单号查询；适配器固定传 `isAutoSign=2`、`isAutoAudit=2`，只读查询，不改变订货宝下载/审核状态 |
| 出库/发货单列表 | `getShipsList` | 订货宝 `page/page_size` 分页；支持状态、下载状态、出库类型、创建/更新时间、客户和仓库筛选 |
| 出库/发货单详情 | `getShipsContent` | `ships_num` 查询；返回主单、收货地址、联营商和 `list` 商品明细 |
| 退货单列表 | `getReturnsList` | `begin + step`；支持退货状态、下载状态、创建/更新时间和仓库筛选 |
| 退货单明细 | `getReturnsContent` | `returnsSn` 查询；返回退货单主信息和 `body` 商品明细 |
| 收款单列表 | `getReceiptsList` | `begin + step`；支持订单号、转账时间、`updateDateGe` 和收款状态筛选 |
| 付款单列表 | `getPaymentList` | `begin + step`；支持订单号、转账时间和付款状态筛选；官方接口不提供更新时间筛选 |

本轮订货宝对接只允许查询类函数。`DhbClientAdapter` 对业务函数设置只读白名单，包含商品、分类、
品牌、规格、标签、仓库、库存、客户类型、归属地区、客户、收货地址、员工、订单、出库、退货、
收款、付款、供应商、采购、采购退货和入库查询；`write...DownloadStatus`、`add...`、`approve...`、
`cancel...`、`confirm...`、`sync...` 等会改变订货宝数据或状态的函数不得进入适配层。我们自己的
CRM、ERP、Order 业务新增、编辑、作废和审核继续走本平台领域服务接口，不反向写订货宝。

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
Portal -> ERP 单一同步入口 -> integration-migration-api 契约 -> Integration -> 订货宝
Order Center定时任务 -> 内部目标发现/可信 SERVICE 契约 -> Integration -> 订货宝
ERP定时任务 -> 内部目标发现/租户范围 SERVICE 契约 -> Integration -> 订货宝
```

调用方使用 `integration-migration-api` 中的 DTO 组织 HTTP 请求，例如商品查询调用
`POST /api/v1/integration/dhb/products/{connectorId}/query`；`tenantId`、权限和
身份来自 Gateway 签名上下文，不能放在请求体里伪造。当前已完成的是 Integration 的 HTTP 边界和
版本化契约；调用方自己的 HTTP 客户端、服务编排和领域落库仍由 ERP/Order Center 实现，不能依赖
Integration 的 JDBC 表。Order Center 定时任务使用独立的可信 `SERVICE` 身份调用未配置到 Gateway
的 `/internal/v1/integration/dhb/sync-targets` 动态发现启用订单任务，再以目标 `tenantId` 调用上述
查询契约；ERP 定时任务同样先按 `PRODUCT_MASTER_DATA` 和 `SUPPLY_CHAIN_DATA` 动态发现目标，
再以目标 `tenantId` 调用统一 ERP 同步应用服务；各领域服务不得复制订货宝认证逻辑。

Integration 暴露 `POST /api/v1/integration/dhb/connectors/{id}/test` 作为连接测试入口，
仅返回成功/稳定错误码和 token 到期时间，不返回 token 或 Secret；该动作需要
`integration:dhb:write` 权限。

Integration 侧仍保留同步任务执行能力，但前端不再调用该执行入口。Order Center 的定时编排会
通过版本化查询契约读取数据，完成订单域业务幂等落库后才推进自己的同步游标；Integration 只负责
订货宝访问、技术原始数据落地和字段转换。

当前本地工作区已确认可供 Order Center 调用的订单查询契约为：

```text
POST /api/v1/integration/dhb/orders/{connectorId}/query
POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/content
POST /api/v1/integration/dhb/orders/{connectorId}/shipments/query
POST /api/v1/integration/dhb/orders/{connectorId}/shipments/{shipmentNumber}/content
POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/wait-ships
POST /api/v1/integration/dhb/orders/{connectorId}/returns/query
POST /api/v1/integration/dhb/orders/{connectorId}/returns/{returnNumber}/content
POST /api/v1/integration/dhb/orders/{connectorId}/receipts/query
POST /api/v1/integration/dhb/orders/{connectorId}/payments/query
GET  /internal/v1/integration/dhb/sync-targets  (仅服务间目标发现，不经过 Gateway)
```

ERP 商品主数据使用的 Integration 查询契约为：

```text
POST /api/v1/integration/dhb/products/{connectorId}/media-sync
GET  /api/v1/integration/dhb/products/{connectorId}/media-sync/{jobId}
POST /api/v1/integration/dhb/products/{connectorId}/query
POST /api/v1/integration/dhb/products/{connectorId}/categories/query
POST /api/v1/integration/dhb/products/{connectorId}/brands/query
POST /api/v1/integration/dhb/products/{connectorId}/specifications/query
POST /api/v1/integration/dhb/products/{connectorId}/tags/query
GET  /internal/v1/integration/dhb/sync-targets?objectType=PRODUCT_MASTER_DATA
```

商品图片先创建 `media-sync` 任务，Integration 将图片明细持久化后由固定并发消费者处理；
任务状态为 `SUCCEEDED` 后，ERP 在商品查询请求中携带 `mediaJobId` 获取已完成的 COS
object key。每个实例默认4个图片消费者，失败最多重试3次；任务使用数据库行锁和
`SKIP LOCKED` 领取，服务重启后可继续处理。

退货单状态原值为 `return_audit`（待退货审核）、`shipp_cust`（待客户发货）、
`shipped`（待收货）、`refunded`（待退款）、`finished`（已完成）和 `cancelled`（已取消）。
收付款列表的状态筛选值为 `pend_receipt`（待确认）、`pend_receipted`（已确认）、
`canceled`（已取消）和 `all`（全部）。订货宝收付款列表的部分历史数据可能不返回状态字段，
Integration 会保留空值并完整返回 `sourceFields`，不自行推断状态。

订货宝同步调度由 Integration 统一编排。Portal 的“统一同步”只调用订货宝同步中心，
不得直接调用 ERP、CRM、Order 的模块级同步接口。首次任务、增量窗口、重叠区间、
失败重试和对账证据均在 Integration 侧记录；前端业务列表只查询 ERP/CRM/Order
各自的新业务表，不实时访问订货宝。

`wait-ships` 对应订货宝 `getWaitShips`，请求参数只有 `orders_num`；返回 `shipped` 已出库/已发货记录和
`wait_stock` 待出库明细。Integration 负责认证、调用和字段归一化，Order Center 只接收不含凭据的业务数据并幂等落库。

商品和订单域的跨服务查询入口使用 POST 请求体承载查询条件，避免调用方直接拼接订货宝字段：

```text
POST /api/v1/integration/dhb/products/{connectorId}/query
POST /api/v1/integration/dhb/orders/{connectorId}/query
POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/content
POST /api/v1/integration/dhb/orders/{connectorId}/{orderNumber}/wait-ships
GET  /api/v1/integration/dhb/orders/mirrors
```

订货宝统一同步入口为：

```text
POST /api/v1/integration/dhb/orchestration/sync
```

Portal 只能调用该统一入口；单对象修复入口保留为后端排障能力，不作为业务员默认操作。

Order Center 面向 Portal 的本地业务查询接口如下：

```text
GET /api/v1/orders/sales-orders
GET /api/v1/orders/sales-orders/{id}
POST /api/v1/orders/sales-orders
POST /api/v1/orders/sales-orders/{id}/stock-out
```

出库、物流、退货和收付款来源快照由 Integration 在 Raw 和对账记录中保存；业务页面按我方
销售订单、ERP 出库单和收款业务模型展示，不再提供订货宝镜像列表。

商品、订单列表和订单明细查询都只执行订货宝读取接口。订单明细虽然官方参数支持自动标记和自动审核，
但本平台固定传入 `isAutoSign=2`、`isAutoAudit=2`，并只要求 `integration:dhb:read` 权限。
返回中的 `sourceFields` 只包含业务字段，不包含账号、密码或 Token。

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

订单 Worker 已实现 Raw Landing、订单镜像、Outbox 和 Order Center 本地投影。
ERP 已实现商品/SPU、SKU、分类、品牌、规格/规格值和标签的手动同步、幂等落库和本地查询。
客户/仓库/员工目录、商品自动增量调度、死信重放以及下游消费仍待后续实现。
