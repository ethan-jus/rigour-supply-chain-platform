package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订单中心自有Transactional Outbox；投递器和重试策略后续由订单中心实现。 */
@TableName("order_outbox_event")
public class OrderOutboxEventEntity {
    /** 事件主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 聚合类型，当前为ORDER。 */
    public String aggregateType;
    /** 订单聚合ID。 */
    public String aggregateId;
    /** 事件类型：ORDER_IMPORTED、ORDER_SOURCE_UPDATED等。 */
    public String eventType;
    /** 事件契约版本。 */
    public Integer eventVersion;
    /** 幂等投递键。 */
    public String eventKey;
    /** 不含敏感信息的版本化事件载荷。 */
    public String payloadJson;
    /** 投递状态：PENDING、PUBLISHED、FAILED、DEAD。 */
    public String status;
    /** 已尝试投递次数。 */
    public Integer attempts;
    /** 下一次可投递时间。 */
    public LocalDateTime availableAt;
    /** 成功投递时间。 */
    public LocalDateTime publishedAt;
    /** 最近一次投递错误。 */
    public String lastError;
    /** 创建时间。 */
    public LocalDateTime createdAt;
    /** 最后更新时间。 */
    public LocalDateTime updatedAt;
}
