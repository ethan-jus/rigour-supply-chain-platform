package com.rigour.integration.infrastructure.feishu;

import com.rigour.integration.infrastructure.config.FeishuClientProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeishuJsapiClientAdapterTest {

    @Test
    void fetchesFreshTicketOnEveryCallWhileCachingTenantToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"app_id":"cli_test_app","app_secret":"secret-fixture"}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","tenant_access_token":"tenant-token","expire":7200}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/jssdk/ticket/get"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"ticket":"ticket-fixture"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/jssdk/ticket/get"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"ticket":"ticket-fixture-rotated"}}
                        """, MediaType.APPLICATION_JSON));

        FeishuJsapiClientAdapter client = new FeishuJsapiClientAdapter(builder.build(), properties());

        assertThat(client.getJsapiTicket()).isEqualTo("ticket-fixture");
        assertThat(client.getJsapiTicket()).isEqualTo("ticket-fixture-rotated");
        server.verify();
    }

    private static FeishuClientProperties properties() {
        FeishuClientProperties properties = new FeishuClientProperties();
        properties.setAppId("cli_test_app");
        properties.setAppSecret("secret-fixture");
        properties.setAllowedOrigins("http://192.168.1.43:5200");
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setTokenSafetyWindow(Duration.ofSeconds(60));
        return properties;
    }
}
