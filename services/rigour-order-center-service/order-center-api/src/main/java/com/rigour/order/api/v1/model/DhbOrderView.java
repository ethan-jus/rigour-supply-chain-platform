package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单列表的本地规范化投影；不向调用方暴露订货宝原始报文。 */
public record DhbOrderView(
        /** 来源订单号，也是一期页面展示的订单号。 */
        String orderSn,
        /** 来源交付日期。 */
        String deliveryDate,
        /** 订单备注。 */
        String orderRemark,
        /** 订单总金额。 */
        BigDecimal orderTotal,
        /** 订货宝来源状态原值，不代表平台内部状态。 */
        String orderStatus,
        /** 来源下单时间。 */
        Instant orderDate,
        /** 来源更新时间。 */
        Instant orderUpdateDate,
        /** 来源订单更新时间原值。 */
        String orderUpdateTime,
        /** 来源订单类型。 */
        String orderType,
        /** 来源接口状态。 */
        String orderApi,
        /** 来源异常状态。 */
        String orderException,
        /** 来源发货方式。 */
        String orderSendType,
        /** 来源最后下单时间。 */
        String lastOrderAt,
        /** 来源客户编号。 */
        String clientNo,
        /** 来源客户GUID。 */
        String clientGuid,
        /** 来源设备。 */
        String sourceDevice,
        /** 是否管理员订单。 */
        String isAdminOrder,
        /** 来源支付状态。 */
        String payStatus,
        /** 客户名称快照。 */
        String clientName,
        /** 收货人名称。 */
        String receiveName,
        /** 收货单位。 */
        String receiveCompany,
        /** 收货电话，前端展示应按权限脱敏。 */
        String receivePhone,
        /** 收货地址，前端展示应按权限脱敏。 */
        String receiveAddress,
        /** 省。 */
        String province,
        /** 市。 */
        String city,
        /** 区县。 */
        String district,
        /** 来源拆单类型。 */
        String splitType,
        /** 来源拆单类型名称。 */
        String splitTypeName,
        /** 是否已经通过getOrderContent取得明细。 */
        boolean detailAvailable,
        /** 最近一次本地同步时间。 */
        Instant syncedAt,
        String customerType,
        String customerArea,
        String adminUser,
        String operationName,
        String salesPerson,
        String salesPersonMobile,
        String assistantSalesPersons,
        String auditAt,
        String settlementMethod,
        BigDecimal goodsWeight,
        BigDecimal taxAmount,
        BigDecimal discountPrice,
        BigDecimal discountTotal,
        BigDecimal freightAmount,
        BigDecimal applyTotal,
        BigDecimal couponDiscountedAmount,
        String customerRemark,
        String internalComment,
        String invoiceTitle,
        String invoiceContent,
        String invoiceBank,
        String invoiceBankAccount,
        String taxpayerNumber,
        String customerTag,
        String invoiceType) {
}
