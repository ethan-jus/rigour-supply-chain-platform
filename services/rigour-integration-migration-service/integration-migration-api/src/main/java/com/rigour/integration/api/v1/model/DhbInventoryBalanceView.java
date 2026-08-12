package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.util.Map;

/** Integration 按仓库、商品和规格组合展开的库存余额。 */
public record DhbInventoryBalanceView(
        /** 订货宝商品 GUID。 */ String goodsGuid,
        /** 商品编码。 */ String goodsCode,
        /** 商品名称。 */ String goodsName,
        /** 订货宝仓库 GUID。 */ String warehouseGuid,
        /** 仓库编码。 */ String warehouseCode,
        /** 仓库名称。 */ String warehouseName,
        /** 第一规格 GUID。 */ String firstOptionGuid,
        /** 第一规格编码。 */ String firstOptionCode,
        /** 第一规格名称。 */ String firstOptionName,
        /** 第二规格 GUID。 */ String secondOptionGuid,
        /** 第二规格编码。 */ String secondOptionCode,
        /** 第二规格名称。 */ String secondOptionName,
        /** 来源可用库存。 */ BigDecimal availableQuantity,
        /** 来源实际库存。 */ BigDecimal realQuantity,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) { }
