package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 规范商品/SPU 实体；外部主导时同步订货宝状态，本地主导后保护人工状态。 */
@TableName("erp_product_spu")
public class ProductSpuEntity {
    /** ERP SPU 主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP 租户内唯一 SPU 编码。 */
    public String spuCode;
    /** ERP 商品名称。 */
    public String name;
    /** 订货宝商品型号。 */
    public String model;
    /** 订货宝商品副标题。 */
    public String subtitle;
    /** 订货宝商品关键词。 */
    public String keywords;
    /** 订货宝货位/存放信息。 */
    public String goodsAllocation;
    /** COS 私桶主图对象 key。 */
    public String mainImageKey;
    /** 订货宝 multi_id 原值。 */
    public String sourceMultiId;
    /** 订货宝分类来源 ID。 */
    public String sourceCategoryId;
    /** 订货宝品牌来源 ID。 */
    public String sourceBrandId;
    /** 订货宝换算条码。 */
    public String conversionBarcode;
    /** ERP 品牌 UUID；来源品牌尚未绑定时为空。 */
    public String brandId;
    /** 商品基础计量单位。 */
    public String baseUnit;
    /** 商品默认条码。 */
    public String defaultBarcode;
    /** 订货宝最低订货量。 */
    public BigDecimal minimumOrder;
    /** 订货宝最低订货量单位原值。 */
    public String minimumOrderUnit;
    /** 数据主权状态。 */
    public String ownershipState;
    /** ERP 内部商品状态。 */
    public String internalStatus;
    /** 记录来源。 */
    public String recordOrigin;
    /** 尚未标准化的扩展属性 JSON。 */
    public String attributesJson;
    /** 乐观版本号。 */
    public Long version;
    /** 创建用户或服务 UUID。 */
    public String createdBy;
    /** 最近更新用户或服务 UUID。 */
    public String updatedBy;
    /** ERP 记录创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** ERP 记录最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
