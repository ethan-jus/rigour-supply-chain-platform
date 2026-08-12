package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品标签分组实体；分组来源于订货宝，后续可转为内部主数据。 */
@TableName("erp_tag_group")
public class TagGroupEntity {
    /** ERP 标签分组主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 租户内唯一分组编码。 */
    public String groupCode;
    /** 分组名称。 */
    public String name;
    /** ERP 分组状态。 */
    public String status;
    /** 数据主权状态。 */
    public String ownershipState;
    /** 记录来源。 */
    public String recordOrigin;
    /** 可扩展属性 JSON。 */
    public String attributesJson;
    /** 乐观版本号。 */
    public Long version;
    /** 创建时间。 */
    public LocalDateTime createdAt;
    /** 更新时间。 */
    public LocalDateTime updatedAt;
}
