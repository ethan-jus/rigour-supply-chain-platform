package com.rigour.erp.api.v1.model;

/** 下拉引用快照；ID 为实际主档 ID，不根据名称推测关联。 */
public record ErpCostReferenceView(
        String id, String code, String name, Long productId, String specification, String unit) {}
