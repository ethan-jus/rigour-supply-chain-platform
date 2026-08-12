package com.rigour.erp.api.v1.model;

/** ERP 规格值列表投影。 */
public record SpecificationValueView(
        /** ERP 规格值 UUID。 */ String id,
        /** 订货宝规格值来源 ID。 */ String sourceSpecificationValueId,
        /** 订货宝父级规格值来源 ID。 */ String sourceParentId,
        /** 规格值编码。 */ String valueCode,
        /** 规格值名称。 */ String valueName,
        /** 展示顺序。 */ Integer sortOrder,
        /** ERP 规格值启用状态。 */ String status,
        /** 数据主权状态。 */ String ownershipState) { }
