package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 入库单详情；字段方向与 Integration 的订货宝标准化入库单保持一致。 */
public record WarehousingReceiptDetailView(
        /** ERP 内部 UUID，仅用于标识本地详情记录。 */ String erpId,
        /** 订货宝入库单主键。 */ String sourceId,
        /** 入库单号。 */ String number,
        /** 订货宝仓库主键。 */ String warehouseSourceId,
        /** 仓库名称。 */ String warehouseName,
        /** 订货宝供应商主键。 */ String supplierSourceId,
        /** 供应商名称。 */ String supplierName,
        /** 入库类型值。 */ String typeId,
        /** 入库类型名称。 */ String typeName,
        /** 来源单据状态值。 */ String sourceStatus,
        /** 来源单据状态名称。 */ String sourceStatusName,
        /** 经办人名称。 */ String staffName,
        /** 客户来源主键。 */ String clientSourceId,
        /** 账户来源主键。 */ String accountSourceId,
        /** 协作方来源主键。 */ String collaboratorSourceId,
        /** 协作方名称。 */ String collaboratorName,
        /** 物流来源主键。 */ String logisticsSourceId,
        /** 快递单号。 */ String expressNumber,
        /** 入库时间。 */ Instant storageAt,
        /** 来源创建时间。 */ Instant sourceCreatedAt,
        /** 来源更新时间。 */ Instant sourceUpdatedAt,
        /** 运费。 */ BigDecimal freightAmount,
        /** 入库总金额。 */ BigDecimal totalAmount,
        /** 入库成本总额。 */ BigDecimal costAmount,
        /** 来源 API 标记。 */ Boolean apiFlag,
        /** 拆单类型。 */ String splitType,
        /** 来源备注。 */ String remark,
        /** 入库明细。 */ List<WarehousingLineView> lines,
        /** 关联采购单。 */ List<PurchaseLinkView> purchaseLinks) {
    public WarehousingReceiptDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
        purchaseLinks = purchaseLinks == null ? List.of() : List.copyOf(purchaseLinks);
    }
}
