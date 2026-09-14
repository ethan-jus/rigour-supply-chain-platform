package com.rigour.tenant.iam.application.service.portal;

import com.rigour.tenant.iam.application.model.IamBiIdentity;
import com.rigour.tenant.iam.application.port.out.IamBiIdentityStore;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 自助校验只读取本人；管理员查看其他账号必须已有范围管理权限。 */
@Service
public final class IamBiIdentityService {
    private final PortalAccessService access;
    private final IamBiIdentityStore store;
    public IamBiIdentityService(PortalAccessService access, IamBiIdentityStore store) { this.access = access; this.store = store; }
    public IamBiIdentity read(PortalAccessQuery actor, UUID requestedUserId) {
        if (!"TENANT".equals(actor.principalScope())) throw new AccessDeniedException("Tenant account required");
        var current = access.currentUser(actor);
        if (!current.permissions().contains("analytics:dashboard:read")) throw new AccessDeniedException("BI permission required");
        UUID userId = requestedUserId == null ? actor.principalId() : requestedUserId;
        if (!userId.equals(actor.principalId()) && !(current.roles().contains("TENANT_SUPER_ADMIN")
                && current.permissions().contains("iam:data-scope:write"))) throw new AccessDeniedException("Self identity only");
        var target = access.currentUser(new PortalAccessQuery("TENANT", userId, actor.tenantId()));
        if (!target.permissions().contains("analytics:dashboard:read")) throw new AccessDeniedException("Target BI permission required");
        return store.read(actor.tenantId(), userId);
    }
}
