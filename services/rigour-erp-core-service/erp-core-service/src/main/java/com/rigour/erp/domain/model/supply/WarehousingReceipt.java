package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 入库单导入模型。 */
public record WarehousingReceipt(String sourceId, String number, String warehouseSourceId,
                                 String warehouseName, String supplierSourceId, String supplierName,
                                 String typeId, String typeName, String sourceStatus,
                                 String sourceStatusName, String staffName, String clientSourceId,
                                 String accountSourceId, String collaboratorSourceId,
                                 String collaboratorName, String logisticsSourceId,
                                 String expressNumber, Instant storageAt, Instant sourceCreatedAt,
                                 Instant sourceUpdatedAt, BigDecimal freightAmount,
                                 BigDecimal totalAmount, BigDecimal costAmount, Boolean apiFlag,
                                 String splitType, String remark, List<Line> lines,
                                 List<PurchaseLink> purchaseLinks, String payloadHash) {
    public WarehousingReceipt {
        lines = lines == null ? List.of() : List.copyOf(lines);
        purchaseLinks = purchaseLinks == null ? List.of() : List.copyOf(purchaseLinks);
    }

    /** 入库商品明细。 */
    public record Line(String sourceLineId, String sourceGoodsId, String goodsCode,
                       String goodsName, String optionsId, String optionsGoodsCode,
                       String optionsSummary, BigDecimal baseQuantity, BigDecimal unitQuantity,
                       String unitCode, String unitName, BigDecimal conversionNumber,
                       BigDecimal costPrice, BigDecimal unitCostPrice, BigDecimal purchasePrice,
                       BigDecimal wholesalePrice, String allocation, String barcode,
                       String goodsModel, BigDecimal sourceRealQuantity,
                       BigDecimal sourceAvailableQuantity, String collaboratorSourceId,
                       String collaboratorName, String remark, String payloadHash) { }

    /** 入库单关联采购单。 */
    public record PurchaseLink(String sourcePurchaseId, String purchaseOrderNo) { }
}
