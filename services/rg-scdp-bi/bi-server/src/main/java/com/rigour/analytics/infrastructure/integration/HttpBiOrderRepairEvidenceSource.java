package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.application.port.out.BiOrderRepairEvidenceSource;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.Evidence;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.EvidencePage;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Forwards the actual operator scope; never grants BI users Order administrator access. */
@Component
public class HttpBiOrderRepairEvidenceSource implements BiOrderRepairEvidenceSource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final URI uri;

    @Autowired
    public HttpBiOrderRepairEvidenceSource(TrustedContextSigner signer,
            @Value("${rigour.order.base-url:${RIGOUR_ORDER_BASE_URL:http://localhost:26885}}") String baseUrl) {
        this(builder(), signer, baseUrl);
    }

    HttpBiOrderRepairEvidenceSource(RestClient.Builder builder, TrustedContextSigner signer, String baseUrl) {
        URI base = URI.create(baseUrl);
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid Order base URL");
        this.uri = UriComponentsBuilder.fromUriString(baseUrl.replaceAll("/+$", ""))
                .path("/api/v1/orders/sales/product-repair-evidence")
                .queryParam("afterLineId", 0).queryParam("limit", 20000).build().toUri();
        this.client = builder.build(); this.signer = signer;
    }

    @Override public List<Evidence> current(CallerIdentity actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null)
            throw new IllegalStateException("Missing tenant operator");
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, actor.principalScope());
        headers.put(RequestHeaders.PRINCIPAL_ID, actor.principalId().toString());
        headers.put(RequestHeaders.TENANT_ID, actor.tenantId().toString());
        headers.put(RequestHeaders.USER_ID, actor.userId().toString());
        headers.put(RequestHeaders.SESSION_ID, actor.sessionId().toString());
        headers.put(RequestHeaders.SESSION_VERSION, Long.toString(actor.sessionVersion()));
        headers.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(actor.userSecurityVersion()));
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(actor.tenantPolicyVersion()));
        headers.put(RequestHeaders.ROLES, String.join(",", actor.roles()));
        headers.put(RequestHeaders.PERMISSIONS, String.join(",", actor.permissions()));
        var signature = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        var response = client.get().uri(uri).headers(h -> headers.forEach(h::set)).retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<EvidencePage>>() { });
        if (response == null || !"OK".equals(response.code()) || response.data() == null
                || response.data().items() == null || response.data().hasMore()
                || response.data().items().size() > 20000)
            throw new IllegalStateException("Order repair evidence is incomplete");
        return List.copyOf(response.data().items());
    }

    private static RestClient.Builder builder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }
}
