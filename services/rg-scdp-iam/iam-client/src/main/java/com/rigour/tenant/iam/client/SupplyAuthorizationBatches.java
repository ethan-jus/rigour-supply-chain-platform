package com.rigour.tenant.iam.client;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import java.util.*;

/** 先验证完整批响应，再发布到本请求；不接受缺动作、拼接版本或其他主体的策略。 */
final class SupplyAuthorizationBatches {
    private SupplyAuthorizationBatches() {}

    static List<String> actions(Collection<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 32)
            throw new IllegalArgumentException("一次授权查询需要1至32个动作");
        for (String action : requested)
            if (action == null
                    || action.length() > 120
                    || !action.matches("[a-z][a-z0-9-]*(?::[a-z0-9_-]+){1,3}"))
                throw new IllegalArgumentException("业务动作编码无效");
        return List.copyOf(new LinkedHashSet<>(requested));
    }

    static Map<String, SupplyAuthorizationView> verify(
            CallerIdentity caller,
            Collection<String> requested,
            List<SupplyAuthorizationView> response) {
        var expected = actions(requested);
        if (caller == null
                || !"TENANT".equals(caller.principalScope())
                || caller.tenantId() == null
                || caller.userId() == null
                || !Objects.equals(caller.principalId(), caller.userId())) throw inconsistent();
        if (response == null || response.size() != expected.size()) throw inconsistent();
        Map<String, SupplyAuthorizationView> verified = new LinkedHashMap<>();
        SupplyAuthorizationView first = null;
        for (var result : response) {
            if (result == null
                    || !Objects.equals(caller.tenantId(), result.tenantId())
                    || !Objects.equals(caller.userId(), result.userId())
                    || !"ACTIVE".equals(result.mode())
                    || !expected.contains(result.action())
                    || verified.put(result.action(), result) != null
                    || result.functionAllowed() != result.permissions().contains(result.action()))
                throw inconsistent();
            if (first != null
                    && (!sameVersions(first, result)
                            || !Objects.equals(first.employeeCode(), result.employeeCode())
                            || !first.permissions().equals(result.permissions())))
                throw inconsistent();
            first = result;
        }
        return verified;
    }

    static boolean sameVersions(SupplyAuthorizationView left, SupplyAuthorizationView right) {
        return left.applicationVersion() == right.applicationVersion()
                && left.memberVersion() == right.memberVersion()
                && left.employeeRevision() == right.employeeRevision()
                && left.organizationVersion() == right.organizationVersion();
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException("IAM 批量授权上下文不完整或不一致");
    }
}
