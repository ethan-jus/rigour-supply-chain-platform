package com.rigour.erp.api.v1.model;

import java.time.Instant;

/** ERP 自研供应商档案视图；不暴露订货宝来源字段。 */
public record InternalSupplierProfileView(
        Long id,
        String supplierCode,
        String supplierName,
        String contactName,
        String contactPhone,
        String address,
        String bankName,
        String bankAccountNo,
        String statusCode,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
