package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 规格维度实体，例如颜色、尺寸。 */
@TableName("erp_specification")
public class SpecificationEntity {
    /** ERP 规格维度主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** ERP 租户内唯一规格维度编码。 */
    public String specificationCode;
    /** 订货宝父级规格 ID。 */
    public String sourceParentId;
    /** 规格维度名称。 */
    public String name;
    /** ERP 规格维度状态。 */
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
