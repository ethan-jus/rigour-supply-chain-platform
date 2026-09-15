package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 公共业务字典项实体，对应 data_dictionary_item。 */
@TableName("data_dictionary_item")
public class DictItemEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO) public Long id;
    /** 字典编码。 */
    public String dictionaryCode;
    /** 字典条目层级。 */
    public Integer dictionaryItemLevel;
    /** 父级字典条目编码。 */
    public String parentDictionaryItemCode;
    /** 字典条目编码。 */
    public String dictionaryItemCode;
    /** 字典条目名称。 */
    public String dictionaryItemName;
    /** 历史兼容项指向的标准字典；与标准项编码同时为空或同时存在。 */
    public String canonicalDictionaryCode;
    /** 直接指向有效标准项，不允许形成别名链。 */
    public String canonicalItemCode;
    /** 备注。 */
    public String remark;
    /** 序号。 */
    public Integer ordinal;
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
