package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研资金收付款单实体，对应 `order_fund_document`。 */
@TableName("order_fund_document")
public class InternalFundDocumentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String documentNo;
    private String directionCode;
    private Long relatedOrderId;
    private String salesOrderNoSnapshot;
    private Long customerId;
    private String customerCodeSnapshot;
    private String customerNameSnapshot;
    private String counterpartyTypeCode;
    private String counterpartyCodeSnapshot;
    private String counterpartyNameSnapshot;
    private String handlerStaffCode;
    private String handlerStaffNameSnapshot;
    private LocalDateTime occurredTime;
    private String settlementMethodCode;
    private String businessTypeCode;
    private String documentStatusCode;
    private BigDecimal amount;
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
    public String getDocumentNo() { return documentNo; }
    public void setDocumentNo(String documentNo) { this.documentNo = documentNo; }
    public String getDirectionCode() { return directionCode; }
    public void setDirectionCode(String directionCode) { this.directionCode = directionCode; }
    public Long getRelatedOrderId() { return relatedOrderId; }
    public void setRelatedOrderId(Long relatedOrderId) { this.relatedOrderId = relatedOrderId; }
    public String getSalesOrderNoSnapshot() { return salesOrderNoSnapshot; }
    public void setSalesOrderNoSnapshot(String salesOrderNoSnapshot) { this.salesOrderNoSnapshot = salesOrderNoSnapshot; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerCodeSnapshot() { return customerCodeSnapshot; }
    public void setCustomerCodeSnapshot(String customerCodeSnapshot) { this.customerCodeSnapshot = customerCodeSnapshot; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String customerNameSnapshot) { this.customerNameSnapshot = customerNameSnapshot; }
    public String getCounterpartyTypeCode() { return counterpartyTypeCode; }
    public void setCounterpartyTypeCode(String counterpartyTypeCode) { this.counterpartyTypeCode = counterpartyTypeCode; }
    public String getCounterpartyCodeSnapshot() { return counterpartyCodeSnapshot; }
    public void setCounterpartyCodeSnapshot(String counterpartyCodeSnapshot) { this.counterpartyCodeSnapshot = counterpartyCodeSnapshot; }
    public String getCounterpartyNameSnapshot() { return counterpartyNameSnapshot; }
    public void setCounterpartyNameSnapshot(String counterpartyNameSnapshot) { this.counterpartyNameSnapshot = counterpartyNameSnapshot; }
    public String getHandlerStaffCode() { return handlerStaffCode; }
    public void setHandlerStaffCode(String handlerStaffCode) { this.handlerStaffCode = handlerStaffCode; }
    public String getHandlerStaffNameSnapshot() { return handlerStaffNameSnapshot; }
    public void setHandlerStaffNameSnapshot(String handlerStaffNameSnapshot) { this.handlerStaffNameSnapshot = handlerStaffNameSnapshot; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getSettlementMethodCode() { return settlementMethodCode; }
    public void setSettlementMethodCode(String settlementMethodCode) { this.settlementMethodCode = settlementMethodCode; }
    public String getBusinessTypeCode() { return businessTypeCode; }
    public void setBusinessTypeCode(String businessTypeCode) { this.businessTypeCode = businessTypeCode; }
    public String getDocumentStatusCode() { return documentStatusCode; }
    public void setDocumentStatusCode(String documentStatusCode) { this.documentStatusCode = documentStatusCode; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
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
