package com.rigour.sales.infrastructure.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.sales.application.port.out.AmapPoiClient.NearbyPoiPage;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.sales.infrastructure.config.AmapProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RestAmapPoiClientTest {

    private final AmapProperties properties = properties();

    @Test
    void parsesAroundResponseWithoutKeyInLogs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestAmapPoiClient client = new RestAmapPoiClient(builder.build(), properties, JsonMapper.builder().build());
        server.expect(requestTo(containsString("/place/around")))
                .andExpect(queryParam("key", "test-amap-key"))
                .andExpect(queryParam("location", "120.1,30.2"))
                .andExpect(queryParam("radius", "3000"))
                .andExpect(queryParam("keywords", "%E4%BE%BF%E5%88%A9%E5%BA%97"))
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","count":"2","pois":[
                          {"id":"B001","name":"测试便利店A","type":"购物服务",
                           "typecode":"060100","address":"科技园路1号",
                           "location":"120.100000,30.200000","distance":"120"},
                          {"id":"B002","name":"测试便利店B","type":"购物服务",
                           "typecode":"060100","address":"科技园路2号",
                           "location":"120.101000,30.201000","distance":"260"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        NearbyPoiPage page = client.searchAround("便利店", new BigDecimal("120.1"),
                new BigDecimal("30.2"), 3000, 1, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).poiId()).isEqualTo("B001");
        assertThat(page.items().get(0).name()).isEqualTo("测试便利店A");
        assertThat(page.items().get(0).distanceMeters()).isEqualByComparingTo("120");
        assertThat(page.items().get(1).longitude()).isEqualByComparingTo("120.101000");
        NearbyPoiPage cached = client.searchAround("便利店", new BigDecimal("120.10004"),
                new BigDecimal("30.20004"), 3000, 1, 20);
        assertThat(cached).isEqualTo(page);
        server.verify();
    }

    @Test
    void failsWhenUpstreamReturnsBusinessError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestAmapPoiClient client = new RestAmapPoiClient(builder.build(), properties, JsonMapper.builder().build());
        server.expect(requestTo(containsString("/place/around")))
                .andRespond(withSuccess("""
                        {"status":"0","info":"INVALID_USER_KEY","count":"0","pois":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.searchAround(null, new BigDecimal("120.1"),
                new BigDecimal("30.2"), 3000, 1, 20))
                .isInstanceOf(AmapPoiException.class);
    }

    @Test
    void failsWhenKeyMissing() {
        AmapProperties empty = properties();
        empty.setWebKey("");
        RestAmapPoiClient client = new RestAmapPoiClient(RestClient.builder().build(), empty,
                JsonMapper.builder().build());
        assertThatThrownBy(() -> client.searchAround(null, new BigDecimal("120.1"),
                new BigDecimal("30.2"), 3000, 1, 20))
                .isInstanceOf(AmapPoiException.class);
    }

    private static AmapProperties properties() {
        AmapProperties properties = new AmapProperties();
        properties.setWebKey("test-amap-key");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
