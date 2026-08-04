package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 不可变来源报文记录；业务表不再承担原始报文长期存储职责。 */
@TableName("order_source_record")
public class OrderSourceRecordEntity {
    /** 原始报文记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 关联内部订单ID。 */
    public String orderId;
    /** 外部来源系统编码。 */
    public String sourceSystem;
    /** 外部来源订单号。 */
    public String sourceOrderNo;
    /** 报文类型：LIST=列表摘要，DETAIL=订单明细。 */
    public String payloadType;
    /** 原始JSON报文；不得在日志中复制。 */
    public String payloadJson;
    /** 报文SHA-256，支持去重和重放定位。 */
    public String payloadHash;
    /** 服务收到报文的时间。 */
    public LocalDateTime receivedAt;
}
