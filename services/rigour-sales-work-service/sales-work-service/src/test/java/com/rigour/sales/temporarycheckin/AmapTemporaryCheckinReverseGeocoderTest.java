package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rigour.sales.infrastructure.config.AmapProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class AmapTemporaryCheckinReverseGeocoderTest {

    @Test
    void convertsGpsAndBuildsReadableIntersectionAddress() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapTemporaryCheckinReverseGeocoder geocoder = new AmapTemporaryCheckinReverseGeocoder(
                builder.build(), properties("test-amap-key"), JsonMapper.builder().build());
        server.expect(requestTo(containsString("/assistant/coordinate/convert")))
                .andExpect(queryParam("key", "test-amap-key"))
                .andExpect(queryParam("locations", "116.425123,39.886789"))
                .andExpect(queryParam("coordsys", "gps"))
                .andRespond(withSuccess("""
                        {"status":"1","info":"ok","infocode":"10000",
                         "locations":"116.431200,39.888300"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/geocode/regeo")))
                .andExpect(queryParam("location", "116.431200,39.888300"))
                .andExpect(queryParam("extensions", "all"))
                .andExpect(queryParam("roadlevel", "0"))
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","infocode":"10000","regeocode":{
                          "formatted_address":"北京市东城区夕照寺街16号",
                          "addressComponent":{"province":"北京市","city":[],"district":"东城区",
                            "township":"龙潭街道","adcode":"110101"},
                          "roadinters":[{"distance":"60.4","direction":"东南",
                            "first_name":"龙潭路","second_name":"夕照寺街"}],
                          "pois":[]
                        }}
                        """, MediaType.APPLICATION_JSON));

        TemporaryCheckinReverseGeocoder.GeocodeResult result = geocoder.resolve(
                new BigDecimal("116.4251234"), new BigDecimal("39.8867886"));

        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.address()).isEqualTo(
                "北京市东城区夕照寺街16号；东城区龙潭路与夕照寺街交叉口东南60米");
        assertThat(result.formattedAddress()).isEqualTo("北京市东城区夕照寺街16号");
        assertThat(result.adcode()).isEqualTo("110101");
        assertThat(result.city()).isNull();
        assertThat(result.district()).isEqualTo("东城区");
        assertThat(result.amapLongitude()).isEqualByComparingTo("116.431200");
        server.verify();
    }

    @Test
    void doesNotCallAmapWhenWebServiceKeyIsMissing() {
        AmapTemporaryCheckinReverseGeocoder geocoder = new AmapTemporaryCheckinReverseGeocoder(
                RestClient.builder().build(), properties(""), JsonMapper.builder().build());

        TemporaryCheckinReverseGeocoder.GeocodeResult result = geocoder.resolve(
                new BigDecimal("116.4"), new BigDecimal("39.9"));

        assertThat(result.status()).isEqualTo("KEY_MISSING");
        assertThat(result.errorCode()).isEqualTo("AMAP_WEB_KEY_MISSING");
    }

    @Test
    void degradesWithoutBlockingWhenAmapReturnsBusinessError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapTemporaryCheckinReverseGeocoder geocoder = new AmapTemporaryCheckinReverseGeocoder(
                builder.build(), properties("test-amap-key"), JsonMapper.builder().build());
        server.expect(requestTo(containsString("/assistant/coordinate/convert")))
                .andRespond(withSuccess("""
                        {"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}
                        """, MediaType.APPLICATION_JSON));

        TemporaryCheckinReverseGeocoder.GeocodeResult result = geocoder.resolve(
                new BigDecimal("116.4"), new BigDecimal("39.9"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("AMAP_10001");
        server.verify();
    }

    private static AmapProperties properties(String key) {
        AmapProperties properties = new AmapProperties();
        properties.setWebKey(key);
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }
}
