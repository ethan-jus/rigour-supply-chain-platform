package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.api.v1.IamBiIdentityApi;
import com.rigour.tenant.iam.api.v1.model.IamBiIdentityView;
import com.rigour.tenant.iam.application.service.portal.IamBiIdentityService;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

/** IAM JWT 验证后的最小 BI 身份入口，不消费客户端自填员工编码。 */
@RestController
public final class IamBiIdentityController implements IamBiIdentityApi {
    private final IamBiIdentityService service;
    public IamBiIdentityController(IamBiIdentityService service) { this.service = service; }
    @Override public IamBiIdentityView identity(UUID userId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) throw new AccessDeniedException("JWT required");
        var jwt = token.getToken();
        if (!"TENANT".equals(jwt.getClaimAsString("principalScope"))) throw new AccessDeniedException("Tenant required");
        var identity = service.read(new PortalAccessQuery("TENANT", UUID.fromString(jwt.getClaimAsString("principalId")),
                UUID.fromString(jwt.getClaimAsString("tenantId"))), userId);
        return new IamBiIdentityView(identity.userId(), identity.staffId(), identity.staffCode(),
                identity.userSecurityVersion(), identity.tenantPolicyVersion(),
                identity.policies().stream().map(policy -> new IamBiIdentityView.Policy(
                        policy.id(), policy.roleCode(), policy.scopeType())).toList(),
                identity.externalBindings().stream().map(binding -> new IamBiIdentityView.ExternalBinding(
                        binding.sourceSystem(), binding.sourceTenantKey(), binding.sourceEmployeeId())).toList());
    }
}
