package com.rigour.merchant.api.v1.model;

/**
 * CRM 自研客户保存命令。
 *
 * <p>客户编号由后端生成，调用方不得传入；修改和删除通过 revision 做乐观锁保护。</p>
 */
public record InternalCustomerCommand(
        String customerName,
        String contactName,
        String contactPhone,
        String customerTypeCode,
        String regionCode,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerStaffCode,
        String ownerStaffNameSnapshot,
        String settlementTypeCode,
        String address,
        String statusCode,
        String remark,
        Integer revision) {
    public InternalCustomerCommand(String customerName, String contactName,
                                   String contactPhone, String regionCode,
                                   String ownerSalesUserId, String ownerSalesName,
                                   String settlementTypeCode, String address,
                                   String statusCode, String remark, Integer revision) {
        this(customerName, contactName, contactPhone, null, regionCode, ownerSalesUserId,
                ownerSalesName, null, null, settlementTypeCode, address, statusCode,
                remark, revision);
    }
}
