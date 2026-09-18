package com.rigour.tenant.iam.client;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

/** 原操作人身份透传给 IAM；禁止将业务请求替换成具有全局权限的服务账号。 */
public interface SupplyAuthorizationClient {
    SupplyAuthorizationView authorization(CallerIdentity caller, String action);

    default SupplyAuthorizationView candidate(CallerIdentity caller, String action) {
        throw new UnsupportedOperationException("候选数据策略不可用");
    }

    default void observeData(
            CallerIdentity caller,
            com.rigour.tenant.iam.api.v1.model.SupplyDataObservation observation) {
        throw new UnsupportedOperationException("数据对比记录不可用");
    }

    default void observe(CallerIdentity caller, String action, String legacyAction) {
        throw new UnsupportedOperationException("授权客户端未提供对比记录能力");
    }
}
