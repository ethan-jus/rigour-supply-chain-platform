package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 公共业务字典实体，对应 data_dictionary。 */
@TableName("data_dictionary")
public class DictEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO) public Long id;
    /** 字典编码。 */
    public String dictionaryCode;
    /** 字典名称。 */
    public String dictionaryName;
    /** 字典类型：COMMON/ERP/CRM/ORDER。 */
    public String dictionaryType;
    /** 维护说明。 */
    public String remark;
    /** 乐观锁版本。 */
    public Integer revision;
    /** 创建主体。 */
    public String createdBy;
    /** 最后修改主体。 */
    public String updatedBy;
    /** 创建时间。 */
    public LocalDateTime createdTime;
    /** 最后修改时间。 */
    public LocalDateTime updatedTime;
    /** 删除标识。 */
    public Integer deleted;
}
