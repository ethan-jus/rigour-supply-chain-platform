package com.rigour.erp.domain.model.product;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Integration 归一化后的单个来源商品及其 SKU 集合。 */
public record Product(
        /** 订货宝商品唯一标识，用于建立来源幂等绑定。 */
        String sourceId,
        /** 订货宝商品编码；缺失或冲突时 ERP 生成稳定替代编码。 */
        String code,
        /** 来源商品名称。 */
        String name,
        /** 订货宝上下架状态原值。 */
        String putaway,
        /** 来源生命周期：NORMAL、INACTIVE、SOURCE_DELETED 或 UNKNOWN。 */
        String sourceLifecycle,
        /** 来源商品默认条码。 */
        String barcode,
        /** 来源商品计量单位。 */
        String unit,
        /** 订货宝分类来源 ID，用于解析 ERP 分类绑定。 */
        String categorySourceId,
        /** 订货宝品牌来源 ID，用于解析 ERP 品牌绑定。 */
        String brandSourceId,
        /** 商品型号。 */
        String model,
        /** 商品副标题。 */
        String subtitle,
        /** 商品关键词。 */
        String keywords,
        /** 商品存放/货位信息。 */
        String allocation,
        /** COS 私桶中的主图对象 key。 */
        String mainImageKey,
        /** 订货宝规格维度来源 ID 串。 */
        String multiId,
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
        /** 基础单位到中包装单位换算率。 */
        BigDecimal baseToMiddleRate,
        /** 基础单位到大包装单位换算率。 */
        BigDecimal baseToBigRate,
        /** 最小订货量。 */
        BigDecimal minimumOrder,
        /** 最小订货量单位。 */
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
        /** 订货宝商品图片，ERP 只保存 COS key。 */
        List<ProductImage> images,
        /** field_1 至 field_6 等未建模扩展字段。 */
        Map<String, String> customFields,
        /** getGoodsList.multi 返回的可销售 SKU 组合。 */
        List<Sku> skus,
        /** 订货宝完整商品来源字段，包含尚未标准化的扩展字段。 */
        Map<String, Object> sourceFields,
        /** 归一化来源字段 SHA-256，用于幂等和变更检测。 */
        String payloadHash) {

    public Product(String sourceId, String code, String name, String putaway, String barcode,
                   String unit, String categorySourceId, String brandSourceId,
                   List<Sku> skus, String payloadHash) {
        this(sourceId, code, name, putaway, "UNKNOWN", barcode, unit, categorySourceId, brandSourceId,
                skus, Map.of(), payloadHash);
    }

    public Product(String sourceId, String code, String name, String putaway, String barcode,
                   String unit, String categorySourceId, String brandSourceId,
                   List<Sku> skus, Map<String, Object> sourceFields, String payloadHash) {
        this(sourceId, code, name, putaway, "UNKNOWN", barcode, unit, categorySourceId, brandSourceId,
                skus, sourceFields, payloadHash);
    }

    public Product(String sourceId, String code, String name, String putaway, String sourceLifecycle,
                   String barcode, String unit, String categorySourceId, String brandSourceId,
                   List<Sku> skus, Map<String, Object> sourceFields, String payloadHash) {
        this(sourceId, code, name, putaway, sourceLifecycle, barcode, unit, categorySourceId, brandSourceId,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                List.<ProductImage>of(), Map.<String, String>of(), skus, sourceFields, payloadHash);
    }

    public Product {
        sourceLifecycle = sourceLifecycle == null || sourceLifecycle.isBlank()
                ? "UNKNOWN" : sourceLifecycle.strip();
        images = images == null ? List.of() : List.copyOf(images);
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
        skus = skus == null ? List.of() : List.copyOf(skus);
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
