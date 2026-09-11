package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** HR 员工主档实体。 */
@TableName("hr_employee")
public class HrEmployeeEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String tenantId;
    public String employeeCode;
    public String employeeName;
    public String mobile;
    public String email;
    public String employmentStatus;
    public String jobCategory;
    public String primaryPositionCode;
    public String primaryPositionNameSnapshot;
    public String departmentNameSnapshot;
    public String leaderEmployeeCode;
    public String leaderNameSnapshot;
    public String regionName;
    public String cityName;
    public LocalDateTime entryDate;
    public LocalDateTime leaveDate;
    public String sourceSystem;
    public String sourceDocumentNo;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public String sourcePayloadHash;
    public String sourcePayloadJson;
    public String remark;
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
