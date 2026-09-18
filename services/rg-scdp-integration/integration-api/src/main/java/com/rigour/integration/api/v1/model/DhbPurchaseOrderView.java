package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Integration 归一化采购单头及完整明细。 */
public record DhbPurchaseOrderView(
        /** 订货宝采购单主键。 */ String sourceId,
        /** 采购单号。 */ String number,
        /** 订货宝供应商主键。 */ String supplierSourceId,
        /** 供应商编码。 */ String supplierCode,
        /** 供应商名称。 */ String supplierName,
        /** 订货宝仓库主键。 */ String warehouseSourceId,
        /** 仓库编码。 */ String warehouseCode,
        /** 仓库名称。 */ String warehouseName,
        /** 订货宝经办人主键。 */ String staffSourceId,
        /** 经办人名称。 */ String staffName,
        /** 来源单据状态值。 */ String sourceStatus,
        /** 来源单据状态名称。 */ String sourceStatusName,
        /** 来源付款状态值。 */ String paymentStatus,
        /** 来源付款状态名称。 */ String paymentStatusName,
        /** 预计交货时间。 */ Instant deliveryAt,
        /** 来源创建时间。 */ Instant sourceCreatedAt,
        /** 来源更新时间。 */ Instant sourceUpdatedAt,
        /** 采购总金额。 */ BigDecimal totalAmount,
        /** 已付金额。 */ BigDecimal paidAmount,
        /** 商品总数量。 */ BigDecimal goodsCount,
        /** 来源下载标记。 */ Boolean downloaded,
        /** 单据备注。 */ String remark,
        /** 内部沟通内容。 */ String internalCommunication,
        /** 采购单明细。 */ List<DhbPurchaseOrderLineView> lines,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) {
    public DhbPurchaseOrderView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
