package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 公共业务字典项实体；父节点必须属于同一本字典。 */
@TableName("biz_dict_item")
public class DictItemEntity {
    /** 字典项主键UUID。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 所属字典主键。 */
    public String dictId;
    /** 父字典项主键。 */
    public String parentId;
    /** 树层级，由服务端维护。 */
    public Integer levelNo;
    /** 字典项业务编码。 */
    public String code;
    /** 面向业务人员的显示名称。 */
    public String name;
    /** 可选业务值。 */
    public String value;
    /** 同级展示顺序。 */
    public Integer sortNo;
    /** 治理状态。 */
    public String status;
    /** 非核心展示扩展JSON。 */
    public String extraJson;
    /** 乐观锁版本。 */
    public Long version;
    /** 创建主体。 */
    public String createdBy;
    /** 最后修改主体。 */
    public String updatedBy;
    /** UTC创建时间。 */
    public LocalDateTime createdAt;
    /** UTC最后修改时间。 */
    public LocalDateTime updatedAt;
}
