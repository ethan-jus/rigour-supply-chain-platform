package com.rigour.order.api.v1.model;

/** 销售订单未提供地址时，记录本趟实际收货地址，不回写订单。 */
public record LogisticsCostDeliveryStop(Long sourceId, String customerName, String address) {}
