package com.rigour.tenant.iam.api.controller.identity;

import com.rigour.tenant.iam.api.v1.IamIdentityApi;
import com.rigour.tenant.iam.api.v1.model.CurrentUserView;
import com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery;
import com.rigour.tenant.iam.application.service.identity.IdentityAccessService;
import com.rigour.shared.context.RequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

/** SCDP外部查询接口；身份只取自本服务再次验签后的Access Token。 */
@RestController
public final class IamIdentityController implements IamIdentityApi {

    private static final Logger log = LoggerFactory.getLogger(IamIdentityController.class);

    private final IdentityAccessService service;

    public IamIdentityController(IdentityAccessService service) {
        this.service = service;
    }

    @Override
    public CurrentUserView getCurrentUser() {
        var user = service.currentUser(currentQuery());
        return new CurrentUserView(
                user.id(), user.tenantId(), user.tenantName(), user.principalScope(), user.username(), user.displayName(),
                user.roles(), user.permissions());
    }

    /** 网关每次业务请求重新核验的授权快照，不能使用 Token 内陈旧的角色声明。 */
    @org.springframework.web.bind.annotation.GetMapping("/api/v1/token/current")
    public CurrentTokenView validateCurrentToken() {
        var user = service.currentUser(currentQuery());
        return new CurrentTokenView(user.roles(), user.permissions());
    }

    public record CurrentTokenView(java.util.Set<String> roles, java.util.Set<String> permissions) {}

    static IdentityAccessQuery currentQuery() {
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
        return new IdentityAccessQuery(scope, principalId, tenant == null ? null : UUID.fromString(tenant));
    }
}
