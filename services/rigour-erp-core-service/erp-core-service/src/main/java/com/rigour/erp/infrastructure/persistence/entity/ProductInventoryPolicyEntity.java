package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 商品库存策略实体；保存订货宝商品的库存上下限和安全库存快照。 */
@TableName("erp_product_inventory_policy")
public class ProductInventoryPolicyEntity {
    /** 策略主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 所属 SPU 主键。 */
    public String spuId;
    /** 库存下限。 */
    public BigDecimal lowerBound;
    /** 库存上限。 */
    public BigDecimal upperBound;
    /** 安全库存。 */
    public BigDecimal safetyStock;
    /** 来源库存下限字段。 */
    public String sourceLowerField;
    /** 来源库存上限字段。 */
    public String sourceUpperField;
    /** 来源安全库存字段。 */
    public String sourceSafeField;
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
