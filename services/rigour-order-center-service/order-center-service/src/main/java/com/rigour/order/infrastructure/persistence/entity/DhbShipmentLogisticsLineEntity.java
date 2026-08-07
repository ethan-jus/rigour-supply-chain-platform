package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** getWaitShips已出库/待出库明细。 */
@TableName("order_dhb_shipment_logistics_line")
public class DhbShipmentLogisticsLineEntity {
    /** 平台物流明细ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 所属物流快照ID。 */ public String logisticsId;
    /** SHIPPED或WAIT_STOCK。 */ public String lineType;
    /** SHIPPED的ships_num；WAIT_STOCK为空。 */ public String shipmentNo;
    /** 来源明细ID。 */ public String sourceLineId;
    /** 订单明细ID。 */ public String orderLineId;
    /** 商品ID。 */ public String productId;
    /** 规格商品编码。 */ public String skuNo;
    /** 买品或赠品。 */ public String listType;
    /** 商品编码。 */ public String productCode;
    /** 商品名称。 */ public String productName;
    /** 商品规格。 */ public String specification;
    /** 小单位。 */ public String unit;
    /** 大单位。 */ public String containerUnit;
    /** 换算关系。 */ public BigDecimal conversionNumber;
    /** SHIPPED出库数量。 */ public BigDecimal quantity;
    /** WAIT_STOCK订购数量。 */ public BigDecimal orderedQuantity;
    /** WAIT_STOCK已出库数量。 */ public BigDecimal stockedQuantity;
    /** WAIT_STOCK实际库存。 */ public BigDecimal realStock;
    /** WAIT_STOCK待出库数量。 */ public BigDecimal waitQuantity;
    /** 仓库编号。 */ public String warehouseNo;
    /** 仓库名称。 */ public String warehouseName;
    /** 明细备注。 */ public String remark;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
