package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 真实 HTTP 验证签名、租户、检查契约与旧版本拒绝，不连接共享 DEV。 */
class HttpAppReadinessClientTest {
    @Test
    void allDomainsAreSignedAndAnOldDomainBlocksActivation() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        UUID tenant = UUID.randomUUID();
        var servers = new ArrayList<HttpServer>();
        var env = new MockEnvironment();
        var invalid = new AtomicInteger();
        var oldHr = new AtomicBoolean();
        try {
            for (String domain : List.of("hr", "crm", "erp", "order", "bi", "settings")) {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                servers.add(server);
                server.createContext(
                        "/internal/v1/supply/readiness",
                        exchange -> {
                            var request =
                                    new MockHttpServletRequest(
                                            "GET", exchange.getRequestURI().getRawPath());
                            request.setQueryString(exchange.getRequestURI().getRawQuery());
                            exchange.getRequestHeaders()
                                    .forEach((k, v) -> request.addHeader(k, v.getFirst()));
                            boolean valid =
                                    signer.verify(request)
                                            && tenant.toString()
                                                    .equals(
                                                            request.getHeader(
                                                                    RequestHeaders.TENANT_ID))
                                            && "SERVICE"
                                                    .equals(
                                                            request.getHeader(
                                                                    RequestHeaders.PRINCIPAL_SCOPE))
                                            && "supply:readiness:read"
                                                    .equals(
                                                            request.getHeader(
                                                                    RequestHeaders.PERMISSIONS));
                            if (!valid) invalid.incrementAndGet();
                            String body =
                                    "{\"domain\":\""
                                            + domain
                                            + "\",\"contractVersion\":"
                                            + ((domain.equals("hr") && oldHr.get()) ? 0 : 1)
                                            + ",\"version\":\"schema-v1\",\"checks\":[]}";
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(valid ? 200 : 403, bytes.length);
                            exchange.getResponseBody().write(bytes);
                            exchange.close();
                        });
                server.start();
                env.setProperty(
                        "rigour.iam." + domain + "-base-url",
                        "http://127.0.0.1:" + server.getAddress().getPort());
            }
            var client = new HttpAppReadinessClient(signer, env);
            assertThat(client.inspect(tenant)).hasSize(6).allMatch(d -> d.issues().isEmpty());
            assertThat(invalid.get()).isZero();
            oldHr.set(true);
            assertThat(client.inspect(tenant))
                    .filteredOn(d -> d.domain().equals("hr"))
                    .allMatch(
                            d ->
                                    d.issues().stream()
                                            .anyMatch(i -> i.severity().equals("BLOCKING")));
        } finally {
            servers.forEach(s -> s.stop(0));
        }
    }
}
