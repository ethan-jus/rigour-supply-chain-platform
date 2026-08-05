package com.rigour.order.application.service.dhb;

import com.rigour.order.api.v1.model.DhbOrderImportResult;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Portal 发起的订货宝订单域同步用例；Order Center 是业务编排和业务落库入口。 */
@Service
public final class DhbOrderSyncService {
    private final DhbOrderSyncClient integration;
    private final DhbOrderImportService importer;

    public DhbOrderSyncService(DhbOrderSyncClient integration, DhbOrderImportService importer) {
        this.integration = integration;
        this.importer = importer;
    }

    /**
     * 执行“Order Center → Integration → 订货宝 → Order Center本地库”的一期同步链路。
     *
     * @param connectorId 当前租户在 Integration 配置的连接器 UUID
     * @param command includeDetails 默认 true；maxPages 默认 100、范围 1..100，可选更新时间窗口
     * @return 供应商拉取数量和本地实际新增/变化数量
     */
    public DhbOrderSyncResult run(UUID connectorId, DhbOrderSyncCommand command) {
        CallerIdentity caller = requireCaller();
        if (connectorId == null) throw new IllegalArgumentException("connectorId不能为空");
        DhbOrderSyncCommand effective = command == null ? new DhbOrderSyncCommand(null, null) : command;
        DhbOrderSyncClient.Collected collected = integration.collect(caller, connectorId, effective);
        DhbOrderImportResult imported = importer.importBatch(caller.tenantId().toString(), collected.batch());
        return new DhbOrderSyncResult(collected.runId(), collected.objectType(), "SUCCEEDED",
                collected.fetched(), imported.totalChanged(), imported.orders(), imported.shipments(),
                imported.returns(), imported.financialDocuments(), collected.completedObjects());
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
}
