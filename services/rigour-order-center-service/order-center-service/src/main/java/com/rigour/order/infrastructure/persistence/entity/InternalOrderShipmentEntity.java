package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("order_order_shipment")
public class InternalOrderShipmentEntity {
    /** 平台内部发货记录主键。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 关联order_order.id。 */
    public String orderId;
    /** 来源发货单号。 */
    public String sourceShipmentNo;
    /** 来源发货状态原值。 */
    public String status;
    /** 来源发货时间原值。 */
    public String shipmentDate;
    /** 来源备货时间原值。 */
    public String stockUpTime;
    /** 创建时间。 */
    public LocalDateTime createdAt;
    /** 最后更新时间。 */
    public LocalDateTime updatedAt;
}
