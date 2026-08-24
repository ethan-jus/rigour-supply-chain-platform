package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研采购退货单明细实体，对应 `erp_purchase_return_order_line`。 */
@TableName("erp_purchase_return_order_line")
public class InternalPurchaseReturnOrderLineEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 采购退货单ID。 */
    private Long purchaseReturnOrderId;
    /** 行号。 */
    private Integer lineNo;
    /** 关联采购订单ID；可由来源采购单号映射到 ERP 自研采购单。 */
    private Long procurementOrderId;
    /** 关联采购订单号快照。 */
    private String procurementNoSnapshot;
    /** 商品ID。 */
    private Long productId;
    /** 商品规格ID。 */
    private Long productVariantId;
    /** 商品编码快照。 */
    private String productCodeSnapshot;
    /** 商品规格编码快照。 */
    private String variantCodeSnapshot;
    /** 商品名称快照。 */
    private String productNameSnapshot;
    /** 退货单位，关联 PRODUCT_UNIT 字典项。 */
    private String unitCode;
    /** 申请退货数量。 */
    private BigDecimal requestedQuantity;
    /** 确认退货数量。 */
    private BigDecimal returnedQuantity;
    /** 退货单价。 */
    private BigDecimal unitPrice;
    /** 确认退货单价。 */
    private BigDecimal confirmedUnitPrice;
    /** 明细金额。 */
    private BigDecimal lineAmount;
    /** 成本价快照。 */
    private BigDecimal costPrice;
    /** 备注。 */
    private String remark;
    /** 创建时间。 */
    private LocalDateTime createdTime;
    /** 更新时间。 */
    private LocalDateTime updatedTime;
    /** 删除标识：0未删除，1已删除。 */
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getPurchaseReturnOrderId() { return purchaseReturnOrderId; }
    public void setPurchaseReturnOrderId(Long purchaseReturnOrderId) { this.purchaseReturnOrderId = purchaseReturnOrderId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public Long getProcurementOrderId() { return procurementOrderId; }
    public void setProcurementOrderId(Long procurementOrderId) { this.procurementOrderId = procurementOrderId; }
    public String getProcurementNoSnapshot() { return procurementNoSnapshot; }
    public void setProcurementNoSnapshot(String procurementNoSnapshot) { this.procurementNoSnapshot = procurementNoSnapshot; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getProductVariantId() { return productVariantId; }
    public void setProductVariantId(Long productVariantId) { this.productVariantId = productVariantId; }
    public String getProductCodeSnapshot() { return productCodeSnapshot; }
    public void setProductCodeSnapshot(String productCodeSnapshot) { this.productCodeSnapshot = productCodeSnapshot; }
    public String getVariantCodeSnapshot() { return variantCodeSnapshot; }
    public void setVariantCodeSnapshot(String variantCodeSnapshot) { this.variantCodeSnapshot = variantCodeSnapshot; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String productNameSnapshot) { this.productNameSnapshot = productNameSnapshot; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public BigDecimal getRequestedQuantity() { return requestedQuantity; }
    public void setRequestedQuantity(BigDecimal requestedQuantity) { this.requestedQuantity = requestedQuantity; }
    public BigDecimal getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(BigDecimal returnedQuantity) { this.returnedQuantity = returnedQuantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getConfirmedUnitPrice() { return confirmedUnitPrice; }
    public void setConfirmedUnitPrice(BigDecimal confirmedUnitPrice) { this.confirmedUnitPrice = confirmedUnitPrice; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public void setLineAmount(BigDecimal lineAmount) { this.lineAmount = lineAmount; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
