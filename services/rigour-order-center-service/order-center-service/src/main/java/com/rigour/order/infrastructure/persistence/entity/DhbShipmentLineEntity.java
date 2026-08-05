package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订货宝独立发货单商品明细。 */
@TableName("order_dhb_shipment_line")
public class DhbShipmentLineEntity {
    /** 平台发货明细ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 所属order_dhb_shipment.id。 */ public String shipmentId;
    /** 来源明细ID；来源无ID时由Integration生成稳定键。 */ public String sourceLineId;
    /** 商品ERP外码goods_guid；缺失时兼容goods_id。 */ public String sourceProductGuid;
    /** 规格商品编码options_goods_num。 */ public String skuNo;
    /** 商品编码goods_num。 */ public String productCode;
    /** 商品名称goods_name快照。 */ public String productName;
    /** 发货数量ships_number，沿用来源小单位语义。 */ public BigDecimal quantity;
    /** orders_list_info.orders_price/order_units_price来源单价。 */ public BigDecimal unitPrice;
    /** orders_list_info.actual_amount来源明细金额。 */ public BigDecimal lineAmount;
    /** orders_list_info.order_units_name/base_units_name来源计量单位。 */ public String unitName;
    /** 主单stock_num出库仓库编号；明细未返回时继承主单。 */ public String warehouseNo;
    /** 发货明细备注。 */ public String remark;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
