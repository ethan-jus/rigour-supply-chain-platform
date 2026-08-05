package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订货宝退货单商品明细。 */
@TableName("order_dhb_return_line")
public class DhbReturnLineEntity {
    /** 平台退货明细ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 所属order_dhb_return.id。 */ public String returnId;
    /** 来源明细ID；来源无ID时由Integration生成稳定键。 */ public String sourceLineId;
    /** 商品ERP外码Guid/TrueGuid。 */ public String sourceProductGuid;
    /** 规格商品编码OptionsGoodsNum。 */ public String skuNo;
    /** 商品编码Coding。 */ public String productCode;
    /** 商品名称Name。 */ public String productName;
    /** 申请退货数量ReturnsNumber。 */ public BigDecimal quantity;
    /** 确认退货数量ReturnsConfirmNumber。 */ public BigDecimal confirmedQuantity;
    /** 申请退货价格ReturnsPrice。 */ public BigDecimal unitPrice;
    /** 确认退货价格ReturnsConfirmPrice。 */ public BigDecimal confirmedPrice;
    /** 退货单位名称ReturnsUnitsName。 */ public String unitName;
    /** body.Stock.StockGuid或StockId退货仓库外码/编号。 */ public String warehouseNo;
    /** body.Stock.StockName退货仓库名称。 */ public String warehouseName;
    /** 退货明细备注。 */ public String remark;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
