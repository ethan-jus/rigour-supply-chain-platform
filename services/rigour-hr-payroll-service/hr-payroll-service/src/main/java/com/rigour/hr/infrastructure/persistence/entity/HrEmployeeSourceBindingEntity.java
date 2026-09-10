package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** HR 员工外部来源绑定实体。 */
@TableName("hr_employee_source_binding")
public class HrEmployeeSourceBindingEntity {
    @TableId(type = IdType.AUTO) public Long id;
    public String tenantId;
    public String employeeCode;
    public byte[] connectorId;
    public String sourceSystem;
    public String sourceTenantKey;
    public String sourceEmployeeId;
    public String sourceAccountName;
    public String sourcePayloadHash;
    public String sourcePayloadJson;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
