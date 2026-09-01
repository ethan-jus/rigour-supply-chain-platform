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
    void capturesOptionalAudioEvidenceMetadataWithoutBlockingFileUploads() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");

        assertThat(html).contains("页面录制会记录开始时间和时长", "时间仅供参考、不可核验");
        assertThat(script)
                .contains("captureSource: \"BROWSER_RECORDER\"")
                .contains("captureSource: \"FILE_UPLOAD\"")
                .contains("state.recorder.clientStartedAt = clientStartedAt;")
                .contains("clientStartedAt: session.clientStartedAt")
                .contains("clientDurationMs: duration")
                .contains("fileLastModifiedAt: audioFileLastModifiedAt(file)")
                .contains("function readAudioDurationMs(file)")
                .contains("local.metadataPromise = metadataPromise;")
                .contains("await metadataPromise;")
                .contains("audio.hidden = true;")
                .contains("audio.addEventListener(\"loadedmetadata\", handleLoadedMetadata")
                .contains("文件元数据是可选证据，读取失败不阻断预览或上传")
                .contains("captureSource: normalizeAudioCaptureSource(segment.captureSource, segment.source)")
                .contains("clientStartedAt: normalizeOptionalInstant(segment.clientStartedAt)")
                .contains("segment.clientDurationMs ?? segment.durationMs")
                .contains("fileLastModifiedAt: normalizeOptionalInstant(segment.fileLastModifiedAt)")
                .contains("Object.entries(optionalFormFields).forEach")
                .contains("formData.append(name, String(value));")
                .contains("rawSegment.clientDurationMs ?? rawSegment.durationMs")
                .contains("normalizeAudioCaptureSource(rawSegment.captureSource, rawSegment.source)")
                .contains("\"页面录制\"")
                .contains("[\"已有文件\", \"时间不可核验\"]");
    }

    @Test
    void startsRecordingDuringConversationAndKeepsOneRecoveryWorkspace() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");
        int workspaceStart = html.indexOf("id=\"visit-recording-workspace\"");
        int visitStepOneStart = html.indexOf("id=\"visit-step-1\"");
        int optionalStart = html.indexOf("<details class=\"optional-evidence-group\">");
        int optionalEnd = html.indexOf("</details>", optionalStart);
        String optionalEvidence = html.substring(optionalStart, optionalEnd);

        assertThat(workspaceStart).isGreaterThanOrEqualTo(0).isLessThan(visitStepOneStart);
        assertThat(html)
                .contains("<strong>进店沟通</strong>")
                .contains("先告知，再开始现场录音")
                .contains("我已告知现场人员并获得允许")
                .contains("开始后可继续填写客户信息")
                .contains("录音仍为选填")
                .contains("id=\"visit-recording-step-2-slot\"")
                .contains("id=\"visit-recording-step-3-slot\"");
        assertThat(optionalEvidence)
                .contains("企微截图（选填）")
                .doesNotContain("record-audio-button", "audio-preview-list");
        assertThat(script)
                .contains("visitStep === 1")
                .contains("state.recorder.starting")
                .contains("const requestSequence = ++state.recorder.startSequence")
                .contains("state.recorder.sessionId !== session.id")
                .contains("state.recorder.activeSession = session")
                .contains("const pendingSession = Boolean(")
                .contains("pendingSession || isRecording()")
                .contains("if (recorder.state === \"inactive\") {")
                .contains("scheduleRecordingStopFallback(session)")
                .contains("if (session.chunks.length) {")
                .contains("stopRecorderStream(stream)")
                .contains("请先告知现场人员并勾选确认，再开始录音")
                .contains("请先结束录音，再进入下一步或返回选店")
                .contains("返回选择门店会清除本次已添加的录音、照片和截图")
                .contains("activeRecording ? \"结束并保存录音\"")
                .contains("recordingSlot.appendChild(recordingWorkspace)")
                .contains("target === 2")
                .contains("if (recordingBusy()) {\n"
                        + "                stopRecording();");
        assertThat(styles)
                .contains(".recording-workspace")
                .contains(".recording-consent")
                .contains(".recording-workspace.is-recording")
                .contains(".recording-workspace.is-review-mode")
                .contains(".recording-workspace.is-review-mode:not(.is-locked-recovery) .recording-consent");
    }

    @Test
    void keepsFailedUploadsRetryableAndUsesLegacyCompatibleXhr() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .doesNotContain("function isSupportedAudioFile")
                .contains("function uploadMedia(kind, file, progressTitle, optionalFormFields = {})")
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

        assertThat(html).contains("业务归属城市", "选择已有音频文件（可多选）");
        assertThat(script)
                .contains("const HEADQUARTERS_CITY = \"总部\"")
                .contains("state.options.cities.filter((city) => city !== HEADQUARTERS_CITY)")
                .contains("const lockWorkCity = !isHeadquartersIdentity() || isBusinessLocked()")
                .contains("· 归属${state.identity.city}")
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
    void forwardsTrustedProxyFactsForTheDedicatedLocationResolver() throws IOException {
        String nginx = Files.readString(
                Path.of("deploy/nginx/sales-checkin-locations.conf"), StandardCharsets.UTF_8);
        String resolverLocation = nginx.substring(
                nginx.indexOf("location = /sales-checkin/api/v1/locations/resolve {"),
                nginx.indexOf("# 个人码验证独立限速"));

        assertThat(resolverLocation)
                .contains("proxy_set_header X-Sales-Checkin-Client-IP $remote_addr;")
                .contains("include /etc/nginx/snippets/sales-checkin-proxy-marker.conf;");
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
                .contains("GEOLOCATION_FRESH_MAX_AGE_MS = 60 * 1000")
                .contains("GEOLOCATION_REFRESH_TIMEOUT_MS = 30000")
                .contains("GEOLOCATION_ATTEMPT_TIMEOUT_MS = 15000")
                .contains("GEOLOCATION_CLOCK_PROGRESS_MIN_MS = 250")
                .contains("navigator.geolocation.watchPosition")
                .contains("navigator.geolocation.clearWatch")
                .contains("startWatch(false)")
                .contains("startSinglePosition(false)")
                .contains("startCompatibleAttempt();")
                .contains("let geolocationAttemptSequence = 0")
                .contains("attemptSequence === geolocationAttemptSequence")
                .contains("const captureDeadlineMs = Date.now() + GEOLOCATION_REFRESH_TIMEOUT_MS")
                .contains("document.addEventListener(\"visibilitychange\", handleCaptureVisibility)")
                .contains("window.addEventListener(\"pageshow\", handleCapturePageShow)")
                .contains("compatibleSingleTimestampRetries < 1")
                .contains("rejectedTimestampSample = null")
                .contains("? \"兼容定位中\"")
                .contains("? \"正在兼容获取当前位置…\"")
                .contains("maximumAge: 0")
                .contains("state[scope].location = null")
                .contains("capturedAt: new Date(capturedAtMs).toISOString()")
                .contains("手机返回的定位时间需要刷新，正在切换兼容定位")
                .contains("正在兼容获取本次现场定位，最多等待30秒")
                .contains("本次未采用该位置")
                .contains("geocodeStatus: \"CAPTURING\"")
                .contains("function assessGeolocationTimestamp")
                .contains("EPOCH_SECONDS", "EPOCH_MICROSECONDS", "EPOCH_NANOSECONDS")
                .contains("APPLE_SECONDS", "APPLE_MICROSECONDS", "APPLE_NANOSECONDS")
                .contains("MONOTONIC_MILLISECONDS", "MONOTONIC_MICROSECONDS", "MONOTONIC_NANOSECONDS")
                .contains("function resolveAdvancingGeolocationClockCapturedAtMs")
                .contains("LOCATION_TIMESTAMP", "NORMALIZED", "ADVANCING")
                .contains("if (repaired === null)")
                .contains("state[scope].location = null")
                .contains("if (!state.visit.location || !state.visit.locationContext")
                .contains("invalidateUnlockedVisitLocationForFreshEntry();")
                .contains("scheduleInitialVisitLocationCapture();")
                .contains("window.requestAnimationFrame(() => captureLocation(\"visit\"))")
                .contains("state.visit.selectedStore = null")
                .contains("function stopGeolocationRefresh(scope)")
                .contains("function cancelLocationCapture(scope)")
                .doesNotContain("repaired ?? savedAtMs")
                .doesNotContain("GEOLOCATION_FALLBACK_MAX_AGE_MS")
                .doesNotContain("resolved ?? reference");
    }

    @Test
    void returnsToVisitWithTheSavedStoreSelectedWithoutRefreshingRemoteSearch() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("source: \"REGISTERED\"")
                .contains("nextAction: \"CHECK_IN\"")
                .contains("function completeStoreSaveTransition(createdStore, payload)")
                .contains("abortPoiSearch();")
                .contains("state.visit.nearbyStores = [createdStore")
                .contains("state.visit.selectedStore = {")
                .contains("resetStoreDraft(payload.city, payload.salespersonId);")
                .contains("state.activeTab = \"visit\";")
                .contains("renderTab(\"visit\");")
                .contains("clearAllErrors();")
                .contains("$(\"#restore-notice\").hidden = true;")
                .contains("$(\"#store-saved-notice\")?.scrollIntoView")
                .contains("当前表单已保留")
                .doesNotContain("abortStoreSearch", "nearbySearchResults", "nearbySearchPoiLookupStatus")
                .doesNotContain("switchTab(\"visit\");\n            selectStore(createdStore);");
    }

    @Test
    void keepsRegisteredStoresLocalAndSearchesNewAmapStoresOnlyOnExplicitAction() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");
        String inputHandler = script.substring(
                script.indexOf("function handlePoiSearchInput()"),
                script.indexOf("async function searchNewStoreOnce()"));

        assertThat(html)
                .contains("附近已建档门店")
                .contains("id=\"nearby-stores-scope\"")
                .contains("id=\"poi-search-button\"")
                .contains("aria-label=\"搜索高德新门店\" disabled>搜索</button>")
                .contains("只返回当前位置300米内候选；输入本身不会调用高德")
                .contains("你正在新增门店", "保存门店并返回打卡")
                .contains("进入蓝色新增门店流程，保存后自动返回打卡");
        assertThat(script)
                .contains("function visitNearbyOptions()")
                .contains("function storePoiLookupStatus()")
                .contains("$(\"#store-search\").addEventListener(\"input\", showVisitStoreOptions);")
                .contains(".filter((store) => store?.source === \"REGISTERED\")")
                .contains(".filter(isUsableNearbyStore)")
                .contains("requestJson(\"/locations/search-new-store\"")
                .contains("salespersonId: state[scope].salespersonId")
                .contains("salespersonId: state.store.salespersonId")
                .contains("locationVerificationToken: cleanText(payload.locationVerificationToken)")
                .contains("locationVerificationToken: state.store.locationContext?.locationVerificationToken")
                .contains("Boolean(cleanText(context.locationVerificationToken))")
                .contains("finiteNumberOrNull(context.maxCheckinDistanceMeters) !== null")
                .contains("finiteNumberOrNull(context.maxCheckinAccuracyMeters) !== null")
                .contains("finiteNumberOrNull(context.maxLocationAgeMinutes) !== null")
                .contains("if (state.submitting || state.poiSearchController) return;")
                .contains("$(\"#poi-search-button\").addEventListener(\"click\", searchNewStoreOnce);")
                .contains("void searchNewStoreOnce();")
                .contains("const selectionToken = cleanText(poi.selectionToken);")
                .contains("sourcePoiToken: optionalText(state.store.sourcePoiToken)")
                .contains("locationVerificationToken: optionalText(\n"
                        + "                state.store.locationContext?.locationVerificationToken)")
                .contains("locationVerificationToken: optionalText(\n"
                        + "                state.visit.locationContext?.locationVerificationToken)")
                .contains("button.addEventListener(\"click\", () => selectStore(store));")
                .contains("function visitRadiusLabel(context = state.visit.locationContext)")
                .contains("context?.maxCheckinDistanceMeters")
                .doesNotContain("/stores?city=", "SEARCH_DELAY_MS", "scheduleStoreSearch",
                        "schedulePoiSearch", "searchNearbyWithQuery", "poi-search-toggle",
                        "locationContextCityVerified", "context.cityMatched !== false");
        assertThat(inputHandler).doesNotContain("requestJson", "/locations/");
        assertThat(script)
                .containsOnlyOnce("/locations/search-new-store")
                .contains("if (state.store.sourceMode === \"POI\"\n"
                        + "            && (!state.store.sourcePoiId || !state.store.sourcePoiToken))")
                .contains("if (state.store.sourceMode === \"POI\" && !cleanText(state.store.sourcePoiToken))");
        assertThat(styles)
                .contains(".workflow-stepper")
                .contains(".visit-store-result__meta")
                .contains(".explicit-search-button:disabled")
                .contains("body.is-store-page {")
                .contains("--brand: #1f4e6b")
                .contains("#create-store-link")
                .doesNotContain(".visit-store-result.is-new-poi");
    }

    @Test
    void treatsCityAsBusinessAttributionAndKeepsActualGpsLocationUsable() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");

        assertThat(html)
                .contains("本人所属城市")
                .contains("业务归属城市")
                .contains("用于门店、报表归属，不限制手机实际定位城市")
                .contains("定位地址")
                .contains("重试获取详细地址")
                .contains("仅显示当前位置300米内候选，不限业务归属城市")
                .contains("只返回当前位置300米内候选")
                .doesNotContain("本次拜访城市", "实际地址", "高德城市搜索");
        assertThat(script)
                .contains("function locationContextReady(context)")
                .contains("const cityMismatch = ready && context?.cityMatched === false")
                .contains("const addressUnavailable = ready && !address")
                .contains("function formatLocationCoordinates(location)")
                .contains("详细地址暂未取得")
                .contains("不受业务归属城市限制")
                .contains("门店归属${cleanText(store.city)}")
                .contains("当前位置300米内高德搜索结果")
                .doesNotContain("locationContextCityVerified", "城市不一致", "地址解析失败",
                        "Number.isFinite(Number(context.max");
        assertThat(styles).contains(".status-pill.is-info", "color: var(--warning);");
    }

    @Test
    void usesCompactHeaderAndVisibleThreeStepNavigation() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");

        assertThat(html)
                .contains("aria-label=\"拜访打卡步骤\"")
                .contains("data-flow-step=\"visit\" data-step-target=\"1\"")
                .contains("data-flow-step=\"visit\" data-step-target=\"2\"")
                .contains("data-flow-step=\"visit\" data-step-target=\"3\"")
                .contains("id=\"hero-description\" class=\"hero__description\" aria-live=\"polite\"")
                .contains("退出新增，返回打卡");
        assertThat(script)
                .contains("const FLOW_STEP_LABELS = Object.freeze")
                .contains("function renderFlowHeader()")
                .contains("`第 ${current}/${FLOW_STEPS[flow]} 步 · ${stepLabel}`")
                .contains("storeActive ? \"#1f4e6b\" : \"#133c3f\"")
                .contains("function releaseActiveInput()")
                .contains("runAfterMobileInputSettles(")
                .contains("window.scrollTo({ top: 0, behavior: \"auto\" });");
        assertThat(styles)
                .contains("--header-height: 70px")
                .contains(".hero {\n    position: sticky;\n    z-index: 50;\n    top: 0;")
                .contains(".workflow-tabs {\n    display: none;")
                .contains(".workflow-stepper {")
                .contains(".step-actions {\n    position: fixed;")
                .doesNotContain(".has-verified-identity .hero__description");
    }

    @Test
    void makesMobileActionsObviousAndKeepsExplicitAmapCandidatesVisible() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");

        assertThat(html)
                .contains("class=\"field__help action-feedback\"")
                .contains("role=\"status\" aria-live=\"polite\"")
                .contains("/sales-checkin/styles.css?v=20260901-recording-flow")
                .contains("/sales-checkin/app.js?v=20260901-recording-flow")
                .contains("实际定位不受业务归属城市限制")
                .contains("当前位置附近没找到对应门店")
                .contains("id=\"visit-step-1-next\"")
                .contains("id=\"nearby-stores-empty\"")
                .contains("class=\"optional-evidence-group\"");
        assertThat(script)
                .contains("const explicitPoiIds = new Set")
                .contains("explicitPoiIds.has(cleanText(poi.poiId))")
                .contains("poi.nextAction === \"OUT_OF_RANGE\"")
                .contains("超过${formatDistance(maximum) || \"允许距离\"}，无法选择")
                .contains("本次返回")
                .contains("个300米内高德候选");
        assertThat(styles)
                .contains(".explicit-search-button")
                .contains("grid-template-columns: minmax(0, 1fr) 86px")
                .contains(".poi-result.is-out-of-range")
                .contains(".file-preview__icon")
                .contains("scroll-padding-top: calc(var(--header-height) + env(safe-area-inset-top) + 18px)")
                .contains("--page-gutter: 28px")
                .contains("--brand: #133c3f");
    }

    @Test
    void declaresMobileViewportAndKeyboardStabilitySafeguards() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");
        String prepareStoreTail = script.substring(
                script.indexOf("        switchTab(\"store\");"),
                script.indexOf("    function clearSourcePoi"));
        String manualEntry = script.substring(
                script.indexOf("function enableManualStoreEntry"),
                script.indexOf("function renderStoreSource"));
        String mobileControls = styles.substring(
                styles.indexOf("input:not([type=\"checkbox\"]):not([type=\"file\"]),"),
                styles.indexOf("input::placeholder"));
        String releaseInput = script.substring(
                script.indexOf("function releaseActiveInput"),
                script.indexOf("function runAfterMobileInputSettles"));
        String flowNavigation = script.substring(
                script.indexOf("function goToFlowStep"),
                script.indexOf("async function fetchOptions"));

        assertThat(html)
                .contains("width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content")
                .doesNotContain("maximum-scale", "user-scalable=no");
        assertThat(mobileControls)
                .contains("font-size: 16px;\n    line-height: 1.4;");
        assertThat(styles)
                .contains("min-height: 100svh")
                .contains("touch-action: manipulation")
                .contains("body.has-mobile-input-focus .step-actions")
                .contains("visibility: hidden;\n        opacity: 0;\n        pointer-events: none;")
                .contains("scroll-padding-bottom: calc(var(--action-height) + env(safe-area-inset-bottom) + 18px)")
                .contains("env(safe-area-inset-left)")
                .contains("env(safe-area-inset-right)");
        assertThat(script)
                .contains("function isMobileTextEntryControl(element)")
                .contains("function handleMobileFocusIn(event)")
                .contains("const MOBILE_KEYBOARD_MIN_DELTA = 120")
                .contains("mobileViewportBaselineHeight - mobileViewportHeight()")
                .contains("inputActive && (viewportCompressed || withinFocusGrace)")
                .contains("function ensureActiveInputVisible()")
                .contains("activeElement.scrollIntoView({ behavior: \"auto\", block: \"center\" })")
                .contains("scheduleActiveInputVisibilityCheck();")
                .contains("document.addEventListener(\"focusin\", handleMobileFocusIn)")
                .contains("document.addEventListener(\"focusout\", scheduleMobileInputStateSync)")
                .contains("window.visualViewport?.addEventListener(\"resize\", handleMobileViewportResize)")
                .contains("\"has-mobile-input-focus\", inputActive && (viewportCompressed || withinFocusGrace)");
        assertThat(prepareStoreTail).doesNotContain(".focus(");
        assertThat(manualEntry).doesNotContain(".focus(");
        assertThat(releaseInput)
                .contains("if (inputWasActive) scheduleMobileInputStateSync();")
                .doesNotContain("classList.remove");
        assertThat(flowNavigation)
                .contains("const inputWasActive = releaseActiveInput();")
                .contains("if (target === current) return true;")
                .contains("behavior: \"auto\", block: \"start\"")
                .doesNotContain("behavior: \"smooth\"");
    }

    @Test
    void doesNotDecodeFullResolutionCameraImagesForAThumbnail() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        int previewStart = script.indexOf("function renderImagePreview(kind, file)");
        int previewEnd = script.indexOf("function handleAudioFileSelection", previewStart);
        String imagePreview = script.substring(previewStart, previewEnd);

        assertThat(html)
                .contains("id=\"photo-preview-card\" class=\"file-preview\"")
                .contains("class=\"file-preview__icon\"")
                .doesNotContain("id=\"photo-preview\"", "id=\"wechat-preview\"");
        assertThat(imagePreview)
                .contains("已选择，提交时上传原图")
                .doesNotContain("URL.createObjectURL", ".src = url");
    }

    @Test
    void emitsPrivacySafeOperatorDiagnosticsForMobileStages() throws IOException {
        String script = resource("static/sales-checkin/app.js");
        String controller = Files.readString(Path.of(
                "src/main/java/com/rigour/sales/temporarycheckin/TemporaryCheckinController.java"),
                StandardCharsets.UTF_8);
        String service = Files.readString(Path.of(
                "src/main/java/com/rigour/sales/temporarycheckin/TemporaryCheckinService.java"),
                StandardCharsets.UTF_8);

        assertThat(script)
                .contains("function emitClientDiagnostic")
                .contains("/diagnostics/events")
                .contains("PHOTO_PICKER_OPEN", "PHOTO_SELECTED", "PHOTO_READY")
                .contains("SEARCH_CLICK", "SEARCH_RESULT")
                .contains("LOCATION_CLICK", "LOCATION_RESULT", "LOCATION_TIMESTAMP")
                .contains("STORE_SAVE_CLICK", "CLIENT_ERROR")
                .contains("X-Sales-Checkin-Client-Event-Id");
        assertThat(controller).contains("recordClientDiagnosticEvent");
        assertThat(service)
                .contains("operatorId={}", "operatorName={}", "clientEventId={}")
                .contains("queryLength={}", "sourceItems={}", "visibleItems={}")
                .contains("fileSizeBytes={}", "client={}")
                .doesNotContain("personalCode={}", "longitude={}", "latitude={}", "filename={}");
    }

    @Test
    void keepsAdminVisitTypeStatisticsPaginationUrlAndExportContracts() throws IOException {
        String html = resource("static/sales-checkin/admin/index.html");
        String script = resource("static/sales-checkin/admin/admin.js");

        assertThat(html)
                .contains("id=\"filter-visit-type\"")
                .contains("value=\"FIRST_VISIT\"")
                .contains("value=\"REVISIT\"")
                .contains("id=\"result-first-visit-total\"")
                .contains("id=\"result-revisit-total\"");
        assertThat(script)
                .contains("visitType: \"\"")
                .contains("visitType: $(\"#filter-visit-type\").value")
                .contains("params.get(\"visitType\")")
                .contains("$(\"#filter-visit-type\").value = state.filters.visitType")
                .contains("payload.firstVisitTotal", "payload.revisitTotal")
                .contains("`\u7b2c ${state.page + 1} / ${state.totalPages} \u9875 · \u5171 ${formatCount(state.total)} \u6761`")
                .contains("Object.entries(state.filters)")
                .contains("$(\"#export-link\").href = params.toString()")
                .contains("window.history.replaceState(null, \"\"");
    }

    @Test
    void requiresServerManualEntryTokenAndDoesNotAuthorizeManualEntryOnNetworkFailure() throws IOException {
        String script = resource("static/sales-checkin/app.js");
        int searchStart = script.indexOf("async function searchNewStoreOnce()");
        int catchStart = script.indexOf("        } catch (error) {", searchStart);
        int finallyStart = script.indexOf("        } finally {", catchStart);
        String networkFailureHandler = script.substring(catchStart, finallyStart);

        assertThat(script)
                .contains("const manualEntryToken = cleanText(payload.manualEntryToken);")
                .contains("state.store.manualEntryToken = manualEntryToken;")
                .contains("state.store.manualEntryAllowed = Boolean(manualEntryToken)")
                .contains("&& (poiLookupStatus === \"EMPTY\" || poiLookupStatus === \"UNAVAILABLE\");")
                .contains("state.store.poiSearchLookupStatus = poiLookupStatus;")
                .contains("if (!state.store.manualEntryAllowed) return;")
                .contains("state.store.sourceMode = \"MANUAL\";")
                .contains("manualEntryToken: state.store.sourceMode === \"MANUAL\"")
                .contains("if (state.store.sourceMode === \"MANUAL\" && !cleanText(state.store.manualEntryToken))")
                .contains("本次高德搜索暂不可用，可点击下方手工录入继续")
                .contains("保存时仍校验当前位置")
                .doesNotContain("function manualStoreFallbackAvailable");
        assertThat(networkFailureHandler)
                .contains("state.store.poiSearchLookupStatus = null;")
                .contains("state.store.manualEntryAllowed = false;")
                .contains("state.store.manualEntryToken = \"\";")
                .contains("未收到服务端搜索确认，请检查网络后重新点击搜索。")
                .doesNotContain("manualEntryAllowed = true");
    }

    @Test
    void routesStoreEntryThroughOneTransitionAndLocksAsyncWorkWhileSaving() throws IOException {
        String script = resource("static/sales-checkin/app.js");

        assertThat(script)
                .contains("button.dataset.tab === \"store\" && state.activeTab !== \"store\"")
                .contains("prepareNewStore();")
                .contains("if (state.submitting) return;")
                .contains("state.submitting = true;\n        setFormsDisabled(true);")
                .contains("Object.values(state.locationControllers).forEach((controller) => controller?.abort());")
                .contains("cancelLocationCapture(\"visit\");")
                .contains("cancelLocationCapture(\"store\");")
                .contains("!state.visit.location || !locationContextReady(state.visit.locationContext)")
                .contains("state.visit.locationContext = state.store.locationContext")
                .contains("state.submitting = false;\n            setFormsDisabled(false);");
    }

    @Test
    void keepsExpiredLocationDraftFromBreakingInitialOptionLoading() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        int renderStart = script.indexOf("function renderNearbyStores()");
        int renderEnd = script.indexOf("function formatDistance", renderStart);
        String renderNearbyStores = script.substring(renderStart, renderEnd);

        assertThat(renderNearbyStores)
                .contains("if (!locationContextReady(context)) {")
                .contains("const createUnavailable = state.submitting || isBusinessLocked();")
                .contains("createButton.disabled = createUnavailable;")
                .doesNotContain("manualFallback");
        assertThat(script).doesNotContain("manualFallback");
        assertThat(html).contains("/sales-checkin/app.js?v=20260901-recording-flow");
    }

    @Test
    void keepsThreeStepNavigationLocalDraftCompatibleAndMediaSafe() throws IOException {
        String html = resource("static/sales-checkin/index.html");
        String script = resource("static/sales-checkin/app.js");
        String flowNavigation = script.substring(
                script.indexOf("function goToFlowStep"),
                script.indexOf("async function fetchOptions"));

        assertThat(html)
                .contains("data-flow-step-panel=\"visit\" data-step-value=\"1\"")
                .contains("data-flow-step-panel=\"visit\" data-step-value=\"2\"")
                .contains("data-flow-step-panel=\"visit\" data-step-value=\"3\"")
                .contains("data-flow-step-panel=\"store\" data-step-value=\"1\"")
                .contains("data-flow-step-panel=\"store\" data-step-value=\"2\"")
                .contains("data-flow-step-panel=\"store\" data-step-value=\"3\"")
                .contains("id=\"storefront-photo\" type=\"file\"")
                .contains("id=\"audio-preview-list\"")
                .contains("id=\"audio-preview-template\"")
                .contains("id=\"visit-step-store-name\"")
                .contains("id=\"visit-step-store-address\"")
                .contains("id=\"visit-step-2-edit-store\"")
                .contains("id=\"submit-visit-button\" class=\"primary-button\" type=\"submit\"")
                .contains("id=\"submit-store-button\" class=\"primary-button\" type=\"submit\"");
        assertThat(script)
                .contains("const freshUiState = () => ({")
                .contains("ui: state.ui")
                .contains("state.ui = sanitizeRestoredUi(saved.ui)")
                .contains("state.ui.visitStep = 3")
                .contains("state.ui.visitStep = 2")
                .contains("state.ui = freshUiState()")
                .contains("setFlowNextState(visitNextOne, isVisitStepReady(1))")
                .contains("button.dataset.incomplete = \"true\"")
                .contains("flowPanel.dataset.flowStepPanel")
                .contains("请先结束录音，再进入下一步或返回选店");
        assertThat(flowNavigation)
                .doesNotContain("requestJson(", "captureLocation(", "resolveLocationContext(",
                        "searchNewStoreOnce(", "uploadMedia(");
    }

    @Test
    void avoidsReopeningTheMobileKeyboardWhenRevealingValidationErrors() throws IOException {
        String script = resource("static/sales-checkin/app.js");
        String styles = resource("static/sales-checkin/styles.css");
        String errorNavigation = script.substring(
                script.indexOf("function scrollToFirstError"),
                script.indexOf("function prepareProgress"));

        assertThat(errorNavigation)
                .contains("const inputWasActive = document.body.classList.contains(\"has-mobile-input-focus\")")
                .contains("changedStep = normalizeFlowStep(state.ui[flowStateKey(flow)]) !== targetStep;")
                .contains("const avoidTextEntryFocus = changedStep || viewportWidth <= 700;")
                .contains("first.tabIndex = -1;")
                .contains("first.focus({ preventScroll: true });")
                .contains("runAfterMobileInputSettles(revealError, inputWasActive);");
        assertThat(styles)
                .contains(".store-owner-summary {", "flex-wrap: wrap;")
                .contains("flex: 1 1 120px;", "overflow-wrap: anywhere;");
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
