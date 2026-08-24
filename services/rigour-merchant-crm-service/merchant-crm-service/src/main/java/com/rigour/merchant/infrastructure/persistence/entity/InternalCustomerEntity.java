package com.rigour.merchant.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** CRM 自研客户实体，对应新业务主表 `crm_customer`。 */
@TableName("crm_customer")
public class InternalCustomerEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 客户编号，由 CRM 编码规则生成。 */
    private String customerCode;
    /** 客户对应 CRM 往来主体 ID，用于同步来源和自研客户主档关联。 */
    private byte[] partyId;
    /** 客户名称，即业务门店名称。 */
    private String customerName;
    /** 联系人。 */
    private String contactName;
    /** 联系电话。 */
    private String contactPhone;
    /** 客户类型编码，关联 CRM 客户类型主数据。 */
    private String customerTypeCode;
    /** 客户归属地区，关联 REGION 字典项。 */
    private String regionCode;
    /** 归属销售用户ID，旧字段兼容；新流程优先使用 ownerStaffCode。 */
    private String ownerSalesUserId;
    /** 归属销售名称快照，旧字段兼容；新流程优先使用 ownerStaffNameSnapshot。 */
    private String ownerSalesName;
    /** 归属销售人员员工编码，来自 IAM 员工中心。 */
    private String ownerStaffCode;
    /** 归属销售人员名称快照。 */
    private String ownerStaffNameSnapshot;
    /** 客户结算类型，关联 CUSTOMER_SETTLEMENT_TYPE 字典项。 */
    private String settlementTypeCode;
    /** 客户地址。 */
    private String address;
    /** 客户状态，关联 CUSTOMER_STATUS 字典项。 */
    private String statusCode;
    /** 备注。 */
    private String remark;
    /** 乐观锁版本。 */
    private Integer revision;
    /** 创建人。 */
    private String createdBy;
    /** 创建时间。 */
    private LocalDateTime createdTime;
    /** 更新人。 */
    private String updatedBy;
    /** 更新时间。 */
    private LocalDateTime updatedTime;
    /** 删除标识：0未删除，1已删除。 */
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public void setCustomerCode(String customerCode) {
        this.customerCode = customerCode;
    }

    public byte[] getPartyId() {
        return partyId;
    }

    public void setPartyId(byte[] partyId) {
        this.partyId = partyId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getCustomerTypeCode() {
        return customerTypeCode;
    }

    public void setCustomerTypeCode(String customerTypeCode) {
        this.customerTypeCode = customerTypeCode;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public void setRegionCode(String regionCode) {
        this.regionCode = regionCode;
    }

    public String getOwnerSalesUserId() {
        return ownerSalesUserId;
    }

    public void setOwnerSalesUserId(String ownerSalesUserId) {
        this.ownerSalesUserId = ownerSalesUserId;
    }

    public String getOwnerSalesName() {
        return ownerSalesName;
    }

    public void setOwnerSalesName(String ownerSalesName) {
        this.ownerSalesName = ownerSalesName;
    }

    public String getOwnerStaffCode() {
        return ownerStaffCode;
    }

    public void setOwnerStaffCode(String ownerStaffCode) {
        this.ownerStaffCode = ownerStaffCode;
    }

    public String getOwnerStaffNameSnapshot() {
        return ownerStaffNameSnapshot;
    }

    public void setOwnerStaffNameSnapshot(String ownerStaffNameSnapshot) {
        this.ownerStaffNameSnapshot = ownerStaffNameSnapshot;
    }

    public String getSettlementTypeCode() {
        return settlementTypeCode;
    }

    public void setSettlementTypeCode(String settlementTypeCode) {
        this.settlementTypeCode = settlementTypeCode;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
