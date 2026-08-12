package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品可扩展字段实体；承接订货宝 field_1 至 field_6 及后续来源字段。 */
@TableName("erp_product_custom_field")
public class ProductCustomFieldEntity {
    /** 扩展字段记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 字段归属类型：SPU 或 SKU。 */
    public String targetType;
    /** 字段归属业务主键。 */
    public String targetId;
    /** 所属 SPU 主键。 */
    public String spuId;
    /** 所属 SKU 主键。 */
    public String skuId;
    /** 来源字段 key。 */
    public String fieldKey;
    /** 字段值。 */
    public String fieldValue;
    /** 订货宝原始字段名。 */
    public String sourceField;
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
