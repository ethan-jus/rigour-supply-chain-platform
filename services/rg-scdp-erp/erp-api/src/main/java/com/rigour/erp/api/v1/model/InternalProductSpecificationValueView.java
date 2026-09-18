package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研商品规格值视图；用于商品规格详情和编辑。 */
public record InternalProductSpecificationValueView(
        Long id,
        String valueCode,
        String valueName,
        Integer ordinal,
        String statusCode,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
