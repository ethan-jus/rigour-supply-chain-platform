package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订货宝独立发货单只读投影；状态保留供应商原值。 */
@TableName("order_dhb_shipment")
public class DhbShipmentEntity {
    /** 平台发货单投影ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户ID，所有查询必须带此条件。 */ public String tenantId;
    /** 来源系统，固定DINGHUOBAO。 */ public String sourceSystem;
    /** 订货宝发货单主键ships_id。 */ public String sourceShipmentId;
    /** 发货单号ships_num，租户内幂等键。 */ public String shipmentNo;
    /** 关联订单号orders_num。 */ public String orderNo;
    /** shipped待发货、receivedin待收货、received已收货、cancelled已取消。 */ public String sourceStatus;
    /** 来源状态中文名status_name。 */ public String sourceStatusName;
    /** 出库类型ID：-2采购退货、10销售出库、11盘亏、17其他、18调拨、19联营。 */ public String sourceTypeId;
    /** 来源出库类型名称。 */ public String sourceTypeName;
    /** 来源客户编号。 */ public String customerNo;
    /** 客户名称快照。 */ public String customerName;
    /** 客户ERP外码。 */ public String customerGuid;
    /** 出库仓库编号。 */ public String warehouseNo;
    /** 出库仓库名称。 */ public String warehouseName;
    /** 出库仓库ERP外码。 */ public String warehouseGuid;
    /** 来源发货时间，统一存UTC。 */ public LocalDateTime shipmentAt;
    /** 物流公司名称。 */ public String logisticsName;
    /** 物流运单号。 */ public String trackingNo;
    /** 发货单备注。 */ public String remark;
    /** 来源单据创建时间，统一存UTC。 */ public LocalDateTime sourceCreatedAt;
    /** 来源单据更新时间，统一存UTC。 */ public LocalDateTime sourceUpdatedAt;
    /** 列表同步为getShipsList单条JSON；含详情时为list+detail组合JSON，不含Token。 */ public String rawJson;
    /** 原始JSON的SHA-256摘要。 */ public String payloadHash;
    /** 是否已保存getShipsContent明细。 */ public Boolean detailAvailable;
    /** 最近一次成功同步时间。 */ public LocalDateTime syncedAt;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
