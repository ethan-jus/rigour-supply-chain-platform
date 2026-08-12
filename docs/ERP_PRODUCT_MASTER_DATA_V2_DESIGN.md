# ERP 商品主数据 V2 设计

## 1. 本期边界

本期只完成商品主数据：SPU、SKU、分类、品牌、规格、规格值、标签、标签分组，以及商品图片、价格、计量单位、库存策略和来源扩展字段。

供应商、采购单、采购退货、入库单、仓库和库存业务暂不实现，避免在商品主数据链路尚未稳定时并行建设第二套业务模型。

新增字段全部并入已有商品同步链路，不新增第二套商品同步入口：

```text
Portal 商品中心
  -> Gateway / ERP Core API
  -> erp-core-service ProductMasterDataSyncService
  -> Integration V1 DhbProductMasterDataClient
  -> integration DhbIntegrationService
  -> 订货宝 getGoodsList / getSite / getBrands / getMultiOptionsList / getGoodsTag
```

Integration 独占订货宝协议、Token、Secret、重试、限流、Raw Landing、字段归一化和图片下载/COS 上传；ERP 负责统一同步批次、来源绑定、幂等落库、内部状态保护和本地查询；Portal 只访问 ERP。

## 2. 表分层

### 2.1 已有主表（V1 保留并增量扩展）

| 表 | 用途 | 关键字段 | 主数据边界 |
| --- | --- | --- | --- |
| `erp_product_spu` | ERP 商品/SPU 主表 | `spu_code`, `name`, `brand_id`, `base_unit`, `default_barcode`, `ownership_state`, `internal_status` | 保存 ERP 商品模型；来源字段不能覆盖人工维护的内部状态 |
| `erp_product_sku` | SKU 主表 | `spu_id`, `sku_code`, `source_options_id`, `barcode`, `middle_barcode`, `big_barcode` | SKU 是商品可销售规格组合，不单独作为商品中心菜单 |
| `erp_category` | 分类主表 | `category_code`, `parent_id`, `category_level`, `source_parent_id`, `source_category_number`, `source_default_flag` | `parent_id` 指向 ERP 分类；`source_parent_id` 只做来源追溯 |
| `erp_brand` | 品牌主表 | `brand_code`, `name`, `source_brand_number`, `source_sort_order`, `source_description` | 保存可被 SPU 引用的 ERP 品牌模型 |
| `erp_specification` | 规格维度主表 | `specification_code`, `name`, `source_parent_id` | 例如颜色、尺寸 |
| `erp_specification_value` | 规格值主表 | `specification_id`, `value_code`, `value_name`, `source_parent_id` | 例如红色、L |
| `erp_tag` | 商品标签主表 | `tag_code`, `name`, `tag_group_id`, `source_group_id`, `source_sort_order`, `source_relation_count` | 标签可以逐步转为内部主数据 |
| `erp_product_spu_category` | SPU 与分类关系 | `spu_id`, `category_id`, `is_primary`, `sort_order` | 支持未来多分类；查询列表当前取主分类 |
| `erp_product_spu_specification` | SPU 可用规格维度 | `spu_id`, `specification_id` | 由 SKU 规格组合反向建立 |
| `erp_product_sku_specification_value` | SKU 与规格值关系 | `sku_id`, `specification_id`, `value_id` | 组合唯一性和展示顺序 |
| `erp_product_spu_tag` | SPU 与标签关系 | `spu_id`, `tag_id` | 后续标签业务可在此扩展 |

### 2.2 V2 辅助明细表

| 表 | 字段说明 | 设计理由 |
| --- | --- | --- |
| `erp_tag_group` | `group_code`、`name`、状态、主权、版本和审计字段 | `getGoodsTag` 返回分组信息，独立建模避免把分组名称复制成不可维护文本 |
| `erp_product_image` | SPU/SKU 归属、来源资源信息、`object_key`、排序、主图标记、主权和审计字段 | 数据库只保存我方 COS 私桶 key；不保存订货宝 URL |
| `erp_product_price` | `target_type`、`target_id`、`price_type`、`unit_level`、`amount`、`source_field`、币种和主权字段 | 同一套表承接 SPU/SKU、基础/中包装/大包装、多种来源价格 |
| `erp_product_unit` | `target_type`、`target_id`、`unit_level`、单位名、条码、`conversion_to_base`、来源字段 | 统一保存基础单位、中包装、大包装和换算率 |
| `erp_product_inventory_policy` | SPU、库存下限、上限、安全库存及来源字段 | 保存 `librarydown`、`libraryup`、`librarysafe` 的来源快照，不冒充实时库存 |
| `erp_product_custom_field` | SPU/SKU 归属、`field_key`、`field_value`、`source_field` | 承接 `field_1` 至 `field_6` 和后续无法提前标准化的来源字段 |

所有新增明细表均带 `tenant_id`、`ownership_state`、`record_origin`、`version`、`created_at`、`updated_at`。所有唯一键都包含租户和业务归属，避免跨租户碰撞。

## 3. 图片处理

1. `getGoodsList` 返回真实可下载的绝对或相对图片地址时，Integration 先创建持久化图片任务，后台按固定并发下载图片字节。
2. Integration 使用私桶 COS 配置上传，object key 固定为
   `{tenantId}/product-images/{sourceProductId}/{sourceImageId}/{sha256}.{extension}`；COS 的
   `product-images` 是由 `rigour.erp.product-media.cos.object-prefix` 和
   `rigour.integration.product-media.cos.object-prefix` 共同配置的对象 Key 前缀，不需要预先创建空目录。
3. Integration 到 ERP 的商品投影只传 `objectKey` 和来源图片元数据。
4. ERP 写入 `erp_product_image.object_key` 与 `erp_product_spu.main_image_key`；其他业务图片字段也只能保存 `*_object_key`。
5. ERP 每次列表查询从 object key 重新生成短时 URL，Portal 只渲染本次响应中的 URL，不缓存永久地址。
6. 相同内容使用同一个 SHA-256 版本 key，COS 已存在时跳过重复上传；内容变化生成新 key，历史对象由私桶生命周期规则延迟清理。
7. 订货宝文档定义 `goods_picture` 为主图，`goods_imgs.file_name` 为图片完整路径；当前租户回执也可能返回相对路径，由 Integration 的独立图片源站配置补全。若实际租户回执为空或地址无效，则同步明确失败或跳过空值，不伪造图片 URL。
8. 商品页默认按50个商品拆分图片任务；单实例默认4个消费者，避免几百张图片占满 ERP/Integration 的 HTTP 请求线程和 COS 连接。

## 4. 幂等与主权

- `erp_master_source_binding` 以租户、来源系统、来源对象类型、来源对象 ID 建立唯一来源绑定。
- `source_payload_hash` 未变化时记录重复；变化时只更新 `EXTERNAL_PRIMARY` 数据。
- `INTERNAL_PRIMARY` 或人工覆盖记录不被订货宝同步覆盖。
- 商品同步由 `PRODUCT_SPU` 一个对象类型完成，`getGoodsList.multi` 中的 SKU 在同一批次内落库；分类、品牌、规格、标签使用同一个同步接口的 `objectType` 参数切换。
- 图片、价格、单位、库存策略、扩展字段由同一个 `importProduct` 事务重建外部辅助明细，不存在另一套商品保存流程。

## 5. 订货宝字段归一化

| 订货宝字段 | ERP 归一化位置 |
| --- | --- |
| `price1` / `price2` / `price3` / `price4` | `erp_product_price`：SPU + `BASE` + `ORDER/MARKET/PURCHASE/OTHER` |
| `whole` / `selling` / `purchase` | `erp_product_price`：SKU + `BASE` |
| `middle_unit_whole_price` / `big_unit_whole_price` | `erp_product_price`：对应 SPU/SKU + `MIDDLE/BIG` + `ORDER` |
| `units` / `middle_units` / `bigunits` | `erp_product_unit` |
| `barcode` / `middle_barcode` / `big_barcode` | `erp_product_sku`、`erp_product_unit` |
| `base2middle_unit_rate` / `conversionnumber` | `erp_product_unit.conversion_to_base` |
| `librarydown` / `libraryup` / `librarysafe` | `erp_product_inventory_policy` |
| `field_1` 至 `field_6` | `erp_product_custom_field` |
| `goods_picture` / `goods_imgs` | Integration 下载后写入 `erp_product_image.object_key` |

正式执行脚本为 `services/rigour-erp-core-service/erp-core-service/src/main/resources/db/migration/V2__erp_product_master_data_media_and_attributes.sql`；历史品牌图片列由 `V6__erp_private_media_object_keys.sql` 收口为 `logo_object_key`。
