package com.rigour.order.application.service.sales;

import com.rigour.shared.context.AuthorizationContext;

/** 发票页有独立读权限；订单内的发票面板保留原订单查看入口。 */
public final class InvoiceReadPermission {
    private InvoiceReadPermission() {}
    public static String current() {
        return AuthorizationContext.hasPermission("order:invoice:read")
                ? "order:invoice:read" : "order:read";
    }
}
