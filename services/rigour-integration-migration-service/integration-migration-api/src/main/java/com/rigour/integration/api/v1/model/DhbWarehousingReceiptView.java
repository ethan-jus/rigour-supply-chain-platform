package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Integration 归一化入库单头、明细及关联采购单。 */
public record DhbWarehousingReceiptView(
        /** 订货宝入库单主键。 */ String sourceId,
        /** 入库单号。 */ String number,
        /** 订货宝仓库主键。 */ String warehouseSourceId,
        /** 仓库名称。 */ String warehouseName,
        /** 订货宝供应商主键。 */ String supplierSourceId,
        /** 供应商名称。 */ String supplierName,
        /** 来源入库类型值。 */ String typeId,
        /** 来源入库类型名称。 */ String typeName,
        /** 来源单据状态值。 */ String sourceStatus,
        /** 来源单据状态名称。 */ String sourceStatusName,
        /** 经办人名称。 */ String staffName,
        /** 订货宝客户主键。 */ String clientSourceId,
        /** 订货宝账户主键。 */ String accountSourceId,
        /** 订货宝协作方主键。 */ String collaboratorSourceId,
        /** 协作方名称。 */ String collaboratorName,
        /** 订货宝物流主键。 */ String logisticsSourceId,
        /** 快递单号。 */ String expressNumber,
        /** 入库时间。 */ Instant storageAt,
        /** 来源创建时间。 */ Instant sourceCreatedAt,
        /** 来源更新时间。 */ Instant sourceUpdatedAt,
        /** 运费金额。 */ BigDecimal freightAmount,
        /** 入库总金额。 */ BigDecimal totalAmount,
        /** 入库成本总额。 */ BigDecimal costAmount,
        /** 来源 API 标记。 */ Boolean apiFlag,
        /** 拆单类型。 */ String splitType,
        /** 单据备注。 */ String remark,
        /** 入库明细。 */ List<DhbWarehousingLineView> lines,
        /** 关联采购单。 */ List<DhbPurchaseLinkView> purchaseLinks,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) {
    public DhbWarehousingReceiptView {
        lines = lines == null ? List.of() : List.copyOf(lines);
        purchaseLinks = purchaseLinks == null ? List.of() : List.copyOf(purchaseLinks);
    }
}
