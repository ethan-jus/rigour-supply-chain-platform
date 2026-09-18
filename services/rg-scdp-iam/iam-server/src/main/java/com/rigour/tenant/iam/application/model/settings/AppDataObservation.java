package com.rigour.tenant.iam.application.model.settings;

/** 领域记录的候选数据决定，绑定原操作人与各层授权版本。 */
public record AppDataObservation(
        String action,
        String domain,
        String recordKey,
        long applicationVersion,
        long memberVersion,
        long employeeRevision,
        long organizationVersion,
        boolean legacyAllowed,
        boolean proposedAllowed) {}
