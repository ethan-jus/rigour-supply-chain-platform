package com.rigour.tenant.iam.infrastructure.feishu;

import com.rigour.tenant.iam.application.port.out.FeishuIdentityProviderException;
import com.rigour.tenant.iam.infrastructure.config.FeishuAuthenticationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FeishuIdentityProviderClientTest {

    @Test
    void exchangesAuthorizationCodeAndCachesAppToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"app_id":"cli_test_app","app_secret":"secret-fixture"}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","app_access_token":"app-token","expire":7200}
                        """, MediaType.APPLICATION_JSON));
        expectUserExchange(server, "code-one", "open-id-one");
        expectUserExchange(server, "code-two", "open-id-two");

        FeishuIdentityProviderClient client = new FeishuIdentityProviderClient(builder.build(), properties());

        var first = client.exchange("code-one");
        var second = client.exchange("code-two");

        assertThat(first.tenantKey()).isEqualTo("tenant-key");
        assertThat(first.openId()).isEqualTo("open-id-one");
        assertThat(first.displayName()).isEqualTo("销售员甲");
        assertThat(first.avatarUrl()).isEqualTo("https://example.test/avatar.png");
        assertThat(second.openId()).isEqualTo("open-id-two");
        server.verify();
    }

    @Test
    void classifiesFeishuServerFailureAsUpstreamFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","app_access_token":"app-token","expire":7200}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/access_token"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        FeishuIdentityProviderClient client = new FeishuIdentityProviderClient(builder.build(), properties());

        assertThatExceptionOfType(FeishuIdentityProviderException.class)
                .isThrownBy(() -> client.exchange("one-time-code"))
                .satisfies(exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(FeishuIdentityProviderException.Reason.UPSTREAM_FAILED);
                    assertThat(exception.httpStatus()).isEqualTo(500);
                });
        server.verify();
    }

    private static void expectUserExchange(
            MockRestServiceServer server, String code, String openId) {
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer app-token"))
                .andExpect(content().json("""
                        {"grant_type":"authorization_code","code":"%s"}
                        """.formatted(code)))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"access_token":"user-token",
                        "tenant_key":"tenant-key","open_id":"%s","name":"销售员甲",
                        "avatar_url":"https://example.test/avatar.png"}}
                        """.formatted(openId), MediaType.APPLICATION_JSON));
    }

    private static FeishuAuthenticationProperties properties() {
        FeishuAuthenticationProperties properties = new FeishuAuthenticationProperties();
        properties.setEnabled(true);
        properties.setAppId("cli_test_app");
        properties.setAppSecret("secret-fixture");
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setTokenSafetyWindow(Duration.ofSeconds(60));
        return properties;
    }
}
