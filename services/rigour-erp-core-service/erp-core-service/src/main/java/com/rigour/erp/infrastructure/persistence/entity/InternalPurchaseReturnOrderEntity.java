package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研采购退货单头实体，对应 `erp_purchase_return_order`。 */
@TableName("erp_purchase_return_order")
public class InternalPurchaseReturnOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 采购退货单号，由 ERP 编码规则生成。 */
    private String purchaseReturnNo;
    /** 供应商ID。 */
    private Long supplierId;
    /** 退货出库仓库ID。 */
    private Long warehouseId;
    /** 经办员工编码，关联 IAM 员工中心员工编码。 */
    private String operatorStaffCode;
    /** 经办员工名称快照。 */
    private String operatorStaffNameSnapshot;
    /** 采购退货状态，关联 PURCHASE_RETURN_STATUS 字典项。 */
    private String statusCode;
    /** 退货总数量，由明细汇总或来源换算。 */
    private BigDecimal totalQuantity;
    /** 退货总金额。 */
    private BigDecimal totalAmount;
    /** 优惠/折让金额。 */
    private BigDecimal discountAmount;
    /** 退货发出时间。 */
    private LocalDateTime returnTime;
    /** 联系人。 */
    private String contactName;
    /** 联系电话。 */
    private String contactPhone;
    /** 联系地址。 */
    private String contactAddress;
    /** 退货原因。 */
    private String reason;
    /** 备注。 */
    private String remark;
    /** 乐观锁。 */
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPurchaseReturnNo() { return purchaseReturnNo; }
    public void setPurchaseReturnNo(String purchaseReturnNo) { this.purchaseReturnNo = purchaseReturnNo; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public String getOperatorStaffCode() { return operatorStaffCode; }
    public void setOperatorStaffCode(String operatorStaffCode) { this.operatorStaffCode = operatorStaffCode; }
    public String getOperatorStaffNameSnapshot() { return operatorStaffNameSnapshot; }
    public void setOperatorStaffNameSnapshot(String operatorStaffNameSnapshot) { this.operatorStaffNameSnapshot = operatorStaffNameSnapshot; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public BigDecimal getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(BigDecimal totalQuantity) { this.totalQuantity = totalQuantity; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public LocalDateTime getReturnTime() { return returnTime; }
    public void setReturnTime(LocalDateTime returnTime) { this.returnTime = returnTime; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactAddress() { return contactAddress; }
    public void setContactAddress(String contactAddress) { this.contactAddress = contactAddress; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
