package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ERP 采购单导入模型；来源状态与未来 ERP 内部状态分离。 */
public record PurchaseOrder(String sourceId, String number, String supplierSourceId,
                            String supplierCode, String supplierName, String warehouseSourceId,
                            String warehouseCode, String warehouseName, String staffSourceId,
                            String staffName, String sourceStatus, String sourceStatusName,
                            String paymentStatus, String paymentStatusName, Instant deliveryAt,
                            Instant sourceCreatedAt, Instant sourceUpdatedAt,
                            BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal goodsCount,
                            Boolean downloaded, String remark, String internalCommunication,
                            List<Line> lines, Map<String, Object> sourceFields, String payloadHash) {
    public PurchaseOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
        sourceFields = immutable(sourceFields);
    }

    /** 采购单商品明细。 */
    public record Line(String sourceLineId, String sourceGoodsId, String sourceGoodsGuid,
                       String goodsCode, String goodsName, String optionsId,
                       String optionsGoodsCode, String optionsSummary, BigDecimal baseQuantity,
                       BigDecimal unitPrice, String unitCode, String unitName,
                       BigDecimal unitQuantity, BigDecimal warehousedQuantity,
                       BigDecimal returnedQuantity, String remark,
                       Map<String, Object> sourceFields, String payloadHash) {
        public Line { sourceFields = immutable(sourceFields); }
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
