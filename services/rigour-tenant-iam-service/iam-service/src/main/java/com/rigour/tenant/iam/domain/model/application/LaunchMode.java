package com.rigour.tenant.iam.domain.model.application;

/** 应用启动模式受控枚举，与`iam_application.launch_mode`及V6种子保持一致。 */
public enum LaunchMode {
    INTERNAL_ROUTE,
    EXTERNAL_URL,
    FEISHU_DEEPLINK,
    SSO_PROVIDER
}
