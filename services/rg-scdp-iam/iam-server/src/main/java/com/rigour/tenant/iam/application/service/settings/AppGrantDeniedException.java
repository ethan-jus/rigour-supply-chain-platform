package com.rigour.tenant.iam.application.service.settings;

import org.springframework.security.access.AccessDeniedException;

import java.util.List;

/** 仅承载可向授权操作者展示的功能或数据范围拒绝原因。 */
public final class AppGrantDeniedException extends AccessDeniedException {
    private final List<String> missingPermissions;

    private AppGrantDeniedException(String message, List<String> missingPermissions) {
        super(message);
        this.missingPermissions = List.copyOf(missingPermissions);
    }

    public static AppGrantDeniedException functions(List<String> permissions) {
        var missing = permissions.stream().distinct().sorted().toList();
        return new AppGrantDeniedException(
                "当前账号不能授予未拥有的功能：" + String.join("、", missing), missing);
    }

    public static AppGrantDeniedException dataScope() {
        return new AppGrantDeniedException("待授予的数据范围超出当前账号的授权范围", List.of());
    }

    public List<String> missingPermissions() {
        return missingPermissions;
    }
}
