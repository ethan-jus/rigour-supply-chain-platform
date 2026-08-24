package com.rigour.integration.client;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingBatchCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingBatchResult;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingCommand;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** 领域服务登记外部对象到我方新业务对象映射的内部客户端。 */
public final class ExternalObjectMappingClient {
    private static final String WRITE_PERMISSION = "integration:dhb:write";

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI uri;
    private final String serviceName;

    public ExternalObjectMappingClient(RestClient.Builder builder, TrustedContextSigner signer,
                                       String baseUrl, String serviceName) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.uri = URI.create(baseUrl.strip().replaceAll("/+$", "")
                + DhbIntegrationInternalApi.OBJECT_MAPPINGS_PATH);
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName不能为空");
        }
        this.serviceName = serviceName.strip();
    }

    public ExternalObjectMappingBatchResult upsert(UUID tenantId,
                                                   List<ExternalObjectMappingCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new ExternalObjectMappingBatchResult(0, 0);
        }
        CallerIdentity caller = serviceCaller(tenantId);
        return restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(new ExternalObjectMappingBatchCommand(commands))
                .retrieve()
                .body(ExternalObjectMappingBatchResult.class);
    }

    private CallerIdentity serviceCaller(UUID tenantId) {
        UUID serviceId = UUID.nameUUIDFromBytes(("service:" + serviceName).getBytes(StandardCharsets.UTF_8));
        return new CallerIdentity("SERVICE", serviceId, tenantId, null, null, UUID.randomUUID(),
                0, 0, 0, Set.of("DHB_SYNC_MAPPING_CLIENT"), Set.of(WRITE_PERMISSION));
    }

    private Map<String, String> signedHeaders(String method, URI targetUri, CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        TrustedContextSigner.SignedContext signed = signer.sign(
                method, targetUri.getRawPath(), targetUri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private static void put(Map<String, String> headers, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            headers.put(name, String.valueOf(value));
        }
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
