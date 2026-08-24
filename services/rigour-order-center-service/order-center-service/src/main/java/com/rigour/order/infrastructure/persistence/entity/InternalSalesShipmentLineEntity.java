package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售发货单明细实体，对应 `order_sales_shipment_line`。 */
@TableName("order_sales_shipment_line")
public class InternalSalesShipmentLineEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long shipmentId;
    private Long salesOrderLineId;
    private Integer lineNo;
    private Long productId;
    private Long productVariantId;
    private String productCodeSnapshot;
    private String skuCodeSnapshot;
    private String productNameSnapshot;
    private String specificationSnapshot;
    private String unitCode;
    private BigDecimal shippedQuantity;
    private String remark;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getShipmentId() { return shipmentId; }
    public void setShipmentId(Long shipmentId) { this.shipmentId = shipmentId; }
    public Long getSalesOrderLineId() { return salesOrderLineId; }
    public void setSalesOrderLineId(Long salesOrderLineId) { this.salesOrderLineId = salesOrderLineId; }
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
    public BigDecimal getShippedQuantity() { return shippedQuantity; }
    public void setShippedQuantity(BigDecimal shippedQuantity) { this.shippedQuantity = shippedQuantity; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
