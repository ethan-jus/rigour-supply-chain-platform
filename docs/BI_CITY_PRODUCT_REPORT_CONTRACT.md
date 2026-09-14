# 城市商品报表只读合同

接口：`GET /api/v1/analytics/supply/dashboard/city-product-report`。

查询参数：`from`、`to`（ISO Instant）、`regionCode`、`ownerStaffCode`、`customerTypeCode`、`productCategoryId`、`sourceSystemCode`，与 overview 一致；新增 `allocationMode=EXACT_ONLY|PROPORTIONAL`，默认 `EXACT_ONLY`。不保存用户选择、不写业务库。

## 返回结构

完整 Java 合同：`CityProductReportView.java`。ID 保持字符串；`Row`、`OrderTrace`、`Summary`、`UnitQuantity` 中所有 BigDecimal 金额和数量均以 JSON 十进制字符串返回，保留源精度，不经浮点数转换。例如 `"123456789012345.001000"` 的高位和六位小数原样保留；没有值仍为 JSON null，不是 `"null"`。订单数、客户数、行数仍为 JSON 数字。本约定仅用于此报表，不改变 overview 或全局 Jackson 契约。

前端须在精度检测和导出前保留十进制字符串，不能先转 Number；字符串传输不解除 Excel 数值单元格的15位有效数字限制。

- `from/to`：实际 UTC 订单日期闭区间；不传时使用最新订单日期及其月首。
- `generatedAt`：报表生成时间。
- `dataUpdatedAt`：本批订单和订单行最近的同步时间，不代表全部来源同步完成水位。
- `allocationMode`：本次请求模式。
- `rows`：城市＋分类＋商品＋SKU＋单位。
- `monthlyRows`：`{ month: "YYYY-MM", metrics: Row }[]`，按北京时间订单月份细分相同商品粒度；回款仍为这些订单的累计回款，并非当月回款流水。
- `customerArchives`：`{ regionCode, regionName, customerCount }[]`，当前客户档案数，按城市、销售和客户类型过滤；不受订单日期、分类和订单来源限制，不是历史期末客户数。
- `categoryRows`：城市＋分类，金额不按单位拆分。
- `orderTrace`：每个选中订单一行，用于导出附表；含来源系统和来源订单号，不能仅按订单号跨来源合并。
- `summary`：独立计算去重数量和金额汇总。
- `truncated/exportBlocked`：超限时均为 true，数据列表为空、summary=null，前端必须阻止导出并要求缩小范围。
- `definitions`：金额、单位、时间、缺口和导出限制说明。

## Row

维度字段：`regionCode/regionName`、`categoryId/categoryCode/categoryName`、`productId/productCode/productName`、`skuId/skuCode`、`unitCode`、`specification`。规格来自商品行事实快照；分类行的商品及 SKU 字段为 null。业务页面和工作簿不展示内部 ID/编码，但接口保留这些关联键。

数量字段：`quantity: string|null`、`quantities: {unitCode: string|null, quantity: string}[]`，均为原始订货数量，未扣退货数量，不是净出库销量。商品行单位唯一；同分类多单位时 `quantity/unitCode=null`，只能逐项展示 quantities，不能合计为箱数。

金额字段均为十进制字符串，可空字段保留 null：

| 字段 | 定义 |
|---|---|
| salesAmount | 所选订单行 line_amount，已含行优惠，未减整单优惠 |
| salesNetAmount | BI 行金额扣退款后的金额，未减整单优惠，与已分配订单净应收 receivableAmount 不同 |
| refundAmount | BI 现有订单退款按行分摊值，并非商品退款核销事实 |
| receivableAmount | 当前粒度可归属的订单净应收；无可归属值为 null |
| paidAmount | 当前粒度可归属累计回款，**包含 allocatedPaidAmount**；无可归属值为 null |
| allocatedPaidAmount | paidAmount 中按比例估算的部分，不能再次相加；没有比例估算为 null |

`allocationStatus`：`EXACT`、`PROPORTIONAL`、`PARTIAL`、`UNALLOCATED`。

`unallocatedOrderCount`：该行仍有未确认归属的去重订单数。有该值时，已归属应收/回款只是已确认部分，不能称为总回款。`orderCount/customerCount` 在各粒度独立去重，不能跨行相加。

## Summary 与附表

`summary`：`orderCount/customerCount` 为本批去重订单和客户；`quantities` 为逐单位数量；`salesAmount/salesNetAmount/refundAmount` 为所选行汇总。

`orderPayableAmount/orderPaidAmount/orderUnpaidAmount` 是所选订单完整应收、累计回款、未回款，即使有分类筛选，也包含该订单其他分类。

`receivableAmount/paidAmount/allocatedPaidAmount/unallocatedOrderCount` 是商品粒度；`categoryPaidAmount/unallocatedCategoryOrderCount` 是分类粒度。同分类多SKU或多单位订单在 EXACT_ONLY 下，分类回款可以明确，商品回款仍待确认。

商品口径资金恒等式：`orderPaidAmount = paidAmount(空按0) + excludedPaidAmount + unallocatedPaidAmount`。`excludedPaidAmount` 是已归属但被分类筛选排除的部分，`unallocatedPaidAmount` 是尚未归属的整订单款项。

`orderTrace` 金额：`total` 为订单净应收，`lineTotal` 为整订单行销售额，`selectedLineTotal` 为筛选内行销售额，`orderAdjustmentAmount=total-lineTotal`。`paid/unpaid` 是整单金额；`selectedReceivableAmount/selectedPaidAmount/allocatedPaidAmount` 是筛选内商品归属金额；`excludedPaidAmount/unallocatedPaidAmount` 对应上述守恒关系。

附表另含 `orderId/orderNo/sourceOrderNo/sourceSystemCode`、城市、销售编码、客户ID、`customerName/ownerStaffName`、订单日期、完整/所选行数、`status/categoryStatus`。客户和销售姓名来自订单事实快照，不通过导出时跨服务拼接。两个 status 分别是商品与分类口径：

| 状态 | 含义 |
|---|---|
| EXACT | 整单唯一归入此粒度；不是商品核销凭证 |
| PROPORTIONAL | 用户显式选择的比例估算 |
| MIXED_ITEMS | EXACT_ONLY 下存在多个待分配对象 |
| MISSING_LINES | 没有可用订单行 |
| REFUND_REVIEW / NEGATIVE_LINE / ZERO_DENOMINATOR | 退款、负数或非正整单分母待确认 |
| EXTRA_CHARGE_OR_MISSING_LINES | 应收超过行合计，额外费用或缺行不能直接判断 |
| ORDER_BALANCE_REVIEW / LINE_TOTAL_REVIEW | 收付或订单行金额校验不一致 |
| INVALID_LINE / UNKNOWN_DIMENSION | 行值缺失或维度无法确定 |

## 分配与边界

本地数据源为 `bi_sales_order_fact`、`bi_sales_order_line_fact` 和客户档案 `bi_customer_dim`。报表在同一只读事务中查询。按订单日期和订单归属筛选，排除取消、删除订单和删除行。分类条件仅选择符合条件的订单，分母始终包含该订单全部有效行；分配完成后才裁取选中分类及月份。绝不联回款流水表，也不在普通报表查询中跨业务数据库查询。

Order 源码的净应收为行金额合计减整单优惠。报表把应收低于行合计的非负折让差额纳入此模型；净应收和累计回款按同一整单分类金额权重分配，再在分类内分到SKU/单位。六位小数最大余数法确定尾差，单项回款不超过已分净应收。未知额外费用、退款、负数、零分母等不分摊。

BI 无独立商品核销、原始整单优惠明细和源订单应有行数；这些来源完整性仍需同步链路验收，不能把报表可归属金额当作原始商品收款凭证。

最多10000订单、50000条源结果、每种汇总（包括月度）10000行，不提供静默截取的导出结果。

## 业务工作簿约定（2026-09-12）

- 用户确认默认不分摊，只有手动选择 `PROPORTIONAL` 才分摊。参考工作簿不改变系统默认口径。
- 城市经营汇总将动态 ERP 品类销售、客户档案、本期去重下单客户与订单数放在同一主表；未筛分类时才呈现整单应收/回款，避免冒充品类回款。
- 城市单品月报按当前日期范围生成月份列、城市小计及筛选范围合计。按北京时间解释页面日期边界；月度聚合复用订单分配结果，不重新按筛选范围分摊。
- 金额在展示与 Excel 输出时按原始精度汇总再四舍五入保留两位；JSON 精度不变。Excel 超过15位有效数字采用文本并提示，不静默丢失精度。
- 单位使用业务字典，数量不保留无意义的尾零。规格文字不能代替经核验的包装换算；当前不自动把桶折成箱。
- 业务字段默认全部勾选，可手动选择；主表之外保留城市分类、商品SKU、订单核对和统计口径附表。未归属回款保留空值及核对状态，不当成零。

服务重启后已用登录页面读取真实月度、档案、规格和姓名字段，并下载两种业务工作簿。平台 `./mvnw verify` 全51模块构建成功，741项中588通过、153跳过，BI模块87项通过；Docker依赖测试跳过不计为通过。实际文件和数据边界见 Portal `docs/bi-implementation-acceptance-2026-09-12.md`。

授权继承 overview 的可信租户及 `analytics:dashboard:read`。当前已确认读权限为租户级；staff/city 是业务筛选，不临时推导细粒度授权。后续 DataScope 政策由主线确认。

本次只新增独立 API/model/controller/service/port/mapper/repository/test 文件，未改动现有 SupplyDashboardQueryMapper 等并行开发文件。
