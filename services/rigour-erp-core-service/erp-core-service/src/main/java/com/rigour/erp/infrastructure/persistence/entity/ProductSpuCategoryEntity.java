package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP SPU 与商品分类的多对多关系实体。 */
@TableName("erp_product_spu_category")
public class ProductSpuCategoryEntity {
    /** 关系主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP SPU UUID。 */
    public String spuId;
    /** ERP 分类 UUID。 */
    public String categoryId;
    /** 是否为 SPU 主分类；应用事务保证每个 SPU 只有一个主分类。 */
    @TableField("is_primary") public Boolean primaryFlag;
    /** 多分类展示顺序。 */
    public Integer sortOrder;
    /** 关系创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 关系最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
