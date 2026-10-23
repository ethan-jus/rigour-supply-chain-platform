package com.rigour.sales.application.port.out;

import com.rigour.shared.context.CallerIdentity;

/** 在线核验原操作人的 IAM 资格与 HR 员工；不接受客户端指定员工。 */
public interface SalesEmployeeIdentitySource {
    Employee current(CallerIdentity caller);

    record Employee(long id, String code, String name, long revision,
                    long organizationVersion, long accessVersion) {}
}
