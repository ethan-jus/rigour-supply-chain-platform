package com.rigour.tenant.iam.api.v1.model;

/** 数据决定由记录所属服务实际执行过滤得到，IAM再次校验策略版本；不参与业务放行。 */
public record SupplyDataObservation(
        String action,
        String domain,
        String recordKey,
        long applicationVersion,
        long memberVersion,
        long employeeRevision,
        long organizationVersion,
        boolean legacyAllowed,
        boolean proposedAllowed) {}
