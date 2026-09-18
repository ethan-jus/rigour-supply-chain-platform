package com.rigour.hr.infrastructure.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class HttpHrAuditActorNameResolverTest {
    @Test
    void resolvesVerifiedCurrentUserAndNeverAcceptsAnotherTenantOrUser() throws Exception {
        var user = UUID.randomUUID(); var tenant = UUID.randomUUID();
        var header = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/me", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = "{\"id\":\""+user+"\",\"tenantId\":\""+tenant+"\",\"principalScope\":\"TENANT\",\"username\":\"admin\",\"displayName\":\"测试管理员\",\"roles\":[],\"permissions\":[]}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var resolver = new HttpHrAuditActorNameResolver("http://127.0.0.1:"+server.getAddress().getPort());
            assertThat(resolver.resolve(tenant.toString(),user.toString())).isNull();
            var request = new MockHttpServletRequest(); request.addHeader("Authorization","Bearer test-only-token");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThat(resolver.resolve(tenant.toString(),user.toString())).isEqualTo("测试管理员");
            assertThat(header.get()).isEqualTo("Bearer test-only-token");
            assertThat(resolver.resolve(UUID.randomUUID().toString(),user.toString())).isNull();
            assertThat(resolver.resolve(tenant.toString(),UUID.randomUUID().toString())).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); server.stop(0); }
    }
}
