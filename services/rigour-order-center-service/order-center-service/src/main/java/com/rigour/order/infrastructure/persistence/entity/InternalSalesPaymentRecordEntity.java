package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售回款记录实体，对应 `order_payment_record`。 */
@TableName("order_payment_record")
public class InternalSalesPaymentRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String paymentNo;
    private String connectorId;
    private String sourceSystemCode;
    private String sourceDocumentNo;
    private Long orderId;
    private String salesOrderNoSnapshot;
    private Long customerId;
    private String customerCodeSnapshot;
    private String customerNameSnapshot;
    private String collectorUserId;
    private String collectorStaffCode;
    private String collectorNameSnapshot;
    private LocalDateTime paymentTime;
    private String paymentMethodCode;
    private BigDecimal paidAmount;
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
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }
    public String getSourceSystemCode() { return sourceSystemCode; }
    public void setSourceSystemCode(String sourceSystemCode) { this.sourceSystemCode = sourceSystemCode; }
    public String getSourceDocumentNo() { return sourceDocumentNo; }
    public void setSourceDocumentNo(String sourceDocumentNo) { this.sourceDocumentNo = sourceDocumentNo; }
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
    public String getCollectorUserId() { return collectorUserId; }
    public void setCollectorUserId(String collectorUserId) { this.collectorUserId = collectorUserId; }
    public String getCollectorStaffCode() { return collectorStaffCode; }
    public void setCollectorStaffCode(String collectorStaffCode) { this.collectorStaffCode = collectorStaffCode; }
    public String getCollectorNameSnapshot() { return collectorNameSnapshot; }
    public void setCollectorNameSnapshot(String collectorNameSnapshot) { this.collectorNameSnapshot = collectorNameSnapshot; }
    public LocalDateTime getPaymentTime() { return paymentTime; }
    public void setPaymentTime(LocalDateTime paymentTime) { this.paymentTime = paymentTime; }
    public String getPaymentMethodCode() { return paymentMethodCode; }
    public void setPaymentMethodCode(String paymentMethodCode) { this.paymentMethodCode = paymentMethodCode; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
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
