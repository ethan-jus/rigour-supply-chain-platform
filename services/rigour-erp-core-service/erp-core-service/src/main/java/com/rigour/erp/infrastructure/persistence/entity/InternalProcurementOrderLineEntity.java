package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研采购订单明细实体，对应 `erp_procurement_order_line`。 */
@TableName("erp_procurement_order_line")
public class InternalProcurementOrderLineEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 采购订单ID。 */
    private Long procurementOrderId;
    /** 行号。 */
    private Integer lineNo;
    /** 商品ID。 */
    private Long productId;
    /** 商品规格ID。 */
    private Long productVariantId;
    /** 商品编码快照。 */
    private String productCodeSnapshot;
    /** 规格编码快照。 */
    private String variantCodeSnapshot;
    /** 商品名称快照。 */
    private String productNameSnapshot;
    /** 采购单位。 */
    private String unitCode;
    /** 采购数量。 */
    private BigDecimal quantity;
    /** 采购单价。 */
    private BigDecimal unitPrice;
    /** 采购金额。 */
    private BigDecimal lineAmount;
    /** 已入库数量。 */
    private BigDecimal receivedQuantity;
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
    public Long getProcurementOrderId() { return procurementOrderId; }
    public void setProcurementOrderId(Long procurementOrderId) { this.procurementOrderId = procurementOrderId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
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
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public void setLineAmount(BigDecimal lineAmount) { this.lineAmount = lineAmount; }
    public BigDecimal getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(BigDecimal receivedQuantity) { this.receivedQuantity = receivedQuantity; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
