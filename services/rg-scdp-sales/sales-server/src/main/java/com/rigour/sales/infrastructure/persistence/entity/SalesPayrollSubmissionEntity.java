package com.rigour.sales.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("temp_sales_checkin_submission")
public class SalesPayrollSubmissionEntity {
    @TableId(type = IdType.INPUT)
    private byte[] id;

    private byte[] tenantId;

    public byte[] getId() {
        return id;
    }

    public void setId(byte[] id) {
        this.id = id;
    }

    public byte[] getTenantId() {
        return tenantId;
    }

    public void setTenantId(byte[] tenantId) {
        this.tenantId = tenantId;
    }
}
