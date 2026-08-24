package com.rigour.erp.api.v1.model;

/** ERP 自研商品规格值保存命令；规格值属于某一个商品规格。 */
public record InternalProductSpecificationValueCommand(
        Long id,
        String valueCode,
        String valueName,
        Integer ordinal,
        String statusCode) {
}
