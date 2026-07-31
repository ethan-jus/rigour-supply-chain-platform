package com.rigour.tenant.iam.api.controller.portal;

import com.rigour.tenant.iam.api.v1.IamPortalApi;
import com.rigour.tenant.iam.api.v1.model.PortalApplicationView;
import com.rigour.tenant.iam.api.v1.model.PortalCurrentUserView;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

/** Portal外部查询接口；身份只取自本服务再次验签后的Access Token。 */
@RestController
public final class IamPortalController implements IamPortalApi {

    private final PortalAccessService service;

    public IamPortalController(PortalAccessService service) {
        this.service = service;
    }

    @Override
    public PortalCurrentUserView getCurrentUser() {
        var user = service.currentUser(currentQuery());
        return new PortalCurrentUserView(
                user.id(), user.tenantId(), user.tenantName(), user.principalScope(), user.username(), user.displayName(),
                user.roles(), user.permissions());
    }

    @Override
    public List<PortalApplicationView> getGrantedApplications() {
        return service.grantedApplications(currentQuery()).stream()
                .map(app -> new PortalApplicationView(
                        app.id(), app.code(), app.name(), app.iconKey(), app.launchMode(),
                        app.targetUri(), app.sortOrder()))
                .toList();
    }

    private static PortalAccessQuery currentQuery() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Access token is required");
        }
        var jwt = jwtAuthentication.getToken();
        String scope = jwt.getClaimAsString("principalScope");
        UUID principalId = UUID.fromString(jwt.getClaimAsString("principalId"));
        String tenant = jwt.getClaimAsString("tenantId");
        return new PortalAccessQuery(scope, principalId, tenant == null ? null : UUID.fromString(tenant));
    }
}
