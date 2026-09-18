package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售退款记录实体，对应 `order_refund_record`。 */
@TableName("order_refund_record")
public class InternalSalesRefundRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String refundNo;
    private Long orderId;
    private String salesOrderNoSnapshot;
    private Long customerId;
    private String customerCodeSnapshot;
    private String customerNameSnapshot;
    private String refundStaffCode;
    private String refundStaffNameSnapshot;
    private LocalDateTime refundTime;
    private String refundMethodCode;
    private String refundStatusCode;
    private BigDecimal refundAmount;
    private String voucherKeysJson;
    private String remark;
    private Integer revision;
    private String createdBy;
    private LocalDateTime createdTime;
    private String updatedBy;
    private LocalDateTime updatedTime;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getSalesOrderNoSnapshot() { return salesOrderNoSnapshot; }
    public void setSalesOrderNoSnapshot(String salesOrderNoSnapshot) { this.salesOrderNoSnapshot = salesOrderNoSnapshot; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerCodeSnapshot() { return customerCodeSnapshot; }
    public void setCustomerCodeSnapshot(String customerCodeSnapshot) { this.customerCodeSnapshot = customerCodeSnapshot; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String customerNameSnapshot) { this.customerNameSnapshot = customerNameSnapshot; }
    public String getRefundStaffCode() { return refundStaffCode; }
    public void setRefundStaffCode(String refundStaffCode) { this.refundStaffCode = refundStaffCode; }
    public String getRefundStaffNameSnapshot() { return refundStaffNameSnapshot; }
    public void setRefundStaffNameSnapshot(String refundStaffNameSnapshot) { this.refundStaffNameSnapshot = refundStaffNameSnapshot; }
    public LocalDateTime getRefundTime() { return refundTime; }
    public void setRefundTime(LocalDateTime refundTime) { this.refundTime = refundTime; }
    public String getRefundMethodCode() { return refundMethodCode; }
    public void setRefundMethodCode(String refundMethodCode) { this.refundMethodCode = refundMethodCode; }
    public String getRefundStatusCode() { return refundStatusCode; }
    public void setRefundStatusCode(String refundStatusCode) { this.refundStatusCode = refundStatusCode; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public String getVoucherKeysJson() { return voucherKeysJson; }
    public void setVoucherKeysJson(String voucherKeysJson) { this.voucherKeysJson = voucherKeysJson; }
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
