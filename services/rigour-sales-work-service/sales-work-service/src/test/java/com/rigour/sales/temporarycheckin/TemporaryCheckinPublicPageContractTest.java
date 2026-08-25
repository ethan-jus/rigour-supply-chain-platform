package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 固化临时打卡页的 iPhone 定位、录音选择和新门店回填兼容契约。 */
class TemporaryCheckinPublicPageContractTest {

    @Test
    void letsMobileFilePickersShowAudioWithoutUnreliableAcceptFiltering() throws IOException {
        String html = resource("static/sales-checkin/index.html");

        assertThat(html)
                .contains("<input id=\"audio-file\" type=\"file\" multiple>")
                .doesNotContain("accept=\"audio/*\"", "audio/x-m4a");
    }

    @Test
    void keepsFailedUploadsRetryableAndUsesLegacyCompatibleXhr() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .doesNotContain("function isSupportedAudioFile")
                .contains("function uploadMedia(kind, file, progressTitle)")
                .contains("function uploadAudioSegment(segment, file, index, total)")
                .contains("audio/${encodeURIComponent(segment.segmentId)}")
                .contains("const xhr = new XMLHttpRequest()")
                .contains("return state.submission.uploadedMedia.includes(mediaKind);")
                .contains("function mayHaveRemoteMediaState(mediaKind)")
                .contains("上次上传结果未确认。请重新选择原文件继续重试")
                .doesNotContain("await deleteUploadedMedia(upload.step)")
                .contains("function createRequestController()")
                .doesNotContain("const controller = new AbortController()")
                .contains("可重新选择并重试");
    }

    @Test
    void keepsHeadquartersIdentityButRequiresAnActualWorkCity() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");

        assertThat(html).contains("本次拜访城市", "选择已有音频文件（可多选）");
        assertThat(script)
                .contains("const HEADQUARTERS_CITY = \"总部\"")
                .contains("state.options.cities.filter((city) => city !== HEADQUARTERS_CITY)")
                .contains("const lockWorkCity = !isHeadquartersIdentity() || isBusinessLocked()")
                .contains("所属：${state.identity.city}")
                .contains("isEnabledHeadquartersWorkCity")
                .doesNotContain("cityMatch(\"总部\")");
    }

    @Test
    void removesClientSideAudioDurationAndSizeGatesButKeepsConfigurableServerCeiling() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String configuration = Files.readString(
                Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String nginx = Files.readString(
                Path.of("deploy/nginx/sales-checkin-locations.conf"), StandardCharsets.UTF_8);

        assertThat(html).doesNotContain("最长 20 分钟", "00:00 / 20:00");
        assertThat(script).doesNotContain("MAX_AUDIO_BYTES", "MAX_RECORDING_MS");
        assertThat(script).contains("xhr.timeout = 0;");
        assertThat(configuration)
                .contains("max-file-size: 100MB")
                .contains("RIGOUR_SALES_TEMPORARY_CHECKIN_MAX_AUDIO_BYTES:104857600");
        assertThat(nginx)
                .contains("client_max_body_size 110m;")
                .contains("client_body_timeout 20m;");
    }

    @Test
    void streamsLargeMediaAndShowsThatAutomaticTranscriptionIsPaused() throws IOException {
        String service = Files.readString(Path.of(
                "src/main/java/com/rigour/sales/temporarycheckin/TemporaryCheckinService.java"),
                StandardCharsets.UTF_8);
        String publicScript = resource("static/sales-checkin/app.js");
        String adminScript = resource("static/sales-checkin/admin/admin.js");

        assertThat(service)
                .contains("MediaSignatureProbe", "validated.file().getInputStream()")
                .doesNotContain("file.getBytes()");
        assertThat(publicScript).contains("自动转文字与摘要当前已暂停");
        assertThat(adminScript)
                .contains("audioIntelligenceEnabled")
                .contains("录音转写与摘要（已暂停）")
                .contains("当前未开通腾讯语音识别权限");
    }

    @Test
    void resetsAnotherSalespersonsLocalDraftWithoutLeavingTheCurrentFormLocked() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("startNewSubmission();")
                .contains("showIdentityDraftResetNotice(hadServerDraft)")
                .contains("已为当前销售打开新表单")
                .doesNotContain("本机恢复的草稿属于另一销售");
    }

    @Test
    void retriesMobileGeolocationAndRepairsBrokenBrowserTimestamps() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("APPLE_REFERENCE_EPOCH_OFFSET_MS = 978307200000")
                .contains("GEOLOCATION_TIMEOUT_MS = 30000")
                .contains("GEOLOCATION_FALLBACK_MAX_AGE_MS = 5 * 60 * 1000")
                .contains("enableHighAccuracy: false")
                .contains("status.textContent = \"兼容定位中\"")
                .contains("capturedAt: normalizeGeolocationCapturedAt(position.timestamp)")
                .contains("return new Date(resolved ?? reference).toISOString()")
                .contains("geocodeStatus: \"CAPTURING\"")
                .contains("if (repaired === null)")
                .contains("state[scope].location = null")
                .contains("if (!state.visit.location || !state.visit.locationContext")
                .contains("function cancelLocationCapture(scope)")
                .doesNotContain("repaired ?? savedAtMs")
                .doesNotContain("timeout: 15000");
    }

    @Test
    void returnsToVisitWithTheSavedStoreSelectedAndClearsStaleSearchState() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("source: \"REGISTERED\"")
                .contains("nextAction: \"CHECK_IN\"")
                .contains("function completeStoreSaveTransition(createdStore, payload)")
                .contains("abortStoreSearch();", "abortPoiSearch();")
                .contains("state.visit.nearbyStores = [createdStore")
                .contains("state.visit.nearbySearchResults = null;")
                .contains("state.visit.selectedStore = {")
                .contains("resetStoreDraft(payload.city, payload.salespersonId);")
                .contains("state.activeTab = \"visit\";")
                .contains("renderTab(\"visit\");")
                .contains("clearAllErrors();")
                .contains("当前表单已保留")
                .doesNotContain("switchTab(\"visit\");\n            selectStore(createdStore);");
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
