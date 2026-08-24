package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 统一出库单明细实体，对应 `erp_stock_out_order_line`。 */
@TableName("erp_stock_out_order_line")
public class InternalStockOutOrderLineEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 出库单ID。 */
    private Long stockOutOrderId;
    /** 行号。 */
    private Integer lineNo;
    /** 销售订单明细ID；销售出库时用于追溯来源。 */
    private Long salesOrderLineId;
    /** 调拨单明细ID；调拨出库时用于追溯来源。 */
    private Long transferOrderLineId;
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
    /** 出库单位。 */
    private String unitCode;
    /** 出库数量。 */
    private BigDecimal quantity;
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
    public Long getStockOutOrderId() { return stockOutOrderId; }
    public void setStockOutOrderId(Long stockOutOrderId) { this.stockOutOrderId = stockOutOrderId; }
    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }
    public Long getSalesOrderLineId() { return salesOrderLineId; }
    public void setSalesOrderLineId(Long salesOrderLineId) { this.salesOrderLineId = salesOrderLineId; }
    public Long getTransferOrderLineId() { return transferOrderLineId; }
    public void setTransferOrderLineId(Long transferOrderLineId) { this.transferOrderLineId = transferOrderLineId; }
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
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
