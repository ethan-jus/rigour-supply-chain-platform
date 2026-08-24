package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售订单明细实体，对应 `order_sales_order_line`。 */
@TableName("order_sales_order_line")
public class InternalSalesOrderLineEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 销售订单ID。 */
    private Long orderId;
    /** 行号。 */
    private Integer lineNo;
    /** ERP 商品ID。 */
    private Long productId;
    /** ERP 商品规格ID。 */
    private Long productVariantId;
    /** 商品编码快照。 */
    private String productCodeSnapshot;
    /** SKU编码快照。 */
    private String skuCodeSnapshot;
    /** 商品名称快照。 */
    private String productNameSnapshot;
    /** 规格快照。 */
    private String specificationSnapshot;
    /** 订货单位。 */
    private String unitCode;
    /** 订货数量。 */
    private BigDecimal quantity;
    /** 成交单价。 */
    private BigDecimal unitPrice;
    /** 明细优惠比例。 */
    private BigDecimal discountRate;
    /** 明细优惠金额。 */
    private BigDecimal discountAmount;
    /** 明细应收金额。 */
    private BigDecimal lineAmount;
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
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getProductVariantId() { return productVariantId; }
    public void setProductVariantId(Long productVariantId) { this.productVariantId = productVariantId; }
    public String getProductCodeSnapshot() { return productCodeSnapshot; }
    public void setProductCodeSnapshot(String productCodeSnapshot) { this.productCodeSnapshot = productCodeSnapshot; }
    public String getSkuCodeSnapshot() { return skuCodeSnapshot; }
    public void setSkuCodeSnapshot(String skuCodeSnapshot) { this.skuCodeSnapshot = skuCodeSnapshot; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String productNameSnapshot) { this.productNameSnapshot = productNameSnapshot; }
    public String getSpecificationSnapshot() { return specificationSnapshot; }
    public void setSpecificationSnapshot(String specificationSnapshot) { this.specificationSnapshot = specificationSnapshot; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public void setDiscountRate(BigDecimal discountRate) { this.discountRate = discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public void setLineAmount(BigDecimal lineAmount) { this.lineAmount = lineAmount; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
