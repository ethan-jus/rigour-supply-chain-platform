package com.rigour.hr.domain.code;

import com.rigour.shared.core.code.BusinessCodeRule;

/** HR 领域业务编码规则定义。 */
public final class HrBusinessCodeRules {
    public static final BusinessCodeRule EMPLOYEE = BusinessCodeRule.daily("EMP", 4);
    public static final BusinessCodeRule POSITION = BusinessCodeRule.daily("POS", 4);

    private HrBusinessCodeRules() {
    }
}
