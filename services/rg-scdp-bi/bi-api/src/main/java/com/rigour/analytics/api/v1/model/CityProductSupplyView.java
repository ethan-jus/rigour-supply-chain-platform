package com.rigour.analytics.api.v1.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 独立仓库库存与全仓采购快照，不将销售城市推断为供货仓库。 */
public record CityProductSupplyView(Instant generatedAt, Instant from, Instant to,
        SourceStatus inventoryStatus, SourceStatus operationStatus,
        List<Stock> stocks, List<Operation> operations, boolean truncated) {
    /** FRESH只表明最近24小时成功同步，不表示源系统数据已完成业务对账。 */
    public record SourceStatus(String status, Instant lastSuccessAt) { }
    public record Stock(String warehouseId, String warehouseName, String warehouseRegionCode,
            String productId, String productName, String skuId, String specification, String unitCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lockedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inTransitQuantity, Instant syncedAt) { }
    /** 采购流转事实当前没有仓库维度；数量是订购量，不是入库量或已付金额。 */
    public record Operation(String productId, String skuId, String unitCode, String month,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal procurementQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shippedQuantity, Instant syncedAt) { }
}
