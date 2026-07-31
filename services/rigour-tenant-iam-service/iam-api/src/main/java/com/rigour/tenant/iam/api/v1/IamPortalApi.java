package com.rigour.tenant.iam.api.v1;

import com.rigour.tenant.iam.api.v1.model.PortalApplicationView;
import com.rigour.tenant.iam.api.v1.model.PortalCurrentUserView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;

/** Portal读取当前登录人和已授权应用的外部V1契约。 */
public interface IamPortalApi {

    @GetMapping("/api/v1/me")
    PortalCurrentUserView getCurrentUser();

    @GetMapping("/api/v1/portal/apps")
    List<PortalApplicationView> getGrantedApplications();
}
