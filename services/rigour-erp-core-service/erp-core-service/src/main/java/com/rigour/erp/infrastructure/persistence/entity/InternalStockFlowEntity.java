package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 库存流水实体，对应 `erp_stock_flow`。 */
@TableName("erp_stock_flow")
public class InternalStockFlowEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 库存流水号，由 ERP 编码规则生成。 */
    private String flowNo;
    /** 仓库ID。 */
    private Long warehouseId;
    /** 商品ID。 */
    private Long productId;
    /** 商品规格ID。 */
    private Long productVariantId;
    /** 业务类型：PURCHASE_IN/SALES_OUT/TRANSFER_OUT/TRANSFER_IN/ADJUST。 */
    private String businessTypeCode;
    /** 业务单据ID。 */
    private Long businessOrderId;
    /** 业务单据号。 */
    private String businessOrderNo;
    /** 库存变动数量，入库为正，出库为负。 */
    private BigDecimal quantityDelta;
    /** 变动前可用库存。 */
    private BigDecimal beforeQuantity;
    /** 变动后可用库存。 */
    private BigDecimal afterQuantity;
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
    public String getFlowNo() { return flowNo; }
    public void setFlowNo(String flowNo) { this.flowNo = flowNo; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getProductVariantId() { return productVariantId; }
    public void setProductVariantId(Long productVariantId) { this.productVariantId = productVariantId; }
    public String getBusinessTypeCode() { return businessTypeCode; }
    public void setBusinessTypeCode(String businessTypeCode) { this.businessTypeCode = businessTypeCode; }
    public Long getBusinessOrderId() { return businessOrderId; }
    public void setBusinessOrderId(Long businessOrderId) { this.businessOrderId = businessOrderId; }
    public String getBusinessOrderNo() { return businessOrderNo; }
    public void setBusinessOrderNo(String businessOrderNo) { this.businessOrderNo = businessOrderNo; }
    public BigDecimal getQuantityDelta() { return quantityDelta; }
    public void setQuantityDelta(BigDecimal quantityDelta) { this.quantityDelta = quantityDelta; }
    public BigDecimal getBeforeQuantity() { return beforeQuantity; }
    public void setBeforeQuantity(BigDecimal beforeQuantity) { this.beforeQuantity = beforeQuantity; }
    public BigDecimal getAfterQuantity() { return afterQuantity; }
    public void setAfterQuantity(BigDecimal afterQuantity) { this.afterQuantity = afterQuantity; }
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
