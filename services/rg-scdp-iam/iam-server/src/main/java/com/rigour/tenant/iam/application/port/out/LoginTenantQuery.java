package com.rigour.tenant.iam.application.port.out;

/** 登录页公开展示的默认企业名称，不返回账号或授权信息。 */
public interface LoginTenantQuery {
    String name(String tenantCode);
}
