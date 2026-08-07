package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝订单域同步游标和最近一次运行状态。 */
@TableName("order_dhb_sync_checkpoint")
public class DhbOrderSyncCheckpointEntity {
    /** 游标记录主键，UUID字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键，所有查询必须带此条件。 */
    public String tenantId;
    /** Integration中的订货宝连接器UUID。 */
    public String connectorId;
    /** 同步对象类型：ORDER；后续可扩展SHIPMENT、RETURN、RECEIPT、PAYMENT。 */
    public String objectType;
    /** 最近一次完整业务落库成功的窗口结束时间，UTC；首次同步为空。 */
    public LocalDateTime lastSuccessAt;
    /** 最近一次同步运行ID，用于跨服务日志追踪。 */
    public String lastRunId;
    /** 最近一次运行状态：IDLE、SUCCEEDED、FAILED。 */
    public String syncStatus;
    /** 最近一次运行时间，UTC。 */
    public LocalDateTime lastRunAt;
    /** 最近一次失败信息，禁止写入Token、密码等敏感内容。 */
    public String lastError;
    /** 本地创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 本地最后更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
