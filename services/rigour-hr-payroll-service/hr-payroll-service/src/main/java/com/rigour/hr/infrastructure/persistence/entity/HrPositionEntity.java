package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** HR 岗位职位实体。 */
@TableName("hr_position")
public class HrPositionEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String tenantId;
    public String positionCode;
    public String positionName;
    public String positionType;
    public String statusCode;
    public String sourceSystem;
    public String remark;
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
