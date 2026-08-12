package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 规格值实体，例如红色、XL。 */
@TableName("erp_specification_value")
public class SpecificationValueEntity {
    /** ERP 规格值主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 所属 ERP 规格维度 UUID。 */
    public String specificationId;
    /** 订货宝父级规格 ID。 */
    public String sourceParentId;
    /** 所属规格维度内唯一的规格值编码。 */
    public String valueCode;
    /** 规格值名称。 */
    public String valueName;
    /** 规格值展示顺序。 */
    public Integer sortOrder;
    /** ERP 规格值状态。 */
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
