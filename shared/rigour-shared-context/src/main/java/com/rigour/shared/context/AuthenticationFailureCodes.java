package com.rigour.shared.context;

/**
 * 跨Gateway和领域服务共享的认证失败分类。
 *
 * <p>这些值是浏览器可见的稳定协议，只表达处置语义，不暴露签名、Token或内部异常细节。</p>
 */
public final class AuthenticationFailureCodes {

    public static final String IAM_TOKEN_INVALID = "IAM_TOKEN_INVALID";
    public static final String IAM_FORBIDDEN = "IAM_FORBIDDEN";
    public static final String IAM_SESSION_CHECK_UNAVAILABLE = "IAM_SESSION_CHECK_UNAVAILABLE";
    public static final String TRUSTED_CONTEXT_INVALID = "TRUSTED_CONTEXT_INVALID";

    private AuthenticationFailureCodes() {
    }
}
