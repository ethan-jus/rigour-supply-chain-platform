package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP SPU 可用规格维度关系实体。 */
@TableName("erp_product_spu_specification")
public class ProductSpuSpecificationEntity {
    /** 关系主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP SPU UUID。 */
    public String spuId;
    /** ERP 规格维度 UUID。 */
    public String specificationId;
    /** 规格维度展示顺序。 */
    public Integer sortOrder;
    /** 关系创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 关系最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
