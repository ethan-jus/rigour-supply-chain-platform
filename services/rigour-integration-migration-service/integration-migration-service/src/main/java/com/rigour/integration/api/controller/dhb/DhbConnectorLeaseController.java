package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbConnectorLeaseApi;
import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseCommand;
import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseView;
import com.rigour.integration.infrastructure.lease.DhbConnectorLeaseManager;
import com.rigour.integration.infrastructure.lease.DhbConnectorLeaseManager.Lease;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/** Integration内部连接器任务租约入口；不向Gateway暴露。 */
@RestController
public final class DhbConnectorLeaseController implements DhbConnectorLeaseApi {
    private final DhbConnectorLeaseManager manager;

    public DhbConnectorLeaseController(DhbConnectorLeaseManager manager) { this.manager = manager; }

    @Override public LeaseView acquire(UUID connectorId, LeaseCommand command) {
        CallerIdentity caller = requireCaller();
        String owner = command == null || command.ownerService() == null || command.ownerService().isBlank()
                ? caller.principalId().toString() : command.ownerService().strip();
        return view(manager.acquire(caller.tenantId(), connectorId, owner));
    }

    @Override public LeaseView renew(UUID connectorId, String token) {
        CallerIdentity caller = requireCaller();
        return view(manager.renew(caller.tenantId(), connectorId, token));
    }

    @Override public void release(UUID connectorId, String token) {
        CallerIdentity caller = requireCaller();
        manager.release(caller.tenantId(), connectorId, token);
    }

    private LeaseView view(Lease lease) {
        long ttlSeconds = manager.ttl().toSeconds();
        return new LeaseView(lease.connectorId(), lease.token(), Instant.now().plusSeconds(ttlSeconds), ttlSeconds);
    }

    private static CallerIdentity requireCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || !"SERVICE".equals(caller.principalScope())) {
            throw new AuthorizationDeniedException("tenant-service-caller");
        }
        AuthorizationContext.requirePermission("integration:dhb:lease");
        return caller;
    }
}
