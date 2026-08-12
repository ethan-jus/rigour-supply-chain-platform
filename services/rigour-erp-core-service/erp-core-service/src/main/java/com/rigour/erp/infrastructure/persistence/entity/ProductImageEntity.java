package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品图片实体；数据库只保存我方 COS 私桶 object key。 */
@TableName("erp_product_image")
public class ProductImageEntity {
    /** 图片记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 所属 SPU 主键。 */
    public String spuId;
    /** 所属 SKU 主键；当前商品接口主要落 SPU 图片。 */
    public String skuId;
    /** 订货宝图片资源 ID。 */
    public String sourceResourceId;
    /** 订货宝商品 ID。 */
    public String sourceGoodsId;
    /** 来源原始图片名。 */
    public String originalName;
    /** 来源文件名。 */
    public String sourceFileName;
    /** 我方 COS 私桶对象 key。 */
    public String objectKey;
    /** 展示顺序。 */
    public Integer sortOrder;
    /** 是否主图。 */
    public Boolean isPrimary;
    /** 数据主权状态。 */
    public String ownershipState;
    /** 记录来源。 */
    public String recordOrigin;
    /** 乐观版本号。 */
    public Long version;
    /** 创建时间。 */
    public LocalDateTime createdAt;
    /** 更新时间。 */
    public LocalDateTime updatedAt;
}
