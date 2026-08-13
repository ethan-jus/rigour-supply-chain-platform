package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 本地仓库列表项。 */
public record WarehouseView(
        /** ERP 内部 UUID。 */ String id,
        /** 订货宝仓库主键。 */ String sourceWarehouseId,
        /** 订货宝仓库 GUID。 */ String sourceWarehouseGuid,
        /** 仓库编码。 */ String warehouseCode,
        /** 仓库名称。 */ String name,
        /** 订货宝仓库状态展示值：T=正常，F=停用。 */ String sourceStatus,
        /** 订货宝仓库状态原值。 */ String sourceStatusCode,
        /** 是否为订货宝默认仓。 */ boolean defaultFlag,
        /** 仓库面积。 */ BigDecimal acreage,
        /** 完整联系电话。 */ String phone,
        /** 仓库地址。 */ String address,
        /** 订货宝协作方主键。 */ String collaboratorSourceId,
        /** 仓库备注。 */ String remark,
        /** ERP 内部状态。 */ String internalStatus,
        /** 数据主控状态。 */ String ownershipState,
        /** 最近来源同步时间。 */ Instant syncedAt) { }
