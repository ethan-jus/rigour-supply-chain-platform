package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.util.Map;

/** Integration 归一化仓库档案。 */
public record DhbWarehouseView(
        /** 订货宝仓库主键。 */ String sourceId,
        /** 订货宝仓库 GUID。 */ String sourceGuid,
        /** 仓库编码。 */ String code,
        /** 仓库名称。 */ String name,
        /** 订货宝仓库状态。 */ String sourceStatus,
        /** 是否为订货宝默认仓。 */ Boolean defaultFlag,
        /** 仓库面积。 */ BigDecimal acreage,
        /** 脱敏联系电话。 */ String phoneMasked,
        /** 仓库地址。 */ String address,
        /** 订货宝协作方主键。 */ String collaboratorSourceId,
        /** 来源备注。 */ String remark,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) { }
