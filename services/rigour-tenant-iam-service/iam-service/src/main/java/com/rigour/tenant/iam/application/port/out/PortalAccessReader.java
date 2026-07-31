package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalApplication;
import com.rigour.tenant.iam.application.service.portal.PortalCurrentUser;
import java.util.List;

/** 读取已认证主体的Portal访问快照；实现必须重新校验主体、租户和授权有效性。 */
public interface PortalAccessReader {

    PortalCurrentUser readCurrentUser(PortalAccessQuery query);

    List<PortalApplication> readGrantedApplications(PortalAccessQuery query);
}
