package com.rigour.order.domain.code;

import com.rigour.shared.core.code.BusinessCodeRule;

/** Order 领域业务编码规则定义。 */
public final class OrderBusinessCodeRules {

    public static final BusinessCodeRule SALES_ORDER = BusinessCodeRule.daily("DD", 4);
    public static final BusinessCodeRule SALES_SHIPMENT = BusinessCodeRule.daily("FH", 4);
    public static final BusinessCodeRule PAYMENT_RECORD = BusinessCodeRule.daily("PAY", 4);
    public static final BusinessCodeRule REFUND_RECORD = BusinessCodeRule.daily("TK", 4);
    public static final BusinessCodeRule FUND_RECEIPT = BusinessCodeRule.daily("SK", 4);
    public static final BusinessCodeRule FUND_PAYMENT = BusinessCodeRule.daily("FK", 4);

    private OrderBusinessCodeRules() {
    }
}
