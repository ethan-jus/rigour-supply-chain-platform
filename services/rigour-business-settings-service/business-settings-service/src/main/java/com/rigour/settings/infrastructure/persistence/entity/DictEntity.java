package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 公共业务字典实体。 */
@TableName("biz_dict")
public class DictEntity {
    /** 字典主键UUID。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 字典编码。 */
    public String code;
    /** 字典中文名称。 */
    public String name;
    /** 作用域类型：SYSTEM/MODULE/TENANT。 */
    public String scopeType;
    /** 作用域标识。 */
    public String scopeId;
    /** 业务模块编码。 */
    public String moduleCode;
    /** 租户级字典所属租户。 */
    public String tenantId;
    /** 租户字典复制来源。 */
    public String baseDictId;
    /** 治理状态。 */
    public String status;
    /** 展示顺序。 */
    public Integer sortNo;
    /** 维护说明。 */
    public String remark;
    /** 乐观锁版本。 */
    public Long version;
    /** 整本字典内容版本；定义或任一条目变化时递增。 */
    public Long revision;
    /** 创建主体。 */
    public String createdBy;
    /** 最后修改主体。 */
    public String updatedBy;
    /** UTC创建时间。 */
    public LocalDateTime createdAt;
    /** UTC最后修改时间。 */
    public LocalDateTime updatedAt;
}
