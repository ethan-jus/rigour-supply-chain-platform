package com.rigour.order.application.service.sales;

import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

/** 回款读取与订单读取独立；旧授权模式保留原权限契约。 */
public final class PaymentReadPermission {
    private PaymentReadPermission() {}

    public static String current() {
        return SupplyAuthorizationContext.current().map(SupplyAuthorizationContext::active).orElse(false)
                ? "order:payment:read" : "order:read";
    }
}
