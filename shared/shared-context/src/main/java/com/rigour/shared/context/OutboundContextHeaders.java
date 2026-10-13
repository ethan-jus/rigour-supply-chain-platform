package com.rigour.shared.context;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 出站请求显式选择原调用人或最小服务身份；签名绑定方法、路径和查询串。 */
public final class OutboundContextHeaders {
    private OutboundContextHeaders() {}

    public static Map<String, String> caller(
            TrustedContextSigner signer, String method, URI uri, CallerIdentity a) {
        var h = new LinkedHashMap<String, String>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, a.principalScope());
        h.put(RequestHeaders.PRINCIPAL_ID, a.principalId().toString());
        if (a.tenantId() != null) h.put(RequestHeaders.TENANT_ID, a.tenantId().toString());
        if (a.userId() != null) h.put(RequestHeaders.USER_ID, a.userId().toString());
        if (a.platformUserId() != null)
            h.put(RequestHeaders.PLATFORM_USER_ID, a.platformUserId().toString());
        h.put(RequestHeaders.SESSION_ID, a.sessionId().toString());
        h.put(RequestHeaders.SESSION_VERSION, Long.toString(a.sessionVersion()));
        h.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(a.userSecurityVersion()));
        h.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(a.tenantPolicyVersion()));
        h.put(RequestHeaders.ROLES, String.join(",", a.roles()));
        h.put(RequestHeaders.PERMISSIONS, String.join(",", a.permissions()));
        var signature = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        return h;
    }

    public static Map<String, String> service(
            TrustedContextSigner signer,
            String method,
            URI uri,
            String service,
            String tenant,
            String permission) {
        var a =
                new CallerIdentity(
                        "SERVICE",
                        UUID.nameUUIDFromBytes(
                                ("service:" + service).getBytes(StandardCharsets.UTF_8)),
                        UUID.fromString(tenant),
                        null,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(permission));
        return caller(signer, method, uri, a);
    }
}
