package com.rigour.erp.api.v1.model;

/** ERP 供应商档案保存命令；供应商编码由后端统一生成。 */
public record InternalSupplierProfileCommand(
        String supplierName,
        String contactName,
        String contactPhone,
        String address,
        String bankName,
        String bankAccountNo,
        String statusCode,
        String remark,
        Integer revision) {
}
