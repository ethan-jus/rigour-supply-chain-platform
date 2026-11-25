package com.rigour.order.application.service.sales;

import com.rigour.tenant.iam.client.SupplyAuthorizationContext;

/** 每个台账页面只合并授予该页面的角色范围。 */
public enum RegisterPage {
    ORDERS("order:register-orders:read"),
    LINES("order:register-lines:read"),
    PAYMENTS("order:register-payments:read");

    private final String action;
    RegisterPage(String action) { this.action = action; }

    public String permission() {
        return SupplyAuthorizationContext.current().isPresent() ? action : "order:read";
    }
}
