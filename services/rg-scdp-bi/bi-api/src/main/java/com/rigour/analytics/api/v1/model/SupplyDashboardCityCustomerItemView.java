package com.rigour.analytics.api.v1.model;

/** 城市下单客户数；复购指期间同城同客户至少两笔非取消订单，客户不等同门店。 */
public record SupplyDashboardCityCustomerItemView(
        String regionCode,
        String regionName,
        Long orderingCustomerCount,
        Long repeatCustomerCount) {
}
