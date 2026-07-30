package com.rigour.tenant.iam.api.v1.model;

import java.util.UUID;

/** 一个应用内的允许型DataScope；scopeRef仅在该scopeType需要固定引用时存在。 */
public record IamDataScopeGrant(
        String applicationCode,
        String scopeType,
        UUID scopeRef
) {
}
