package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ERP 采购退货单详情；字段方向与 Integration 的订货宝标准化退货单保持一致。 */
public record PurchaseReturnDetailView(
        /** ERP 内部 UUID，仅用于标识本地详情记录。 */ String erpId,
        /** 订货宝采购退货单主键。 */ String sourceId,
        /** 采购退货单号。 */ String number,
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
        /** 退货金额。 */ BigDecimal returnAmount,
        /** 折扣金额。 */ BigDecimal discountAmount,
        /** 退货原因。 */ String reason,
        /** 来源创建时间。 */ Instant sourceCreatedAt,
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
        /** 订货宝单据原始字段，仅详情查询返回。 */ Map<String, Object> sourceFields,
        /** 采购退货明细。 */ List<PurchaseReturnLineView> lines) {
    public PurchaseReturnDetailView {
        cityIds = cityIds == null ? List.of() : List.copyOf(cityIds);
        cityNames = cityNames == null ? List.of() : List.copyOf(cityNames);
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
