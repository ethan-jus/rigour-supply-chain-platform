package com.rigour.tenant.iam.domain.code;

import com.rigour.shared.core.code.BusinessCodeRule;

/** IAM 领域业务编码规则定义。 */
public final class IamBusinessCodeRules {

    public static final BusinessCodeRule STAFF = BusinessCodeRule.daily("RY", 4);
    public static final BusinessCodeRule POSITION = BusinessCodeRule.daily("GW", 4);
    public static final BusinessCodeRule ROLE = BusinessCodeRule.daily("JS", 4);

    private IamBusinessCodeRules() {
    }
}
