package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品标签主表实体。 */
@TableName("erp_tag")
public class ProductTagEntity {
    /** ERP 标签主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** ERP 标签分组 UUID。 */
    public String tagGroupId;
    /** 租户主键。 */
    public String tenantId;
    /** ERP 租户内唯一标签编码。 */
    public String tagCode;
    /** 订货宝标签分组来源 ID。 */
    public String sourceGroupId;
    /** 订货宝标签分组名称。 */
    public String sourceGroupName;
    /** 订货宝标签排序。 */
    public Integer sourceSortOrder;
    /** 订货宝标签关联数量快照。 */
    public Integer sourceRelationCount;
    /** 订货宝标签创建时间。 */
    public LocalDateTime sourceCreatedAt;
    /** 订货宝标签更新时间。 */
    public LocalDateTime sourceUpdatedAt;
    /** 标签名称。 */
    public String name;
    /** 标签展示颜色。 */
    public String color;
    /** ERP 标签状态。 */
    public String status;
    /** 数据主权状态。 */
    public String ownershipState;
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
