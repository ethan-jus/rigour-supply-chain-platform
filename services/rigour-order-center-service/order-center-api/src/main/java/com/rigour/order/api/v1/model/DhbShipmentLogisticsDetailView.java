package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** getWaitShips出库/发货物流主信息与已出库、待出库明细。 */
public record DhbShipmentLogisticsDetailView(
        /** 物流主信息。 */ DhbShipmentLogisticsView logistics,
        /** 物流明细。 */ List<Line> lines) {
    public DhbShipmentLogisticsDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** getWaitShips.shipped或wait_stock明细统一视图。 */
    public record Line(
            /** SHIPPED已出库/已发货，WAIT_STOCK待出库。 */ String lineType,
            /** 来源明细ID。 */ String sourceLineId,
            /** 关联订单明细ID。 */ String orderLineId,
            /** 商品ID。 */ String productId,
            /** 规格商品编码。 */ String skuNo,
            /** 商品编码。 */ String productCode,
            /** 商品名称。 */ String productName,
            /** 商品规格。 */ String specification,
            /** 商品单位。 */ String unit,
            /** 大单位。 */ String containerUnit,
            /** 换算关系。 */ BigDecimal conversionNumber,
            /** SHIPPED出库数量。 */ BigDecimal quantity,
            /** WAIT_STOCK订购数量。 */ BigDecimal orderedQuantity,
            /** WAIT_STOCK已出库数量。 */ BigDecimal stockedQuantity,
            /** WAIT_STOCK实际库存。 */ BigDecimal realStock,
            /** WAIT_STOCK待出库数量。 */ BigDecimal waitQuantity,
            /** 仓库编号。 */ String warehouseNo,
            /** 仓库名称。 */ String warehouseName,
            /** 明细备注。 */ String remark) {
    }
}
