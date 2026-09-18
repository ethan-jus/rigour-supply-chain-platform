package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP 自研商品规格保存命令；一次维护规格主信息及其子规格值。 */
public record InternalProductSpecificationCommand(
        String specificationCode,
        String specificationName,
        String statusCode,
        List<InternalProductSpecificationValueCommand> values,
        Integer revision) {
    public InternalProductSpecificationCommand {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
