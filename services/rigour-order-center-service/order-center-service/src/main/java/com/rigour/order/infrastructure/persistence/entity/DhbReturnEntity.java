package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订货宝退货单只读投影；状态保留供应商原值。 */
@TableName("order_dhb_return")
public class DhbReturnEntity {
    /** 平台退货单投影ID，UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户ID，所有查询必须带此条件。 */ public String tenantId;
    /** 来源系统，固定DINGHUOBAO。 */ public String sourceSystem;
    /** 退货单号ReturnsSN，租户内幂等键。 */ public String returnNo;
    /** 关联订单号OrdersNum。 */ public String orderNo;
    /** return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消。 */ public String sourceStatus;
    /** 退货单经办人。 */ public String staffName;
    /** 退单金额ReturnsTotal。 */ public BigDecimal returnAmount;
    /** 退单结算金额ReturnsDiscountTotal。 */ public BigDecimal settlementAmount;
    /** 来源退货日期，统一存UTC。 */ public LocalDateTime returnedAt;
    /** 来源最后更新时间，统一存UTC。 */ public LocalDateTime sourceUpdatedAt;
    /** 退货原因。 */ public String reason;
    /** 来源客户编号。 */ public String customerNo;
    /** 客户ERP外码。 */ public String customerGuid;
    /** 退单收货人。 */ public String consignee;
    /** 退单联系电话，敏感字段。 */ public String phone;
    /** 退货地址，敏感字段。 */ public String address;
    /** 退货物流公司。 */ public String logisticsCompany;
    /** 退货物流单号。 */ public String logisticsNo;
    /** 退货类型：0未确认、1退货退款、2仅退款。 */ public String returnType;
    /** 退货配送方式。 */ public String deliveryMode;
    /** 列表同步为getReturnsList单条JSON；含详情时为list+detail组合JSON，不含Token。 */ public String rawJson;
    /** 原始JSON的SHA-256摘要。 */ public String payloadHash;
    /** 是否已保存getReturnsContent明细。 */ public Boolean detailAvailable;
    /** 最近一次成功同步时间。 */ public LocalDateTime syncedAt;
    /** 本地创建时间。 */ public LocalDateTime createdAt;
    /** 本地更新时间。 */ public LocalDateTime updatedAt;
}
