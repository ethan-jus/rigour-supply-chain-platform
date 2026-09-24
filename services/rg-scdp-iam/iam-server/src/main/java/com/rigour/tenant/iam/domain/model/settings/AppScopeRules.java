package com.rigour.tenant.iam.domain.model.settings;

import com.rigour.shared.core.exception.RequestValidationException;

import java.util.Set;

/** 固定业务维度契约：管理员不能移除某一业务动作的必需范围。 */
public final class AppScopeRules {
    private AppScopeRules() {}

    public static final Set<String> DIMENSIONS = Set.of("DEPARTMENT", "REGION", "WAREHOUSE");

    public static String objectType(String action) {
        if (action == null) return null;
        return action.startsWith("crm:")
                ? "CUSTOMER"
                : action.startsWith("hr:")
                        ? "EMPLOYEE"
                        : action.startsWith("erp:")
                                ? "INVENTORY"
                                : (action.startsWith("bi:") || action.startsWith("analytics:"))
                                        ? "ANALYTICS"
                                        : action.contains("outbound")
                                                        || action.contains("stock-out")
                                                ? "FULFILLMENT"
                                                : action.startsWith("order:") ? "ORDER" : null;
    }

    public static void validate(
            String action,
            String object,
            String scope,
            String department,
            String region,
            String warehouse) {
        if (action == null || action.isBlank()) throw new RequestValidationException("规则缺少动作权限");
        if (!Set.of("CUSTOMER", "ORDER", "EMPLOYEE", "INVENTORY", "FULFILLMENT", "ANALYTICS")
                .contains(object)) throw new RequestValidationException("未知业务对象");
        if (!Set.of("NONE", "SELF", "DEPARTMENT", "REGION", "WAREHOUSE", "ALL").contains(scope))
            throw new RequestValidationException("未知数据范围");
        if (!Set.of("NONE", "CURRENT", "MANAGED", "SPECIFIED", "ALL").contains(department))
            throw new RequestValidationException("未知部门范围");
        if (!Set.of("NONE", "MEMBER", "SPECIFIED", "ALL").contains(region)
                || !Set.of("NONE", "MEMBER", "SPECIFIED", "ALL").contains(warehouse))
            throw new RequestValidationException("未知地区或仓库范围");
        String expected = objectType(action);
        if (expected == null || !expected.equals(object))
            throw new RequestValidationException("动作与业务对象不匹配");
        if ("NONE".equals(scope)) return;
        if ("CUSTOMER".equals(object)
                && (!Set.of("SELF", "DEPARTMENT", "REGION", "ALL").contains(scope)
                        || !Set.of("NONE", "ALL").contains(warehouse)))
            throw new RequestValidationException("客户范围支持本人、部门及归属地区，不使用仓库作为客户范围");
        if ("EMPLOYEE".equals(object)
                && (!Set.of("SELF", "DEPARTMENT", "ALL").contains(scope)
                        || !Set.of("NONE", "ALL").contains(region)
                        || !Set.of("NONE", "ALL").contains(warehouse)))
            throw new RequestValidationException("员工范围按本人或部门配置");
        if ("CUSTOMER".equals(object) && "NONE".equals(region))
            throw new RequestValidationException("客户操作必须配置地区范围");
        if (Set.of("INVENTORY", "FULFILLMENT").contains(object) && "NONE".equals(warehouse))
            throw new RequestValidationException("库存与履约操作必须配置仓库范围");
        if ("DEPARTMENT".equals(scope) && "NONE".equals(department))
            throw new RequestValidationException("请配置部门范围");
        if ("REGION".equals(scope) && "NONE".equals(region))
            throw new RequestValidationException("请配置地区范围");
        if ("WAREHOUSE".equals(scope) && "NONE".equals(warehouse))
            throw new RequestValidationException("请配置仓库范围");
    }
}
