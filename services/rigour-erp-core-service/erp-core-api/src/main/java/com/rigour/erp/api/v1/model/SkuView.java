package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP SKU 列表投影；SKU 是一组规格值组合的可销售单元，不等同于规格字典。 */
public record SkuView(
        /** ERP SKU UUID。 */
        String id,
        /** 订货宝多规格项来源 ID，仅用于追溯和幂等绑定。 */
        String sourceSkuId,
        /** ERP 租户内唯一的 SKU 编码。 */
        String skuCode,
        /** 所属 ERP SPU 编码。 */
        String spuCode,
        /** 所属商品名称。 */
        String productName,
        /** SKU 条码。 */
        String barcode,
        /** SKU 销售或库存计量单位。 */
        String unit,
        /** 来源规格组合名称，例如颜色和尺寸组合。 */
        String specificationSummary,
        /** 所属来源商品的订货宝上下架状态原值。 */
        String sourcePutaway,
        /** ERP 内部 SKU 状态。 */
        String internalStatus,
        /** SKU 数据主权状态。 */
        String ownershipState,
        /** 最近一次成功处理该来源 SKU 的时间。 */
        Instant syncedAt,
        /** 订货宝 options_id。 */
        String optionsId,
        /** 第一规格值来源 ID。 */
        String firstSpecificationValueSourceId,
        /** 第二规格值来源 ID。 */
        String secondSpecificationValueSourceId,
        /** 中包装条码。 */
        String middleBarcode,
        /** 大包装条码。 */
        String bigBarcode,
        /** 基础单位订货价。 */
        BigDecimal orderPrice,
        /** 基础单位市场价。 */
        BigDecimal marketPrice,
        /** 基础单位采购价。 */
        BigDecimal purchasePrice,
        /** 中包装订货价。 */
        BigDecimal middleOrderPrice,
        /** 大包装订货价。 */
        BigDecimal bigOrderPrice,
        /** ERP 已解释计价类型和计量单位的业务价格投影。 */
        List<ProductPriceView> priceItems) {
    public SkuView {
        priceItems = priceItems == null ? List.of() : List.copyOf(priceItems);
    }
}
