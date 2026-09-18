package com.rigour.integration.infrastructure.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.integration.application.port.out.FeishuBitableClientException;
import com.rigour.integration.application.port.out.FeishuCaptureBudget;
import com.rigour.integration.infrastructure.config.FeishuClientProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuBitableCaptureTest {
    private static final String SEARCH = "https://open.feishu.cn/open-apis/bitable/v1/apps/appFixture/tables/tblFixture/records/search?page_size=500&user_id_type=open_id";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FeishuBitableClientAdapter client = new FeishuBitableClientAdapter(builder.build(), properties());

    @Test
    void capturesAllPagesAndPreservesNullRichTextLinksAndTimestampsWithoutMetadataActors() {
        token();
        page(null, """
                {"code":0,"data":{"items":[{"record_id":"rec1","created_time":1770000000123,
                "last_modified_time":1770000001123,"created_by":{"id":"must-not-retain"},
                "fields":{"empty":null,"amount":12.340000,"rich":[{"text":"原文"}],
                "link":{"link_record_ids":["recProduct"]}}}],"has_more":true,"page_token":"next"}}
                """, true);
        page("next", """
                {"code":0,"data":{"items":[{"record_id":"rec2","fields":{"status":"退货"}}],"has_more":false}}
                """, true);
        FeishuCaptureBudget budget = new FeishuCaptureBudget();
        var result = client.captureRecords("appFixture", "tblFixture", "viwFixture", budget);
        assertThat(result.complete()).isTrue();
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(budget.pageCount()).isEqualTo(2);
        assertThat(budget.recordCount()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);
        var first = result.rows().getFirst();
        assertThat(first.fields()).containsEntry("empty", null).containsKeys("rich", "link");
        assertThat(first.fields().get("amount").toString()).isEqualTo("12.340000");
        assertThat(first.createdTime()).isEqualTo(1770000000123L);
        assertThat(first.lastModifiedTime()).isEqualTo(1770000001123L);
        assertThat(result.rows().get(1).createdTime()).isNull();
        assertThat(first.fields()).doesNotContainKey("created_by");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[],\"has_more\":true}",
            "{\"items\":[{\"record_id\":\"r\",\"fields\":{}}],\"has_more\":true,\"page_token\":\"\"}",
            "{\"items\":[],\"has_more\":true,\"page_token\":\"next\"}",
            "{\"items\":[]}",
            "{\"items\":[],\"has_more\":\"false\"}",
            "{\"items\":[],\"has_more\":false,\"total\":2}",
            "{\"has_more\":false}",
            "{\"items\":[{\"fields\":{}}],\"has_more\":false}",
            "{\"items\":[{\"record_id\":\"r\"}],\"has_more\":false}",
            "{\"items\":[{\"record_id\":\"r\",\"fields\":{},\"created_time\":-1}],\"has_more\":false}",
            "{\"items\":[{\"record_id\":\"r\",\"fields\":{}},{\"record_id\":\"r\",\"fields\":{}}],\"has_more\":false}"
    })
    void rejectsMalformedOrTruncatedPages(String data) {
        token();
        page(null, "{\"code\":0,\"data\":" + data + "}", false);
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).isInstanceOf(FeishuBitableClientException.class);
        server.verify();
    }

    @Test
    void rejectsDuplicateRecordAcrossPages() {
        token();
        page(null, rowPage("r", true, "next"), false);
        page("next", rowPage("r", false, null), false);
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).hasMessageContaining("重复记录");
        server.verify();
    }

    @Test
    void rejectsRepeatedCursorEvenWithNewRecords() {
        token();
        page(null, rowPage("r1", true, "next"), false);
        page("next", rowPage("r2", true, "next"), false);
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).hasMessageContaining("游标");
        server.verify();
    }

    @Test
    void rejectsTotalChangingDuringPagination() {
        token();
        page(null, rowPage("r1", true, "next").replace("\"items\"", "\"total\":2,\"items\""), false);
        page("next", rowPage("r2", false, null).replace("\"items\"", "\"total\":3,\"items\""), false);
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).hasMessageContaining("总数发生变化");
        server.verify();
    }

    @Test
    void failsOnLaterHttpPageAndDoesNotReturnPartialResult() {
        token();
        page(null, rowPage("r1", true, "next"), false);
        server.expect(requestTo(SEARCH + "&page_token=next")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).hasMessageContaining("未保存");
        server.verify();
    }

    @Test
    void capsPagesAcrossTablesBeforeRequestingNextPage() {
        token();
        page(null, rowPage("r1", false, null), false);
        FeishuCaptureBudget budget = new FeishuCaptureBudget(20, 1, 10000, Duration.ofSeconds(5), System::nanoTime);
        assertThat(capture(budget).complete()).isTrue();
        assertThatThrownBy(() -> capture(budget)).hasMessageContaining("分页上限");
        server.verify();
    }

    @Test
    void capsRecordsAcrossTables() {
        token();
        page(null, rowPage("r1", false, null), false);
        page(null, rowPage("r2", false, null), false);
        FeishuCaptureBudget budget = new FeishuCaptureBudget(1, 20, 10000, Duration.ofSeconds(5), System::nanoTime);
        capture(budget);
        assertThatThrownBy(() -> capture(budget)).hasMessageContaining("记录上限");
        server.verify();
    }

    @Test
    void capsTotalResponseBytesWhileStreaming() {
        token();
        page(null, "{\"code\":0,\"padding\":\"" + "x".repeat(2000) + "\"}", false);
        FeishuCaptureBudget budget = new FeishuCaptureBudget(20, 20, 1000, Duration.ofSeconds(5), System::nanoTime);
        assertThatThrownBy(() -> capture(budget)).hasMessageContaining("大小上限");
        server.verify();
    }

    @Test
    void refusesExpiredBudgetWithoutAnyNetworkRequest() {
        AtomicLong now = new AtomicLong();
        FeishuCaptureBudget budget = new FeishuCaptureBudget(20, 20, 1000, Duration.ofSeconds(1), now::get);
        now.set(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> capture(budget)).hasMessageContaining("时间限制");
        server.verify();
    }

    @Test
    void rejectsDuplicateJsonKeysAndDoesNotExposeResponseBody() {
        token();
        page(null, "{\"code\":0,\"data\":{\"items\":[],\"has_more\":true,\"has_more\":false},\"secret\":\"PRIVATE\"}", false);
        assertThatThrownBy(() -> capture(new FeishuCaptureBudget())).isInstanceOf(FeishuBitableClientException.class)
                .hasMessageNotContaining("PRIVATE");
        server.verify();
    }

    private com.rigour.integration.application.port.out.FeishuBitableClient.CaptureResult capture(FeishuCaptureBudget budget) {
        return client.captureRecords("appFixture", "tblFixture", null, budget);
    }
    private void token() {
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess(
                        "{\"code\":0,\"tenant_access_token\":\"fixture\",\"expire\":7200}", MediaType.APPLICATION_JSON));
    }
    private void page(String cursor, String response, boolean view) {
        server.expect(requestTo(SEARCH + (cursor == null ? "" : "&page_token=" + cursor)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(view ? "{\"automatic_fields\":true,\"view_id\":\"viwFixture\"}"
                        : "{\"automatic_fields\":true}"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
    private static String rowPage(String id, boolean more, String cursor) {
        return "{\"code\":0,\"data\":{\"items\":[{\"record_id\":\"" + id
                + "\",\"fields\":{}}],\"has_more\":" + more
                + (cursor == null ? "" : ",\"page_token\":\"" + cursor + "\"") + "}}";
    }
    private static FeishuClientProperties properties() {
        FeishuClientProperties result = new FeishuClientProperties();
        result.setAppId("fixture");
        result.setAppSecret("fixture-not-a-secret");
        return result;
    }
}
