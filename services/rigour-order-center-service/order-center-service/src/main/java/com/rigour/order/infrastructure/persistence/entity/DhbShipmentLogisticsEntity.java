package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** getWaitShips按订货单落库的出库/发货物流快照主表。 */
@TableName("order_dhb_shipment_logistics")
public class DhbShipmentLogisticsEntity {
    /** 平台物流快照ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户ID。 */ public String tenantId;
    /** 来源系统，固定DINGHUOBAO。 */ public String sourceSystem;
    /** 订货宝订单号orders_num，租户内幂等键。 */ public String orderNo;
    /** 最近一条shipped记录的ships_num。 */ public String shipmentNo;
    /** 最近一条shipped记录的状态。 */ public String sourceStatus;
    /** 物流公司名称。 */ public String logisticsName;
    /** 物流公司编码。 */ public String logisticsCode;
    /** 物流单号express_num。 */ public String trackingNo;
    /** 发货时间ships_date，UTC。 */ public LocalDateTime shipmentAt;
    /** 出库时间ships_time，UTC。 */ public LocalDateTime stockUpAt;
    /** 仓库编号stock_num。 */ public String warehouseNo;
    /** 仓库名称stock_name。 */ public String warehouseName;
    /** 已出库/已发货记录数量。 */ public Integer shippedCount;
    /** 待出库明细数量。 */ public Integer waitStockCount;
    /** getWaitShips完整原始JSON，不含sKey。 */ public String rawJson;
    /** rawJson的SHA-256摘要。 */ public String payloadHash;
    /** 最近一次成功同步时间，UTC。 */ public LocalDateTime syncedAt;
    /** 本地首次创建时间，UTC。 */ public LocalDateTime createdAt;
    /** 本地最近更新时间，UTC。 */ public LocalDateTime updatedAt;
}
