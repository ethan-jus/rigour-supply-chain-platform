package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuJsapiClientException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuJsapiSignServiceTest {

    private static final String APP_ID = "cli_test_app";
    private static final String ORIGIN = "http://192.168.1.43:5200";
    private static final Instant NOW = Instant.parse("2026-08-07T07:00:00Z");

    @Test
    void signsExactPageUrlWithMillisecondTimestamp() throws Exception {
        FeishuJsapiSignService service = service(() -> "ticket-fixture");
        String pageUrl = ORIGIN + "/?entry=feishu";

        FeishuJsapiSignService.SignResult result = service.sign(pageUrl);

        assertThat(result.appId()).isEqualTo(APP_ID);
        // 飞书官方示例使用毫秒级时间戳；秒级会被校验服务按毫秒解析成 1970 年而判定过期。
        assertThat(result.timestamp()).isEqualTo(Long.toString(NOW.toEpochMilli()));
        assertThat(result.nonceStr()).isNotBlank();
        assertThat(result.nonceStr()).matches("[A-Za-z0-9]+");
        String plainText = "jsapi_ticket=ticket-fixture"
                + "&noncestr=" + result.nonceStr()
                + "&timestamp=" + result.timestamp()
                + "&url=" + pageUrl;
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(plainText.getBytes(StandardCharsets.UTF_8)));
        assertThat(result.signature()).isEqualTo(expected);
    }

    @Test
    void rejectsOriginThatIsNotExplicitlyConfigured() {
        FeishuJsapiSignService service = service(() -> "ticket-fixture");

        assertThatThrownBy(() -> service.sign("http://127.0.0.1:5200/"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FEISHU_JSAPI_ORIGIN_NOT_ALLOWED));
    }

    @Test
    void allowsChangedPrivateLanAddressWhenLocalLanModeIsEnabled() {
        FeishuJsapiSignService service = localLanService(() -> "ticket-fixture");

        FeishuJsapiSignService.SignResult result = service.sign("http://10.24.7.19:5200/");

        assertThat(result.signedUrl()).isEqualTo("http://10.24.7.19:5200/");
    }

    @Test
    void rejectsFragmentBecauseBrowserMustSignThePreHashUrl() {
        FeishuJsapiSignService service = service(() -> "ticket-fixture");

        assertThatThrownBy(() -> service.sign(ORIGIN + "/#/home"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FEISHU_JSAPI_URL_INVALID));
    }

    @Test
    void mapsFeishuFailureToStableServiceError() {
        FeishuJsapiSignService service = service(() -> {
            throw new FeishuJsapiClientException("FEISHU_TENANT_TOKEN_FAILED_999", 200);
        });

        assertThatThrownBy(() -> service.sign(ORIGIN + "/"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FEISHU_JSAPI_UPSTREAM_FAILED));
    }

    private static FeishuJsapiSignService service(
            com.rigour.integration.application.port.out.FeishuJsapiClient client) {
        return new FeishuJsapiSignService(client, APP_ID, List.of(ORIGIN),
                Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom());
    }

    private static FeishuJsapiSignService localLanService(
            com.rigour.integration.application.port.out.FeishuJsapiClient client) {
        return new FeishuJsapiSignService(client, APP_ID, List.of("http://localhost:5200"), true,
                Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom());
    }
}
