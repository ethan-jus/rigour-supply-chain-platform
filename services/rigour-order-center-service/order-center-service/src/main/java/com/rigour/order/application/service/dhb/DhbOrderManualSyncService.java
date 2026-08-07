package com.rigour.order.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbSyncTargetDiscoveryClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Order Center手动编排订货宝同步；任务发现和实际调用均复用定时同步链路。 */
@Service
public final class DhbOrderManualSyncService {
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> SERVICE_PERMISSIONS = Set.of(
            "integration:dhb:read", "integration:dhb:write", "integration:dhb:sync-discovery");

    private final DhbOrderSyncService syncService;
    private final DhbSyncTargetDiscoveryClient targetDiscoveryClient;

    public DhbOrderManualSyncService(DhbOrderSyncService syncService,
                                      DhbSyncTargetDiscoveryClient targetDiscoveryClient) {
        this.syncService = Objects.requireNonNull(syncService, "syncService不能为空");
        this.targetDiscoveryClient = Objects.requireNonNull(targetDiscoveryClient,
                "targetDiscoveryClient不能为空");
    }

    /**
     * 按当前登录租户自动解析唯一启用的订货宝连接器，再执行同步。
     * connectorId只在Order Center内部流转，Portal无需感知或选择连接器。
     */
    public DhbOrderSyncResult run(DhbOrderSyncCommand command) {
        CallerIdentity caller = requireCaller(true);
        List<SyncTargetView> targets = targetsFor(caller);
        if (targets.isEmpty()) {
            throw new AuthorizationDeniedException("integration:dhb:sync-target-not-found");
        }
        if (targets.size() > 1) {
            throw new IllegalStateException("当前租户存在多个启用的订货宝连接器，无法自动确定同步目标");
        }
        return syncService.run(targets.getFirst().connectorId(), command);
    }

    /**
     * 兼容旧调用方接收Portal传入的connectorId，但不直接信任；先通过Integration sync-targets校验任务归属和启用状态。
     * 新的Portal入口应使用无connectorId的run(command)。
     */
    public DhbOrderSyncResult run(UUID connectorId, DhbOrderSyncCommand command) {
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        CallerIdentity caller = requireCaller(true);
        boolean available = targetsFor(caller).stream()
                .anyMatch(target -> connectorId.equals(target.connectorId()));
        if (!available) {
            throw new AuthorizationDeniedException("integration:dhb:sync-target");
        }
        return syncService.run(connectorId, command);
    }

    private List<SyncTargetView> targetsFor(CallerIdentity caller) {
        return targetDiscoveryClient.discover(serviceCaller(caller.tenantId())).stream()
                .filter(target -> target != null && caller.tenantId().equals(target.tenantId())
                        && target.connectorId() != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(SyncTargetView::connectorId, target -> target,
                                (first, ignored) -> first, LinkedHashMap::new),
                        values -> List.copyOf(values.values())));
    }

    private static CallerIdentity requireCaller(boolean write) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-caller");
        }
        AuthorizationContext.requirePermission("integration:dhb:read");
        if (write) AuthorizationContext.requirePermission("integration:dhb:write");
        return caller;
    }

    private static CallerIdentity serviceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_SYNC_SERVICE"), SERVICE_PERMISSIONS);
    }
}
