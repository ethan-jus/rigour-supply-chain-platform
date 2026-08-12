package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 商品计量单位实体；保留基础、中包装、大包装及其换算关系。 */
@TableName("erp_product_unit")
public class ProductUnitEntity {
    /** 单位记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 单位归属类型：SPU 或 SKU。 */
    public String targetType;
    /** 单位归属业务主键。 */
    public String targetId;
    /** 所属 SPU 主键。 */
    public String spuId;
    /** 所属 SKU 主键。 */
    public String skuId;
    /** 计量层级：BASE/MIDDLE/BIG。 */
    public String unitLevel;
    /** 单位名称。 */
    public String unitName;
    /** 当前层级条码。 */
    public String barcode;
    /** 换算为基础单位的数量。 */
    public BigDecimal conversionToBase;
    /** 订货宝来源字段名。 */
    public String sourceField;
    /** 展示顺序。 */
    public Integer sortOrder;
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
