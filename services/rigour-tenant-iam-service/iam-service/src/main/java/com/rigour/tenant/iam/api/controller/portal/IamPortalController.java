package com.rigour.tenant.iam.api.controller.portal;

import com.rigour.tenant.iam.api.v1.IamPortalApi;
import com.rigour.tenant.iam.api.v1.model.PortalApplicationView;
import com.rigour.tenant.iam.api.v1.model.PortalCurrentUserView;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import com.rigour.shared.context.RequestContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

/** Portal外部查询接口；身份只取自本服务再次验签后的Access Token。 */
@RestController
public final class IamPortalController implements IamPortalApi {

    private static final Logger log = LoggerFactory.getLogger(IamPortalController.class);

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
        PortalAccessQuery query = currentQuery();
        List<PortalApplicationView> result = service.grantedApplications(query).stream()
                .map(app -> new PortalApplicationView(
                        app.id(), app.code(), app.name(), app.iconKey(), app.launchMode(),
                        app.targetUri(), app.sortOrder()))
                .toList();
        log.info("门户应用加载成功 requestId={} scope={} principalId={} tenantId={} count={} applications={}",
                RequestContext.getRequestId(), query.principalScope(), query.principalId(), query.tenantId(), result.size(),
                result.stream().map(app -> app.code() + "(" + app.launchMode() + ":" + app.targetUri() + ")").toList());
        return result;
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
