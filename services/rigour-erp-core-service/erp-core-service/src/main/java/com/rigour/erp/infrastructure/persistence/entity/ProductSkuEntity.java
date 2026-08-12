package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 规范 SKU 实体；SKU 表示一组规格值组合的可销售单元。 */
@TableName("erp_product_sku")
public class ProductSkuEntity {
    /** ERP SKU 主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 所属 ERP SPU UUID。 */
    public String spuId;
    /** ERP 租户内唯一 SKU 编码。 */
    public String skuCode;
    /** 订货宝 options_id。 */
    public String sourceOptionsId;
    /** 第一规格值来源 ID。 */
    public String firstSpecificationValueSourceId;
    /** 第二规格值来源 ID。 */
    public String secondSpecificationValueSourceId;
    /** SKU 条码，不假设跨租户全局唯一。 */
    public String barcode;
    /** 中包装条码。 */
    public String middleBarcode;
    /** 大包装条码。 */
    public String bigBarcode;
    /** SKU 销售或库存计量单位。 */
    public String unit;
    /** 展示用规格组合名称，例如“红色,L”；结构化规格以关系表为准。 */
    public String specificationSummary;
    /** 尚未标准化的扩展属性 JSON。 */
    public String attributesJson;
    /** 数据主权状态。 */
    public String ownershipState;
    /** ERP 内部 SKU 状态。 */
    public String internalStatus;
    /** 记录来源。 */
    public String recordOrigin;
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
