package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态资源接缝与手机兼容约束。行为由 Node 浏览器用例和数据库集成测试验证。 */
class TemporaryCheckinPublicPageContractTest {
    @Test
    void allInteractiveElementIdsAreUnique() throws IOException {
        for (String path : new String[]{"static/sales-checkin/index.html", "static/sales-checkin/admin/index.html"}) {
            var matcher = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(resource(path));
            Set<String> ids = new HashSet<>();
            while (matcher.find()) assertThat(ids.add(matcher.group(1))).as(path + " duplicate: " + matcher.group(1)).isTrue();
        }
    }

    @Test
    void loadsDurableStorageBeforeApplicationAndUsesOneResourceVersion() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        assertThat(html.indexOf("/sales-checkin/storage.js?")).isGreaterThan(0)
                .isLessThan(html.indexOf("/sales-checkin/app.js?"));
        assertThat(html.indexOf("/sales-checkin/personal-history.js?")).isGreaterThan(html.indexOf("/sales-checkin/storage.js?"))
                .isLessThan(html.indexOf("/sales-checkin/app.js?"));
        var matcher = Pattern.compile("/sales-checkin/(?:styles.css|storage.js|personal-history.js|app.js)\\?v=([^\"]+)").matcher(html);
        Set<String> versions = new HashSet<>();
        int count = 0;
        while (matcher.find()) { versions.add(matcher.group(1)); count++; }
        assertThat(count).isEqualTo(4);
        assertThat(versions).hasSize(1);
        assertThat(html).contains("id=\"draft-save-status\"", "id=\"records-panel\"", "id=\"records-retry-button\"");
    }

    @Test
    void keepsCameraAndAlbumSeparateAndOnlyPreviewsHeaderVerifiedSmallImages() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        assertThat(element(html, "storefront-photo")).contains("type=\"file\"", "accept=\"image/*\"", "capture=\"environment\"");
        assertThat(element(html, "photo-album-input")).contains("type=\"file\"", "accept=\"image/*\"").doesNotContain("capture=");
        assertThat(html).contains("id=\"quick-photo-button\"", "id=\"photo-preview-card\"")
                .doesNotContain("id=\"photo-preview\"", "id=\"wechat-preview\"");
        String script = resource("static/sales-checkin/app.js");
        int previewStart = script.indexOf("function renderImagePreview(");
        int safePreviewStart = script.indexOf("async function prepareSafePhotoPreview(", previewStart);
        String preview = script.substring(previewStart, safePreviewStart);
        assertThat(preview).doesNotContain("URL.createObjectURL", ".src =", "createImageBitmap", "readAsDataURL");
        String safePreview = script.substring(safePreviewStart, script.indexOf("function imageHeaderDimensions(", safePreviewStart));
        assertThat(safePreview).contains("readFilePrefix(file, 256 * 1024)", "imageHeaderDimensions(prefix)",
                "!dimensions || state.files.photo !== file || dimensions.width * dimensions.height > 4 * 1024 * 1024) return")
                .doesNotContain("createImageBitmap", "readAsDataURL");
        assertThat(safePreview.indexOf("dimensions.width * dimensions.height >"))
                .isLessThan(safePreview.indexOf("URL.createObjectURL(file)"));
        String dimensions = script.substring(script.indexOf("function imageHeaderDimensions("),
                script.indexOf("function handleAudioFileSelection", safePreviewStart));
        assertThat(dimensions).contains("if (type === 0x6163544c) return null;",
                "if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;");
    }

    @Test
    void exposesThreeStepsAndAVisibleEscapeFromLocationProblems() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        for (String flow : new String[]{"visit", "store"}) {
            for (int step = 1; step <= 3; step++) {
                assertThat(html).contains("data-flow-step-panel=\"" + flow + "\" data-step-value=\"" + step + "\"");
            }
        }
        assertThat(element(html, "visit-location-continue")).contains("type=\"button\"").doesNotContain("hidden", "disabled");
        assertThat(html.indexOf("id=\"nearby-stores-panel\"")).isLessThan(html.indexOf("id=\"visit-location-button\""));
        assertThat(element(html, "submit-visit-button")).contains("type=\"submit\"");
    }

    @Test
    void browserRecordingHasAnUncollapsedWorkspaceAndStillRequiresConsent() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        assertThat(html).containsOnlyOnce("id=\"visit-recording-workspace\"")
                .contains("id=\"recording-consent\"", "id=\"visit-recording-step-2-slot\"", "id=\"visit-recording-step-3-slot\"");
        assertThat(element(html, "visit-recording-workspace")).startsWith("<section ")
                .contains("aria-labelledby=\"recording-workspace-title\"");
        assertThat(element(html, "record-audio-button")).startsWith("<button ").contains("type=\"button\"");
        assertThat(html).contains("现场录音", "直接在浏览器录制", "上传已有录音");
        assertThat(element(html, "audio-file")).contains("type=\"file\"", "multiple").doesNotContain("accept=", "required");
        assertThat(element(html, "recording-consent")).contains("type=\"checkbox\"");
        assertThat(html).doesNotContain("autoplay", "最长 20 分钟");
    }

    @Test
    void offersHistoryAndDateSelectionAlongsideClearSubmissionAndAttachmentStates() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        for (String id : new String[]{"nav-visit-button", "nav-records-button", "success-view-record-button"}) {
            assertThat(element(html, id)).startsWith("<button ").contains("type=\"button\"");
        }
        assertThat(element(html, "app-bottom-nav")).contains("aria-label=\"主导航\"");
        assertThat(element(html, "personal-history-page")).contains("aria-label=\"我的打卡记录\"");
        assertThat(element(html, "history-detail-page")).contains("aria-label=\"打卡明细\"");
        assertThat(element(html, "history-calendar-dialog")).startsWith("<dialog ").contains("aria-label=\"选择日期\"");
        assertThat(element(html, "history-photo-dialog")).startsWith("<dialog ").contains("aria-label=\"现场照片\"");
        assertThat(html).contains("服务器确认后显示打卡成功", "附件状态", "id=\"success-photo-status\"",
                "id=\"success-audio-list\"", "id=\"success-retry-button\"", "id=\"success-supplement-until\"");
        String history = resource("static/sales-checkin/personal-history.js");
        assertThat(history).contains("Asia/Shanghai", "dateFrom:", "dateTo:", "sortDir:", "status: \"SUBMITTED\"",
                "时长待解析", "audio.preload = \"none\"");
    }

    @Test
    void keepsIdentityAndPrivacyControlsAlongsideRecovery() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        assertThat(element(html, "identity-code")).contains("type=\"password\"", "autocomplete=\"current-password\"", "required");
        assertThat(element(html, "privacy-accepted")).contains("type=\"checkbox\"", "required");
        assertThat(html).contains("id=\"identity-switch\"", "id=\"my-records-button\"", "id=\"success-location-note\"");
    }

    @Test
    void supportsSafeAreasZoomAndKeyboardResize() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        assertThat(html).contains("width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content")
                .doesNotContain("maximum-scale", "user-scalable=no");
        String styles = resource("static/sales-checkin/styles.css").replaceAll("\\s+", "");
        assertThat(styles).contains("min-height:100svh", "env(safe-area-inset-bottom", "touch-action:manipulation",
                "body.has-mobile-input-focus.step-actions");
    }

    @Test
    void adminOffersSeparateSortableDimensionsReviewAndOneAudioPlayer() throws IOException {
        String html = resource("static/sales-checkin/admin/index.html");
        for (String sort : new String[]{"completedAt", "cityName", "salespersonName", "storeName"}) {
            assertThat(html).containsOnlyOnce("data-sort-by=\"" + sort + "\"");
        }
        assertThat(html).contains("aria-sort=\"descending\"", "id=\"filter-location-status\"", "id=\"filter-review-status\"", "id=\"filter-media-status\"");
        assertThat(element(html, "shared-audio")).contains("controls", "preload=\"none\"").doesNotContain("autoplay");
        assertThat(element(html, "review-note")).contains("required");
        assertThat(html).contains("id=\"review-history\"", "id=\"result-location-attention\"", "id=\"result-review-pending\"");
    }

    @Test
    void preservesStreamingAndConfigurableServerAudioLimit() throws IOException {
        String service = Files.readString(Path.of("src/main/java/com/rigour/sales/temporarycheckin/TemporaryCheckinService.java"));
        assertThat(service).contains("MediaSignatureProbe", "validated.file().getInputStream()").doesNotContain("file.getBytes()");
        assertThat(resource("static/sales-checkin/app.js")).doesNotContain("MAX_RECORDING_MS");
        assertThat(Files.readString(Path.of("src/main/resources/application.yml"))).contains("max-file-size: 256MB", "max-request-size: 260MB", "RIGOUR_SALES_TEMPORARY_CHECKIN_MAX_AUDIO_BYTES:268435456");
        assertThat(Files.readString(Path.of("deploy/nginx/sales-checkin-locations.conf"))).contains("client_max_body_size 270m;", "client_body_timeout 20m;");
    }

    @Test
    void dedicatedResolverPreservesTrustedProxyBoundary() throws IOException {
        String nginx = Files.readString(Path.of("deploy/nginx/sales-checkin-locations.conf"));
        String resolver = nginx.substring(nginx.indexOf("location = /sales-checkin/api/v1/locations/resolve {"), nginx.indexOf("# 个人码验证独立限速"));
        assertThat(resolver).contains("proxy_set_header X-Sales-Checkin-Client-IP $remote_addr;", "include /etc/nginx/snippets/sales-checkin-proxy-marker.conf;");
    }

    private static String element(String html, String id) {
        var matcher = Pattern.compile("<[^>]+\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("element " + id).isTrue();
        return matcher.group();
    }
    private static String resource(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
