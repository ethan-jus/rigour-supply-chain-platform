package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研商品实体，对应新业务主表 `erp_product`。 */
@TableName("erp_product")
public class InternalProductEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 商品编码，由 ERP 编码规则生成。 */
    private String productCode;
    /** 商品名称；草稿允许为空，提交时由应用服务校验。 */
    private String productName;
    /** 商品分类ID。 */
    private Long categoryId;
    /** 商品品牌ID。 */
    private Long brandId;
    /** 商品规格说明。 */
    private String productSpecification;
    /** 商品单位，关联 PRODUCT_UNIT 字典项。 */
    private String unitCode;
    /** 起订量。 */
    private BigDecimal minOrderQuantity;
    /** 是否整倍订货。 */
    private Boolean orderMultipleFlag;
    /** 整倍订货数量。 */
    private BigDecimal orderMultipleQuantity;
    /** 售卖类型，关联 PRODUCT_SALE_TYPE 字典项。 */
    private String saleTypeCode;
    /** 上架状态，关联 PRODUCT_SHELF_STATUS 字典项。 */
    private String shelfStatusCode;
    /** 商品标签编码数组 JSON。 */
    private String tagCodesJson;
    /** 限购量。 */
    private BigDecimal limitQuantity;
    /** 默认归属仓库ID。 */
    private Long defaultWarehouseId;
    /** 商品图片 COS key 数组 JSON。 */
    private String imageKeysJson;
    /** 推荐商品ID数组 JSON。 */
    private String recommendProductIdsJson;
    /** 提交状态，关联 PRODUCT_SUBMIT_STATUS 字典项。 */
    private String submitStatusCode;
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
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getBrandId() { return brandId; }
    public void setBrandId(Long brandId) { this.brandId = brandId; }
    public String getProductSpecification() { return productSpecification; }
    public void setProductSpecification(String productSpecification) { this.productSpecification = productSpecification; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }
    public BigDecimal getMinOrderQuantity() { return minOrderQuantity; }
    public void setMinOrderQuantity(BigDecimal minOrderQuantity) { this.minOrderQuantity = minOrderQuantity; }
    public Boolean getOrderMultipleFlag() { return orderMultipleFlag; }
    public void setOrderMultipleFlag(Boolean orderMultipleFlag) { this.orderMultipleFlag = orderMultipleFlag; }
    public BigDecimal getOrderMultipleQuantity() { return orderMultipleQuantity; }
    public void setOrderMultipleQuantity(BigDecimal orderMultipleQuantity) { this.orderMultipleQuantity = orderMultipleQuantity; }
    public String getSaleTypeCode() { return saleTypeCode; }
    public void setSaleTypeCode(String saleTypeCode) { this.saleTypeCode = saleTypeCode; }
    public String getShelfStatusCode() { return shelfStatusCode; }
    public void setShelfStatusCode(String shelfStatusCode) { this.shelfStatusCode = shelfStatusCode; }
    public String getTagCodesJson() { return tagCodesJson; }
    public void setTagCodesJson(String tagCodesJson) { this.tagCodesJson = tagCodesJson; }
    public BigDecimal getLimitQuantity() { return limitQuantity; }
    public void setLimitQuantity(BigDecimal limitQuantity) { this.limitQuantity = limitQuantity; }
    public Long getDefaultWarehouseId() { return defaultWarehouseId; }
    public void setDefaultWarehouseId(Long defaultWarehouseId) { this.defaultWarehouseId = defaultWarehouseId; }
    public String getImageKeysJson() { return imageKeysJson; }
    public void setImageKeysJson(String imageKeysJson) { this.imageKeysJson = imageKeysJson; }
    public String getRecommendProductIdsJson() { return recommendProductIdsJson; }
    public void setRecommendProductIdsJson(String recommendProductIdsJson) {
        this.recommendProductIdsJson = recommendProductIdsJson;
    }
    public String getSubmitStatusCode() { return submitStatusCode; }
    public void setSubmitStatusCode(String submitStatusCode) { this.submitStatusCode = submitStatusCode; }
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
