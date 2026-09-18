package com.rigour.integration.infrastructure.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.application.port.out.FeishuBitableClient.DownloadedAttachment;
import com.rigour.integration.application.port.out.FeishuBitableClientException;
import com.rigour.integration.infrastructure.config.FeishuClientProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuBitableClientAdapterTest {

    @Test
    void readsBaseMetadataAndDownloadsAttachmentWithBitablePermissionContext() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/appToken123/tables?page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"items":[{"table_id":"tblStaff","name":"渡江战役团队管理"}],"has_more":false}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/appToken123/tables/tblStaff/fields?page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"items":[{"field_id":"fldPhoto","field_name":"照片"}],"has_more":false}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/appToken123/tables/tblStaff/records/search?page_size=500&user_id_type=open_id"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andExpect(content().json("""
                        {"automatic_fields":true}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","data":{"items":[{"record_id":"recStaff1","fields":{"销售姓名":"2026-06-24-李嘉豪","照片":[{"file_token":"file-token-1","name":"face.png","type":"image/png"}]}}],"has_more":false}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(request -> {
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/open-apis/drive/v1/medias/file-token-1/download");
                    String rawQuery = request.getURI().getRawQuery();
                    assertThat(rawQuery).startsWith("extra=");
                    String extra = URLDecoder.decode(rawQuery.substring("extra=".length()), StandardCharsets.UTF_8);
                    assertThat(extra).contains("\"tableId\":\"tblStaff\"");
                    assertThat(extra).contains("\"fldPhoto\":{\"recStaff1\":[\"file-token-1\"]}");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));

        FeishuBitableClientAdapter client = new FeishuBitableClientAdapter(builder.build(), properties());

        assertThat(client.tables("appToken123")).singleElement().satisfies(table -> {
            assertThat(table.tableId()).isEqualTo("tblStaff");
            assertThat(table.name()).isEqualTo("渡江战役团队管理");
        });
        assertThat(client.fields("appToken123", "tblStaff")).singleElement().satisfies(field -> {
            assertThat(field.fieldId()).isEqualTo("fldPhoto");
            assertThat(field.name()).isEqualTo("照片");
        });
        assertThat(client.records("appToken123", "tblStaff", null)).singleElement().satisfies(record ->
                assertThat(record.recordId()).isEqualTo("recStaff1"));
        DownloadedAttachment attachment = client.downloadAttachment(
                "file-token-1", "face.png", "tblStaff", "recStaff1", "fldPhoto");
        assertThat(attachment.fileName()).isEqualTo("face.png");
        assertThat(attachment.contentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(attachment.content()).containsExactly(1, 2, 3);
        server.verify();
    }

    @Test
    void exposesFeishuPermissionFailureMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/appToken123/tables?page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tenant-token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":99991672,"msg":"Access denied. Required scopes: bitable:app:readonly"}
                                """));

        FeishuBitableClientAdapter client = new FeishuBitableClientAdapter(builder.build(), properties());

        assertThatThrownBy(() -> client.tables("appToken123"))
                .isInstanceOf(FeishuBitableClientException.class)
                .hasMessageContaining("Access denied")
                .hasMessageContaining("99991672");
        server.verify();
    }

    private static void expectToken(MockRestServiceServer server) {
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"app_id":"cli_test_app","app_secret":"secret-fixture"}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","tenant_access_token":"tenant-token","expire":7200}
                        """, MediaType.APPLICATION_JSON));
    }

    private static FeishuClientProperties properties() {
        FeishuClientProperties properties = new FeishuClientProperties();
        properties.setAppId("cli_test_app");
        properties.setAppSecret("secret-fixture");
        properties.setAllowedOrigins("http://127.0.0.1:5100");
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setTokenSafetyWindow(Duration.ofSeconds(60));
        return properties;
    }
}
