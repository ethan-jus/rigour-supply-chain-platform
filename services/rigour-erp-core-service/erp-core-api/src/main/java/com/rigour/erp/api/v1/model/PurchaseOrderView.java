package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 本地采购单列表项。 */
public record PurchaseOrderView(
        /** ERP 内部 UUID。 */ String id,
        /** 订货宝采购单主键。 */ String sourcePurchaseId,
        /** 采购单号。 */ String purchaseOrderNo,
        /** 订货宝供应商主键。 */ String supplierSourceId,
        /** 供应商编码快照。 */ String supplierCode,
        /** 供应商名称快照。 */ String supplierName,
        /** 订货宝仓库主键。 */ String warehouseSourceId,
        /** 仓库编码快照。 */ String warehouseCode,
        /** 仓库名称快照。 */ String warehouseName,
        /** 订货宝经办人主键。 */ String staffSourceId,
        /** 经办人名称。 */ String staffName,
        /** 订货宝单据状态值。 */ String sourceStatus,
        /** 订货宝单据状态名称。 */ String sourceStatusName,
        /** 订货宝付款状态值。 */ String paymentStatus,
        /** 订货宝付款状态名称。 */ String paymentStatusName,
        /** ERP 内部状态。 */ String internalStatus,
        /** 采购总金额。 */ BigDecimal totalAmount,
        /** 已付金额。 */ BigDecimal paidAmount,
        /** 商品总数量。 */ BigDecimal goodsCount,
        /** 来源下载标记。 */ Boolean downloaded,
        /** 单据备注。 */ String remark,
        /** 内部沟通内容。 */ String internalCommunication,
        /** 预计交货时间。 */ Instant deliveryAt,
        /** 订货宝单据创建时间。 */ Instant sourceCreatedAt,
        /** 订货宝单据更新时间。 */ Instant sourceUpdatedAt,
        /** 采购明细数量。 */ int lineCount,
        /** 最近来源同步时间。 */ Instant syncedAt) { }
