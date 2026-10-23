package com.rigour.shared.context;

/** 请求期间授权快照已变化；必须中止本次操作，由调用方重新获取当前授权。 */
public final class AuthorizationStateChangedException extends IllegalStateException {
    public AuthorizationStateChangedException() {
        super("权限配置已更新，请重试当前操作");
    }
}
