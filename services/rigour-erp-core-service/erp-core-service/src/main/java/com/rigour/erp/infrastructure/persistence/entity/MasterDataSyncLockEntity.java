package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 同步互斥锁；同一租户同一对象类型同时只允许一个批次执行。 */
@TableName("erp_master_data_sync_lock")
public class MasterDataSyncLockEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String sourceSystem;
    public String objectType;
    public String runId;
    public LocalDateTime acquiredAt;
    public LocalDateTime expiresAt;
    public Integer revision;
    public String createdBy;
    public LocalDateTime createdTime;
    public String updatedBy;
    public LocalDateTime updatedTime;
    public Integer deleted;
}
