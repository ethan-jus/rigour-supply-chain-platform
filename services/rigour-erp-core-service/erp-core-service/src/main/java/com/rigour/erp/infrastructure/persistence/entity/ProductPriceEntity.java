package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 商品价格实体；通过 target_type/target_id 支持 SPU、SKU 和多计量单位。 */
@TableName("erp_product_price")
public class ProductPriceEntity {
    /** 价格记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 价格归属类型：SPU 或 SKU。 */
    public String targetType;
    /** 价格归属业务主键。 */
    public String targetId;
    /** 所属 SPU 主键。 */
    public String spuId;
    /** 所属 SKU 主键。 */
    public String skuId;
    /** 价格类型：ORDER/MARKET/PURCHASE/OTHER。 */
    public String priceType;
    /** 计量层级：BASE/MIDDLE/BIG。 */
    public String unitLevel;
    /** 价格金额。 */
    public BigDecimal amount;
    /** 订货宝来源字段名。 */
    public String sourceField;
    /** 币种。 */
    public String currency;
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
