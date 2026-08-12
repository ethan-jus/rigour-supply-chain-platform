package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP SKU 在一个规格维度下选中的规格值关系实体。 */
@TableName("erp_product_sku_specification_value")
public class ProductSkuSpecificationValueEntity {
    /** 关系主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP SKU UUID。 */
    public String skuId;
    /** ERP 规格维度 UUID。 */
    public String specificationId;
    /** ERP 规格值 UUID。 */
    public String valueId;
    /** 规格值展示顺序。 */
    public Integer sortOrder;
    /** 关系创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 关系最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
