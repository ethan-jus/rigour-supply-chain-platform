package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery;
import com.rigour.tenant.iam.application.service.identity.CurrentUser;

/** 读取已认证主体的SCDP访问快照；实现必须重新校验主体、租户和授权有效性。 */
public interface IdentityAccessReader {

    CurrentUser readCurrentUser(IdentityAccessQuery query);
    java.util.Set<String> readSupplyRoles(IdentityAccessQuery query);

}
