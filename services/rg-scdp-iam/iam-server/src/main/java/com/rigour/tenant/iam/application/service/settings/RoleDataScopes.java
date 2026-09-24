package com.rigour.tenant.iam.application.service.settings;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.*;
import com.rigour.tenant.iam.domain.model.settings.AppScopeRules;

import java.util.*;

/** 页面只选择一次范围；后端按实际授予的动作生成完整条件。 */
public final class RoleDataScopes {
    private RoleDataScopes() {}

    public static void validate(RoleDataScope scope) {
        if (scope == null
                || scope.mode() == null
                || !Set.of("ALL", "CUSTOM", "DEPARTMENT", "SELF").contains(scope.mode()))
            throw new RequestValidationException("请选择全部数据、选择部门、本部门或本人数据");
        var ids = scope.departmentIds();
        if (ids == null
                || ids.size() > 1000
                || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]*")))
            throw new RequestValidationException("部门选择无效");
        if ("CUSTOM".equals(scope.mode()) == ids.isEmpty())
            throw new RequestValidationException(
                    "CUSTOM".equals(scope.mode()) ? "请至少选择一个部门" : "当前数据范围不应包含指定部门");
    }

    public static ScopeRule rule(RoleDataScope scope, String action) {
        String object = AppScopeRules.objectType(action);
        if (object == null) return null;
        String department =
                switch (scope.mode()) {
                    case "CUSTOM" -> "SPECIFIED";
                    case "DEPARTMENT" -> "CURRENT";
                    default -> "ALL";
                };
        String mode = "CUSTOM".equals(scope.mode()) ? "DEPARTMENT" : scope.mode();
        return new ScopeRule(
                null,
                action,
                object,
                mode,
                department,
                "ALL",
                "ALL",
                false,
                "SPECIFIED".equals(department)
                        ? Map.of("DEPARTMENT", List.copyOf(scope.departmentIds()))
                        : Map.of());
    }
}
