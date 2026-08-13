package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** ERP 本地采购退货单列表项。 */
public record PurchaseReturnView(
        /** ERP 内部 UUID。 */ String id,
        /** 订货宝采购退货单主键。 */ String sourceReturnId,
        /** 采购退货单号。 */ String purchaseReturnNo,
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
        /** ERP 内部状态。 */ String internalStatus,
        /** 退货金额。 */ BigDecimal returnAmount,
        /** 折扣金额。 */ BigDecimal discountAmount,
        /** 退货原因。 */ String reason,
        /** 订货宝单据创建时间。 */ Instant sourceCreatedAt,
        /** 退货发送时间。 */ Instant sendAt,
        /** 内部沟通内容。 */ String internalCommunication,
        /** 单据备注。 */ String remark,
        /** 来源明细数量。 */ Integer detailCount,
        /** 联系人名称。 */ String contactName,
        /** 完整联系电话。 */ String contactPhone,
        /** 完整联系地址。 */ String contactAddress,
        /** 来源城市主键路径。 */ List<String> cityIds,
        /** 来源城市名称路径。 */ List<String> cityNames,
        /** 来源设备类型。 */ String sourceDevice,
        /** 父退货单来源主键。 */ String parentReturnSourceId,
        /** 父公司来源主键。 */ String parentCompanySourceId,
        /** 来源下载标记。 */ Boolean downloaded,
        /** 退货明细数量。 */ int lineCount,
        /** 最近来源同步时间。 */ Instant syncedAt) { }
