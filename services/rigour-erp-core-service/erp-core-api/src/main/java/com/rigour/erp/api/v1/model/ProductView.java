package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** ERP 本地商品/SPU 列表投影，不暴露订货宝原始报文。 */
public record ProductView(
        /** ERP 商品 UUID，后续自研业务应引用该标识。 */
        String id,
        /** 订货宝商品来源 ID，仅用于追溯和幂等绑定。 */
        String sourceProductId,
        /** 订货宝分类来源 ID。 */
        String sourceCategoryId,
        /** 订货宝品牌来源 ID。 */
        String sourceBrandId,
        /** ERP 租户内唯一的 SPU 编码。 */
        String spuCode,
        /** 商品名称。 */
        String name,
        /** ERP 品牌名称快照；来源品牌尚未绑定时为空。 */
        String brandName,
        /** ERP 主分类名称；来源分类尚未绑定时为空。 */
        String categoryName,
        /** 商品默认条码。 */
        String barcode,
        /** 商品基础计量单位。 */
        String unit,
        /** 订货宝上下架状态原值，不等同于 ERP 内部状态。 */
        String sourcePutaway,
        /** ERP 内部商品状态；外部主导记录随来源上下架更新，本地主导记录保留人工状态。 */
        String internalStatus,
        /** 数据主权状态，例如 EXTERNAL_PRIMARY 或 INTERNAL_PRIMARY。 */
        String ownershipState,
        /** 当前 SPU 已落库的 SKU 数量。 */
        int skuCount,
        /** 最近一次成功处理该来源商品的时间。 */
        Instant syncedAt,
        /** 商品型号。 */
        String model,
        /** 商品副标题。 */
        String subtitle,
        /** 商品关键词。 */
        String keywords,
        /** 商品货位号。 */
        String goodsAllocation,
        /** 订货宝 multi_id 原值。 */
        String sourceMultiId,
        /** 基础单位订货价。 */
        BigDecimal orderPrice,
        /** 基础单位市场价。 */
        BigDecimal marketPrice,
        /** 基础单位采购价。 */
        BigDecimal purchasePrice,
        /** 订货宝扩展价格。 */
        BigDecimal price4,
        /** 中包装单位。 */
        String middleUnit,
        /** 大包装单位。 */
        String bigUnit,
        /** 中包装条码。 */
        String middleBarcode,
        /** 大包装条码。 */
        String bigBarcode,
        /** 换算条码。 */
        String conversionBarcode,
        /** 基础单位到中包装单位的换算数量。 */
        BigDecimal baseToMiddleRate,
        /** 基础单位到大包装单位的换算数量。 */
        BigDecimal baseToBigRate,
        /** 最低订货量。 */
        BigDecimal minimumOrder,
        /** 最低订货量单位原值。 */
        String minimumOrderUnit,
        /** 库存下限。 */
        BigDecimal inventoryLower,
        /** 库存上限。 */
        BigDecimal inventoryUpper,
        /** 安全库存。 */
        BigDecimal safetyInventory,
        /** 中包装单位订货价。 */
        BigDecimal middleOrderPrice,
        /** 大包装单位订货价。 */
        BigDecimal bigOrderPrice,
        /** 商品图片及当前生成的短时 URL。 */
        List<ProductImageView> images,
        /** 当前 SPU 下的可销售 SKU。 */
        List<SkuView> skus,
        /** 订货宝 field_1 至 field_6 扩展字段。 */
        Map<String, String> customFields,
        /** ERP 已解释计价类型和计量单位的业务价格投影。 */
        List<ProductPriceView> priceItems,
        /** ERP 已解释数量类型和计量单位的业务数量投影。 */
        List<ProductQuantityView> quantityItems) {
    public ProductView {
        images = images == null ? List.of() : List.copyOf(images);
        skus = skus == null ? List.of() : List.copyOf(skus);
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
        priceItems = priceItems == null ? List.of() : List.copyOf(priceItems);
        quantityItems = quantityItems == null ? List.of() : List.copyOf(quantityItems);
    }
}
