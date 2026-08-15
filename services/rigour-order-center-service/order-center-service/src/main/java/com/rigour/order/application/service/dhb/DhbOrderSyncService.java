package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.model.DhbOrderImportResult;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbOrderSyncCheckpointStore;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Order Center编排订货宝订单域同步，并负责业务落库。 */
@Service
public final class DhbOrderSyncService {
    private final DhbOrderSyncClient integration;
    private final DhbOrderImportService importer;
    private final DhbOrderSyncCheckpointStore checkpointStore;
    private final Clock clock;
    private final OrderDictionaryCoverageService dictionaryCoverage;
    private final ConnectorSyncLeaseClient connectorLease;

    public DhbOrderSyncService(DhbOrderSyncClient integration, DhbOrderImportService importer,
                               DhbOrderSyncCheckpointStore checkpointStore, Clock clock,
                               OrderDictionaryCoverageService dictionaryCoverage,
                               ConnectorSyncLeaseClient connectorLease) {
        this.integration = integration;
        this.importer = importer;
        this.checkpointStore = checkpointStore;
        this.clock = clock;
        this.dictionaryCoverage = dictionaryCoverage;
        this.connectorLease = connectorLease;
    }

    /** 前端立即同步入口的应用用例；认证和权限来自Gateway签名上下文。 */
    public DhbOrderSyncResult run(UUID connectorId, DhbOrderSyncCommand command) {
        CallerIdentity caller = requireCaller();
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        Instant checkpointAt = command != null && command.updatedTo() != null
                ? command.updatedTo() : clock.instant();
        try {
            DhbOrderSyncResult result = runWithCaller(caller, connectorId, command);
            if (result.completedObjects().contains("ORDER")) {
                checkpointStore.markSucceeded(caller.tenantId().toString(), connectorId,
                        "ORDER", result.runId(), checkpointAt);
            }
            return result;
        } catch (RuntimeException error) {
            checkpointStore.markFailed(caller.tenantId().toString(), connectorId,
                    "ORDER", null, error.getMessage());
            throw error;
        }
    }

    /**
     * 供Order Center内部定时编排器调用的同步入口。
     *
     * <p>该入口仍要求任务配置生成的租户身份带有订货宝读写权限，但不依赖
     * 定时线程不存在的HTTP ThreadLocal上下文。</p>
     */
    public DhbOrderSyncResult runScheduled(CallerIdentity caller, UUID connectorId,
                                            DhbOrderSyncCommand command) {
        requireScheduledCaller(caller);
        return runWithCaller(caller, connectorId, command);
    }

    private DhbOrderSyncResult runWithCaller(CallerIdentity caller, UUID connectorId,
                                             DhbOrderSyncCommand command) {
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        return connectorLease.execute(caller.tenantId(), connectorId,
                () -> runUnderLease(caller, connectorId, command));
    }

    private DhbOrderSyncResult runUnderLease(CallerIdentity caller, UUID connectorId,
                                              DhbOrderSyncCommand command) {
        DhbOrderSyncCommand effective = command == null ? new DhbOrderSyncCommand(null, null) : command;
        DhbOrderSyncClient.Collected collected = integration.collect(caller, connectorId, effective);
        String tenantId = caller.tenantId().toString();
        DhbOrderImportResult imported = importer.importBatchInternal(tenantId, collected.batch());
        Audit dictionaryAudit = dictionaryCoverage.sync(caller.tenantId(), collected.objectType(), collected.batch());
        String status = dictionaryAudit.unmapped() == 0 ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS";
        return new DhbOrderSyncResult(collected.runId(), collected.objectType(), status,
                collected.fetched(), imported.totalChanged(), imported.orders(), imported.shipments(),
                imported.shipmentLogistics(), imported.returns(), imported.financialDocuments(),
                collected.completedObjects(), dictionaryAudit.unmapped(), dictionaryAudit.revisions());
    }

    private static CallerIdentity requireCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-caller");
        }
        AuthorizationContext.requirePermission("integration:dhb:read");
        AuthorizationContext.requirePermission("integration:dhb:write");
        return Objects.requireNonNull(caller);
    }

    private static void requireScheduledCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null
                || !("SERVICE".equals(caller.principalScope())
                || ("TENANT".equals(caller.principalScope()) && caller.userId() != null))) {
            throw new AuthorizationDeniedException("tenant-caller");
        }
        if (!caller.permissions().contains("integration:dhb:read")
                && !caller.permissions().contains("*:*:*")) {
            throw new AuthorizationDeniedException("integration:dhb:read");
        }
        if (!caller.permissions().contains("integration:dhb:write")
                && !caller.permissions().contains("*:*:*")) {
            throw new AuthorizationDeniedException("integration:dhb:write");
        }
    }
}
