package com.rigour.integration.domain.thirdparty.oauth;

/** 只携带已归类的业务错误，不能附加原始响应、URL或底层异常消息。 */
public final class OAuthFailure extends IllegalStateException {
    public enum Kind {
        TEMPORARY,
        REAUTHORIZE,
        UNCERTAIN
    }

    private final Kind kind;
    private final String code;

    public OAuthFailure(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
