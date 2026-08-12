package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 品牌主表实体；来源同步只更新外部主数据仍拥有的数据。 */
@TableName("erp_brand")
public class BrandEntity {
    /** ERP 品牌主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键，所有查询和写入必须带此条件。 */
    public String tenantId;
    /** ERP 租户内唯一品牌编码。 */
    public String brandCode;
    /** 订货宝品牌编码。 */
    public String sourceBrandNumber;
    /** 订货宝品牌排序。 */
    public Integer sourceSortOrder;
    /** 订货宝品牌说明。 */
    public String sourceDescription;
    /** 品牌名称。 */
    public String name;
    /** 品牌英文名称。 */
    public String englishName;
    /** 品牌 Logo 的我方 COS 私桶对象 key；接口返回时再生成短时 URL。 */
    public String logoObjectKey;
    /** ERP 品牌状态：ACTIVE、INACTIVE 或 ARCHIVED。 */
    public String status;
    /** 数据主权状态，决定外部同步是否允许覆盖规范字段。 */
    public String ownershipState;
    /** 记录来源：IMPORTED、SELF_BUILT 或 MIXED。 */
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
