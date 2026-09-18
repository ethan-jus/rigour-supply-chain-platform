package com.rigour.tenant.iam.application.model.settings;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 当前动作的应用授权快照；每个子句保持完整，消费方按自己的业务事实执行过滤。 */
public record AppAuthorizationSnapshot(
        String mode,
        UUID tenantId,
        UUID userId,
        String employeeCode,
        long applicationVersion,
        long memberVersion,
        long employeeRevision,
        long organizationVersion,
        Set<String> permissions,
        String action,
        boolean functionAllowed,
        List<Clause> clauses,
        Limit regionLimit,
        Limit warehouseLimit) {
    public AppAuthorizationSnapshot {
        permissions = Set.copyOf(permissions);
        clauses = List.copyOf(clauses);
    }

    public record Limit(String mode, List<String> references) {
        public Limit {
            references = List.copyOf(references);
        }
    }

    public record Clause(
            UUID roleId,
            String objectType,
            String scopeMode,
            Limit departments,
            Limit regions,
            Limit warehouses,
            boolean includeDescendants) {}
}
