package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 本地入库单列表项。 */
public record WarehousingReceiptView(
        /** ERP 内部 UUID。 */ String id,
        /** 订货宝入库单主键。 */ String sourceWarehousingId,
        /** 入库单号。 */ String warehousingNo,
        /** 订货宝仓库主键。 */ String warehouseSourceId,
        /** 仓库名称快照。 */ String warehouseName,
        /** 订货宝供应商主键。 */ String supplierSourceId,
        /** 供应商名称快照。 */ String supplierName,
        /** 订货宝入库类型值。 */ String typeId,
        /** 订货宝入库类型名称。 */ String typeName,
        /** 订货宝单据状态值。 */ String sourceStatus,
        /** 订货宝单据状态名称。 */ String sourceStatusName,
        /** ERP 内部状态。 */ String internalStatus,
        /** 经办人名称。 */ String staffName,
        /** 客户来源主键。 */ String clientSourceId,
        /** 账户来源主键。 */ String accountSourceId,
        /** 协作方来源主键。 */ String collaboratorSourceId,
        /** 协作方名称。 */ String collaboratorName,
        /** 物流来源主键。 */ String logisticsSourceId,
        /** 物流单号。 */ String expressNumber,
        /** 入库总金额。 */ BigDecimal totalAmount,
        /** 入库成本总额。 */ BigDecimal costAmount,
        /** 运费。 */ BigDecimal freightAmount,
        /** 入库时间。 */ Instant storageAt,
        /** 来源创建时间。 */ Instant sourceCreatedAt,
        /** 来源更新时间。 */ Instant sourceUpdatedAt,
        /** 来源备注。 */ String remark,
        /** 来源 API 标记。 */ Boolean apiFlag,
        /** 拆单类型。 */ String splitType,
        /** 入库明细数量。 */ int lineCount,
        /** 最近来源同步时间。 */ Instant syncedAt) { }
