package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

/** ERP 自研商品规格视图；一个规格下包含多个子规格值。 */
public record InternalProductSpecificationView(
        Long id,
        String specificationCode,
        String specificationName,
        String statusCode,
        Integer valueCount,
        List<InternalProductSpecificationValueView> values,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
    public InternalProductSpecificationView {
        values = values == null ? List.of() : List.copyOf(values);
        valueCount = valueCount == null ? values.size() : valueCount;
    }
}
