package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 订货宝退货单本地投影。 */
public record DhbReturnDocumentView(
        /** 订货宝退货单号ReturnsSN。 */ String returnNo,
        /** 关联订货宝订单号OrdersNum。 */ String orderNo,
        /** return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消。 */ String status,
        /** 申请退货金额ReturnsTotal。 */ BigDecimal returnAmount,
        /** 确认结算金额ReturnsDiscountTotal。 */ BigDecimal settlementAmount,
        /** 退货日期ReturnsDate，响应为UTC Instant。 */ Instant returnedAt,
        /** 退货原因ReturnsReason。 */ String reason,
        /** 来源客户编号ClientNum。 */ String customerNo,
        /** 退单收货人ReturnsConsignee。 */ String consignee,
        /** 退货物流公司ReturnsSendCompany。 */ String logisticsCompany,
        /** 退货物流单号ReturnsSendNo。 */ String logisticsNo,
        /** 是否已经保存getReturnsContent商品明细。 */ boolean detailAvailable,
        /** 最近一次成功落库时间，UTC。 */ Instant syncedAt) {
}
