package com.rigour.integration.application.port.out;

import com.rigour.shared.context.CallerIdentity;

/** 以当前业务人员身份读取目标，保留目标领域的权限和数据范围检查。 */
public interface DhbDependencyTargets {
    Target require(CallerIdentity caller, String type, Long id, Long productId);

    record Target(String domain, String objectType, Long id, String code) {}
}
