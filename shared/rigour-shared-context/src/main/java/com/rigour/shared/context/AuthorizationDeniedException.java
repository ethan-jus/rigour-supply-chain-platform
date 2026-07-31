package com.rigour.shared.context;

/** 领域接口缺少必需功能权限；统一异常层必须将其返回为403。 */
public final class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException(String permission) {
        super("Permission denied: " + permission);
    }
}
