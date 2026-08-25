package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 固化临时打卡页的 iPhone 定位、录音选择和新门店回填兼容契约。 */
class TemporaryCheckinPublicPageContractTest {

    @Test
    void explicitlyAllowsIphoneAudioFilesWithoutUsingTheBrokenAudioWildcard() throws IOException {
        String html = resource("static/sales-checkin/index.html");

        assertThat(html)
                .contains(".m4a", "audio/x-m4a", "audio/mp4")
                .doesNotContain("accept=\"audio/*\"");
    }

    @Test
    void normalizesAppleEpochLocationTimeAndKeepsCreatedStoreInNearbyState() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("APPLE_REFERENCE_EPOCH_OFFSET_MS = 978307200000")
                .contains("capturedAt: normalizeGeolocationCapturedAt(position.timestamp)")
                .contains("source: \"REGISTERED\"")
                .contains("nextAction: \"CHECK_IN\"")
                .contains("state.visit.nearbyStores = [createdStore");
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
