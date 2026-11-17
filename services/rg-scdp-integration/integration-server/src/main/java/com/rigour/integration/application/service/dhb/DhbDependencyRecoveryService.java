package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbDependencyRecoveryCommand;
import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** 人工补录后的显式来源关联；不新增运输、不确认费用、不再次扣库存。 */
@Service
public class DhbDependencyRecoveryService {
    private final DhbIntegrationStore store;
    private final DhbSyncStore mappings;
    private final DhbDependencyTargets targets;
    private final DhbOrchestrationLease lease;

    public DhbDependencyRecoveryService(
            DhbIntegrationStore store,
            DhbSyncStore mappings,
            DhbDependencyTargets targets,
            DhbOrchestrationLease lease) {
        this.store = store;
        this.mappings = mappings;
        this.targets = targets;
        this.lease = lease;
    }

    public void link(DhbDependencyRecoveryCommand c) {
        var actor = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("integration:dhb:write");
        if (actor.tenantId() == null
                || actor.userId() == null
                || !"TENANT".equals(actor.principalScope()))
            throw new AuthorizationDeniedException("manual-dependency-user-only");
        if (c == null
                || c.connectorId() == null
                || c.targetId() == null
                || c.targetId() <= 0
                || c.sourceId() == null
                || c.sourceId().isBlank()
                || c.sourceId().length() > 128
                || !Set.of("SALES_SHIPMENT", "ERP_STOCK_OUT")
                        .contains(Objects.toString(c.sourceObjectType(), "")))
            throw bad("请选择待处理发货及已补录资料");
        lease.execute(
                actor.tenantId(),
                c.connectorId(),
                "DHB_MANUAL_DEPENDENCY_RECOVERY",
                () -> {
                    linkLocked(actor, c);
                    return true;
                });
    }

    private void linkLocked(CallerIdentity actor, DhbDependencyRecoveryCommand c) {
        var issue =
                store.openException(
                        actor.tenantId(), c.connectorId(), c.sourceObjectType(), c.sourceId());
        var run = issue == null ? null : store.syncRun(actor.tenantId(), issue.runId());
        if (run == null || !c.connectorId().equals(run.connectorId()))
            throw bad("待处理记录已变化或不属于当前来源，请刷新");
        var detail =
                store.openReconciliation(
                        actor.tenantId(), issue.runId(), c.sourceObjectType(), c.sourceId());
        var needed =
                DhbDependencyRequirement.from(
                        issue.lastErrorCode(),
                        detail == null ? null : detail.expectedValueJson(),
                        detail == null ? null : detail.actualValueJson());
        if (needed == null) throw bad("当前问题需先核对原资料，不能直接改绑");
        if (!needed.type().equals(c.requiredDataType())
                || !needed.sourceId().equals(c.requiredSourceId())) throw bad("缺失资料已变化，请刷新后重新选择");
        if ("PRODUCT_SKU".equals(needed.type()) && (c.productId() == null || c.productId() <= 0))
            throw bad("请选择商品及规格");
        var target = targets.require(actor, needed.type(), c.targetId(), c.productId());
        var existing =
                mappings.findActiveMapping(
                        actor.tenantId(), c.connectorId(), needed.type(), needed.sourceId());
        if (existing != null) {
            if (Objects.equals(target.id(), existing.internalObjectId())
                    && target.domain().equals(existing.internalDomain())
                    && target.objectType().equals(existing.internalObjectType())) return;
            throw bad("来源已关联其他资料，请先核对，不能覆盖现有关系");
        }
        if (mappings.dependencyAutoSyncBlocked(
                actor.tenantId(),
                c.connectorId(),
                List.of(needed.type()),
                List.of(needed.sourceId()))) throw bad("来源关联存在冲突或已被人工移除，请先核对");
        mappings.upsertExternalObjectMapping(
                actor.tenantId(),
                actor.userId(),
                new DhbSyncStore.ExternalObjectMappingWrite(
                        c.connectorId(),
                        needed.type(),
                        needed.sourceId(),
                        needed.sourceId(),
                        target.domain(),
                        target.objectType(),
                        target.id(),
                        target.code(),
                        "ACTIVE",
                        issue.runId(),
                        Instant.now(),
                        null,
                        null,
                        "发货单 " + c.sourceId() + " 的缺失资料由业务补录后人工关联"));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
