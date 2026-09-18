package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研商品标签视图；商品页只引用 tagCode，标签配置在本接口维护。 */
public record InternalProductTagView(
        Long id,
        String tagCode,
        String tagName,
        String tagTypeCode,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
