package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售订单头实体，对应 `order_sales_order`。 */
@TableName("order_sales_order")
public class InternalSalesOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 销售订单号，由 Order 编码规则生成。 */
    private String orderNo;
    /** 来源系统编码；订货宝同步为 DINGHUOBAO，手工订单为空。 */
    private String sourceSystemCode;
    /** 来源平台订单号，用于展示、搜索和人工对账。 */
    private String sourceOrderNo;
    /** CRM 客户ID。 */
    private Long customerId;
    /** 客户编号快照。 */
    private String customerCodeSnapshot;
    /** 客户名称快照。 */
    private String customerNameSnapshot;
    /** 联系人快照。 */
    private String contactNameSnapshot;
    /** 联系电话快照。 */
    private String contactPhoneSnapshot;
    /** 客户归属地区。 */
    private String regionCode;
    /** 归属销售用户ID，旧字段兼容；新流程优先使用 ownerStaffCode。 */
    private String ownerSalesUserId;
    /** 归属销售名称快照。 */
    private String ownerSalesName;
    /** 归属销售人员员工编码，来自 IAM 员工中心。 */
    private String ownerStaffCode;
    /** 归属销售人员姓名快照。 */
    private String ownerStaffNameSnapshot;
    /** 销售日期。 */
    private LocalDateTime orderDate;
    /** 销售订单状态。 */
    private String orderStatusCode;
    /** 订单类型。 */
    private String orderTypeCode;
    /** 付款方式。 */
    private String paymentMethodCode;
    /** 收款状态。 */
    private String paymentStatusCode;
    /** 出库状态。 */
    private String outboundStatusCode;
    /** 总数量。 */
    private BigDecimal totalQuantity;
    /** 原价小计。 */
    private BigDecimal originalAmount;
    /** 整单优惠比例。 */
    private BigDecimal discountRate;
    /** 优惠金额。 */
    private BigDecimal discountAmount;
    /** 应收金额。 */
    private BigDecimal payableAmount;
    /** 已收金额。 */
    private BigDecimal paidAmount;
    /** 待收金额。 */
    private BigDecimal unpaidAmount;
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
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getSourceSystemCode() { return sourceSystemCode; }
    public void setSourceSystemCode(String sourceSystemCode) { this.sourceSystemCode = sourceSystemCode; }
    public String getSourceOrderNo() { return sourceOrderNo; }
    public void setSourceOrderNo(String sourceOrderNo) { this.sourceOrderNo = sourceOrderNo; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerCodeSnapshot() { return customerCodeSnapshot; }
    public void setCustomerCodeSnapshot(String customerCodeSnapshot) { this.customerCodeSnapshot = customerCodeSnapshot; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String customerNameSnapshot) { this.customerNameSnapshot = customerNameSnapshot; }
    public String getContactNameSnapshot() { return contactNameSnapshot; }
    public void setContactNameSnapshot(String contactNameSnapshot) { this.contactNameSnapshot = contactNameSnapshot; }
    public String getContactPhoneSnapshot() { return contactPhoneSnapshot; }
    public void setContactPhoneSnapshot(String contactPhoneSnapshot) { this.contactPhoneSnapshot = contactPhoneSnapshot; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getOwnerSalesUserId() { return ownerSalesUserId; }
    public void setOwnerSalesUserId(String ownerSalesUserId) { this.ownerSalesUserId = ownerSalesUserId; }
    public String getOwnerSalesName() { return ownerSalesName; }
    public void setOwnerSalesName(String ownerSalesName) { this.ownerSalesName = ownerSalesName; }
    public String getOwnerStaffCode() { return ownerStaffCode; }
    public void setOwnerStaffCode(String ownerStaffCode) { this.ownerStaffCode = ownerStaffCode; }
    public String getOwnerStaffNameSnapshot() { return ownerStaffNameSnapshot; }
    public void setOwnerStaffNameSnapshot(String ownerStaffNameSnapshot) { this.ownerStaffNameSnapshot = ownerStaffNameSnapshot; }
    public LocalDateTime getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDateTime orderDate) { this.orderDate = orderDate; }
    public String getOrderStatusCode() { return orderStatusCode; }
    public void setOrderStatusCode(String orderStatusCode) { this.orderStatusCode = orderStatusCode; }
    public String getOrderTypeCode() { return orderTypeCode; }
    public void setOrderTypeCode(String orderTypeCode) { this.orderTypeCode = orderTypeCode; }
    public String getPaymentMethodCode() { return paymentMethodCode; }
    public void setPaymentMethodCode(String paymentMethodCode) { this.paymentMethodCode = paymentMethodCode; }
    public String getPaymentStatusCode() { return paymentStatusCode; }
    public void setPaymentStatusCode(String paymentStatusCode) { this.paymentStatusCode = paymentStatusCode; }
    public String getOutboundStatusCode() { return outboundStatusCode; }
    public void setOutboundStatusCode(String outboundStatusCode) { this.outboundStatusCode = outboundStatusCode; }
    public BigDecimal getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(BigDecimal totalQuantity) { this.totalQuantity = totalQuantity; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public void setDiscountRate(BigDecimal discountRate) { this.discountRate = discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getUnpaidAmount() { return unpaidAmount; }
    public void setUnpaidAmount(BigDecimal unpaidAmount) { this.unpaidAmount = unpaidAmount; }
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
