package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP SPU 与商品标签的多对多关系实体。 */
@TableName("erp_product_spu_tag")
public class ProductSpuTagEntity {
    /** 关系主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP SPU UUID。 */
    public String spuId;
    /** ERP 标签 UUID。 */
    public String tagId;
    /** 分配标签的用户或服务 UUID。 */
    public String assignedBy;
    /** 关系创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 关系最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
