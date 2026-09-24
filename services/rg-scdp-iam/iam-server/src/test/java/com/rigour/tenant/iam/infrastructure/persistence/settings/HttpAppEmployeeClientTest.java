package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.rigour.platform.http.ServiceAddressResolver;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class HttpAppEmployeeClientTest {
    @Test
    void chineseEmployeeSearchSurvivesDiscoveryAndTrustedContextVerification() throws Exception {
        var trust = new ContextTrustProperties();
        trust.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var signer = new TrustedContextSigner(trust);
        UUID tenant = UUID.randomUUID();
        var receivedQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/hr/employee-identities", exchange -> {
            var request = new MockHttpServletRequest("GET", exchange.getRequestURI().getRawPath());
            request.setQueryString(exchange.getRequestURI().getRawQuery());
            exchange.getRequestHeaders().forEach((k, v) -> request.addHeader(k, v.getFirst()));
            boolean valid = signer.verify(request)
                    && tenant.toString().equals(request.getHeader(RequestHeaders.TENANT_ID))
                    && "hr:employee:identity-read".equals(request.getHeader(RequestHeaders.PERMISSIONS));
            receivedQuery.set(URLDecoder.decode(request.getQueryString(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"code":"OK","data":{"total":1,"begin":0,"step":20,"items":[
                    {"id":1,"employeeCode":"E1","employeeName":"赵测试","usable":true,
                    "employeeRevision":1,"organizationVersion":1,"accessVersion":1}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(valid ? 200 : 401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var discovery = mock(DiscoveryClient.class);
            when(discovery.getInstances("rigour-hr-payroll-service")).thenReturn(List.of(
                    new DefaultServiceInstance("hr", "rigour-hr-payroll-service", "127.0.0.1",
                            server.getAddress().getPort(), false)));
            var beans = new StaticListableBeanFactory(Map.of("discovery", discovery));
            var resolver = new ServiceAddressResolver(beans.getBeanProvider(DiscoveryClient.class),
                    beans.getBeanProvider(LoadBalancerClient.class));
            var client = new HttpAppEmployeeClient(signer, "http://rigour-hr-payroll-service",
                    RestClient.builder().requestInterceptor(resolver));

            var page = client.search(tenant, "赵", 0, 20);

            assertThat(receivedQuery.get()).isEqualTo("keyword=赵&begin=0&step=20");
            assertThat(page.items()).singleElement().satisfies(e ->
                    assertThat(e.employeeName()).isEqualTo("赵测试"));
        } finally {
            server.stop(0);
        }
    }
}
