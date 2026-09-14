# 城市商品报表与供货调查增量契约

## 数据边界

- 商品分类、品牌与SKU选项读取ERP API；分类父子关系不得写死或从有成交商品反推。
- V13增加`bi_product_category_dim`全量分类树和`bi_product_category_closure`包含自身的祖先闭包。`refreshProductDim`在同一刷新事务中读取ERP目录、重建闭包、修正本地订单行/库存/采购事实分类；分类环和父级断链直接阻止刷新。
- 普通BI选项/查询只读BI分类投影。查询父分类含全部后代。分类图展示叶分类及直接承载商品的父分类，不把父汇总和子汇总相加；未成交叶分类保留零值，分类关联缺失的既有订单独立保留为待核对，不能丢金额。
- 报表查询优先使用`bi_product_dim`中当前ERP商品分类与品牌，而非陈旧订单行分类快照。未同步分类目录时明确拒绝分类报表，不把目录缺失解释为零销售。

## 报表参数

`GET /api/v1/analytics/supply/dashboard/city-product-report`新增可选`brandId`、`productId`、`skuId`；与已有`productCategoryId`及城市、销售、客户类型、来源、期间相交。

- 参数名是`skuId`，不是`productVariantId`；允许SKU单独下钻，不要求调用方提供商品ID。
- SQL先筛匹配的订单，再取完整订单所有明细计算分母；不得提前过滤整单行。
- EXACT_ONLY继续默认。选中分类的一部分品牌/SKU时，不能复制该分类的完整回款。PROPORTIONAL手选时才使用整单权重计算，再输出所选子集。
- 分类/商品/月度归属与资金守恒、未归属金额均保留。金额API仍为十进制字符串，Excel金额保留两位数值；超安全精度保留文本并警示。
- 商品SKU及单品月报增加品牌、分类业务列；选项与Excel默认全选业务字段，不显示内部ID。筛选元数据及业务页标题包含品牌、商品、规格名称。

## 供货调查

`GET /api/v1/analytics/supply/dashboard/city-product-report/supply`

必填`from/to/productId/warehouseId`，可选`skuId`。无city参数，销售城市不能自动等同供货仓库。主数据范围治理需同时覆盖此独立Controller。

- `stocks`：BI当前库存，按租户、ERP商品/SKU主键与用户明确选择的仓库读取，可用/锁定/在途分别展示。
- `operations`：选定期间、同商品/SKU的全仓采购订购量与发货量，按北京时间月份、原始单位独立统计。现有流转投影不含仓库维度，不能宣称城市/所选仓库采购量，也不是采购付款或实际入库量。
- `inventoryStatus/operationStatus`分别返回FRESH/STALE/FAILED/RUNNING/UNAVAILABLE和最近成功时间。FRESH仅表示24小时内成功同步，不代表业务已对账；旧快照附显著状态，空结果不当零库存。
- 不按名称、默认仓库、`minOrderQuantity`或“12桶/箱”等文本推断SKU/地点/换算关系。BOX与BUCKET不同单位明确待核查；无关联不估算可售天数、补货量或成本。

## 页面与集成

`CityProductReport.vue`使用ERP分类树、品牌、远程商品检索、SKU规格筛选；父类商品选项按真实后代分类检索。`CityProductInvestigation.vue`提供城市商品矩阵和月份趋势，点选商品进入供货调查，表格折叠为核对明细。SKU单独下钻仅在返回事实唯一关联商品时补出供货入口。

部署需要BI重启执行V13后，经现有BI刷新流程建立目录及修正关联。此变更不自动导入飞书，不修改ERP/Order/CRM/HR业务数据。

## 验证

后端由主代理统一串行执行：`CityProductReportRepositoryTest`、`CityProductReportSerializationTest`、`ProductCategoryProjectionTest`、`CityProductSupplyRepositoryTest`、`SupplyDashboardOperatingAnalysisRepositoryTest`。

前端覆盖报表参数/导出、树递归与父类商品检索、ERP当前分类优先、SKU-only上下文、异步过期响应、库存缺失/过期/失败、单位不匹配；真实运行服务/数据库及浏览器业务对账不由单元测试代替。
