package com.rigour.merchant.domain.code;

import com.rigour.shared.core.code.BusinessCodeRule;

/** CRM 领域业务编码规则定义。 */
public final class CrmBusinessCodeRules {

    public static final BusinessCodeRule CUSTOMER = BusinessCodeRule.daily("CUS", 4);
    public static final BusinessCodeRule CUSTOMER_TYPE = BusinessCodeRule.daily("CUSTYPE", 3);
    public static final BusinessCodeRule CUSTOMER_AREA = BusinessCodeRule.daily("CUSAREA", 3);

    private CrmBusinessCodeRules() {
    }
}
