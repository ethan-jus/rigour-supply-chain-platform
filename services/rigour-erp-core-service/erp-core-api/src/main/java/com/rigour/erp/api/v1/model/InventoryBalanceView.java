package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** ERP 本地库存余额快照列表项。 */
public record InventoryBalanceView(
        /** ERP 内部 UUID。 */ String id,
        /** 订货宝商品 GUID。 */ String goodsGuid,
        /** 仓库编码快照。 */ String warehouseCode,
        /** 仓库名称快照。 */ String warehouseName,
        /** 订货宝仓库 GUID。 */ String warehouseGuid,
        /** 商品编码快照。 */ String goodsCode,
        /** 商品名称快照。 */ String goodsName,
        /** 第一规格 GUID。 */ String firstOptionGuid,
        /** 第一规格编码。 */ String firstOptionCode,
        /** 第一规格名称。 */ String firstOptionName,
        /** 第二规格 GUID。 */ String secondOptionGuid,
        /** 第二规格编码。 */ String secondOptionCode,
        /** 第二规格名称。 */ String secondOptionName,
        /** 规格组合展示文本。 */ String optionSummary,
        /** 订货宝实际库存。 */ BigDecimal realQuantity,
        /** 订货宝可用库存。 */ BigDecimal availableQuantity,
        /** ERP 预占库存，一期为 0。 */ BigDecimal reservedQuantity,
        /** ERP 在途库存，一期为 0。 */ BigDecimal inTransitQuantity,
        /** 库存计算来源。 */ String calculationOrigin,
        /** 最近来源同步时间。 */ Instant syncedAt) { }
