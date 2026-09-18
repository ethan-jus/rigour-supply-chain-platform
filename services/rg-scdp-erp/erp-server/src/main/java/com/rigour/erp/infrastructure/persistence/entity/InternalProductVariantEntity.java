package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研商品规格价格实体，对应 `erp_product_variant`。 */
@TableName("erp_product_variant")
public class InternalProductVariantEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 商品ID。 */
    private Long productId;
    /** 规格编码，由 ERP 编码规则生成。 */
    private String variantCode;
    /** 规格快照。 */
    private String specificationSnapshot;
    /** 规格销售单位，关联 PRODUCT_UNIT 字典项。 */
    private String unitCode;
    /** 订货价。 */
    private BigDecimal salePrice;
    /** 市场价。 */
    private BigDecimal marketPrice;
    /** 采购参考价。 */
    private BigDecimal purchasePrice;
    /** 起订量。 */
    private BigDecimal minOrderQuantity;
    /** 整倍订货数量。 */
    private BigDecimal orderMultipleQuantity;
    /** 限购量。 */
    private BigDecimal limitQuantity;
    /** 是否默认规格。 */
    private Boolean defaultFlag;
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
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getVariantCode() { return variantCode; }
    public void setVariantCode(String variantCode) { this.variantCode = variantCode; }
    public String getSpecificationSnapshot() { return specificationSnapshot; }
    public void setSpecificationSnapshot(String specificationSnapshot) { this.specificationSnapshot = specificationSnapshot; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public BigDecimal getMarketPrice() { return marketPrice; }
    public void setMarketPrice(BigDecimal marketPrice) { this.marketPrice = marketPrice; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public BigDecimal getMinOrderQuantity() { return minOrderQuantity; }
    public void setMinOrderQuantity(BigDecimal minOrderQuantity) { this.minOrderQuantity = minOrderQuantity; }
    public BigDecimal getOrderMultipleQuantity() { return orderMultipleQuantity; }
    public void setOrderMultipleQuantity(BigDecimal orderMultipleQuantity) {
        this.orderMultipleQuantity = orderMultipleQuantity;
    }
    public BigDecimal getLimitQuantity() { return limitQuantity; }
    public void setLimitQuantity(BigDecimal limitQuantity) { this.limitQuantity = limitQuantity; }
    public Boolean getDefaultFlag() { return defaultFlag; }
    public void setDefaultFlag(Boolean defaultFlag) { this.defaultFlag = defaultFlag; }
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
