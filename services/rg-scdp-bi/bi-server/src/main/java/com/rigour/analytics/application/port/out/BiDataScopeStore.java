package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** BI 授权投影与审计端口；不跨库查询身份，不为账号自动授权。 */
public interface BiDataScopeStore {
    default void compareObject(
            String tenant, String city, String employee, String action, boolean oldAllowed) {
        throw new UnsupportedOperationException("数据对比适配器不可用");
    }

    boolean appObjectVisible(String tenantId, String city, String employee, String action);

    Optional<Identity> identity(String tenantId, String userId);

    List<Grant> grants(String tenantId, String userId);

    void replace(
            String tenantId, String userId, Identity identity, List<Grant> grants, String actorId);

    void revoke(String tenantId, String userId, String actorId, String reason);

    boolean renewIfUnchanged(
            String tenantId, String userId, Identity previous, Identity refreshed, String actorId);

    SupplyDashboardFilterOptionsView filterOptions(
            String tenantId, List<String> regions, String ownerStaffCode);

    record Identity(
            String employeeCode,
            String ownerStaffCode,
            String iamBindingRef,
            String hrEmployeeRef,
            String crmEmployeeRef,
            long userSecurityVersion,
            long tenantPolicyVersion,
            Instant verifiedAt,
            Instant expiresAt) {}

    record Grant(String roleCode, String scopeType, String regionCode, String iamPolicyRef) {}
}
