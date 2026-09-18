package com.rigour.hr.application.port.out;

/** 解析已认证操作人的显示名称；账号 ID 始终是审计主体。 */
public interface HrAuditActorNameResolver {
    String resolve(String tenantId, String actorId);
}
