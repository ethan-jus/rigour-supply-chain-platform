package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 采购退货导入模型。 */
public record PurchaseReturn(String sourceId, String number, String supplierSourceId,
                             String supplierCode, String supplierName, String warehouseSourceId,
                             String warehouseCode, String warehouseName, String staffSourceId,
                             String staffName, String sourceStatus, String sourceStatusName,
                             BigDecimal returnAmount, BigDecimal discountAmount, String reason,
                             Instant sourceCreatedAt, Instant sendAt, String internalCommunication,
                             String remark, Integer detailCount, String contactName,
                             String contactPhoneMasked, String contactAddressMasked,
                             List<String> cityIds, List<String> cityNames, String sourceDevice,
                             String parentReturnSourceId, String parentCompanySourceId,
                             Boolean downloaded, List<Line> lines, String payloadHash) {
    public PurchaseReturn {
        cityIds = cityIds == null ? List.of() : List.copyOf(cityIds);
        cityNames = cityNames == null ? List.of() : List.copyOf(cityNames);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 采购退货商品明细。 */
    public record Line(String sourceLineId, String sourceGoodsId, String goodsCode,
                       String goodsName, String optionsId, String optionsGoodsCode,
                       String optionsSummary, BigDecimal requestedQuantity,
                       BigDecimal confirmedQuantity, BigDecimal returnPrice,
                       BigDecimal confirmedPrice, String unitCode, String unitName,
                       BigDecimal unitQuantity, BigDecimal confirmedUnitQuantity,
                       BigDecimal conversionNumber, BigDecimal amount, BigDecimal costPrice,
                       String purchaseOrderNo, String categoryName, String brandName,
                       String remark, String payloadHash) { }
}
