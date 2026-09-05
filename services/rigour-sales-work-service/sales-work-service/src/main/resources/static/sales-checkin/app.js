(function () {
    "use strict";

    const API_BASE = "/sales-checkin/api/v1";
    const STORAGE_KEY = "rigour.sales-checkin.draft.v1";
    const STORAGE_VERSION = 1;
    const DRAFT_TTL_MS = 8 * 60 * 60 * 1000;
    const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    const APPLE_REFERENCE_EPOCH_OFFSET_MS = 978307200000;
    const LOCATION_CAPTURE_FUTURE_SKEW_MS = 2 * 60 * 1000;
    const GEOLOCATION_FRESH_MAX_AGE_MS = 60 * 1000;
    const GEOLOCATION_REFRESH_TIMEOUT_MS = 30000;
    const GEOLOCATION_ATTEMPT_TIMEOUT_MS = 15000;
    const GEOLOCATION_CONTINUE_AFTER_MS = 6000;
    const MAX_RECORDED_UNVERIFIED_ACCURACY_METERS = 10000;
    const GEOLOCATION_CLOCK_PROGRESS_MIN_MS = 250;
    const GEOLOCATION_MAX_MONOTONIC_UPTIME_MS = 366 * 24 * 60 * 60 * 1000;
    const MICROPHONE_PERMISSION_TIMEOUT_MS = 12 * 1000;
    const OPTIONAL_MEDIA_UPLOAD_BUDGET_MS = 30 * 1000;
    const PRIVACY_NOTICE_VERSION = "2026-08-25-identity-v2";
    const HEADQUARTERS_CITY = "总部";
    const FLOW_STEPS = Object.freeze({ visit: 3, store: 3 });
    const MOBILE_INPUT_SETTLE_MS = 320;
    const MOBILE_INPUT_FOCUS_GRACE_MS = 650;
    const MOBILE_KEYBOARD_MIN_DELTA = 120;
    const FLOW_STEP_LABELS = Object.freeze({
        visit: Object.freeze(["", "选择门店", "进店沟通", "现场证明"]),
        store: Object.freeze(["", "搜索门店", "基础资料", "业务标签"])
    });
    const LOCATION_FAILURE_REASONS = new Set([
        "PERMISSION_DENIED", "POSITION_UNAVAILABLE", "TIMEOUT", "UNSUPPORTED",
        "INSECURE_CONTEXT", "INVALID_POSITION", "TIMESTAMP_UNUSABLE",
        "ACCURACY_INSUFFICIENT", "RESOLVE_FAILED", "USER_CONTINUED_AFTER_WAIT"
    ]);

    const MEDIA = Object.freeze({
        photo: "storefront-photo",
        wechat: "wechat-screenshot",
        audio: "audio"
    });

    const LOCKED_BUSINESS_SELECTORS = Object.freeze([
        "#visit-city", "#visit-salesperson", "#visit-location-button", "#visit-location-retry",
        "#visit-location-continue",
        "#visit-location-note", "#store-search", "#store-search-toggle", "#clear-store-button",
        "#create-store-link", "#customer-name", "#customer-phone", "#visit-result", "#privacy-accepted",
        "#store-tab"
    ]);

    const emptyLocation = () => null;
    const freshVisit = () => ({
        city: "",
        salespersonId: "",
        selectedStore: null,
        customerName: "",
        customerPhone: "",
        visitResult: "",
        location: emptyLocation(),
        locationContext: null,
        nearbyStores: [],
        directoryStores: [],
        directoryQuery: "",
        privacyAccepted: false
    });
    const freshStore = () => ({
        clientStoreId: secureUuid(),
        city: "",
        salespersonId: "",
        attribute: "",
        name: "",
        operatingStatus: "",
        contactName: "",
        contactPhone: "",
        areaRange: "",
        facilityCount: "",
        businessTypes: [],
        intendedBusinesses: [],
        cooperationIntent: "",
        storeGrade: "",
        tags: [],
        location: emptyLocation(),
        locationContext: null,
        nearbyPois: [],
        poiSearchResults: null,
        poiSearchLookupStatus: null,
        poiSearchQuery: "",
        manualEntryAllowed: false,
        manualEntryToken: "",
        sourceMode: "",
        sourcePoiToken: "",
        sourcePoiId: "",
        sourcePoiName: "",
        sourcePoiAddress: "",
        sourcePoiLongitude: null,
        sourcePoiLatitude: null
    });
    const freshSubmission = () => ({
        clientSubmissionId: secureUuid(),
        submissionKey: secureSubmissionKey(),
        serverId: null,
        status: "LOCAL_DRAFT",
        createdAt: null,
        businessLocked: false,
        attemptedPayload: null,
        mediaUploadAttempts: [],
        uploadedMedia: [],
        audioSegments: []
    });
    const freshUiState = () => ({
        visitStep: 1,
        storeStep: 1
    });

    const state = {
        activeTab: "visit",
        ui: freshUiState(),
        identity: null,
        visit: freshVisit(),
        store: freshStore(),
        submission: freshSubmission(),
        options: {
            cities: [],
            storeAttributes: [],
            operatingStatuses: [],
            areaRanges: [],
            businessTypes: [],
            intendedBusinesses: [],
            cooperationIntents: [],
            storeGrades: [],
            storeTags: []
        },
        salespersonsByCity: new Map(),
        files: {
            photo: null,
            wechat: null,
            audio: []
        },
        recorder: {
            instance: null,
            stream: null,
            chunks: [],
            startedAt: 0,
            clientStartedAt: null,
            elapsedMs: 0,
            timer: null,
            starting: false,
            stopping: false,
            startSequence: 0,
            sessionId: null,
            activeSession: null,
            stopFallbackTimer: null
        },
        objectUrls: {
            photo: null,
            wechat: null,
            audio: new Map()
        },
        audioRetrySegmentId: null,
        audioFileSelectionSequence: 0,
        poiSearchController: null,
        storeDirectoryController: null,
        locationControllers: {
            visit: null,
            store: null
        },
        locationCaptureSequence: {
            visit: 0,
            store: 0
        },
        geolocationWatchIds: {
            visit: null,
            store: null
        },
        geolocationTimeoutIds: {
            visit: null,
            store: null
        },
        geolocationContinueIds: {
            visit: null,
            store: null
        },
        geolocationLifecycleCleanups: {
            visit: null,
            store: null
        },
        submitting: false,
        completed: false,
        storageUnavailableShown: false
    };

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
    let mobileInputBlurTimer = null;
    let mobileInputFocusGraceTimer = null;
    let mobileInputFocusGraceUntil = 0;
    let mobileViewportSettleTimer = null;
    let mobileViewportBaselineHeight = 0;
    let mobileViewportBaselineWidth = 0;

    document.addEventListener("DOMContentLoaded", init);

    async function init() {
        try {
            restoreDraft();
            invalidateUnlockedVisitLocationForFreshEntry();
        } catch (error) {
            showError(errorMessage(error, "无法生成安全的提交凭据，请使用新版浏览器并通过 HTTPS 打开页面。"));
            $("#submit-visit-button").disabled = true;
        }

        rememberMobileViewportBaseline();
        bindEvents();
        renderRestoredValues();
        renderTab(state.activeTab);
        renderSelectedStore();
        renderLocation("visit");
        renderLocation("store");
        renderNearbyStores();
        renderStoreSource();
        renderStorePrefillMessage();
        renderAudioSegments();
        renderUploadedBadges();
        renderBusinessLock();
        updateVisitResultCount();
        checkRecorderSupport();

        try {
            const initialCity = state.visit.city || state.store.city || "";
            await fetchOptions(initialCity);
            populateCitySelects();
            await restoreDependentOptions();
            await loadCurrentIdentity();
            if (state.identity?.authenticated) {
                await applyVerifiedIdentity(state.identity);
            }
            renderDictionaryControls();
            renderRestoredValues();
            renderBusinessLock();
            ["visit", "store"].forEach((scope) => {
                const context = state[scope].locationContext;
                if (state[scope].city && state[scope].location
                    && !(scope === "visit" && isBusinessLocked())
                    && !locationFlowReady(context)) {
                    resolveLocationContext(scope);
                }
            });
        } catch (error) {
            showError(errorMessage(error, "加载城市和下拉选项失败，请检查网络后刷新页面。"));
        }

        renderIdentityState();
        scheduleInitialVisitLocationCapture();

        if (hasRestoredDraft()) {
            showRestoreNotice();
            emitClientDiagnostic("PAGE_RESTORED", "SUCCEEDED");
        }
    }

    function isMobileTextEntryControl(element) {
        if (element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement) return true;
        if (!(element instanceof HTMLInputElement)) return false;
        return !["button", "checkbox", "color", "file", "hidden", "radio", "range", "reset", "submit"]
            .includes(element.type);
    }

    function syncMobileInputState() {
        window.clearTimeout(mobileInputBlurTimer);
        mobileInputBlurTimer = null;
        const inputActive = window.innerWidth <= 700 && isMobileTextEntryControl(document.activeElement);
        const viewportCompressed = mobileViewportBaselineHeight - mobileViewportHeight()
            >= MOBILE_KEYBOARD_MIN_DELTA;
        const withinFocusGrace = Date.now() < mobileInputFocusGraceUntil;
        document.body.classList.toggle(
            "has-mobile-input-focus", inputActive && (viewportCompressed || withinFocusGrace));
        if (!inputActive) {
            mobileInputFocusGraceUntil = 0;
            rememberMobileViewportBaseline();
        }
    }

    function mobileViewportHeight() {
        const viewport = window.visualViewport;
        return (viewport?.height || window.innerHeight) * (viewport?.scale || 1);
    }

    function rememberMobileViewportBaseline() {
        const width = window.innerWidth;
        const height = mobileViewportHeight();
        if (!mobileViewportBaselineWidth || Math.abs(width - mobileViewportBaselineWidth) > 80) {
            mobileViewportBaselineWidth = width;
            mobileViewportBaselineHeight = height;
            return;
        }
        mobileViewportBaselineHeight = Math.max(mobileViewportBaselineHeight, height);
    }

    function scheduleMobileInputStateSync() {
        window.clearTimeout(mobileInputBlurTimer);
        mobileInputBlurTimer = window.setTimeout(syncMobileInputState, MOBILE_INPUT_SETTLE_MS);
    }

    function ensureActiveInputVisible() {
        const activeElement = document.activeElement;
        const viewport = window.visualViewport;
        const viewportWidth = viewport?.width || window.innerWidth;
        if (viewportWidth > 700 || !isMobileTextEntryControl(activeElement)) return;
        const fieldRect = activeElement.getBoundingClientRect();
        const headerBottom = $(".hero")?.getBoundingClientRect().bottom || 0;
        const viewportTop = viewport?.offsetTop || 0;
        const visibleTop = Math.max(viewportTop, headerBottom) + 12;
        const visibleBottom = viewportTop + (viewport?.height || window.innerHeight) - 16;
        if (fieldRect.top < visibleTop || fieldRect.bottom > visibleBottom) {
            activeElement.scrollIntoView({ behavior: "auto", block: "center" });
        }
    }

    function scheduleActiveInputVisibilityCheck() {
        window.clearTimeout(mobileViewportSettleTimer);
        mobileViewportSettleTimer = window.setTimeout(ensureActiveInputVisible, 140);
    }

    function handleMobileFocusIn(event) {
        if (isMobileTextEntryControl(event.target)) {
            if (!mobileViewportBaselineHeight) rememberMobileViewportBaseline();
            mobileInputFocusGraceUntil = Date.now() + MOBILE_INPUT_FOCUS_GRACE_MS;
            window.clearTimeout(mobileInputFocusGraceTimer);
            mobileInputFocusGraceTimer = window.setTimeout(
                syncMobileInputState, MOBILE_INPUT_FOCUS_GRACE_MS + 20);
            syncMobileInputState();
            scheduleActiveInputVisibilityCheck();
        } else {
            scheduleMobileInputStateSync();
        }
    }

    function handleMobileViewportResize() {
        if (isMobileTextEntryControl(document.activeElement)) {
            syncMobileInputState();
            scheduleActiveInputVisibilityCheck();
        } else if (mobileInputBlurTimer === null) {
            rememberMobileViewportBaseline();
            syncMobileInputState();
        }
    }

    function releaseActiveInput() {
        const activeElement = document.activeElement;
        const inputWasActive = document.body.classList.contains("has-mobile-input-focus")
            || isMobileTextEntryControl(activeElement);
        if (isMobileTextEntryControl(activeElement)) activeElement.blur();
        if (inputWasActive) scheduleMobileInputStateSync();
        else syncMobileInputState();
        return inputWasActive;
    }

    function runAfterMobileInputSettles(callback, inputWasActive) {
        window.setTimeout(callback, inputWasActive ? MOBILE_INPUT_SETTLE_MS + 20 : 0);
    }

    function invalidateUnlockedVisitLocationForFreshEntry() {
        if (!hasRestoredDraft() || isBusinessLocked()) return;
        if (locationExceptionReady(state.visit.locationContext)) return;
        state.visit.location = null;
        state.visit.locationContext = null;
        state.visit.nearbyStores = [];
        state.visit.selectedStore = null;
        state.ui.visitStep = 1;
    }

    function scheduleInitialVisitLocationCapture() {
        if (state.activeTab !== "visit"
                || !state.identity?.authenticated
                || !state.visit.city
                || isBusinessLocked()
                || locationFlowReady(state.visit.locationContext)) return;
        window.requestAnimationFrame(() => captureLocation("visit"));
    }

    async function loadCurrentIdentity() {
        try {
            const current = normalizeResponse(await requestJson("/identity/me"));
            state.identity = current && typeof current === "object" ? current : null;
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                state.identity = null;
                return;
            }
            throw error;
        }
    }

    async function handleIdentityCityChange() {
        hideError();
        clearFieldError("identity-city");
        clearFieldError("identity-salesperson");
        const city = $("#identity-city").value;
        if (!city) {
            renderIdentitySalespersonSelect("");
            return;
        }
        const select = $("#identity-salesperson");
        select.disabled = true;
        renderSelect(select, [], "正在加载销售…", "");
        try {
            await ensureSalespersons(city);
            renderIdentitySalespersonSelect(city);
        } catch (error) {
            renderSelect(select, [], "加载失败，请重选城市", "");
            setFieldError("identity-city", errorMessage(error, "销售列表加载失败。"));
        }
    }

    function renderIdentitySalespersonSelect(city) {
        const select = $("#identity-salesperson");
        if (!city) {
            renderSelect(select, [], "请先选择城市", "");
            select.disabled = true;
            return;
        }
        const people = state.salespersonsByCity.get(city) || [];
        renderSelect(select, people, people.length ? "请选择本人" : "当前城市暂无销售", "",
            (person) => person.id, (person) => person.name);
        select.disabled = people.length === 0;
    }

    async function verifyIdentity(event) {
        event.preventDefault();
        hideError();
        ["identity-city", "identity-salesperson", "identity-code"].forEach(clearFieldError);
        const city = $("#identity-city").value;
        const salespersonId = $("#identity-salesperson").value;
        const personalCode = $("#identity-code").value.trim();
        let valid = true;
        if (!city) {
            setFieldError("identity-city", "请选择本人所属城市。");
            valid = false;
        }
        if (!salespersonId) {
            setFieldError("identity-salesperson", "请选择本人姓名。");
            valid = false;
        }
        if (personalCode.length < 8) {
            setFieldError("identity-code", "请输入至少8位个人打卡码。");
            valid = false;
        }
        if (!valid) return;

        const button = $("#identity-submit");
        const originalLabel = button.textContent;
        button.disabled = true;
        button.textContent = "正在验证…";
        try {
            const identity = normalizeResponse(await requestJson("/identity/verify", {
                method: "POST",
                body: { city, salespersonId, personalCode }
            }));
            $("#identity-code").value = "";
            await applyVerifiedIdentity(identity);
            renderIdentityState();
            persistDraft();
            window.scrollTo({ top: 0, behavior: "smooth" });
        } catch (error) {
            $("#identity-code").value = "";
            const message = errorMessage(error, "身份验证失败，请检查个人打卡码。");
            setFieldError("identity-code", message);
            showError(message);
        } finally {
            button.disabled = false;
            button.textContent = originalLabel;
        }
    }

    async function applyVerifiedIdentity(identity) {
        if (!identity?.authenticated || !identity.salespersonId || !identity.city) return;
        const salespersonMismatch = (state.visit.salespersonId
                && state.visit.salespersonId !== String(identity.salespersonId))
            || (state.store.salespersonId
                && state.store.salespersonId !== String(identity.salespersonId));
        const workCityMismatch = (state.visit.city && !identityAllowsWorkCity(identity, state.visit.city))
            || (state.store.city && !identityAllowsWorkCity(identity, state.store.city));
        const restoredMismatch = salespersonMismatch || workCityMismatch;
        if (restoredMismatch) {
            const hadServerDraft = Boolean(state.submission.serverId
                || state.submission.attemptedPayload
                || state.submission.uploadedMedia.length
                || state.submission.mediaUploadAttempts.length
                || state.submission.audioSegments.length);
            state.identity = identity;
            startNewSubmission();
            showIdentityDraftResetNotice(hadServerDraft);
        }
        state.identity = identity;
        if (isHeadquartersIdentity(identity)) {
            state.visit.city = isEnabledHeadquartersWorkCity(state.visit.city) ? state.visit.city : "";
            state.store.city = isEnabledHeadquartersWorkCity(state.store.city) ? state.store.city : "";
        } else {
            state.visit.city = identity.city;
            state.store.city = identity.city;
        }
        state.visit.salespersonId = String(identity.salespersonId);
        state.store.salespersonId = String(identity.salespersonId);
        await ensureSalespersons(identity.city);
        populateCitySelects();
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
        renderRestoredValues();
        renderStoreOwnerSummary();
        lockIdentitySelectors();
    }

    function showIdentityDraftResetNotice(hadServerDraft) {
        $("#restore-notice strong").textContent = "已为当前销售打开新表单";
        $("#restore-message").textContent = hadServerDraft
            ? "本机保存的未完成表单与当前身份或业务归属不一致，未带入当前账号；原服务端草稿未做任何修改。"
            : "本机保存的表单与当前身份或业务归属不一致，已安全清除。请确认业务归属后重新定位。";
        $("#discard-draft-button").hidden = true;
        $("#restore-notice").hidden = false;
    }

    function isHeadquartersIdentity(identity = state.identity) {
        return identity?.authenticated === true && identity.city === HEADQUARTERS_CITY;
    }

    function isEnabledHeadquartersWorkCity(city) {
        return Boolean(city && city !== HEADQUARTERS_CITY && state.options.cities.includes(city));
    }

    function identityAllowsWorkCity(identity, city) {
        if (!city) return true;
        return isHeadquartersIdentity(identity)
            ? isEnabledHeadquartersWorkCity(city)
            : city === identity.city;
    }

    function renderIdentityState() {
        const authenticated = state.identity?.authenticated === true;
        const legacyMode = state.identity?.enforcementEnabled === false;
        document.body.classList.toggle("has-verified-identity", authenticated);
        document.body.classList.toggle("is-headquarters-identity", isHeadquartersIdentity());
        $("#identity-gate").hidden = authenticated || legacyMode;
        $("#checkin-workspace").hidden = !authenticated && !legacyMode;
        $("#identity-summary").hidden = !authenticated;
        if (authenticated) {
            $("#identity-summary-name").textContent = state.identity.salespersonName || "--";
            $("#identity-summary-city").textContent = state.identity.city
                ? `· 归属${state.identity.city}`
                : "--";
        }
        $("#identity-switch").disabled = state.submitting || isBusinessLocked();
        lockIdentitySelectors();
        renderFlowSteps();
    }

    function lockIdentitySelectors() {
        if (!state.identity?.authenticated) return;
        const lockWorkCity = !isHeadquartersIdentity() || isBusinessLocked();
        ["#visit-city", "#store-city"].forEach((selector) => {
            const element = $(selector);
            if (element) element.disabled = lockWorkCity;
        });
        ["#visit-salesperson", "#store-salesperson"].forEach((selector) => {
            const element = $(selector);
            if (element) element.disabled = true;
        });
    }

    async function switchIdentity() {
        if (state.submitting) return;
        if (recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再切换销售身份。");
            return;
        }
        if (isBusinessLocked()) {
            showError("当前草稿已上传或锁定，请先完成提交或放弃草稿，再切换销售身份。");
            return;
        }
        if (!window.confirm("切换身份会清空当前未提交表单，确定继续吗？")) return;
        try {
            await requestJson("/identity/logout", { method: "POST" });
            state.identity = null;
            startNewSubmission();
            renderIdentityState();
            $("#identity-city").value = "";
            renderIdentitySalespersonSelect("");
            $("#identity-code").value = "";
            $("#identity-gate").scrollIntoView({ behavior: "smooth", block: "start" });
        } catch (error) {
            showError(errorMessage(error, "切换身份失败，请刷新后重试。"));
        }
    }

    function bindEvents() {
        $("#identity-form").addEventListener("submit", verifyIdentity);
        $("#identity-city").addEventListener("change", handleIdentityCityChange);
        $("#identity-switch").addEventListener("click", switchIdentity);

        $$("[data-tab]").forEach((button) => {
            button.addEventListener("click", () => {
                if (button.dataset.tab === "store" && state.activeTab !== "store") {
                    prepareNewStore();
                } else {
                    switchTab(button.dataset.tab);
                }
            });
            button.addEventListener("keydown", handleTabKeydown);
        });

        $$("[data-flow-step]").forEach((button) => {
            button.addEventListener("click", () => {
                goToFlowStep(button.dataset.flowStep, Number(button.dataset.stepTarget));
            });
        });
        $("#visit-step-1-next").addEventListener("click", () => goToFlowStep("visit", 2));
        $("#visit-step-2-back").addEventListener("click", () => goToFlowStep("visit", 1));
        $("#visit-step-2-edit-store").addEventListener("click", () => goToFlowStep("visit", 1));
        $("#visit-step-2-next").addEventListener("click", () => {
            if (recordingBusy()) {
                stopRecording();
                return;
            }
            goToFlowStep("visit", 3);
        });
        $("#visit-step-3-back").addEventListener("click", () => goToFlowStep("visit", 2));
        $("#store-step-1-next").addEventListener("click", () => goToFlowStep("store", 2));
        $("#store-step-2-back").addEventListener("click", () => goToFlowStep("store", 1));
        $("#store-step-2-next").addEventListener("click", () => goToFlowStep("store", 3));
        $("#store-step-3-back").addEventListener("click", () => goToFlowStep("store", 2));

        $("#dismiss-error-button").addEventListener("click", hideError);
        $("#discard-draft-button").addEventListener("click", async () => discardDraft());
        $("#new-submission-button").addEventListener("click", startNewSubmission);

        $("#visit-city").addEventListener("change", () => handleCityChange("visit"));
        $("#store-city").addEventListener("change", () => handleCityChange("store"));
        $("#visit-salesperson").addEventListener("change", persistFromForm);
        $("#store-salesperson").addEventListener("change", persistFromForm);

        $("#store-search").addEventListener("input", handleVisitStoreSearchInput);
        $("#store-search").addEventListener("focus", () => showVisitStoreOptions());
        $("#store-search").addEventListener("keydown", handleStoreSearchKeydown);
        $("#store-search-toggle").addEventListener("click", toggleVisitStoreOptions);
        $("#clear-store-button").addEventListener("click", () => clearSelectedStore(true, true, true));
        $("#create-store-link").addEventListener("click", () => prepareNewStore());
        $("#cancel-store-button").addEventListener("click", () => switchTab("visit"));

        $("#poi-search").addEventListener("input", handlePoiSearchInput);
        $("#poi-search").addEventListener("focus", () => renderPoiOptions(true));
        $("#poi-search").addEventListener("keydown", handlePoiSearchKeydown);
        $("#poi-search-button").addEventListener("click", searchNewStoreOnce);
        $("#clear-poi-button").addEventListener("click", clearSelectedPoi);
        $("#manual-store-button").addEventListener("click", enableManualStoreEntry);

        $("#visit-location-button").addEventListener("click", () => captureLocation("visit"));
        $("#store-location-button").addEventListener("click", () => captureLocation("store"));
        $("#visit-location-continue").addEventListener("click", () => continueWithoutVerifiedLocation("visit"));
        $("#store-location-continue").addEventListener("click", () => continueWithoutVerifiedLocation("store"));
        $("#visit-location-retry").addEventListener("click", () => resolveLocationContext("visit"));
        $("#store-location-retry").addEventListener("click", () => resolveLocationContext("store"));

        $("#storefront-photo").addEventListener("change", (event) => handleImageSelection("photo", event));
        $("#wechat-screenshot").addEventListener("change", (event) => handleImageSelection("wechat", event));
        $("#storefront-photo").addEventListener("click", () => {
            emitClientDiagnostic("PHOTO_PICKER_OPEN", "STARTED");
        });
        $("#remove-photo-button").addEventListener("click", async () => clearFile("photo"));
        $("#remove-wechat-button").addEventListener("click", async () => clearFile("wechat"));
        $("#delete-uploaded-photo-button").addEventListener("click", async () => clearFile("photo"));
        $("#delete-uploaded-wechat-button").addEventListener("click", async () => clearFile("wechat"));

        $("#record-audio-button").addEventListener("click", toggleRecording);
        $("#recording-consent").addEventListener("change", () => {
            if ($("#recording-consent").checked) clearFieldError("audio-file");
        });
        $("#audio-file").addEventListener("change", handleAudioFileSelection);
        $("#audio-file-label").addEventListener("click", () => {
            state.audioRetrySegmentId = null;
        });

        $("#visit-form").addEventListener("input", persistFromForm);
        $("#visit-form").addEventListener("change", persistFromForm);
        $("#store-form").addEventListener("input", persistFromForm);
        $("#store-form").addEventListener("change", persistFromForm);
        $("#visit-result").addEventListener("input", updateVisitResultCount);

        $("#visit-form").addEventListener("submit", submitVisit);
        $("#store-form").addEventListener("submit", submitStore);

        document.addEventListener("click", (event) => {
            if (!event.target.closest(".store-search-field")) hideStoreResults();
            if (!event.target.closest(".poi-search-field")) hidePoiResults();
        });
        document.addEventListener("focusin", handleMobileFocusIn);
        document.addEventListener("focusout", scheduleMobileInputStateSync);
        window.addEventListener("resize", handleMobileViewportResize);
        window.visualViewport?.addEventListener("resize", handleMobileViewportResize);

        window.addEventListener("beforeunload", (event) => {
            if (!state.completed) {
                syncStateFromForm();
                persistDraft();
            }
            if (recordingBusy() || state.submitting) {
                event.preventDefault();
                event.returnValue = "";
            }
        });
        window.addEventListener("error", () => {
            emitClientDiagnostic("CLIENT_ERROR", "FAILED");
        });
        window.addEventListener("unhandledrejection", () => {
            emitClientDiagnostic("CLIENT_ERROR", "FAILED");
        });
    }

    function handleTabKeydown(event) {
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
        event.preventDefault();
        const next = event.currentTarget.dataset.tab === "visit" ? "store" : "visit";
        if (next === "store") prepareNewStore();
        else switchTab(next, true);
    }

    function switchTab(tab, focusTab = false) {
        if (state.submitting) return;
        if (tab !== state.activeTab && recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再切换页面。");
            return;
        }
        if (isBusinessLocked() && tab === "store") return;
        const inputWasActive = releaseActiveInput();
        state.activeTab = tab === "store" ? "store" : "visit";
        renderTab(state.activeTab);
        if (state.activeTab === "store") {
            renderLocation("store");
            renderStoreSource();
        } else {
            renderLocation("visit");
            renderNearbyStores();
            renderSelectedStore();
        }
        persistFromForm();
        if (focusTab) {
            $(`#${state.activeTab}-tab`).focus();
        } else {
            runAfterMobileInputSettles(
                () => window.scrollTo({ top: 0, behavior: "auto" }), inputWasActive);
        }
    }

    function renderTab(tab) {
        const visitActive = tab !== "store";
        $("#visit-tab").classList.toggle("is-active", visitActive);
        $("#visit-tab").setAttribute("aria-selected", String(visitActive));
        $("#store-tab").classList.toggle("is-active", !visitActive);
        $("#store-tab").setAttribute("aria-selected", String(!visitActive));
        $("#visit-panel").hidden = !visitActive;
        $("#store-panel").hidden = visitActive;
        document.body.classList.toggle("is-store-page", !visitActive);
        renderFlowSteps();
    }

    function renderFlowHeader() {
        const flow = state.activeTab === "store" ? "store" : "visit";
        const storeActive = flow === "store";
        const title = storeActive ? "新增门店" : "拜访打卡";
        const current = normalizeFlowStep(state.ui?.[flowStateKey(flow)]);
        const stepLabel = FLOW_STEP_LABELS[flow][current];
        const description = state.identity?.authenticated
            ? `第 ${current}/${FLOW_STEPS[flow]} 步 · ${stepLabel}`
            : "现场拜访记录";
        const titleElement = $("#hero-title");
        const descriptionElement = $("#hero-description");
        if (titleElement.textContent !== title) titleElement.textContent = title;
        if (descriptionElement.textContent !== description) descriptionElement.textContent = description;
        document.title = storeActive ? "新增门店" : "销售拜访打卡";
        $("meta[name=\"theme-color\"]")?.setAttribute(
            "content", storeActive ? "#1f4e6b" : "#133c3f");
    }

    function flowStateKey(flow) {
        return flow === "store" ? "storeStep" : "visitStep";
    }

    function normalizeFlowStep(value, fallback = 1) {
        const parsed = Number(value);
        return Number.isInteger(parsed) && parsed >= 1 && parsed <= 3 ? parsed : fallback;
    }

    function hasValidStoreSource() {
        if (state.store.sourceMode === "POI") {
            return Boolean(cleanText(state.store.sourcePoiId) && cleanText(state.store.sourcePoiToken));
        }
        if (state.store.sourceMode === "MANUAL") {
            return Boolean(state.store.manualEntryAllowed
                && (locationExceptionReady(state.store.locationContext)
                    || cleanText(state.store.manualEntryToken)));
        }
        return false;
    }

    function visitSelectedStoreReady() {
        const selectedId = state.visit.selectedStore?.id;
        if (!selectedId) return false;
        if (isBusinessLocked() || locationExceptionReady(state.visit.locationContext)) return true;
        return registeredNearbyStores().some((store) =>
            String(store.storeId || store.id) === String(selectedId));
    }

    function isVisitStepReady(step) {
        if (step === 1) {
            return Boolean(state.visit.city
                && state.visit.salespersonId
                && visitSelectedStoreReady()
                && locationFlowReady(state.visit.locationContext));
        }
        if (step === 2) {
            return Boolean(cleanText(state.visit.customerName) && cleanText(state.visit.visitResult));
        }
        return Boolean((state.files.photo || state.submission.uploadedMedia.includes(MEDIA.photo))
            && state.visit.privacyAccepted);
    }

    function isStoreStepReady(step) {
        if (step === 1) {
            return Boolean(state.store.city
                && state.store.salespersonId
                && locationFlowReady(state.store.locationContext)
                && hasValidStoreSource());
        }
        if (step === 2) {
            return Boolean(cleanText(state.store.name)
                && state.store.attribute
                && state.store.operatingStatus
                && cleanText(state.store.contactName)
                && state.store.areaRange
                && cleanText(state.store.facilityCount)
                && state.store.cooperationIntent);
        }
        return Boolean(state.store.businessTypes.length
            && state.store.intendedBusinesses.length
            && state.store.tags.length);
    }

    function deriveVisitStep() {
        if (isBusinessLocked()) return 3;
        if (!isVisitStepReady(1)) return 1;
        if (!isVisitStepReady(2)) return 2;
        return 3;
    }

    function deriveStoreStep() {
        if (!isStoreStepReady(1)) return 1;
        if (!isStoreStepReady(2)) return 2;
        return 3;
    }

    function sanitizeRestoredUi(savedUi) {
        const derivedVisit = deriveVisitStep();
        const derivedStore = deriveStoreStep();
        const requestedVisit = normalizeFlowStep(savedUi?.visitStep, derivedVisit);
        const requestedStore = normalizeFlowStep(savedUi?.storeStep, derivedStore);
        return {
            visitStep: isBusinessLocked() ? 3 : Math.min(requestedVisit, derivedVisit),
            storeStep: Math.min(requestedStore, derivedStore)
        };
    }

    function maximumAccessibleStep(flow) {
        let maximum = 1;
        if (flow === "visit") {
            if (isVisitStepReady(1)) maximum = 2;
            if (maximum === 2 && isVisitStepReady(2)) maximum = 3;
            if (isBusinessLocked()) maximum = 3;
        } else {
            if (isStoreStepReady(1)) maximum = 2;
            if (maximum === 2 && isStoreStepReady(2)) maximum = 3;
        }
        const current = normalizeFlowStep(state.ui[flowStateKey(flow)]);
        return Math.max(maximum, current);
    }

    function renderFlowSteps() {
        if (!state.ui) state.ui = freshUiState();
        ["visit", "store"].forEach((flow) => {
            const key = flowStateKey(flow);
            const current = normalizeFlowStep(state.ui[key]);
            state.ui[key] = current;
            const maximum = maximumAccessibleStep(flow);

            $$('[data-flow-step-panel="' + flow + '"]').forEach((panel) => {
                panel.hidden = Number(panel.dataset.stepValue) !== current;
            });
            $$('[data-flow-step="' + flow + '"]').forEach((button) => {
                const step = Number(button.dataset.stepTarget);
                const active = step === current;
                button.classList.toggle("is-active", active);
                button.classList.toggle("is-complete", step < current);
                button.toggleAttribute("aria-current", active);
                if (active) button.setAttribute("aria-current", "step");
                const recordingGuard = flow === "visit" && recordingBusy() && step !== current;
                const lockedGuard = flow === "visit" && isBusinessLocked() && step !== 3;
                button.disabled = state.submitting || step > maximum || recordingGuard || lockedGuard;
            });
        });
        const visitStep = normalizeFlowStep(state.ui.visitStep);
        const recordingWorkspace = $("#visit-recording-workspace");
        const recordingSlot = visitStep === 3
            ? $("#visit-recording-step-3-slot") : $("#visit-recording-step-2-slot");
        if (recordingWorkspace.parentElement !== recordingSlot) {
            recordingSlot.appendChild(recordingWorkspace);
        }
        recordingWorkspace.hidden = state.activeTab !== "visit" || visitStep === 1;
        recordingWorkspace.classList.toggle("is-review-mode", visitStep === 3);
        recordingWorkspace.classList.toggle("is-locked-recovery", visitStep === 3 && isBusinessLocked());
        $("#recording-stage-badge").textContent = visitStep === 3
            ? "第 3 步 · 核对" : "第 2 步 · 录音";
        $("#recording-workspace-copy").textContent = visitStep === 3
            ? isBusinessLocked()
                ? "草稿已保留；可重试或跳过失败录音，继续提交"
                : "在此回放确认；需要补录时请先返回第2步"
            : "开始后可继续填写客户信息；请结束录音后再进入现场证明";
        if (!recordingBusy()) {
            $("#record-button-label").textContent = visitStep === 3 && !isBusinessLocked()
                ? "返回第2步补录" : "开始现场录音";
        }
        renderFlowHeader();
        renderFlowActions();
    }

    function renderFlowActions() {
        const visitNextOne = $("#visit-step-1-next");
        const visitNextTwo = $("#visit-step-2-next");
        const storeNextOne = $("#store-step-1-next");
        const storeNextTwo = $("#store-step-2-next");
        setFlowNextState(visitNextOne, isVisitStepReady(1));
        setFlowNextState(visitNextTwo, isVisitStepReady(2));
        setFlowNextState(storeNextOne, isStoreStepReady(1));
        setFlowNextState(storeNextTwo, isStoreStepReady(2));
        const recorderBusy = recordingBusy();
        const activeRecording = isRecording();
        visitNextTwo.classList.toggle("is-recording-action", activeRecording);
        visitNextTwo.disabled = state.submitting
            || state.recorder.starting
            || state.recorder.stopping
            || (!activeRecording && (recorderBusy || !isVisitStepReady(2)));
        visitNextTwo.textContent = state.recorder.starting
            ? "正在等待麦克风"
            : state.recorder.stopping ? "正在生成录音"
                : activeRecording ? "结束并保存录音"
                    : recorderBusy ? "正在保留录音" : "下一步：现场证明";
        ["#visit-step-2-back", "#visit-step-2-edit-store", "#visit-step-3-back"].forEach((selector) => {
            const button = $(selector);
            if (button) button.disabled = state.submitting || recorderBusy || isBusinessLocked();
        });
        ["#storefront-photo", "#wechat-screenshot", "#audio-file", "#privacy-accepted"].forEach((selector) => {
            const input = $(selector);
            if (input) input.disabled = state.submitting || recorderBusy;
        });
        $("#audio-file-label").setAttribute("aria-disabled", String(state.submitting || recorderBusy));
        // 麦克风授权可能在部分手机 WebView 中一直 pending；此时提交按钮必须可达，
        // submitVisit 会主动取消这次授权等待并继续。真正录制或生成文件时仍禁止提交。
        $("#submit-visit-button").disabled = state.submitting
            || (recorderBusy && !state.recorder.starting);
        $("#store-tab").disabled = state.submitting || recorderBusy || isBusinessLocked();
        $("#identity-switch").disabled = state.submitting || recorderBusy || isBusinessLocked();
        $("#discard-draft-button").disabled = state.submitting || recorderBusy;
    }

    function setFlowNextState(button, ready) {
        if (!button) return;
        button.disabled = state.submitting;
        if (ready) {
            delete button.dataset.incomplete;
            button.removeAttribute("title");
        } else {
            button.dataset.incomplete = "true";
            button.setAttribute("title", "点击后会提示还需完成的内容");
        }
    }

    function clearFlowStepErrors(flow, step) {
        const panel = document.querySelector(
            '[data-flow-step-panel="' + flow + '"][data-step-value="' + step + '"]');
        if (!panel) return;
        $$(".field__error", panel).forEach((error) => {
            error.textContent = "";
            error.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
                ?.classList.remove("has-error");
        });
    }

    function validateFlowStep(flow, step) {
        syncStateFromForm();
        clearFlowStepErrors(flow, step);
        let valid = true;
        if (flow === "visit" && step === 1) {
            valid = requireValue(state.visit.city, "visit-city", "请选择业务归属城市。") && valid;
            valid = requireValue(state.visit.salespersonId, "visit-salesperson", "请选择销售。") && valid;
            if (!locationFlowReady(state.visit.locationContext)) {
                setFieldError("visit-location", state.visit.locationContext?.errorMessage
                    || "请刷新定位；如定位失败或等待较久，可按页面提示继续录入。");
                valid = false;
            }
            valid = requireValue(state.visit.selectedStore?.id, "selected-store", "请选择本次拜访门店。") && valid;
            if (state.visit.selectedStore?.id && !visitSelectedStoreReady()) {
                setFieldError("selected-store", "当前已选门店不在本次定位允许范围，请重新选择；也可在定位失败后按名称核对。" );
                valid = false;
            }
        } else if (flow === "visit" && step === 2) {
            valid = requireValue(cleanText(state.visit.customerName), "customer-name", "请输入客户姓名。") && valid;
            valid = requireValue(cleanText(state.visit.visitResult), "visit-result", "请填写拜访结果。") && valid;
        } else if (flow === "store" && step === 1) {
            valid = requireValue(state.store.city, "store-city", "请选择业务归属城市。") && valid;
            valid = requireValue(state.store.salespersonId, "store-salesperson", "请选择销售。") && valid;
            if (!locationFlowReady(state.store.locationContext)) {
                setFieldError("store-location", state.store.locationContext?.errorMessage
                    || "请刷新定位；如定位失败或等待较久，可按页面提示继续录入。");
                valid = false;
            }
            if (!hasValidStoreSource()) {
                setFieldError("store-source", "请从高德搜索结果选择门店；无结果后可使用人工录入。");
                valid = false;
            }
        } else if (flow === "store" && step === 2) {
            valid = requireValue(cleanText(state.store.name), "store-name", "请输入门店名称。") && valid;
            valid = requireValue(state.store.attribute, "store-attribute", "请选择门店属性。") && valid;
            valid = requireValue(state.store.operatingStatus, "operating-status", "请选择营业状态。") && valid;
            valid = requireValue(cleanText(state.store.contactName), "contact-name", "请输入联系人。") && valid;
            valid = requireValue(state.store.areaRange, "area-range", "请选择面积范围。") && valid;
            valid = requireValue(cleanText(state.store.facilityCount), "facility-count", "请输入设施数量。") && valid;
            valid = requireValue(state.store.cooperationIntent, "cooperation-intent", "请选择合作意向。") && valid;
        }
        return valid;
    }

    function recordingBusy() {
        const session = state.recorder.activeSession;
        const pendingSession = Boolean(
            session
            && !session.finished
            && state.recorder.sessionId === session.id
        );
        return Boolean(state.recorder.starting || state.recorder.stopping || pendingSession || isRecording());
    }

    function showRecordingNavigationError(message) {
        setFieldError("audio-file", message);
        const workspace = $("#visit-recording-workspace");
        workspace.hidden = false;
        window.requestAnimationFrame(() => {
            workspace.scrollIntoView({ behavior: "smooth", block: "center" });
        });
    }

    function hasVisitMediaForStoreChange() {
        return Boolean(
            state.files.photo
            || state.files.wechat
            || state.submission.audioSegments.length
            || state.submission.uploadedMedia.length
            || state.submission.mediaUploadAttempts.length
        );
    }

    function confirmVisitMediaResetForStoreChange() {
        if (!hasVisitMediaForStoreChange()) return true;
        if (!window.confirm("更换本次拜访门店会清除已添加的录音、照片和截图，确定继续吗？")) {
            return false;
        }
        return true;
    }

    function resetVisitMediaForStoreChange() {
        resetLocalFile("photo");
        resetLocalFile("wechat");
        resetLocalFile("audio");
        state.submission.uploadedMedia = [];
        state.submission.mediaUploadAttempts = [];
        $("#recording-consent").checked = false;
        clearFieldError("audio-file");
        clearFieldError("storefront-photo");
        clearFieldError("wechat-screenshot");
        renderUploadedBadges();
    }

    function goToFlowStep(flow, requestedStep, options = {}) {
        if (!Object.prototype.hasOwnProperty.call(FLOW_STEPS, flow) || state.submitting) return false;
        const key = flowStateKey(flow);
        const current = normalizeFlowStep(state.ui[key]);
        const target = normalizeFlowStep(requestedStep, current);
        if (flow === "visit" && isBusinessLocked() && target !== 3) {
            showError("当前草稿的门店和拜访内容已锁定，请继续完成媒体上传或放弃草稿。");
            return false;
        }
        if (flow === "visit" && target !== current && recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再进入下一步或返回选店。");
            return false;
        }
        if (options.validateForward !== false && target > current) {
            for (let step = current; step < target; step += 1) {
                if (!validateFlowStep(flow, step)) {
                    renderFlowSteps();
                    scrollToFirstError();
                    return false;
                }
            }
        }
        const inputWasActive = releaseActiveInput();
        if (target === current) return true;
        state.ui[key] = target;
        renderFlowSteps();
        if (options.persist !== false) {
            syncStateFromForm();
            persistDraft();
        }
        if (options.scroll !== false) {
            const panel = document.querySelector(
                '[data-flow-step-panel="' + flow + '"][data-step-value="' + target + '"]');
            const scrollTarget = flow === "visit" && target === 2
                ? $("#visit-recording-workspace") : panel;
            runAfterMobileInputSettles(() => scrollTarget?.scrollIntoView({
                behavior: "auto", block: "start"
            }), inputWasActive);
        }
        return true;
    }

    async function fetchOptions(city) {
        const query = city ? `?city=${encodeURIComponent(city)}` : "";
        const data = await requestJson(`/options${query}`);
        const normalized = normalizeResponse(data) || {};

        mergeArrayOption("cities", normalized.cities);
        mergeArrayOption("storeAttributes", normalized.storeAttributes);
        mergeArrayOption("operatingStatuses", normalized.operatingStatuses);
        mergeArrayOption("areaRanges", normalized.areaRanges);
        mergeArrayOption("businessTypes", normalized.businessTypes);
        mergeArrayOption("intendedBusinesses", normalized.intendedBusinesses);
        mergeArrayOption("cooperationIntents", normalized.cooperationIntents);
        mergeArrayOption("storeGrades", normalized.storeGrades);
        mergeArrayOption("storeTags", normalized.storeTags);

        if (city && Array.isArray(normalized.salespersons)) {
            state.salespersonsByCity.set(city, normalized.salespersons);
        }
        return normalized;
    }

    function mergeArrayOption(name, values) {
        if (Array.isArray(values)) state.options[name] = values;
    }

    function populateCitySelects() {
        const workCities = isHeadquartersIdentity()
            ? state.options.cities.filter((city) => city !== HEADQUARTERS_CITY)
            : state.options.cities;
        renderSelect($("#visit-city"), workCities, "请选择业务归属城市", state.visit.city);
        renderSelect($("#store-city"), workCities, "请选择业务归属城市", state.store.city);
        renderSelect($("#identity-city"), state.options.cities, "请选择本人所属城市",
            $("#identity-city")?.value || "");
    }

    async function restoreDependentOptions() {
        const cities = [...new Set([state.visit.city, state.store.city].filter(Boolean))];
        for (const city of cities) {
            await ensureSalespersons(city);
        }
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
    }

    async function ensureSalespersons(city) {
        if (!city || state.salespersonsByCity.has(city)) return;
        await fetchOptions(city);
    }

    function renderSalespersonSelect(scope) {
        const current = state[scope];
        const select = $(`#${scope}-salesperson`);
        if (state.identity?.authenticated) {
            const person = {
                id: String(state.identity.salespersonId),
                name: state.identity.salespersonName || "当前销售"
            };
            current.salespersonId = person.id;
            renderSelect(select, [person], "已验证销售", person.id,
                (item) => item.id, (item) => item.name);
            select.disabled = true;
            if (scope === "store") renderStoreOwnerSummary();
            return;
        }
        if (!current.city) {
            renderSelect(select, [], "请先选择城市", "");
            select.disabled = true;
            return;
        }
        const people = state.salespersonsByCity.get(current.city) || [];
        renderSelect(select, people, people.length ? "请选择销售" : "当前城市暂无销售", current.salespersonId,
            (person) => person.id, (person) => person.name);
        select.disabled = people.length === 0 || state.identity?.authenticated === true;
        if (scope === "store") renderStoreOwnerSummary();
    }

    async function handleCityChange(scope) {
        if (state.submitting) return;
        if (scope === "visit" && isBusinessLocked()) return;
        hideError();
        const city = $(`#${scope}-city`).value;
        state[scope].city = city;
        state[scope].salespersonId = state.identity?.authenticated
            ? String(state.identity.salespersonId)
            : "";
        cancelLocationCapture(scope);
        state.locationControllers[scope]?.abort();
        state.locationControllers[scope] = null;
        state[scope].locationContext = null;
        if (scope === "visit") {
            abortStoreDirectorySearch();
            hideStoreResults();
            clearSelectedStore(false, false);
            state.visit.nearbyStores = [];
            state.visit.directoryStores = [];
            state.visit.directoryQuery = "";
            $("#store-search").value = "";
            renderNearbyStores();
        } else {
            abortPoiSearch();
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchLookupStatus = null;
            state.store.poiSearchQuery = "";
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
            $("#poi-search").value = "";
            clearSourcePoi(true, true);
            hidePoiResults();
            renderStoreSource();
            renderStorePrefillMessage();
        }
        renderLocation(scope);
        renderSalespersonSelect(scope);
        persistDraft();
        if (!city) return;
        try {
            await ensureSalespersons(city);
            renderSalespersonSelect(scope);
            renderDictionaryControls();
        } catch (error) {
            showError(errorMessage(error, "加载该城市的销售列表失败，请稍后重试。"));
        }
        if (state[scope].location) await resolveLocationContext(scope);
    }

    function renderDictionaryControls() {
        renderSelect($("#store-attribute"), state.options.storeAttributes, "请选择", state.store.attribute);
        renderSelect($("#operating-status"), state.options.operatingStatuses, "请选择", state.store.operatingStatus);
        renderSelect($("#area-range"), state.options.areaRanges, "请选择", state.store.areaRange);
        renderSelect($("#cooperation-intent"), state.options.cooperationIntents, "请选择", state.store.cooperationIntent);
        renderSelect($("#store-grade"), state.options.storeGrades, "选填", state.store.storeGrade);
        renderChoiceGrid("business-types", state.options.businessTypes, state.store.businessTypes);
        renderChoiceGrid("intended-businesses", state.options.intendedBusinesses, state.store.intendedBusinesses);
        renderChoiceGrid("store-tags", state.options.storeTags, state.store.tags);
    }

    function renderSelect(select, items, placeholder, selected, valueResolver = optionValue, labelResolver = optionLabel) {
        select.replaceChildren();
        const placeholderOption = document.createElement("option");
        placeholderOption.value = "";
        placeholderOption.textContent = placeholder;
        select.appendChild(placeholderOption);

        (Array.isArray(items) ? items : []).forEach((item) => {
            const option = document.createElement("option");
            option.value = String(valueResolver(item) ?? "");
            option.textContent = String(labelResolver(item) ?? option.value);
            select.appendChild(option);
        });
        if (selected && Array.from(select.options).some((item) => item.value === String(selected))) {
            select.value = String(selected);
        }
    }

    function renderChoiceGrid(id, items, selectedValues) {
        const root = $(`#${id}`);
        root.replaceChildren();
        if (!Array.isArray(items) || items.length === 0) {
            const empty = document.createElement("span");
            empty.className = "field__help";
            empty.textContent = "暂无可选项，请联系管理员维护。";
            root.appendChild(empty);
            return;
        }
        const selected = new Set((selectedValues || []).map(String));
        items.forEach((item, index) => {
            const value = String(optionValue(item) ?? "");
            const label = document.createElement("label");
            label.className = "choice-chip";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.value = value;
            input.name = id;
            input.id = `${id}-${index}`;
            input.checked = selected.has(value);
            const text = document.createElement("span");
            text.textContent = String(optionLabel(item) ?? value);
            label.append(input, text);
            root.appendChild(label);
        });
    }

    function optionValue(item) {
        if (item == null) return "";
        if (typeof item !== "object") return item;
        return item.value ?? item.code ?? item.id ?? item.name ?? item.label ?? "";
    }

    function optionLabel(item) {
        if (item == null) return "";
        if (typeof item !== "object") return item;
        return item.label ?? item.name ?? item.value ?? item.code ?? item.id ?? "";
    }

    function visitNearbyOptions() {
        return (Array.isArray(state.visit.nearbyStores) ? state.visit.nearbyStores : [])
            .filter((store) => store?.source === "REGISTERED")
            .filter(isUsableNearbyStore);
    }

    function storePoiLookupStatus() {
        return Array.isArray(state.store.poiSearchResults)
            ? cleanText(state.store.poiSearchLookupStatus) || "UNAVAILABLE"
            : "";
    }

    function registeredNearbyStores() {
        return visitNearbyOptions();
    }

    function visitDirectoryOptions() {
        return (Array.isArray(state.visit.directoryStores) ? state.visit.directoryStores : [])
            .filter((store) => store?.source === "REGISTERED")
            .filter(isUsableNearbyStore);
    }

    function abortStoreDirectorySearch() {
        state.storeDirectoryController?.abort();
        state.storeDirectoryController = null;
        const toggle = $("#store-search-toggle");
        if (toggle) {
            toggle.disabled = false;
            toggle.textContent = locationExceptionReady(state.visit.locationContext)
                ? "搜索门店" : "展开";
        }
    }

    function abortPoiSearch() {
        state.poiSearchController?.abort();
        state.poiSearchController = null;
        $("#poi-search-spinner").hidden = true;
    }

    function handlePoiSearchInput() {
        if (state.poiSearchController) return;
        const query = $("#poi-search").value.trim();
        if (query !== state.store.poiSearchQuery) {
            state.store.poiSearchResults = null;
            state.store.poiSearchLookupStatus = null;
            state.store.poiSearchQuery = query;
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
        }
        renderStoreSource();
        renderPoiOptions(true);
    }

    async function searchNewStoreOnce() {
        if (state.submitting || state.poiSearchController) return;
        const diagnosticId = secureUuid();
        const query = $("#poi-search").value.trim();
        if (query.length < 2) {
            emitClientDiagnostic("SEARCH_CLICK", "BLOCKED", {}, diagnosticId);
            $("#poi-search-help").textContent = "请输入至少 2 个字后再点击搜索。";
            $("#poi-search").focus();
            return;
        }
        if (!locationContextReady(state.store.locationContext) || !state.store.location) {
            emitClientDiagnostic("SEARCH_CLICK", "BLOCKED", {}, diagnosticId);
            $("#poi-search-help").textContent = "请先完成现场定位，再搜索高德新门店。";
            return;
        }
        emitClientDiagnostic("SEARCH_CLICK", "STARTED", {}, diagnosticId);
        clearFieldError("store-source");
        const controller = createRequestController();
        state.poiSearchController = controller;
        state.store.poiSearchResults = null;
        state.store.poiSearchLookupStatus = null;
        state.store.poiSearchQuery = query;
        state.store.manualEntryAllowed = false;
        state.store.manualEntryToken = "";
        if (state.store.sourceMode === "MANUAL") {
            state.store.sourceMode = "";
            state.store.name = "";
            $("#store-name").value = "";
        }
        hidePoiResults();
        $("#poi-search-spinner").hidden = false;
        renderStoreSource();
        try {
            const payload = normalizeResponse(await requestJson("/locations/search-new-store", {
                method: "POST",
                headers: { "X-Sales-Checkin-Client-Event-Id": diagnosticId },
                body: {
                    clientStoreId: state.store.clientStoreId,
                    city: state.store.city,
                    salespersonId: state.store.salespersonId,
                    location: locationRequestValue("store"),
                    q: query,
                    locationVerificationToken: state.store.locationContext?.locationVerificationToken
                },
                signal: controller.signal,
                timeout: 20000
            })) || {};
            if (state.poiSearchController !== controller) return;
            const stores = Array.isArray(payload.nearbyStores)
                ? payload.nearbyStores
                    .filter((store) => store?.source === "AMAP_POI")
                    .filter(isUsableNearbyStore)
                : [];
            const poiLookupStatus = cleanText(payload.poiLookupStatus) || "UNAVAILABLE";
            const manualEntryToken = cleanText(payload.manualEntryToken);
            state.store.poiSearchResults = stores;
            state.store.poiSearchLookupStatus = poiLookupStatus;
            state.store.manualEntryToken = manualEntryToken;
            state.store.manualEntryAllowed = Boolean(manualEntryToken)
                && (poiLookupStatus === "EMPTY" || poiLookupStatus === "UNAVAILABLE");
            emitClientDiagnostic("SEARCH_RESULT", poiLookupStatus, {
                itemCount: stores.length
            }, diagnosticId);
            if (state.store.manualEntryAllowed) {
                hidePoiResults();
                window.requestAnimationFrame(() => {
                    $("#manual-store-button").scrollIntoView({ behavior: "smooth", block: "nearest" });
                });
            }
            persistDraft();
        } catch (error) {
            if (state.poiSearchController !== controller) return;
            if (error.name === "AbortError") return;
            state.store.poiSearchResults = [];
            state.store.poiSearchLookupStatus = null;
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
            emitClientDiagnostic("SEARCH_RESULT", "FAILED", {}, diagnosticId);
            $("#poi-search-help").textContent = "未收到服务端搜索确认，请检查网络后重新点击搜索。";
            persistDraft();
        } finally {
            if (state.poiSearchController === controller) {
                state.poiSearchController = null;
                $("#poi-search-spinner").hidden = true;
                renderStoreSource();
                if (state.store.poiSearchLookupStatus === "AVAILABLE"
                    && state.store.poiSearchResults?.length) {
                    renderPoiOptions(true);
                }
            }
        }
    }

    function handleVisitStoreSearchInput() {
        if (locationExceptionReady(state.visit.locationContext)) {
            if (state.storeDirectoryController) abortStoreDirectorySearch();
            const query = $("#store-search").value.trim();
            if (query !== state.visit.directoryQuery) {
                state.visit.directoryStores = [];
                state.visit.directoryQuery = "";
            }
        }
        showVisitStoreOptions();
    }

    function showVisitStoreOptions() {
        const input = $("#store-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        const directoryMode = locationExceptionReady(state.visit.locationContext);
        const options = directoryMode ? visitDirectoryOptions() : visitNearbyOptions();
        const stores = options.filter((store) => {
            if (!query) return true;
            return [store.name, store.address, store.locationSummary]
                .some((value) => cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        renderStoreResults(stores);
        $("#store-search-help").textContent = stores.length
            ? directoryMode
                ? `已按门店名称找到 ${stores.length} 家，请核对后选择。`
                : `${visitRadiusLabel()}已加载 ${options.length} 家已建档门店；当前筛选显示 ${stores.length} 家`
            : options.length
                ? "已加载的门店中没有匹配项，可换关键词或新增门店。"
                : directoryMode
                    ? "请输入至少 2 个字，再点击“搜索门店”；找不到可新增门店。"
                    : "附近没有已建档门店，可点击下方“新增门店”。";
    }

    function toggleVisitStoreOptions() {
        if ($("#store-search").disabled) return;
        if (locationExceptionReady(state.visit.locationContext)) {
            void searchVisitStoreDirectory();
            return;
        }
        if ($("#store-search-results").hidden) {
            showVisitStoreOptions();
            $("#store-search").focus();
        } else {
            hideStoreResults();
        }
    }

    async function searchVisitStoreDirectory() {
        if (state.submitting || state.storeDirectoryController) return;
        const input = $("#store-search");
        const query = input.value.trim();
        if (!locationExceptionReady(state.visit.locationContext)) {
            showVisitStoreOptions();
            return;
        }
        if (query.length < 2) {
            $("#store-search-help").textContent = "请输入至少 2 个字，再点击“搜索门店”。";
            input.focus();
            return;
        }
        const controller = createRequestController();
        state.storeDirectoryController = controller;
        const toggle = $("#store-search-toggle");
        toggle.disabled = true;
        toggle.textContent = "搜索中…";
        $("#store-search-help").textContent = `正在按“${query}”搜索${state.visit.city}已建档门店…`;
        try {
            const path = `/stores?city=${encodeURIComponent(state.visit.city)}&q=${encodeURIComponent(query)}&limit=20`;
            const payload = normalizeResponse(await requestJson(path, {
                signal: controller.signal,
                timeout: 20000
            }));
            if (state.storeDirectoryController !== controller) return;
            const stores = (Array.isArray(payload) ? payload : [])
                .map((store) => ({
                    ...store,
                    source: "REGISTERED",
                    storeId: store.storeId || store.id,
                    checkinEligible: true,
                    nextAction: "CHECK_IN",
                    directoryMatch: true
                }))
                .filter(isUsableNearbyStore);
            state.visit.directoryStores = stores;
            state.visit.directoryQuery = query;
            renderStoreResults(stores);
            $("#store-search-help").textContent = stores.length
                ? `找到 ${stores.length} 家已建档门店；定位未核验，请按门店名称核对后选择。`
                : "没有找到同名已建档门店，可换关键词或新增门店。";
            persistDraft();
        } catch (error) {
            if (state.storeDirectoryController !== controller || error.name === "AbortError") return;
            $("#store-search-help").textContent = errorMessage(error, "门店搜索失败，请检查网络后重试。");
        } finally {
            if (state.storeDirectoryController === controller) {
                state.storeDirectoryController = null;
                toggle.disabled = false;
                toggle.textContent = "搜索门店";
            }
        }
    }

    function renderStoreResults(stores) {
        const root = $("#store-search-results");
        root.replaceChildren();
        if (!stores.length) {
            const empty = document.createElement("div");
            empty.className = "search-empty";
            empty.textContent = "已加载的门店中没有匹配结果，可换关键词或新增门店。";
            root.appendChild(empty);
        } else {
            stores.forEach((store) => {
                const button = document.createElement("button");
                button.type = "button";
                button.className = "search-result visit-store-result is-registered";
                button.setAttribute("role", "option");

                const detail = document.createElement("span");
                const name = document.createElement("strong");
                name.textContent = store.name || "未命名门店";
                const location = document.createElement("span");
                const businessCityHint = cleanText(store.city)
                    && cleanText(store.city) !== cleanText(state.visit.city)
                    ? ` · 门店归属${cleanText(store.city)}` : "";
                location.textContent = `${store.locationSummary || store.address
                    || "暂无位置摘要"}${businessCityHint}`;
                detail.append(name, location);

                const meta = document.createElement("span");
                meta.className = "visit-store-result__meta";
                const status = document.createElement("strong");
                const directoryMode = store.directoryMatch === true
                    || locationExceptionReady(state.visit.locationContext);
                status.textContent = directoryMode
                    ? "定位未核验 · 按门店名称选择"
                    : "已建档 · 直接打卡";
                const distance = document.createElement("small");
                if (!directoryMode) {
                    distance.textContent = formatDistance(store.distanceMeters) || "附近";
                    meta.append(status, distance);
                } else {
                    meta.append(status);
                }

                button.append(detail, meta);
                button.addEventListener("click", () => selectStore(store));
                root.appendChild(button);
            });
        }
        root.hidden = false;
        $("#store-search").setAttribute("aria-expanded", "true");
    }

    function hideStoreResults() {
        $("#store-search-results").hidden = true;
        $("#store-search").setAttribute("aria-expanded", "false");
    }

    function handleStoreSearchKeydown(event) {
        if (event.isComposing) return;
        if (event.key === "Escape") {
            hideStoreResults();
            event.currentTarget.blur();
        }
        if (event.key === "Enter" && locationExceptionReady(state.visit.locationContext)) {
            event.preventDefault();
            void searchVisitStoreDirectory();
            return;
        }
        if (event.key === "ArrowDown" && $("#store-search-results").hidden) {
            event.preventDefault();
            showVisitStoreOptions();
        }
    }

    function selectStore(store) {
        if (isBusinessLocked()) return;
        const storeId = store.id || store.storeId;
        if (!storeId) return;
        const previousStoreId = state.visit.selectedStore?.id;
        if (previousStoreId && String(previousStoreId) !== String(storeId)
                && !confirmVisitMediaResetForStoreChange()) return;
        if (previousStoreId && String(previousStoreId) !== String(storeId)) {
            resetVisitMediaForStoreChange();
        }
        hideStoreSavedNotice();
        state.visit.selectedStore = {
            id: storeId,
            name: store.name || "未命名门店",
            city: store.city || state.visit.city,
            locationSummary: store.locationSummary || store.address || "",
            locationVerificationStatus: cleanText(store.locationVerificationStatus)
        };
        $("#store-search").value = state.visit.selectedStore.name;
        hideStoreResults();
        renderSelectedStore();
        renderNearbyStores();
        clearFieldError("selected-store");
        persistDraft();
    }

    function clearSelectedStore(persist = true, focusSearch = true, clearVisitMedia = false) {
        if (isBusinessLocked()) return;
        if (clearVisitMedia && state.visit.selectedStore
                && !confirmVisitMediaResetForStoreChange()) return;
        if (clearVisitMedia && state.visit.selectedStore) resetVisitMediaForStoreChange();
        hideStoreSavedNotice();
        state.visit.selectedStore = null;
        $("#store-search").value = "";
        renderSelectedStore();
        renderNearbyStores();
        if (persist) persistDraft();
        if (focusSearch && !$("#store-search").disabled) {
            $("#store-search").focus();
            showVisitStoreOptions();
        }
    }

    function renderSelectedStore() {
        const selected = state.visit.selectedStore;
        const businessCityHint = cleanText(selected?.city)
            && cleanText(selected?.city) !== cleanText(state.visit.city)
            ? ` · 门店归属${cleanText(selected.city)}` : "";
        $("#selected-store-card").hidden = !selected;
        $("#visit-step-store-name").textContent = selected?.name || "尚未选择门店";
        $("#visit-step-store-address").textContent = selected
            ? `${selected.locationSummary || selected.address || selected.city
                || "位置已采集"}${businessCityHint}`
            : "请返回第一步选择门店";
        if (!selected) {
            $("#store-search").value = "";
            renderFlowActions();
            return;
        }
        $("#selected-store-name").textContent = selected.name || "未命名门店";
        $("#selected-store-location").textContent = `${selected.locationSummary
            || selected.city || ""}${businessCityHint}`;
        $("#store-search").value = selected.name || "";
        renderFlowActions();
    }

    function locationContextReady(context) {
        return Boolean(context
            && context.accuracyAccepted === true
            && context.freshnessAccepted === true
            && Boolean(cleanText(context.locationVerificationToken))
            && finiteNumberOrNull(context.maxCheckinDistanceMeters) !== null
            && finiteNumberOrNull(context.maxCheckinAccuracyMeters) !== null
            && finiteNumberOrNull(context.maxLocationAgeMinutes) !== null);
    }

    function locationExceptionReady(context) {
        return Boolean(context
            && cleanText(context.locationVerificationStatus) === "UNVERIFIED"
            && LOCATION_FAILURE_REASONS.has(cleanText(context.locationFailureReason))
            && isUuidValue(context.locationAttemptId));
    }

    function locationFlowReady(context) {
        return locationContextReady(context) || locationExceptionReady(context);
    }

    function renderUnverifiedBanner(scope) {
        const unverified = locationExceptionReady(state[scope].locationContext);
        const banner = $(`#${scope}-unverified-banner`);
        if (banner) banner.hidden = !unverified;
    }

    function renderNearbyStores() {
        const panel = $("#nearby-stores-panel");
        const context = state.visit.locationContext;
        const options = visitNearbyOptions();
        const input = $("#store-search");
        const toggle = $("#store-search-toggle");
        const createButton = $("#create-store-link");
        const createUnavailable = state.submitting || isBusinessLocked();
        const unverified = locationExceptionReady(context);
        const flowReady = locationFlowReady(context);
        const resolving = context?.geocodeStatus === "RESOLVING";
        panel.hidden = false;
        $("#nearby-stores-empty").hidden = flowReady || resolving;
        $(".store-search-field", panel).hidden = !flowReady;
        hideStoreResults();
        $("#nearby-stores-scope").textContent = unverified
            ? "定位未核验：按业务城市和门店名称搜索，不按距离筛选"
            : locationContextReady(context)
            ? `${visitRadiusLabel(context)}的已建档门店，不受业务归属城市限制`
            : "定位通过后，仅显示当前位置允许范围内的已建档门店";
        if (!flowReady) {
            input.disabled = true;
            toggle.disabled = true;
            toggle.textContent = "展开";
            toggle.setAttribute("aria-label", "展开附近门店选项");
            createButton.disabled = createUnavailable;
            $("#nearby-stores-summary").textContent = resolving
                ? "正在解析地址并加载…" : "等待定位";
            renderFlowActions();
            return;
        }

        if (unverified) {
            createButton.disabled = createUnavailable;
            input.disabled = false;
            input.placeholder = "输入门店名称（至少2个字）";
            toggle.disabled = state.submitting;
            toggle.textContent = state.storeDirectoryController ? "搜索中…" : "搜索门店";
            toggle.setAttribute("aria-label", "按门店名称搜索已建档门店");
            $("#nearby-stores-summary").textContent = state.visit.directoryStores.length
                ? `已找到 ${state.visit.directoryStores.length} 家`
                : "按名称搜索";
            $("#store-search-help").textContent = state.visit.directoryStores.length
                ? "定位未核验；请按门店名称核对后选择，不显示距离。"
                : "输入至少 2 个字并点击“搜索门店”；找不到可新增门店。";
            renderFlowActions();
            return;
        }

        if (!locationContextReady(context)) {
            $("#nearby-stores-summary").textContent = "需要处理定位问题";
            $("#store-search-help").textContent = context?.errorMessage
                || "请使用上方重试按钮重新解析定位。";
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = createUnavailable;
            return;
        }
        createButton.disabled = createUnavailable;
        input.disabled = false;
        if (!options.length) {
            $("#nearby-stores-summary").textContent = "附近暂无已建档门店";
            $("#store-search-help").textContent = "可点击下方“新增门店”，再明确搜索高德新门店。";
            input.placeholder = "附近暂无已建档门店";
            toggle.disabled = true;
            return;
        }
        toggle.disabled = false;
        toggle.textContent = "展开";
        toggle.setAttribute("aria-label", "展开附近门店选项");
        input.placeholder = "点击选择，或输入名称筛选";
        $("#nearby-stores-summary").textContent = `${visitRadiusLabel(context)} ${options.length} 家`;
        $("#store-search-help").textContent = `已加载 ${options.length} 家已建档门店；输入文字只在本地筛选。`;
    }

    function formatDistance(value) {
        const meters = Number(value);
        if (!Number.isFinite(meters) || meters < 0) return "";
        if (meters < 1000) return `${Math.max(1, Math.round(meters))} 米`;
        return `${(meters / 1000).toFixed(meters < 10000 ? 1 : 0)} 公里`;
    }

    function visitRadiusLabel(context = state.visit.locationContext) {
        const radius = finiteNumberOrNull(context?.maxCheckinDistanceMeters);
        return radius === null ? "当前定位附近" : `${formatDistance(radius)}内`;
    }

    function nearbyPoiStores() {
        const candidates = [
            ...(Array.isArray(state.store.nearbyPois) ? state.store.nearbyPois : []),
            ...(Array.isArray(state.visit.nearbyStores) ? state.visit.nearbyStores : []),
            ...(Array.isArray(state.store.poiSearchResults) ? state.store.poiSearchResults : [])
        ].filter(isUsableNearbyStore);
        const seen = new Set();
        return candidates.filter((store) => {
            const id = `${store.source}:${store.storeId || store.poiId}`;
            if (seen.has(id)) return false;
            seen.add(id);
            return true;
        });
    }

    function renderPoiOptions(open = false) {
        const input = $("#poi-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        // 高德已经按明确提交的关键词完成服务端检索。部分 POI 名称会使用别名、英文名或
        // 分店名，不能再要求返回名称必须逐字包含输入值，否则接口有结果却会被页面隐藏。
        const explicitPoiIds = new Set((state.store.poiSearchResults || [])
            .map((poi) => cleanText(poi?.poiId))
            .filter(Boolean));
        const pois = nearbyPoiStores().filter((poi) => {
            if (poi.source === "AMAP_POI" && explicitPoiIds.has(cleanText(poi.poiId))) return true;
            if (!query) return true;
            return [poi.name, poi.address]
                .some((value) => cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        const root = $("#poi-search-results");
        const searched = Array.isArray(state.store.poiSearchResults);
        root.replaceChildren();
        if (!pois.length) {
            const empty = document.createElement("div");
            empty.className = "search-empty";
            empty.textContent = state.store.manualEntryAllowed
                ? "本次高德搜索无结果或不可用，现在可使用下方手动录入。"
                : searched
                    ? "本次高德搜索没有可用候选，可换关键词后再次明确搜索。"
                    : "没有匹配的已建档门店；输入至少 2 个字并点击搜索可查高德新门店。";
            root.appendChild(empty);
        } else {
            pois.forEach((poi) => {
                const registered = poi.source === "REGISTERED" && (poi.storeId || poi.id);
                const button = document.createElement("button");
                button.type = "button";
                const outOfRange = !registered && poi.nextAction === "OUT_OF_RANGE";
                button.className = `search-result poi-result${registered ? " is-registered" : ""}${outOfRange ? " is-out-of-range" : ""}`;
                button.setAttribute("role", "option");

                const detail = document.createElement("span");
                const name = document.createElement("strong");
                name.textContent = poi.name;
                const address = document.createElement("span");
                address.textContent = poi.address || "高德暂无详细地址";
                detail.append(name, address);

                const meta = document.createElement("span");
                meta.className = "poi-result__meta";
                const badge = document.createElement("strong");
                badge.textContent = registered
                    ? "已录入 · 直接打卡"
                    : outOfRange
                        ? "未录入 · 距离超限"
                        : "未录入 · 可建档";
                const distance = document.createElement("small");
                distance.textContent = formatDistance(poi.distanceMeters) || "附近";
                meta.append(badge, distance);
                button.append(detail, meta);
                button.addEventListener("click", () => {
                    if (registered) selectExistingStoreFromProfileFlow(poi);
                    else selectSourcePoi(poi);
                });
                root.appendChild(button);
            });
        }
        if (open) {
            root.hidden = false;
            input.setAttribute("aria-expanded", "true");
        }
        const registeredCount = pois.filter((poi) => poi.source === "REGISTERED").length;
        const amapCount = pois.length - registeredCount;
        const selectableCount = pois.filter((poi) => poi.source === "REGISTERED"
            || poi.nextAction !== "OUT_OF_RANGE").length;
        const maximumDistance = finiteNumberOrNull(state.store.locationContext?.maxCheckinDistanceMeters);
        $("#poi-search-help").textContent = pois.length && selectableCount > 0
            ? `本次返回：${registeredCount} 家已建档门店、${amapCount} 个300米内高德候选；请选择一项继续。`
            : pois.length
                ? `本次返回 ${amapCount} 个高德候选，但均超过${formatDistance(maximumDistance) || "允许距离"}，已展示供核对，暂不可选择；请到店后重新定位。`
            : state.store.manualEntryAllowed
                ? "搜索完成：没有找到可用候选，请点击下方“手动录入门店”继续。"
                : "建议输入或粘贴高德完整店名，也可用名称关键字；只有点击搜索才会请求一次高德。";
    }

    function hidePoiResults() {
        $("#poi-search-results").hidden = true;
        $("#poi-search").setAttribute("aria-expanded", "false");
    }

    function handlePoiSearchKeydown(event) {
        if (event.isComposing) return;
        if (event.key === "Escape") {
            hidePoiResults();
            event.currentTarget.blur();
            return;
        }
        if (event.key === "Enter") {
            event.preventDefault();
            void searchNewStoreOnce();
            return;
        }
        if (event.key === "ArrowDown" && $("#poi-search-results").hidden) {
            event.preventDefault();
            renderPoiOptions(true);
        }
    }

    function selectSourcePoi(poi) {
        const distance = finiteNumberOrNull(poi.distanceMeters);
        const maximum = finiteNumberOrNull(state.store.locationContext?.maxCheckinDistanceMeters);
        if (poi.nextAction === "OUT_OF_RANGE"
                || (distance !== null && maximum !== null && distance > maximum)) {
            const message = `该门店距当前位置约${formatDistance(distance) || "较远"}，超过${formatDistance(maximum) || "允许距离"}，无法选择；请到店后重新定位。`;
            setFieldError("store-source", message);
            $("#poi-search-help").textContent = message;
            return;
        }
        const selectionToken = cleanText(poi.selectionToken);
        if (!selectionToken) {
            showError("该高德候选已失效，请重新点击搜索后再选择。");
            return;
        }
        state.store.sourceMode = "POI";
        state.store.name = poi.name || "";
        state.store.sourcePoiToken = selectionToken;
        state.store.sourcePoiId = poi.poiId || "";
        state.store.sourcePoiName = poi.name || "";
        state.store.sourcePoiAddress = poi.address || "";
        state.store.sourcePoiLongitude = finiteNumberOrNull(poi.longitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(poi.latitude);
        state.store.manualEntryAllowed = false;
        state.store.manualEntryToken = "";
        $("#store-name").value = state.store.name;
        clearFieldError("store-source");
        hidePoiResults();
        renderStoreSource();
        renderStorePrefillMessage();
        goToFlowStep("store", 2, { validateForward: false });
    }

    function selectExistingStoreFromProfileFlow(store) {
        const inputWasActive = releaseActiveInput();
        abortPoiSearch();
        const exists = state.visit.nearbyStores.some((item) =>
            item.source === "REGISTERED"
            && String(item.storeId || item.id) === String(store.storeId || store.id));
        if (!exists) state.visit.nearbyStores.unshift(store);
        selectStore(store);
        state.activeTab = "visit";
        state.ui.visitStep = 2;
        renderTab("visit");
        renderNearbyStores();
        $("#restore-notice").hidden = true;
        $("#store-saved-name").textContent = store.name;
        $("#store-saved-notice").hidden = false;
        persistDraft();
        runAfterMobileInputSettles(() => $("#store-saved-notice").scrollIntoView({
            behavior: "auto", block: "start"
        }), inputWasActive);
    }

    function clearSelectedPoi() {
        clearSourcePoi(true, true);
        state.ui.storeStep = 1;
        $("#poi-search").value = state.store.poiSearchQuery;
        renderStoreSource();
        renderStorePrefillMessage();
        persistDraft();
    }

    function enableManualStoreEntry() {
        if (state.store.sourceMode === "MANUAL") {
            if (locationExceptionReady(state.store.locationContext)) return;
            state.store.sourceMode = "";
            state.store.name = "";
            state.ui.storeStep = 1;
            $("#store-name").value = "";
            renderStoreSource();
            persistDraft();
            return;
        }
        if (!state.store.manualEntryAllowed) return;
        const suggestedName = $("#poi-search").value.trim();
        clearSourcePoi(false, false);
        state.store.sourceMode = "MANUAL";
        state.store.name = suggestedName;
        $("#store-name").value = suggestedName;
        hidePoiResults();
        clearFieldError("store-source");
        renderStoreSource();
        renderStorePrefillMessage();
        goToFlowStep("store", 2, { validateForward: false });
    }

    function renderStoreSource() {
        const context = state.store.locationContext;
        const pois = nearbyPoiStores();
        const input = $("#poi-search");
        const searchButton = $("#poi-search-button");
        const selected = state.store.sourceMode === "POI"
            && Boolean(state.store.sourcePoiId)
            && Boolean(state.store.sourcePoiToken);
        const manual = state.store.sourceMode === "MANUAL";
        const ready = locationContextReady(context);
        const unverified = locationExceptionReady(context);
        if (unverified && !selected) {
            state.store.manualEntryAllowed = true;
            state.store.poiSearchLookupStatus = "UNAVAILABLE";
        }
        const lookupStatus = storePoiLookupStatus();
        const canSearch = ready && !selected && !unverified;
        const searching = Boolean(state.poiSearchController);
        const searchQuery = input.value.trim();
        $("#store-source-description").textContent = unverified
            ? "本次定位未核验，不限制继续录入；请手工填写真实门店资料。"
            : "输入门店名称后点击“搜索”，只返回当前位置300米内候选；输入本身不会调用高德。";

        input.disabled = !canSearch || searching;
        searchButton.hidden = selected;
        searchButton.disabled = !canSearch || searching || searchQuery.length < 2;
        searchButton.textContent = searching ? "搜索中…" : "搜索";
        $(".poi-search-field").hidden = selected || unverified;
        $("#selected-poi-card").hidden = !selected;
        $("#store-profile-card").hidden = !selected && !manual;
        $(".button-row").hidden = !selected && !manual;
        $("#submit-store-button").disabled = !selected && !manual;
        $("#store-name").readOnly = selected;
        $("#store-name-field").classList.toggle("is-readonly", selected);

        if (selected) {
            $("#selected-poi-name").textContent = state.store.sourcePoiName || state.store.name;
            $("#selected-poi-address").textContent = state.store.sourcePoiAddress || "高德暂无详细地址";
            $("#store-name-help").textContent = "门店名称来自高德；如果选错，请返回上一步重选。";
        } else if (manual) {
            $("#store-name-help").textContent = "当前为手动录入，请使用门店完整名称。";
        } else {
            $("#store-name-help").textContent = "";
        }

        const manualButton = $("#manual-store-button");
        manualButton.disabled = searching || (!manual && !state.store.manualEntryAllowed);
        manualButton.classList.toggle("is-active", manual);
        manualButton.classList.toggle("is-ready", !manual && state.store.manualEntryAllowed);
        manualButton.querySelector("strong").textContent = manual
            ? unverified ? "定位未核验 · 手动录入门店" : "已选择手动录入"
            : state.store.manualEntryAllowed
                ? unverified ? "直接手动录入门店" : "仍未找到，手动录入门店"
                : "高德附近搜索没找到？";
        manualButton.querySelector("span").textContent = manual
            ? unverified
                ? "请填写真实门店名称和资料；本次定位情况会一并保存"
                : "点击可返回高德附近搜索结果"
            : state.store.manualEntryAllowed
                ? unverified
                    ? "不等待定位和高德搜索，继续补全基础资料"
                    : "以当前 GPS 作为门店位置，继续补全基础资料"
                : "确认当前位置300米内搜索无结果后，再手动录入门店名称";

        if (unverified) {
            $("#poi-search-help").textContent = "定位未核验，本次新增门店使用手工录入。";
        } else if (!state.store.location) {
            input.placeholder = "先获取定位";
            $("#poi-search-help").textContent = "先完成上方现场定位，才能搜索高德新门店。";
        } else if (context?.geocodeStatus === "RESOLVING") {
            input.placeholder = "正在解析定位";
            $("#poi-search-help").textContent = "正在解析定位并加载已建档门店…";
        } else if (!locationContextReady(context)) {
            input.placeholder = "定位暂不可用";
            $("#poi-search-help").textContent = context?.errorMessage
                || "请重新获取符合精度和时效要求的定位。";
        } else if (searching) {
            input.placeholder = "正在搜索高德新门店";
            $("#poi-search-help").textContent = `正在按“${state.store.poiSearchQuery}”搜索，本次操作只发起一次请求…`;
        } else if (lookupStatus === "UNAVAILABLE") {
            input.placeholder = "可换关键词后再次搜索";
            $("#poi-search-help").textContent = "本次高德搜索暂不可用，可点击下方手工录入继续；保存时仍校验当前位置。";
        } else if (lookupStatus === "EMPTY") {
            input.placeholder = "可换关键词后再次搜索";
            $("#poi-search-help").textContent = "本次高德搜索无结果，可点击下方手工录入。";
        } else if (Array.isArray(state.store.poiSearchResults)) {
            const amapCount = state.store.poiSearchResults.length;
            input.placeholder = "可换关键词后再次搜索";
            $("#poi-search-help").textContent = `本次高德搜索返回 ${amapCount} 个候选；输入新关键词不会自动请求。`;
        } else if (!selected) {
            const registeredCount = pois.filter((poi) => poi.source === "REGISTERED").length;
            input.placeholder = "输入至少 2 个字，再点击搜索";
            $("#poi-search-help").textContent = registeredCount
                ? `已加载 ${registeredCount} 家300米内已建档门店；搜索附近高德门店需点击“搜索”。`
                : "输入至少 2 个字并点击“搜索”当前位置300米内门店；输入本身不会请求高德。";
        }
        renderFlowSteps();
    }

    async function prepareNewStore() {
        if (state.submitting) return;
        if (recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再进入新增门店。");
            return;
        }
        if (isBusinessLocked()) return;
        abortStoreDirectorySearch();
        hideStoreResults();
        $("#store-search").value = state.visit.selectedStore?.name || "";
        hideStoreSavedNotice();
        syncStateFromForm();
        clearFieldError("visit-city");
        clearFieldError("visit-salesperson");
        if (!state.visit.city || !state.visit.salespersonId) {
            if (!state.visit.city) setFieldError("visit-city", "请先选择城市。");
            if (!state.visit.salespersonId) setFieldError("visit-salesperson", "请先选择销售。");
            const target = !state.visit.city ? $("#visit-city") : $("#visit-salesperson");
            target?.scrollIntoView({ behavior: "smooth", block: "center" });
            target?.focus();
            return;
        }
        clearFieldError("visit-location");
        if (!locationFlowReady(state.visit.locationContext)) {
            setFieldError("visit-location", state.visit.locationContext?.errorMessage
                || "请先刷新定位；若定位失败，可按页面提示继续新增门店。");
            $("#visit-location-button")?.scrollIntoView({ behavior: "smooth", block: "center" });
            $("#visit-location-button")?.focus();
            return;
        }
        const locationChanged = state.store.location?.capturedAt
            && state.visit.location?.capturedAt
            && state.store.location.capturedAt !== state.visit.location.capturedAt;
        if (state.store.city && (state.store.city !== state.visit.city || locationChanged)) {
            state.store = freshStore();
        }
        state.store.city = state.visit.city || state.store.city;
        state.store.salespersonId = state.visit.salespersonId || state.store.salespersonId;
        state.store.location = state.visit.location ? { ...state.visit.location } : null;
        state.store.locationContext = state.visit.locationContext
            ? { ...state.visit.locationContext }
            : null;
        if (locationExceptionReady(state.store.locationContext)) {
            state.store.sourceMode = "MANUAL";
            state.store.manualEntryAllowed = true;
            state.store.manualEntryToken = "";
            state.store.poiSearchLookupStatus = "UNAVAILABLE";
            clearSourcePoi(false, false);
        }
        state.store.nearbyPois = (Array.isArray(state.visit.nearbyStores) ? state.visit.nearbyStores : [])
            .filter((store) => store?.source === "REGISTERED")
            .filter(isUsableNearbyStore);
        state.ui.storeStep = deriveStoreStep();
        persistDraft();
        try {
            if (state.store.city) await ensureSalespersons(state.store.city);
        } catch (error) {
            showError(errorMessage(error, "加载销售列表失败。"));
        }
        renderDictionaryControls();
        renderRestoredValues();
        renderStoreOwnerSummary();
        renderLocation("store");
        renderStoreSource();
        renderStorePrefillMessage();
        switchTab("store");
    }

    function clearSourcePoi(resetMode = false, clearName = false) {
        state.store.sourcePoiToken = "";
        state.store.sourcePoiId = "";
        state.store.sourcePoiName = "";
        state.store.sourcePoiAddress = "";
        state.store.sourcePoiLongitude = null;
        state.store.sourcePoiLatitude = null;
        if (resetMode) state.store.sourceMode = "";
        if (clearName) {
            state.store.name = "";
            $("#store-name").value = "";
        }
    }

    function renderStorePrefillMessage() {
        const message = $("#store-prefill-message");
        if (!state.store.sourcePoiToken) {
            message.textContent = state.store.sourceMode === "MANUAL"
                ? locationExceptionReady(state.store.locationContext)
                    ? "定位未核验，当前使用手动录入；保存后会自动返回打卡并选中这家门店。"
                    : "已明确选择手动录入；保存后会自动返回打卡并选中这家门店。"
                : "请输入门店名称并明确点击搜索；无结果或高德不可用时才可手工录入。";
            return;
        }
        const name = state.store.sourcePoiName || state.store.name || "附近地点";
        const address = state.store.sourcePoiAddress ? `（${state.store.sourcePoiAddress}）` : "";
        message.textContent = `已预填“${name}”${address}，定位使用当前 GPS，请核对并补齐必填资料。`;
    }

    function renderStoreOwnerSummary() {
        const city = state.store.city || $("#store-city")?.value || "";
        const salespersonId = state.store.salespersonId || $("#store-salesperson")?.value || "";
        const people = state.salespersonsByCity.get(city) || [];
        const person = people.find((item) => String(item.id) === String(salespersonId));
        const selectedName = $("#store-salesperson")?.selectedOptions?.[0]?.textContent || "";
        $(".store-owner-card")?.classList.toggle("is-incomplete", !city || !salespersonId);
        $("#store-owner-city").textContent = city || "未选择";
        $("#store-owner-salesperson").textContent = person?.name
            || (selectedName && !selectedName.startsWith("请") ? selectedName : "未选择");
    }

    function locationFailureReason(error, staleOnly = false) {
        if (staleOnly) return "TIMESTAMP_UNUSABLE";
        if (error?.code === 1) return "PERMISSION_DENIED";
        if (error?.code === 2) return "POSITION_UNAVAILABLE";
        if (error?.code === 3) return "TIMEOUT";
        return "POSITION_UNAVAILABLE";
    }

    function locationEvidenceFromPosition(position, receivedAtMs = Date.now()) {
        // position.timestamp 无法解释时，不能把它伪装成可信采集时间；这里只把回调接收时刻
        // 作为“未核验坐标”的留档时间，最终记录仍由 locationVerificationStatus 明确标记。
        return normalizeUnverifiedLocationEvidence({
            longitude: position?.coords?.longitude,
            latitude: position?.coords?.latitude,
            accuracyMeters: position?.coords?.accuracy,
            capturedAt: new Date(receivedAtMs).toISOString()
        });
    }

    function normalizeUnverifiedLocationEvidence(value) {
        const longitude = finiteNumberOrNull(value?.longitude);
        const latitude = finiteNumberOrNull(value?.latitude);
        const accuracy = finiteNumberOrNull(value?.accuracyMeters);
        const capturedAt = normalizeOptionalInstant(value?.capturedAt);
        if (longitude === null || latitude === null || accuracy === null || !capturedAt
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90
                || accuracy < 0 || accuracy > MAX_RECORDED_UNVERIFIED_ACCURACY_METERS) {
            return null;
        }
        return {
            longitude: roundCoordinate(longitude),
            latitude: roundCoordinate(latitude),
            accuracyMeters: roundAccuracy(accuracy),
            capturedAt,
            ...(cleanText(value?.note) ? { note: cleanText(value.note) } : {})
        };
    }

    function setUnverifiedLocation(scope, reason, message, attemptId, evidence = null, extra = {}) {
        const normalizedReason = LOCATION_FAILURE_REASONS.has(cleanText(reason))
            ? cleanText(reason) : "POSITION_UNAVAILABLE";
        const normalizedAttemptId = isUuidValue(attemptId) ? attemptId : secureUuid();
        const note = $(`#${scope}-location-note`)?.value.trim();
        const availableLocation = normalizeUnverifiedLocationEvidence(
            evidence || state[scope].location || null);
        state[scope].location = availableLocation
            ? { ...availableLocation, ...(note ? { note } : {}) }
            : null;
        state[scope].locationContext = {
            ...extra,
            geocodeStatus: cleanText(extra.geocodeStatus) || "FAILED",
            locationVerificationStatus: "UNVERIFIED",
            locationFailureReason: normalizedReason,
            locationAttemptId: normalizedAttemptId,
            locationVerificationToken: "",
            errorMessage: message,
            locationMessage: message,
            canContinueWithoutLocation: false
        };
        if (scope === "visit") {
            abortStoreDirectorySearch();
            state.visit.nearbyStores = [];
            state.visit.directoryStores = [];
            state.visit.directoryQuery = "";
        } else {
            abortPoiSearch();
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchQuery = "";
            state.store.poiSearchLookupStatus = "UNAVAILABLE";
            state.store.manualEntryAllowed = true;
            state.store.manualEntryToken = "";
            clearSourcePoi(true, false);
        }
        clearFieldError(`${scope}-location`);
        const button = $(`#${scope}-location-button`);
        if (button) button.disabled = false;
        renderLocation(scope);
        if (scope === "visit") renderNearbyStores();
        else renderStoreSource();
        renderBusinessLock();
        persistDraft();
    }

    function continueWithoutVerifiedLocation(scope) {
        if (state.submitting || (scope === "visit" && isBusinessLocked())) return;
        const context = state[scope].locationContext;
        if (context?.geocodeStatus !== "CAPTURING" || context.canContinueWithoutLocation !== true) return;
        const attemptId = context.locationAttemptId;
        const evidence = context.candidateLocation || null;
        cancelLocationCapture(scope);
        setUnverifiedLocation(
            scope,
            "USER_CONTINUED_AFTER_WAIT",
            "手机定位仍在等待，已转为定位未核验；可以继续录入，后台会结合现场照片复核。",
            attemptId,
            evidence
        );
        emitClientDiagnostic("LOCATION_RESULT", "FALLBACK", {}, attemptId);
    }

    async function captureLocation(scope) {
        if (state.submitting) return;
        if (scope === "visit" && isBusinessLocked()) return;
        hideError();
        clearFieldError(`${scope}-location`);
        const city = $(`#${scope}-city`).value;
        if (!city) {
            setFieldError(`${scope}-city`, "请先选择城市，再获取定位。");
            $(`#${scope}-city`).focus();
            return;
        }
        const salespersonId = state[scope].salespersonId || $(`#${scope}-salesperson`).value;
        if (!salespersonId) {
            setFieldError(`${scope}-salesperson`, "请先确认销售身份，再获取定位。");
            $(`#${scope}-salesperson`).focus();
            return;
        }
        state[scope].city = city;
        state[scope].salespersonId = salespersonId;
        const diagnosticId = secureUuid();
        emitClientDiagnostic("LOCATION_CLICK", "STARTED", {}, diagnosticId);
        if (!window.isSecureContext) {
            cancelLocationCapture(scope);
            state.locationControllers[scope]?.abort();
            state.locationControllers[scope] = null;
            setUnverifiedLocation(scope, "INSECURE_CONTEXT",
                "当前浏览器环境不能读取定位，已转为定位未核验；可以继续录入。", diagnosticId);
            emitClientDiagnostic("LOCATION_RESULT", "FALLBACK", {}, diagnosticId);
            return;
        }
        if (!navigator.geolocation) {
            cancelLocationCapture(scope);
            state.locationControllers[scope]?.abort();
            state.locationControllers[scope] = null;
            setUnverifiedLocation(scope, "UNSUPPORTED",
                "当前浏览器不支持定位，已转为定位未核验；可以继续录入。", diagnosticId);
            emitClientDiagnostic("LOCATION_RESULT", "FALLBACK", {}, diagnosticId);
            return;
        }

        const button = $(`#${scope}-location-button`);
        const captureSequence = ++state.locationCaptureSequence[scope];
        const captureDeadlineMs = Date.now() + GEOLOCATION_REFRESH_TIMEOUT_MS;
        let captureSettled = false;
        const captureIsActive = () => !captureSettled
            && state.locationCaptureSequence[scope] === captureSequence;
        stopGeolocationRefresh(scope);
        state.locationControllers[scope]?.abort();
        state.locationControllers[scope] = null;
        state[scope].location = null;
        state[scope].locationContext = {
            geocodeStatus: "CAPTURING",
            locationAttemptId: diagnosticId,
            canContinueWithoutLocation: false,
            errorMessage: "正在刷新手机当前位置，请稍候。"
        };
        if (scope === "visit") {
            abortStoreDirectorySearch();
            state.visit.nearbyStores = [];
            state.visit.directoryStores = [];
            state.visit.directoryQuery = "";
            renderNearbyStores();
        } else {
            abortPoiSearch();
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchLookupStatus = null;
            state.store.poiSearchQuery = "";
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
            clearSourcePoi(true, false);
            $("#poi-search").value = "";
            hidePoiResults();
        }
        button.disabled = true;
        renderLocation(scope);
        if (scope === "store") renderStoreSource();
        persistDraft();

        state.geolocationContinueIds[scope] = window.setTimeout(() => {
            if (!captureIsActive()) return;
            state[scope].locationContext = {
                ...state[scope].locationContext,
                geocodeStatus: "CAPTURING",
                locationAttemptId: diagnosticId,
                canContinueWithoutLocation: true
            };
            renderLocation(scope);
            persistDraft();
        }, GEOLOCATION_CONTINUE_AFTER_MS);

        let compatibleAttempted = false;
        let stalePositionReceived = false;
        let geolocationAttemptSequence = 0;
        let geolocationCallbackCount = 0;
        let rejectedTimestampSample = null;
        let timestampIssueReported = false;
        let compatibleSingleTimestampRetries = 0;
        let usingWatch = typeof navigator.geolocation.watchPosition === "function"
            && typeof navigator.geolocation.clearWatch === "function";

        const diagnosticCallbackCount = () => Math.min(25, geolocationCallbackCount);

        const acceptPosition = (position, capturedAtMs) => {
            if (!captureIsActive() || state.submitting || (scope === "visit" && isBusinessLocked())) return;
            captureSettled = true;
            stopGeolocationRefresh(scope);
            const note = $(`#${scope}-location-note`).value.trim();
            state[scope].location = {
                longitude: roundCoordinate(position.coords.longitude),
                latitude: roundCoordinate(position.coords.latitude),
                accuracyMeters: roundAccuracy(position.coords.accuracy),
                capturedAt: new Date(capturedAtMs).toISOString(),
                ...(note ? { note } : {})
            };
            state[scope].locationContext = { geocodeStatus: "RESOLVING" };
            if (scope === "visit") {
                state.visit.nearbyStores = [];
            } else {
                state.store.nearbyPois = [];
                state.store.poiSearchResults = null;
                state.store.poiSearchLookupStatus = null;
                state.store.poiSearchQuery = "";
                state.store.manualEntryAllowed = false;
                state.store.manualEntryToken = "";
                $("#poi-search").value = "";
                clearSourcePoi(true, false);
            }
            button.disabled = false;
            renderLocation(scope);
            if (scope === "visit") renderNearbyStores();
            else renderStoreSource();
            renderBusinessLock();
            persistDraft();
            emitClientDiagnostic("LOCATION_RESULT", "SUCCEEDED", {
                itemCount: diagnosticCallbackCount()
            }, diagnosticId);
            resolveLocationContext(scope, diagnosticId);
        };
        const failLocation = (error, staleOnly = stalePositionReceived, explicitReason = "") => {
            if (!captureIsActive()) return;
            captureSettled = true;
            stopGeolocationRefresh(scope);
            const message = staleOnly
                ? "手机未返回可核验的新定位，已转为定位未核验；可以继续录入。"
                : `${geolocationErrorMessage(error, compatibleAttempted)} 已转为定位未核验，可以继续录入。`;
            const previousContext = state[scope].locationContext || {};
            const evidence = previousContext.candidateLocation || null;
            setUnverifiedLocation(scope,
                explicitReason || locationFailureReason(error, staleOnly),
                message,
                diagnosticId,
                evidence);
            emitClientDiagnostic("LOCATION_RESULT", "FALLBACK", {
                itemCount: diagnosticCallbackCount()
            }, diagnosticId);
        };
        const captureDeadlineExceeded = () => {
            if (!captureIsActive()) return true;
            if (Date.now() < captureDeadlineMs) return false;
            failLocation({ code: 3 });
            return true;
        };
        const handleCaptureVisibility = () => {
            if (document.visibilityState === "visible") captureDeadlineExceeded();
        };
        const handleCapturePageShow = () => captureDeadlineExceeded();
        document.addEventListener("visibilitychange", handleCaptureVisibility);
        window.addEventListener("pageshow", handleCapturePageShow);
        state.geolocationLifecycleCleanups[scope] = () => {
            document.removeEventListener("visibilitychange", handleCaptureVisibility);
            window.removeEventListener("pageshow", handleCapturePageShow);
        };

        const handlePosition = (position) => {
            if (captureDeadlineExceeded()) return true;
            geolocationCallbackCount += 1;
            if (!Number.isFinite(Number(position?.coords?.longitude))
                    || !Number.isFinite(Number(position?.coords?.latitude))
                    || !Number.isFinite(Number(position?.coords?.accuracy))) {
                failLocation({ code: 2 }, false, "INVALID_POSITION");
                return true;
            }
            const receivedAtMs = Date.now();
            const timestamp = assessGeolocationTimestamp(
                position?.timestamp, receivedAtMs, GEOLOCATION_FRESH_MAX_AGE_MS);
            let capturedAtMs = timestamp.capturedAtMs;
            if (capturedAtMs === null && rejectedTimestampSample) {
                capturedAtMs = resolveAdvancingGeolocationClockCapturedAtMs(
                    rejectedTimestampSample,
                    { value: position?.timestamp, receivedAtMs });
                if (capturedAtMs !== null) {
                    emitClientDiagnostic("LOCATION_TIMESTAMP", "ADVANCING", {
                        itemCount: diagnosticCallbackCount()
                    }, diagnosticId);
                }
            }
            const browserCapturedAtMs = capturedAtMs;
            capturedAtMs = resolveCompatibleGeolocationCapturedAtMs({
                capturedAtMs,
                compatibleAttempted,
                visibilityState: document.visibilityState,
                receivedAtMs,
                captureDeadlineMs
            });
            if (browserCapturedAtMs === null && capturedAtMs !== null) {
                emitClientDiagnostic("LOCATION_TIMESTAMP", "FALLBACK", {
                    itemCount: diagnosticCallbackCount()
                }, diagnosticId);
            }
            if (capturedAtMs === null) {
                stalePositionReceived = true;
                rejectedTimestampSample = { value: position?.timestamp, receivedAtMs };
                state[scope].locationContext = {
                    ...state[scope].locationContext,
                    geocodeStatus: "CAPTURING",
                    locationAttemptId: diagnosticId,
                    candidateLocation: locationEvidenceFromPosition(position, receivedAtMs),
                    stalePosition: true,
                    compatibleAttempt: compatibleAttempted,
                    errorMessage: compatibleAttempted
                        ? "正在适配此手机定位，通常几秒内完成。"
                        : "正在适配此手机定位，请稍候。"
                };
                clearFieldError(`${scope}-location`);
                renderLocation(scope);
                if (!timestampIssueReported) {
                    timestampIssueReported = true;
                    emitClientDiagnostic("LOCATION_TIMESTAMP", timestamp.kind, {
                        itemCount: diagnosticCallbackCount()
                    }, diagnosticId);
                }
                return false;
            }
            if (timestamp.kind === "NORMALIZED") {
                emitClientDiagnostic("LOCATION_TIMESTAMP", "NORMALIZED", {
                    itemCount: diagnosticCallbackCount()
                }, diagnosticId);
            }
            clearFieldError(`${scope}-location`);
            acceptPosition(position, capturedAtMs);
            return true;
        };

        const startSinglePosition = (enableHighAccuracy) => {
            const attemptSequence = ++geolocationAttemptSequence;
            const attemptIsActive = () => captureIsActive()
                && attemptSequence === geolocationAttemptSequence;
            navigator.geolocation.getCurrentPosition(
                (position) => {
                    if (!attemptIsActive()) return;
                    if (handlePosition(position)) return;
                    if (!compatibleAttempted) startCompatibleAttempt();
                    else if (compatibleSingleTimestampRetries < 1) {
                        compatibleSingleTimestampRetries += 1;
                        startSinglePosition(false);
                    } else {
                        failLocation({ code: 3 }, true);
                    }
                },
                (error) => {
                    if (attemptIsActive() && !captureDeadlineExceeded()) {
                        retryCompatibleLocation(error);
                    }
                },
                {
                    enableHighAccuracy,
                    timeout: GEOLOCATION_ATTEMPT_TIMEOUT_MS,
                    maximumAge: 0
                }
            );
        };

        const startWatch = (enableHighAccuracy) => {
            const attemptSequence = ++geolocationAttemptSequence;
            const attemptIsActive = () => captureIsActive()
                && attemptSequence === geolocationAttemptSequence;
            try {
                const watchId = navigator.geolocation.watchPosition(
                    (position) => {
                        if (!attemptIsActive()) return;
                        if (!handlePosition(position) && !compatibleAttempted) {
                            startCompatibleAttempt();
                        }
                    },
                    (error) => {
                        if (attemptIsActive() && !captureDeadlineExceeded()) {
                            retryCompatibleLocation(error);
                        }
                    },
                    {
                        enableHighAccuracy,
                        timeout: GEOLOCATION_ATTEMPT_TIMEOUT_MS,
                        maximumAge: 0
                    }
                );
                state.geolocationWatchIds[scope] = watchId;
            } catch (_) {
                usingWatch = false;
                state.geolocationWatchIds[scope] = null;
                startSinglePosition(enableHighAccuracy);
            }
        };

        const retryCompatibleLocation = (error) => {
            if (!captureIsActive()) return;
            if (error?.code === 1) {
                failLocation(error, false);
                return;
            }
            if (compatibleAttempted) {
                failLocation(error);
                return;
            }
            startCompatibleAttempt();
        };

        const startCompatibleAttempt = () => {
            if (!captureIsActive() || compatibleAttempted) return;
            compatibleAttempted = true;
            rejectedTimestampSample = null;
            if (usingWatch && state.geolocationWatchIds[scope] !== null) {
                navigator.geolocation.clearWatch(state.geolocationWatchIds[scope]);
                state.geolocationWatchIds[scope] = null;
            }
            state[scope].locationContext = {
                ...state[scope].locationContext,
                geocodeStatus: "CAPTURING",
                locationAttemptId: diagnosticId,
                stalePosition: true,
                compatibleAttempt: true,
                errorMessage: "正在适配此手机定位，通常几秒内完成。"
            };
            clearFieldError(`${scope}-location`);
            renderLocation(scope);
            if (usingWatch) startWatch(false);
            else startSinglePosition(false);
        };

        const enforceCaptureDeadline = () => {
            if (captureDeadlineExceeded()) return;
            state.geolocationTimeoutIds[scope] = window.setTimeout(
                enforceCaptureDeadline, Math.max(0, captureDeadlineMs - Date.now()));
        };
        state.geolocationTimeoutIds[scope] = window.setTimeout(
            enforceCaptureDeadline, GEOLOCATION_REFRESH_TIMEOUT_MS);
        if (usingWatch) startWatch(true);
        else startSinglePosition(true);
    }

    async function resolveLocationContext(scope, clientEventId = secureUuid()) {
        const city = state[scope].city;
        const location = state[scope].location;
        if (!city || !location) return;
        const previousVerifiedContext = locationContextReady(state[scope].locationContext)
            ? { ...state[scope].locationContext }
            : null;
        const previousRegisteredStores = previousVerifiedContext
            ? [...(scope === "visit" ? state.visit.nearbyStores : state.store.nearbyPois)]
            : [];

        state.locationControllers[scope]?.abort();
        const controller = createRequestController();
        state.locationControllers[scope] = controller;
        state[scope].locationContext = {
            geocodeStatus: "RESOLVING",
            locationAttemptId: clientEventId
        };
        if (scope === "visit") {
            state.visit.nearbyStores = [];
        } else {
            abortPoiSearch();
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchLookupStatus = null;
            state.store.poiSearchQuery = "";
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
            $("#poi-search").value = "";
            hidePoiResults();
        }
        renderLocation(scope);
        if (scope === "visit") renderNearbyStores();
        else renderStoreSource();

        try {
            const payload = normalizeResponse(await requestJson("/locations/resolve", {
                method: "POST",
                headers: { "X-Sales-Checkin-Client-Event-Id": clientEventId },
                body: {
                    city,
                    salespersonId: state[scope].salespersonId,
                    location: locationRequestValue(scope)
                },
                signal: controller.signal,
                timeout: 20000
            })) || {};
            if (state.locationControllers[scope] !== controller) return;
            const address = cleanText(payload.address);
            const formattedAddress = cleanText(payload.formattedAddress);
            const locationMessage = cleanText(payload.locationMessage);
            const resolvedContext = {
                geocodeStatus: cleanText(payload.geocodeStatus) || (address || formattedAddress ? "RESOLVED" : "FAILED"),
                address,
                formattedAddress,
                adcode: cleanText(payload.adcode),
                cityMatched: payload.cityMatched === true
                    ? true
                    : payload.cityMatched === false ? false : null,
                resolvedCity: cleanText(payload.resolvedCity),
                accuracyAccepted: payload.accuracyAccepted === true,
                freshnessAccepted: payload.freshnessAccepted === true,
                maxCheckinDistanceMeters: finiteNumberOrNull(payload.maxCheckinDistanceMeters),
                maxCheckinAccuracyMeters: finiteNumberOrNull(payload.maxCheckinAccuracyMeters),
                maxLocationAgeMinutes: finiteNumberOrNull(payload.maxLocationAgeMinutes),
                poiLookupStatus: cleanText(payload.poiLookupStatus) || "UNAVAILABLE",
                locationVerificationToken: cleanText(payload.locationVerificationToken),
                locationMessage,
                errorMessage: locationMessage
            };
            state[scope].locationContext = resolvedContext;
            const ready = locationContextReady(resolvedContext);
            if (ready) {
                clearFieldError(`${scope}-location`);
            } else {
                const reason = payload.accuracyAccepted === false
                    ? "ACCURACY_INSUFFICIENT"
                    : payload.freshnessAccepted === false
                        ? "TIMESTAMP_UNUSABLE"
                        : "RESOLVE_FAILED";
                const message = locationMessage
                    || (reason === "ACCURACY_INSUFFICIENT"
                        ? "定位精度不足，已转为定位未核验；可以继续录入。"
                        : reason === "TIMESTAMP_UNUSABLE"
                            ? "定位时间无法核验，已转为定位未核验；可以继续录入。"
                            : "定位解析暂不可用，已转为定位未核验；可以继续录入。");
                setUnverifiedLocation(scope, reason, message, clientEventId, location, resolvedContext);
            }
            if (scope === "visit") {
                state.visit.nearbyStores = ready && Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores
                        .filter((store) => store?.source === "REGISTERED")
                        .filter(isUsableNearbyStore)
                    : [];
            } else {
                state.store.nearbyPois = ready && Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores
                        .filter((store) => store?.source === "REGISTERED")
                        .filter(isUsableNearbyStore)
                    : [];
            }
        } catch (error) {
            if (state.locationControllers[scope] !== controller) return;
            if (error.name === "AbortError") return;
            const message = errorMessage(error, "暂时无法确认附近门店，请检查网络后重试。");
            if (previousVerifiedContext) {
                state[scope].locationContext = {
                    ...previousVerifiedContext,
                    errorMessage: `${message} 原定位核验结果仍保留。`
                };
                if (scope === "visit") state.visit.nearbyStores = previousRegisteredStores;
                else state.store.nearbyPois = previousRegisteredStores;
                clearFieldError(`${scope}-location`);
            } else {
                setUnverifiedLocation(scope, "RESOLVE_FAILED",
                    `${message} 已转为定位未核验，可以继续录入。`, clientEventId, location);
            }
        } finally {
            if (state.locationControllers[scope] === controller) {
                state.locationControllers[scope] = null;
                renderLocation(scope);
                if (scope === "visit") renderNearbyStores();
                else renderStoreSource();
                renderBusinessLock();
                persistDraft();
            }
        }
    }

    function locationRequestValue(scope) {
        const location = state[scope].location;
        const note = $(`#${scope}-location-note`).value.trim();
        return compactObject({
            longitude: location.longitude,
            latitude: location.latitude,
            accuracyMeters: location.accuracyMeters,
            capturedAt: location.capturedAt,
            note: note || undefined
        });
    }

    function isUsableNearbyStore(store) {
        if (!store || typeof store !== "object") return false;
        if (store.source === "REGISTERED") {
            return Boolean(store.storeId && store.name
                && store.checkinEligible === true
                && store.nextAction === "CHECK_IN");
        }
        if (store.source !== "AMAP_POI" || !store.poiId || !store.name
                || store.checkinEligible !== false) return false;
        if (store.nextAction === "OUT_OF_RANGE") return true;
        return store.nextAction === "COMPLETE_STORE_PROFILE"
            && Boolean(cleanText(store.selectionToken));
    }

    function renderLocation(scope) {
        const location = state[scope].location;
        const context = state[scope].locationContext;
        const status = $(`#${scope}-location-status`);
        const detail = $(`#${scope}-location-detail`);
        const button = $(`#${scope}-location-button`);
        const retry = $(`#${scope}-location-retry`);
        const continueButton = $(`#${scope}-location-continue`);
        const exceptionNote = $(`#${scope}-location-exception`);
        const capturing = context?.geocodeStatus === "CAPTURING";
        const awaitingFreshPosition = capturing && context?.stalePosition === true;
        const compatibleAttempt = capturing && context?.compatibleAttempt === true;
        const failed = context?.geocodeStatus === "FAILED";
        const unverified = locationExceptionReady(context);
        const locationCard = button.closest(".location-card");
        locationCard?.classList.toggle("is-located", Boolean(location));
        locationCard?.classList.toggle("is-unverified", unverified);
        continueButton.hidden = !capturing || context?.canContinueWithoutLocation !== true;
        exceptionNote.hidden = !unverified;
        if (unverified) {
            const explanation = exceptionNote.querySelector("span");
            if (explanation) explanation.textContent = location
                ? "已保存当前能取得的坐标，但不作为已核验定位；可以继续录入，门头照仍必需。"
                : "本次没有取得可用坐标；可以继续录入，门头照仍必需。";
        }
        renderUnverifiedBanner(scope);
        if (scope === "store") {
            locationCard?.classList.toggle("has-inherited-location", Boolean(location));
            $("#store-location-note-field").hidden = !location;
        }
        if (!location) {
            status.textContent = unverified
                ? "定位未核验"
                : compatibleAttempt
                ? "定位适配中"
                : awaitingFreshPosition ? "刷新定位中" : capturing ? "定位中" : failed ? "定位失败" : "未定位";
            status.className = unverified
                ? "status-pill is-unverified"
                : capturing ? "status-pill is-loading" : failed ? "status-pill is-warning" : "status-pill";
            detail.hidden = true;
            retry.hidden = true;
            $(`#${scope}-location-button-label`).textContent = unverified
                ? "重新尝试定位"
                : compatibleAttempt
                ? "正在适配此手机定位…"
                : awaitingFreshPosition ? "正在等待手机刷新…"
                : capturing ? "正在刷新当前位置…" : "刷新当前位置";
            renderFlowActions();
            return;
        }
        const resolving = context?.geocodeStatus === "RESOLVING";
        const ready = locationContextReady(context);
        const cityMismatch = ready && context?.cityMatched === false;
        const inaccurate = context?.accuracyAccepted === false;
        const expired = context?.freshnessAccepted === false;
        const address = cleanText(context?.formattedAddress || context?.address);
        const addressUnavailable = ready && !address;
        status.textContent = unverified
            ? "定位未核验"
            : capturing
            ? "定位中"
            : resolving
            ? "解析地址中"
            : inaccurate
                    ? "精度不足"
                    : expired
                        ? "定位已过期"
                        : ready
                            ? cityMismatch
                                ? `已定位·${cleanText(context?.resolvedCity) || "跨城"}`
                                : addressUnavailable ? "坐标已获取" : "已定位"
                            : "需重试";
        status.className = unverified
            ? "status-pill is-unverified"
            : capturing || resolving
            ? "status-pill is-loading"
            : ready && (cityMismatch || addressUnavailable)
                ? "status-pill is-info"
                : ready ? "status-pill is-ready" : "status-pill is-warning";
        detail.hidden = false;
        const addressElement = $(`#${scope}-location-address`);
        addressElement.textContent = address
            || (capturing ? "正在重新获取当前位置…"
                : resolving ? "正在解析定位地址…"
                : unverified ? `${formatLocationCoordinates(location)} · 本次定位未核验`
                : ready ? `${formatLocationCoordinates(location)} · 详细地址暂未取得`
                    : context?.errorMessage || "详细地址暂未取得，请重试");
        addressElement.classList.toggle("is-missing", !address && !capturing && !resolving);
        retry.hidden = unverified || capturing || resolving || Boolean(address);
        $(`#${scope}-location-accuracy`).textContent = `约 ${location.accuracyMeters} 米`;
        const timeLabel = $(`#${scope}-location-time`)?.closest("div")?.querySelector("span");
        if (timeLabel) timeLabel.textContent = unverified ? "坐标接收时间" : "采集时间";
        $(`#${scope}-location-time`).textContent = formatDateTime(location.capturedAt);
        $(`#${scope}-location-note`).value = location.note || "";
        $(`#${scope}-location-button-label`).textContent = unverified
            ? "重新尝试定位"
            : scope === "store"
            ? "定位不准？重新获取"
            : "重新定位";
        renderFlowActions();
    }

    function formatLocationCoordinates(location) {
        const longitude = Number(location?.longitude);
        const latitude = Number(location?.latitude);
        if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return "GPS 坐标已获取";
        return `GPS ${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
    }

    function geolocationErrorMessage(error, fallbackAttempted = false) {
        if (error && error.code === 1) return "定位权限被拒绝。请在浏览器设置中允许位置访问后重试。";
        if (error && error.code === 2) return fallbackAttempted
            ? "已自动尝试两种定位方式，仍无法获取位置。请打开系统定位后重试。"
            : "暂时无法获取当前位置，请移动到信号较好的位置后重试。";
        if (error && error.code === 3) return fallbackAttempted
            ? "两种定位方式均未返回结果，请保持页面在前台并重新获取。"
            : "定位超时，请重试并保持页面在前台。";
        return "获取定位失败，请检查系统定位服务后重试。";
    }

    function handleImageSelection(kind, event) {
        const file = event.target.files && event.target.files[0];
        if (!file) {
            if (kind === "photo") emitClientDiagnostic("PHOTO_REJECTED", "CANCELLED");
            return;
        }
        const diagnosticId = kind === "photo" ? secureUuid() : null;
        const errorKey = kind === "photo" ? "storefront-photo" : "wechat-screenshot";
        clearFieldError(errorKey);
        if (hasRemoteMediaState(MEDIA[kind])) {
            setFieldError(errorKey, "请先删除草稿中已上传的文件，再选择替换文件。" );
            event.target.value = "";
            return;
        }
        if (!isSupportedImageFile(file)) {
            if (diagnosticId) {
                emitClientDiagnostic("PHOTO_REJECTED", "UNSUPPORTED", {}, diagnosticId);
            }
            setFieldError(errorKey, "仅支持 JPG、PNG、WebP、HEIC/HEIF 或 AVIF 图片。" );
            event.target.value = "";
            return;
        }
        if (file.size > MAX_IMAGE_BYTES) {
            if (diagnosticId) {
                emitClientDiagnostic("PHOTO_REJECTED", "TOO_LARGE", {}, diagnosticId);
            }
            setFieldError(errorKey, "图片超过 10MB，请压缩或重新拍摄。" );
            event.target.value = "";
            return;
        }
        if (diagnosticId) {
            emitClientDiagnostic("PHOTO_SELECTED", "ACCEPTED", {
                fileSizeBytes: file.size
            }, diagnosticId);
        }
        state.files[kind] = file;
        renderImagePreview(kind, file);
        if (diagnosticId) {
            emitClientDiagnostic("PHOTO_READY", "SUCCEEDED", {
                fileSizeBytes: file.size
            }, diagnosticId);
        }
        persistDraft();
    }

    function renderImagePreview(kind, file) {
        revokeObjectUrl(kind);
        const prefix = kind === "photo" ? "photo" : "wechat";
        $(`#${prefix}-file-name`).textContent = file.name || "待上传图片";
        // 相机原图即使压缩文件不到 10MB，解码后的 40MP~50MP RGBA 仍可能占用
        // 160MB~200MB，足以让部分 Android/微信 WebView 在系统确认照片后闪退。
        // 这里只展示选择状态与文件信息，提交时仍上传未经二次处理的原文件。
        $(`#${prefix}-file-size`).textContent = `${formatBytes(file.size)} · 已选择，提交时上传原图`;
        $(`#${prefix}-preview-card`).hidden = false;
    }

    async function handleAudioFileSelection(event) {
        const files = Array.from(event.target.files || []);
        event.target.value = "";
        if (!files.length) {
            state.audioRetrySegmentId = null;
            return;
        }
        clearFieldError("audio-file");
        if (isRecording()) {
            setFieldError("audio-file", "请先结束当前录音，再选择已有音频文件。" );
            state.audioRetrySegmentId = null;
            return;
        }

        const retryId = state.audioRetrySegmentId;
        state.audioRetrySegmentId = null;
        const selectionSequence = ++state.audioFileSelectionSequence;
        const readableFiles = files.filter((file) => Number.isFinite(file.size) && file.size > 0);
        const emptyCount = files.length - readableFiles.length;
        const imageFlags = await Promise.all(readableFiles.map(isImageSelectedAsAudio));
        if (selectionSequence !== state.audioFileSelectionSequence) return;
        const imageCount = imageFlags.filter(Boolean).length;
        let validFiles = readableFiles.filter((_, index) => !imageFlags[index]);
        if (imageCount || emptyCount) {
            const ignored = [];
            if (imageCount) ignored.push(`${imageCount} 张图片`);
            if (emptyCount) ignored.push(`${emptyCount} 个空文件`);
            showAudioSelectionNotice(`选到的是${ignored.join("和")}，已忽略；录音为选填，不影响打卡。`);
        } else {
            hideAudioSelectionNotice();
        }
        if (!validFiles.length) {
            state.audioRetrySegmentId = retryId;
            return;
        }

        const metadataTasks = [];
        if (retryId) {
            const file = validFiles[0];
            const segmentId = attachAudioFile(retryId, file);
            metadataTasks.push({ segmentId, file });
            validFiles = validFiles.slice(1);
        }
        validFiles.forEach((file) => {
            const segmentId = appendAudioFile(file, {
                captureSource: "FILE_UPLOAD",
                fileLastModifiedAt: audioFileLastModifiedAt(file)
            });
            metadataTasks.push({ segmentId, file });
        });
        renderAudioSegments();
        renderUploadedBadges();
        persistDraft();
        metadataTasks.forEach(({ segmentId, file }) => {
            beginAudioFileMetadataRead(segmentId, file);
        });
    }

    async function isImageSelectedAsAudio(file) {
        const mimeType = cleanText(file?.type).toLowerCase().split(";", 1)[0];
        if (mimeType.startsWith("image/")) return true;
        if (/\.(avif|bmp|gif|heic|heif|jpe?g|png|tiff?|webp)$/i.test(file?.name || "")) return true;
        const prefix = await readFilePrefix(file, 32);
        return hasImageSignature(prefix);
    }

    function readFilePrefix(file, byteLength) {
        return new Promise((resolve) => {
            if (!file || typeof window.FileReader !== "function" || typeof file.slice !== "function") {
                resolve(null);
                return;
            }
            const reader = new FileReader();
            let settled = false;
            const finish = (value = null) => {
                if (settled) return;
                settled = true;
                window.clearTimeout(timeoutId);
                resolve(value);
            };
            const timeoutId = window.setTimeout(() => {
                try {
                    reader.abort();
                } catch (_) {
                    // 某些旧 WebView 在文件提供器已退出时不允许再次 abort。
                }
                finish();
            }, 1500);
            reader.addEventListener("load", () => {
                try {
                    finish(new Uint8Array(reader.result));
                } catch (_) {
                    finish();
                }
            }, { once: true });
            reader.addEventListener("error", () => finish(), { once: true });
            reader.addEventListener("abort", () => finish(), { once: true });
            try {
                reader.readAsArrayBuffer(file.slice(0, byteLength));
            } catch (_) {
                finish();
            }
        });
    }

    function hasImageSignature(bytes) {
        if (!bytes || bytes.length < 2) return false;
        const asciiAt = (offset, value) => value.split("").every((character, index) =>
            bytes[offset + index] === character.charCodeAt(0));
        if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return true;
        if (bytes.length >= 8 && bytes[0] === 0x89 && asciiAt(1, "PNG\r\n\u001a\n")) return true;
        if (bytes.length >= 6 && (asciiAt(0, "GIF87a") || asciiAt(0, "GIF89a"))) return true;
        if (bytes.length >= 12 && asciiAt(0, "RIFF") && asciiAt(8, "WEBP")) return true;
        if (bytes.length >= 2 && asciiAt(0, "BM")) return true;
        if (bytes.length >= 4 && (asciiAt(0, "II*\u0000") || asciiAt(0, "MM\u0000*"))) return true;
        if (bytes.length >= 12 && asciiAt(4, "ftyp")) {
            const brand = String.fromCharCode(bytes[8], bytes[9], bytes[10], bytes[11]).toLowerCase();
            return ["avif", "avis", "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"]
                .includes(brand);
        }
        return false;
    }

    function showAudioSelectionNotice(message) {
        const note = $("#audio-selection-note");
        note.textContent = message;
        note.hidden = false;
    }

    function hideAudioSelectionNotice() {
        const note = $("#audio-selection-note");
        note.textContent = "";
        note.hidden = true;
    }

    function appendAudioFile(file, evidence = {}) {
        const segmentId = secureUuid();
        state.submission.audioSegments.push({
            segmentId,
            originalFilename: file.name || "待上传音频",
            sizeBytes: file.size,
            captureSource: normalizeAudioCaptureSource(evidence.captureSource, evidence.source),
            clientStartedAt: normalizeOptionalInstant(evidence.clientStartedAt),
            clientDurationMs: normalizePositiveDurationMs(evidence.clientDurationMs),
            fileLastModifiedAt: normalizeOptionalInstant(evidence.fileLastModifiedAt),
            uploadState: "LOCAL",
            errorMessage: ""
        });
        state.files.audio.push({ segmentId, file });
        ensureAudioObjectUrl(segmentId, file);
        return segmentId;
    }

    function beginAudioFileMetadataRead(segmentId, file) {
        const local = localAudioFile(segmentId);
        if (!local || local.file !== file) return;
        const metadataPromise = enrichAudioFileMetadata(segmentId, file);
        local.metadataPromise = metadataPromise;
        const clearPromise = () => {
            const current = localAudioFile(segmentId);
            if (current?.metadataPromise === metadataPromise) delete current.metadataPromise;
        };
        void metadataPromise.then(clearPromise, clearPromise);
    }

    function attachAudioFile(segmentId, file) {
        const segment = findAudioSegment(segmentId);
        if (!segment) {
            return appendAudioFile(file, {
                captureSource: "FILE_UPLOAD",
                fileLastModifiedAt: audioFileLastModifiedAt(file)
            });
        }
        removeLocalAudioFile(segmentId);
        segment.originalFilename = file.name || segment.originalFilename || "待上传音频";
        segment.sizeBytes = file.size;
        segment.captureSource = "FILE_UPLOAD";
        segment.clientStartedAt = null;
        segment.clientDurationMs = null;
        segment.fileLastModifiedAt = audioFileLastModifiedAt(file);
        segment.uploadState = "LOCAL";
        segment.errorMessage = "";
        state.files.audio.push({ segmentId, file });
        ensureAudioObjectUrl(segmentId, file);
        return segmentId;
    }

    async function enrichAudioFileMetadata(segmentId, file) {
        let durationMs = null;
        try {
            durationMs = await readAudioDurationMs(file);
        } catch (_) {
            // 文件元数据是可选证据，读取失败不阻断预览或上传。
        }
        const segment = findAudioSegment(segmentId);
        const local = localAudioFile(segmentId);
        if (!segment || local?.file !== file) return;
        segment.clientDurationMs = durationMs;
        renderAudioSegments();
        persistDraft();
    }

    function readAudioDurationMs(file) {
        return new Promise((resolve) => {
            const audio = document.createElement("audio");
            const objectUrl = URL.createObjectURL(file);
            let settled = false;
            const finish = (durationMs = null) => {
                if (settled) return;
                settled = true;
                window.clearTimeout(timeoutId);
                audio.removeEventListener("loadedmetadata", handleLoadedMetadata);
                audio.removeEventListener("error", handleFailure);
                audio.removeAttribute("src");
                try {
                    audio.load();
                } catch (_) {
                    // 元数据不可读时不阻断文件上传。
                }
                audio.remove();
                URL.revokeObjectURL(objectUrl);
                resolve(normalizePositiveDurationMs(durationMs));
            };
            const handleLoadedMetadata = () => finish(Number(audio.duration) * 1000);
            const handleFailure = () => finish();
            const timeoutId = window.setTimeout(handleFailure, 5000);
            audio.hidden = true;
            audio.preload = "metadata";
            audio.addEventListener("loadedmetadata", handleLoadedMetadata, { once: true });
            audio.addEventListener("error", handleFailure, { once: true });
            document.body.appendChild(audio);
            audio.src = objectUrl;
            try {
                audio.load();
            } catch (_) {
                finish();
            }
        });
    }

    function audioFileLastModifiedAt(file) {
        const lastModified = Number(file?.lastModified);
        return Number.isFinite(lastModified) && lastModified > 0
            ? new Date(lastModified).toISOString()
            : null;
    }

    function normalizeAudioCaptureSource(captureSource, legacySource = "") {
        const source = cleanText(captureSource || legacySource).toUpperCase();
        if (source === "BROWSER_RECORDER" || source === "RECORDED") return "BROWSER_RECORDER";
        return "FILE_UPLOAD";
    }

    function normalizeOptionalInstant(value) {
        if (!value) return null;
        const milliseconds = typeof value === "number" ? value : Date.parse(value);
        return Number.isFinite(milliseconds) ? new Date(milliseconds).toISOString() : null;
    }

    function normalizePositiveDurationMs(value) {
        const milliseconds = Number(value);
        return Number.isFinite(milliseconds) && milliseconds > 0
            ? Math.round(milliseconds)
            : null;
    }

    function isSupportedImageFile(file) {
        const mimeType = (file.type || "").toLowerCase().split(";", 1)[0];
        const supportedMimeTypes = new Set([
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/heic", "image/heif", "image/avif"
        ]);
        return supportedMimeTypes.has(mimeType) || /\.(avif|heic|heif|jpe?g|png|webp)$/i.test(file.name || "");
    }

    function recorderSupported() {
        return Boolean(window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
    }

    function checkRecorderSupport() {
        const supported = recorderSupported();
        if (!supported) {
            $("#recorder-help").textContent = window.isSecureContext
                ? "当前浏览器不支持网页录音，可直接从手机文件中多选录音上传。"
                : "网页录音需要 HTTPS，可直接从手机文件中多选录音上传。";
        } else {
            updateRecorderHelp(preferredRecorderOptions()?.mimeType);
        }
        setRecordingUi(isRecording(), state.recorder.stopping, state.recorder.starting);
    }

    function updateRecorderHelp(mimeType) {
        const format = recorderFormatLabel(mimeType);
        $("#recorder-help").textContent = `本机将录制 ${format}；也可选择 M4A、MP3、WAV、AAC、AMR、OGG/Opus、WebM、3GP、FLAC、CAF、AIFF、SILK 等已有录音。无法识别时自动跳过，不影响打卡。自动转文字与摘要当前已暂停。`;
    }

    async function toggleRecording() {
        if (state.recorder.starting || state.recorder.stopping || state.submitting) return;
        const activeSession = state.recorder.activeSession;
        if (activeSession && !activeSession.finished
                && state.recorder.sessionId === activeSession.id) {
            stopRecording();
            return;
        }
        hideError();
        clearFieldError("audio-file");
        const visitStep = normalizeFlowStep(state.ui.visitStep);
        if (state.activeTab !== "visit" || visitStep === 1) {
            setFieldError("audio-file", "请先完成现场定位和门店选择，再开始录音。");
            return;
        }
        if (visitStep === 3 && !isBusinessLocked()) {
            goToFlowStep("visit", 2);
            return;
        }
        if (!$("#recording-consent").checked) {
            setFieldError("audio-file", "请先告知现场人员并勾选确认，再开始录音。");
            $("#recording-consent").scrollIntoView({ behavior: "smooth", block: "center" });
            return;
        }
        if (!recorderSupported()) {
            setFieldError("audio-file", "当前环境无法录音，请通过 HTTPS 打开或选择已有音频文件。" );
            return;
        }
        pauseAllAudioPreviews();
        state.recorder.starting = true;
        const requestSequence = ++state.recorder.startSequence;
        setRecordingUi(false, false, true);
        let stream = null;
        let session = null;
        try {
            stream = await requestMicrophoneStream({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            }, requestSequence);
            const requestInvalidated = requestSequence !== state.recorder.startSequence;
            if (requestInvalidated
                    || state.submitting
                    || state.activeTab !== "visit"
                    || normalizeFlowStep(state.ui.visitStep) === 1) {
                stopRecorderStream(stream);
                if (!requestInvalidated) cleanupRecorder();
                return;
            }
            const options = preferredRecorderOptions();
            let recorder;
            try {
                recorder = options ? new MediaRecorder(stream, options) : new MediaRecorder(stream);
            } catch (firstError) {
                try {
                    recorder = new MediaRecorder(stream);
                } catch (fallbackError) {
                    stopRecorderStream(stream);
                    throw fallbackError;
                }
            }
            const startedAt = Date.now();
            const clientStartedAt = new Date(startedAt).toISOString();
            session = {
                id: secureUuid(),
                recorder,
                stream,
                chunks: [],
                startedAt,
                clientStartedAt,
                failed: false,
                finished: false
            };
            state.recorder.stream = stream;
            state.recorder.instance = recorder;
            state.recorder.chunks = session.chunks;
            state.recorder.startedAt = startedAt;
            state.recorder.clientStartedAt = clientStartedAt;
            state.recorder.elapsedMs = 0;
            state.recorder.starting = false;
            state.recorder.stopping = false;
            state.recorder.sessionId = session.id;
            state.recorder.activeSession = session;

            recorder.addEventListener("dataavailable", (event) => {
                if (event.data && event.data.size > 0) session.chunks.push(event.data);
            });
            recorder.addEventListener("error", () => {
                interruptRecordingSession(session, "录音意外中断；如已生成音频，请回放确认后再提交。");
            });
            recorder.addEventListener("pause", () => {
                interruptRecordingSession(session, "系统暂停了录音，已停止并尝试保留录到的内容。");
            });
            recorder.addEventListener("stop", () => finishRecording(session), { once: true });
            stream.getAudioTracks?.().forEach((track) => {
                track.addEventListener("ended", () => {
                    if (!session.finished && !state.recorder.stopping) {
                        interruptRecordingSession(session, "麦克风被系统中断，已停止并尝试保留录到的内容。");
                    }
                });
            });
            recorder.start(1000);
            setRecordingUi(true);
            updateRecordingClock();
            state.recorder.timer = window.setInterval(updateRecordingClock, 500);
        } catch (error) {
            stopRecorderStream(stream);
            if (requestSequence !== state.recorder.startSequence) return;
            cleanupRecorder(session?.id || null);
            setFieldError("audio-file", microphoneErrorMessage(error));
        }
    }

    function requestMicrophoneStream(constraints, requestSequence) {
        return new Promise((resolve, reject) => {
            let settled = false;
            const finish = (callback, value) => {
                if (settled) return false;
                settled = true;
                window.clearTimeout(timeoutId);
                callback(value);
                return true;
            };
            const timeoutId = window.setTimeout(() => {
                const error = new Error("麦克风授权等待超过12秒，已取消等待；录音为选填，可直接提交打卡。");
                error.code = "MICROPHONE_PERMISSION_TIMEOUT";
                finish(reject, error);
            }, MICROPHONE_PERMISSION_TIMEOUT_MS);
            let permissionRequest;
            try {
                permissionRequest = navigator.mediaDevices.getUserMedia(constraints);
            } catch (error) {
                finish(reject, error);
                return;
            }
            Promise.resolve(permissionRequest).then((stream) => {
                if (settled || requestSequence !== state.recorder.startSequence) {
                    // 部分 WebView 在前端超时后仍会迟到返回麦克风流，必须立即释放。
                    stopRecorderStream(stream);
                    return;
                }
                finish(resolve, stream);
            }, (error) => finish(reject, error));
        });
    }

    function preferredRecorderOptions() {
        const candidates = [
            "audio/mp4;codecs=mp4a.40.2", "audio/mp4",
            "audio/webm;codecs=opus", "audio/ogg;codecs=opus",
            "audio/webm", "audio/ogg", "audio/aac"
        ];
        const mimeType = candidates.find((value) => MediaRecorder.isTypeSupported?.(value));
        return mimeType ? { mimeType, audioBitsPerSecond: 64000 } : undefined;
    }

    function recorderFormatLabel(mimeType) {
        const normalized = cleanText(mimeType).toLowerCase();
        if (normalized.includes("mp4") || normalized.includes("m4a")) return "M4A";
        if (normalized.includes("webm")) return "WebM";
        if (normalized.includes("ogg") || normalized.includes("opus")) return "OGG/Opus";
        if (normalized.includes("aac")) return "AAC";
        return "浏览器支持的音频格式";
    }

    function stopRecording() {
        const recorder = state.recorder.instance;
        const session = state.recorder.activeSession;
        if (!recorder || !session || session.finished
                || state.recorder.sessionId !== session.id) return;
        if (state.recorder.stopping) {
            scheduleRecordingStopFallback(session);
            return;
        }
        state.recorder.stopping = true;
        state.recorder.elapsedMs = Date.now() - state.recorder.startedAt;
        setRecordingUi(false, true);
        if (recorder.state === "inactive") {
            scheduleRecordingStopFallback(session);
            return;
        }
        try {
            recorder.stop();
            scheduleRecordingStopFallback(session);
        } catch (_) {
            if (session) session.failed = true;
            setFieldError("audio-file", "录音停止异常，正在尝试保留已录内容，请稍候。");
            scheduleRecordingStopFallback(session);
        }
    }

    function interruptRecordingSession(session, message) {
        if (!session || session.finished || state.recorder.sessionId !== session.id) return;
        session.failed = true;
        setFieldError("audio-file", message);
        if (!state.recorder.stopping) {
            state.recorder.stopping = true;
            state.recorder.elapsedMs = Date.now() - session.startedAt;
            setRecordingUi(false, true);
        }
        if (session.recorder.state !== "inactive") {
            try {
                session.recorder.stop();
            } catch (_) {
                // 仍等待可能已经排队的 dataavailable/stop，超时后再统一清理。
            }
        }
        scheduleRecordingStopFallback(session);
    }

    function scheduleRecordingStopFallback(session) {
        if (!session || session.finished || state.recorder.sessionId !== session.id) return;
        window.clearTimeout(state.recorder.stopFallbackTimer);
        state.recorder.stopFallbackTimer = window.setTimeout(() => {
            if (session.finished || state.recorder.sessionId !== session.id) return;
            session.failed = true;
            if (session.chunks.length) {
                finishRecording(session);
                return;
            }
            session.finished = true;
            cleanupRecorder(session.id);
            setFieldError("audio-file", "录音停止超时且未生成有效音频，请重新录制或选择已有音频。");
        }, 5000);
    }

    function finishRecording(session) {
        if (!session || session.finished || state.recorder.sessionId !== session.id) {
            stopRecorderStream(session?.stream);
            return;
        }
        window.clearTimeout(state.recorder.stopFallbackTimer);
        state.recorder.stopFallbackTimer = null;
        session.finished = true;
        const duration = state.recorder.elapsedMs || Date.now() - session.startedAt;
        const mimeType = session.recorder?.mimeType || session.chunks[0]?.type || "audio/webm";
        const blob = new Blob(session.chunks, { type: mimeType });
        cleanupRecorder(session.id);
        updateRecorderHelp(mimeType);
        if (!blob.size) {
            setFieldError("audio-file", "没有录到有效音频，请重新录制。" );
            return;
        }
        const extension = audioExtension(mimeType);
        const filename = `现场录音-${formatFilenameTime(new Date())}.${extension}`;
        const file = typeof window.File === "function"
            ? new File([blob], filename, { type: mimeType, lastModified: Date.now() })
            : Object.assign(blob, { name: filename, lastModified: Date.now() });
        $("#audio-file").value = "";
        hideAudioSelectionNotice();
        appendAudioFile(file, {
            captureSource: "BROWSER_RECORDER",
            clientStartedAt: session.clientStartedAt,
            clientDurationMs: duration
        });
        renderAudioSegments();
        renderUploadedBadges();
        if (session.failed) {
            setFieldError("audio-file", "录音曾被系统中断，已保留可用部分；请回放确认是否完整。");
        }
        persistDraft();
    }

    function stopRecorderStream(stream) {
        stream?.getTracks?.().forEach((track) => {
            try {
                track.stop();
            } catch (_) {
                // 某些 WebView 会在系统已回收麦克风后再次抛错。
            }
        });
    }

    function cleanupRecorder(expectedSessionId = null) {
        if (expectedSessionId && state.recorder.sessionId !== expectedSessionId) return false;
        state.recorder.startSequence += 1;
        clearInterval(state.recorder.timer);
        window.clearTimeout(state.recorder.stopFallbackTimer);
        stopRecorderStream(state.recorder.stream);
        state.recorder.instance = null;
        state.recorder.stream = null;
        state.recorder.chunks = [];
        state.recorder.startedAt = 0;
        state.recorder.clientStartedAt = null;
        state.recorder.timer = null;
        state.recorder.starting = false;
        state.recorder.stopping = false;
        state.recorder.sessionId = null;
        state.recorder.activeSession = null;
        state.recorder.stopFallbackTimer = null;
        setRecordingUi(false);
        return true;
    }

    function setRecordingUi(recording, stopping = false, starting = false) {
        const button = $("#record-audio-button");
        button.classList.toggle("is-recording", recording);
        button.classList.toggle("is-starting", starting);
        button.disabled = state.submitting || starting || stopping || !recorderSupported();
        $("#record-button-label").textContent = recording
            ? "结束并保存录音"
            : starting ? "等待麦克风权限"
                : stopping ? "正在生成录音"
                    : normalizeFlowStep(state.ui.visitStep) === 3 && !isBusinessLocked()
                        ? "返回第2步补录" : "开始现场录音";
        $("#recording-meter").hidden = !recording;
        $("#recording-consent").disabled = state.submitting || recording || starting || stopping;
        $("#visit-recording-workspace").classList.toggle("is-recording", recording || stopping);
        renderRecordingStatus();
        renderFlowSteps();
    }

    function renderRecordingStatus() {
        const note = $("#recording-status-note");
        if (state.recorder.starting) {
            note.textContent = "正在等待系统麦克风权限，请不要重复点击或切换页面。";
            return;
        }
        if (isRecording()) {
            note.textContent = "正在录音，可继续填写本页客户信息；请保持页面前台，并先结束录音再进入现场证明。";
            return;
        }
        if (state.recorder.stopping) {
            note.textContent = "正在生成录音文件，请稍候，不要切换步骤或关闭页面。";
            return;
        }
        const audioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState !== "SKIPPED").length;
        const skippedAudioCount = state.submission.audioSegments.length - audioCount;
        if (audioCount) {
            note.textContent = `已添加 ${audioCount} 段录音，可回放确认、继续补录或选择已有音频；刷新页面前请先完成提交。`;
            return;
        }
        if (skippedAudioCount) {
            note.textContent = `已跳过 ${skippedAudioCount} 段选填录音；可重新选择，也可直接提交现场照片完成打卡。`;
            return;
        }
        if (!recorderSupported()) {
            note.textContent = "当前浏览器不支持网页录音；可选择已有文件，也可不录音继续打卡。";
            return;
        }
        note.textContent = normalizeFlowStep(state.ui.visitStep) === 3
            ? "本次未添加录音（选填）；可直接提交现场照片完成打卡。"
            : "录音为选填，只暂存在当前页面；请先停止录音再进入现场证明，上传失败会自动跳过。";
    }

    function updateRecordingClock() {
        if (isRecording()) state.recorder.elapsedMs = Date.now() - state.recorder.startedAt;
        $("#recording-clock").textContent = formatDuration(state.recorder.elapsedMs);
    }

    function isRecording() {
        return Boolean(state.recorder.instance && state.recorder.instance.state !== "inactive");
    }

    function renderAudioSegments() {
        const root = $("#audio-preview-list");
        const template = $("#audio-preview-template");
        root.querySelectorAll("audio").forEach(releaseAudioElement);
        root.replaceChildren();
        state.submission.audioSegments.forEach((segment, index) => {
            const card = template.content.firstElementChild.cloneNode(true);
            card.dataset.segmentId = segment.segmentId;
            const audio = card.querySelector("audio");
            const local = localAudioFile(segment.segmentId);
            const objectUrl = local ? ensureAudioObjectUrl(segment.segmentId, local.file) : null;
            if (objectUrl) {
                audio.src = objectUrl;
                audio.setAttribute("aria-label", `播放第${index + 1}段现场录音`);
                audio.addEventListener("play", () => pauseOtherAudioPreviews(audio));
            } else {
                audio.hidden = true;
                audio.removeAttribute("src");
            }
            card.querySelector("[data-audio-name]").textContent =
                `第${index + 1}段 · ${segment.originalFilename || "现场录音"}`;
            card.querySelector("[data-audio-size]").textContent = audioSegmentDetail(segment);
            const status = card.querySelector("[data-audio-status]");
            status.textContent = audioSegmentStatusText(segment);
            status.className = `audio-segment-status is-${String(segment.uploadState || "LOCAL").toLowerCase()}`;

            const retry = card.querySelector("[data-audio-retry]");
            retry.hidden = !audioSegmentNeedsRetry(segment);
            retry.textContent = local && state.submission.serverId ? "重试上传" : "重新选择";
            retry.addEventListener("click", () => retryAudioSegment(segment.segmentId));
            const skip = card.querySelector("[data-audio-skip]");
            skip.hidden = !audioSegmentCanSkip(segment);
            skip.addEventListener("click", () => skipAudioSegment(segment.segmentId));
            const remove = card.querySelector("[data-audio-remove]");
            remove.textContent = segment.uploadState === "DELETING" ? "删除中…" : "移除";
            remove.disabled = segment.uploadState === "UPLOADING" || segment.uploadState === "DELETING";
            remove.addEventListener("click", () => removeAudioSegment(segment.segmentId));
            root.appendChild(card);
        });
    }

    function audioSegmentDetail(segment) {
        const captureSource = normalizeAudioCaptureSource(segment.captureSource, segment.source);
        const details = captureSource === "BROWSER_RECORDER"
            ? [
                "页面录制",
                segment.clientStartedAt
                    ? `录制时间 ${formatDateTime(segment.clientStartedAt)}`
                    : "录制时间未保留"
            ]
            : ["已有文件", "时间不可核验"];
        if (captureSource === "FILE_UPLOAD" && segment.fileLastModifiedAt) {
            details.push(`文件标记 ${formatDateTime(segment.fileLastModifiedAt)}`);
        }
        if (Number.isFinite(Number(segment.clientDurationMs)) && Number(segment.clientDurationMs) > 0) {
            details.push(`时长 ${formatDuration(Number(segment.clientDurationMs))}`);
        }
        if (segment.sizeBytes != null && Number.isFinite(Number(segment.sizeBytes))
                && Number(segment.sizeBytes) > 0) {
            details.push(formatBytes(Number(segment.sizeBytes)));
        }
        return details.join(" · ") || "文件信息待确认";
    }

    function audioSegmentStatusText(segment) {
        if (segment.uploadState === "UPLOADED") return "草稿已上传";
        if (segment.uploadState === "UPLOADING") return "正在上传…";
        if (segment.uploadState === "DELETING") return "正在删除…";
        if (segment.uploadState === "SKIPPED") {
            return segment.errorMessage || "已跳过此段，不影响本次打卡";
        }
        if (segment.uploadState === "UNKNOWN") return "上次上传结果待确认，可重选原文件重试或移除";
        if (segment.uploadState === "NEEDS_FILE") return "刷新后需重新选择原文件";
        if (segment.uploadState === "ERROR") return segment.errorMessage || "上传失败，可重试";
        return "待上传";
    }

    function audioSegmentNeedsRetry(segment) {
        return ["UNKNOWN", "NEEDS_FILE", "ERROR", "SKIPPED"].includes(segment.uploadState);
    }

    function audioSegmentCanSkip(segment) {
        return ["UNKNOWN", "NEEDS_FILE", "ERROR"].includes(segment.uploadState);
    }

    function findAudioSegment(segmentId) {
        return state.submission.audioSegments.find((segment) => segment.segmentId === segmentId) || null;
    }

    function localAudioFile(segmentId) {
        return state.files.audio.find((entry) => entry.segmentId === segmentId) || null;
    }

    function ensureAudioObjectUrl(segmentId, file) {
        if (state.objectUrls.audio.has(segmentId)) return state.objectUrls.audio.get(segmentId);
        const url = URL.createObjectURL(file);
        state.objectUrls.audio.set(segmentId, url);
        return url;
    }

    async function retryAudioSegment(segmentId) {
        if (state.submitting) return;
        const segment = findAudioSegment(segmentId);
        if (!segment) return;
        const local = localAudioFile(segmentId);
        if (local && state.submission.serverId) {
            clearFieldError("audio-file");
            try {
                await uploadAudioSegment(segment, local.file, 1, 1,
                    Date.now() + OPTIONAL_MEDIA_UPLOAD_BUDGET_MS);
                renderAudioSegments();
                renderUploadedBadges();
            } catch (error) {
                segment.uploadState = segment.mayExistRemotely ? "UNKNOWN" : "ERROR";
                segment.errorMessage = optionalUploadFailureMessage(
                    error, "录音", segment.mayExistRemotely);
                renderAudioSegments();
                setFieldError("audio-file", segment.errorMessage);
            }
            persistDraft();
            return;
        }
        state.audioRetrySegmentId = segmentId;
        $("#audio-file").click();
    }

    function skipAudioSegment(segmentId) {
        if (state.submitting) return;
        const segment = findAudioSegment(segmentId);
        if (!segment || ["UPLOADED", "UPLOADING", "DELETING"].includes(segment.uploadState)) return;
        markAudioSegmentSkipped(segment, segment.errorMessage || "已手动跳过此段录音，不影响打卡");
        clearFieldError("audio-file");
        renderAudioSegments();
        renderUploadedBadges();
        persistDraft();
    }

    function markAudioSegmentSkipped(segment, reason) {
        segment.uploadState = "SKIPPED";
        segment.errorMessage = cleanText(reason) || "录音未能上传，已自动跳过，不影响打卡";
    }

    async function removeAudioSegment(segmentId) {
        if (state.submitting) return;
        const segment = findAudioSegment(segmentId);
        if (!segment) return;
        clearFieldError("audio-file");
        if (segment.mayExistRemotely) {
            if (!state.submission.serverId) {
                setFieldError("audio-file", "已上传录音缺少服务端草稿编号，无法安全删除。" );
                return;
            }
            const previousState = segment.uploadState;
            segment.uploadState = "DELETING";
            renderAudioSegments();
            try {
                await deleteAudioSegmentRemote(segmentId);
            } catch (error) {
                segment.uploadState = previousState;
                const message = errorMessage(error, "录音物理删除失败，原文件仍保留。");
                segment.errorMessage = message;
                renderAudioSegments();
                setFieldError("audio-file", message);
                return;
            }
        }
        removeLocalAudioFile(segmentId);
        state.submission.audioSegments = state.submission.audioSegments
            .filter((item) => item.segmentId !== segmentId);
        renderAudioSegments();
        renderUploadedBadges();
        persistDraft();
    }

    async function deleteAudioSegmentRemote(segmentId) {
        await requestJson(
            `/submissions/${encodeURIComponent(state.submission.serverId)}/media/audio/${encodeURIComponent(segmentId)}`,
            {
                method: "DELETE",
                headers: { "X-Submission-Key": state.submission.submissionKey },
                timeout: 45000
            }
        );
    }

    async function clearFile(kind) {
        const mediaKind = MEDIA[kind];
        const mayExistRemotely = mayHaveRemoteMediaState(mediaKind);
        const errorKey = mediaErrorKey(kind);
        const button = mediaRemoveButton(kind);
        const originalLabel = button.textContent;
        clearFieldError(errorKey);
        if (mayExistRemotely) {
            if (!state.submission.serverId) {
                const message = "已上传文件缺少服务端草稿编号，无法安全删除；请刷新页面后重试。";
                setFieldError(errorKey, message);
                showError(message);
                return false;
            }
            button.disabled = true;
            button.textContent = "删除中…";
            try {
                await deleteUploadedMedia(mediaKind);
            } catch (error) {
                const message = errorMessage(error, "服务端文件删除失败，原文件仍保留，请稍后重试。");
                setFieldError(errorKey, message);
                showError(message);
                return false;
            } finally {
                button.disabled = false;
                button.textContent = originalLabel;
            }
        }

        resetLocalFile(kind);
        state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts
            .filter((item) => item !== mediaKind);
        renderUploadedBadges();
        persistDraft();
        clearFieldError(errorKey);
        return true;
    }

    async function deleteUploadedMedia(mediaKind) {
        await requestJson(
            `/submissions/${encodeURIComponent(state.submission.serverId)}/media/${mediaKind}`,
            {
                method: "DELETE",
                headers: { "X-Submission-Key": state.submission.submissionKey },
                timeout: 45000
            }
        );
        state.submission.uploadedMedia = state.submission.uploadedMedia
            .filter((item) => item !== mediaKind);
        state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts
            .filter((item) => item !== mediaKind);
        renderUploadedBadges();
        persistDraft();
    }

    function resetLocalFile(kind) {
        if (kind === "photo") {
            state.files[kind] = null;
            revokeObjectUrl(kind);
            $("#storefront-photo").value = "";
            $("#photo-preview-card").hidden = true;
        } else if (kind === "wechat") {
            state.files[kind] = null;
            revokeObjectUrl(kind);
            $("#wechat-screenshot").value = "";
            $("#wechat-preview-card").hidden = true;
        } else {
            state.audioFileSelectionSequence += 1;
            state.audioRetrySegmentId = null;
            releaseAllAudioPreviews();
            state.files.audio = [];
            state.submission.audioSegments = [];
            $("#audio-file").value = "";
            hideAudioSelectionNotice();
            renderAudioSegments();
        }
    }

    function mediaErrorKey(kind) {
        if (kind === "photo") return "storefront-photo";
        if (kind === "wechat") return "wechat-screenshot";
        return "audio-file";
    }

    function mediaErrorKeyForMediaKind(mediaKind) {
        if (mediaKind === MEDIA.photo) return "storefront-photo";
        if (mediaKind === MEDIA.wechat) return "wechat-screenshot";
        return "audio-file";
    }

    function mediaRemoveButton(kind) {
        const previewButton = kind === "photo"
            ? $("#remove-photo-button") : $("#remove-wechat-button");
        if (!previewButton.closest("[hidden]")) return previewButton;
        if (kind === "photo") return $("#delete-uploaded-photo-button");
        return $("#delete-uploaded-wechat-button");
    }

    function pauseAudioElement(audio) {
        if (!audio) return;
        audio.pause();
        try {
            audio.currentTime = 0;
        } catch (_) {
            // 尚未读取元数据时部分浏览器不允许修改 currentTime。
        }
    }

    function releaseAudioElement(audio) {
        if (!audio) return;
        pauseAudioElement(audio);
        audio.removeAttribute("src");
        audio.load();
    }

    function pauseAllAudioPreviews() {
        $$("#audio-preview-list audio").forEach((audio) => audio.pause());
    }

    function pauseOtherAudioPreviews(current) {
        $$("#audio-preview-list audio").forEach((audio) => {
            if (audio !== current) audio.pause();
        });
    }

    function removeLocalAudioFile(segmentId) {
        $$("#audio-preview-list [data-audio-segment]").forEach((card) => {
            if (card.dataset.segmentId === segmentId) releaseAudioElement(card.querySelector("audio"));
        });
        const url = state.objectUrls.audio.get(segmentId);
        if (url) URL.revokeObjectURL(url);
        state.objectUrls.audio.delete(segmentId);
        state.files.audio = state.files.audio.filter((entry) => entry.segmentId !== segmentId);
    }

    function releaseAllAudioPreviews() {
        $$("#audio-preview-list audio").forEach(releaseAudioElement);
        state.objectUrls.audio.forEach((url) => URL.revokeObjectURL(url));
        state.objectUrls.audio.clear();
    }

    function revokeObjectUrl(kind) {
        if (state.objectUrls[kind]) URL.revokeObjectURL(state.objectUrls[kind]);
        state.objectUrls[kind] = null;
    }

    async function submitStore(event) {
        event.preventDefault();
        if (state.submitting) return;
        hideError();
        syncStateFromForm();
        const diagnosticId = secureUuid();
        if (!validateStore()) {
            emitClientDiagnostic("STORE_SAVE_CLICK", "BLOCKED", {}, diagnosticId);
            scrollToFirstError();
            return;
        }
        emitClientDiagnostic("STORE_SAVE_CLICK", "STARTED", {}, diagnosticId);
        const payload = buildStorePayload();
        const selectedStoreBeforeSave = state.visit.selectedStore?.id || null;
        if (selectedStoreBeforeSave && !confirmVisitMediaResetForStoreChange()) {
            emitClientDiagnostic("STORE_SAVE_CLICK", "BLOCKED", {}, diagnosticId);
            return;
        }
        const button = $("#submit-store-button");
        state.submitting = true;
        setFormsDisabled(true);
        renderBusinessLock();
        abortPoiSearch();
        Object.values(state.locationControllers).forEach((controller) => controller?.abort());
        state.locationControllers.visit = null;
        state.locationControllers.store = null;
        cancelLocationCapture("visit");
        cancelLocationCapture("store");
        button.textContent = "正在保存…";
        let savedStore = null;
        try {
            const unverified = locationExceptionReady(state.store.locationContext);
            const response = normalizeResponse(await requestJson(
                unverified ? "/stores/unverified-location" : "/stores", {
                method: "POST",
                headers: { "X-Sales-Checkin-Client-Event-Id": diagnosticId },
                body: payload,
                timeout: 45000
            })) || {};
            if (!response.id) {
                throw new Error("门店保存请求已完成，但服务端未返回门店编号。当前表单已保留，请勿重复填写并联系管理员。" );
            }
            const locationSummary = response.locationSummary || payload.sourcePoiAddress
                || payload.location?.note || state.store.locationContext?.address
                || (unverified ? "定位未核验" : "位置已采集");
            const createdStore = {
                source: "REGISTERED",
                storeId: response.id,
                name: response.name || payload.name,
                city: response.city || payload.city,
                address: locationSummary,
                locationSummary,
                distanceMeters: 0,
                locationSource: payload.sourcePoiToken ? "AMAP_POI" : "STORE_LOCATION",
                checkinEligible: true,
                nextAction: "CHECK_IN",
                locationVerificationStatus: cleanText(response.locationVerificationStatus)
                    || (unverified ? "UNVERIFIED" : "VERIFIED")
            };
            savedStore = createdStore;
            if (selectedStoreBeforeSave
                    && String(selectedStoreBeforeSave) !== String(createdStore.storeId)) {
                resetVisitMediaForStoreChange();
            }
            completeStoreSaveTransition(createdStore, payload);
            emitClientDiagnostic("STORE_SAVE_CLICK", "SUCCEEDED", {}, diagnosticId);
        } catch (error) {
            emitClientDiagnostic("STORE_SAVE_CLICK", "FAILED", {}, diagnosticId);
            if (savedStore) {
                recoverSavedStoreTransition(savedStore, payload);
                showError(`门店“${savedStore.name}”已保存并选中，但页面局部刷新失败。请刷新页面后继续打卡，不要重复录入门店。`);
            } else {
                showError(errorMessage(error, "保存门店失败，已保留当前填写内容，请检查网络后重试。"));
            }
        } finally {
            state.submitting = false;
            setFormsDisabled(false);
            if (state.activeTab === "store") renderStoreSource();
            button.textContent = "保存门店并返回打卡";
        }
    }

    function completeStoreSaveTransition(createdStore, payload) {
        const inputWasActive = releaseActiveInput();
        // 先终止旧搜索，再一次性写入新门店和选中状态，避免迟到响应覆盖刚保存的门店。
        abortPoiSearch();
        hideStoreResults();
        hidePoiResults();

        state.visit.city = payload.city;
        state.visit.salespersonId = payload.salespersonId;
        const existingNearbyStores = Array.isArray(state.visit.nearbyStores)
            ? state.visit.nearbyStores
            : [];
        state.visit.nearbyStores = [createdStore, ...existingNearbyStores.filter((item) =>
            String(item.storeId || item.id) !== String(createdStore.storeId))];
        state.visit.selectedStore = {
            id: createdStore.storeId,
            name: createdStore.name,
            city: createdStore.city,
            locationSummary: createdStore.locationSummary
        };
        if (!state.visit.customerName) state.visit.customerName = payload.contactName;
        if (!state.visit.customerPhone && payload.contactPhone) state.visit.customerPhone = payload.contactPhone;
        if (locationExceptionReady(state.store.locationContext)
                || !locationFlowReady(state.visit.locationContext)) {
            state.visit.location = payload.location ? { ...payload.location } : null;
            state.visit.locationContext = state.store.locationContext
                ? { ...state.store.locationContext }
                : null;
        }

        resetStoreDraft(payload.city, payload.salespersonId);
        state.activeTab = "visit";
        state.ui.visitStep = 2;
        clearAllErrors();
        hideError();
        persistDraft();
        populateCitySelects();
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
        renderRestoredValues();
        renderTab("visit");
        renderLocation("visit");
        renderNearbyStores();
        renderSelectedStore();
        $("#restore-notice").hidden = true;
        $("#store-saved-name").textContent = createdStore.name;
        $("#store-saved-notice").hidden = false;
        persistDraft();
        runAfterMobileInputSettles(() => {
            $("#store-saved-notice")?.scrollIntoView({ behavior: "auto", block: "start" });
        }, inputWasActive);
    }

    function recoverSavedStoreTransition(createdStore, payload) {
        state.visit.city = payload.city;
        state.visit.salespersonId = payload.salespersonId;
        const existingNearbyStores = Array.isArray(state.visit.nearbyStores)
            ? state.visit.nearbyStores
            : [];
        state.visit.nearbyStores = [createdStore, ...existingNearbyStores.filter((item) =>
            String(item.storeId || item.id) !== String(createdStore.storeId))];
        state.visit.selectedStore = {
            id: createdStore.storeId,
            name: createdStore.name,
            city: createdStore.city,
            locationSummary: createdStore.locationSummary
        };
        if (locationExceptionReady(state.store.locationContext)
                || !locationFlowReady(state.visit.locationContext)) {
            state.visit.location = payload.location ? { ...payload.location } : null;
            state.visit.locationContext = state.store.locationContext
                ? { ...state.store.locationContext }
                : null;
        }
        state.store = freshStore();
        state.store.city = payload.city;
        state.store.salespersonId = payload.salespersonId;
        state.ui.storeStep = 1;
        state.activeTab = "visit";
        state.ui.visitStep = 2;
        persistDraft();
        try {
            renderTab("visit");
            renderNearbyStores();
            renderSelectedStore();
            $("#restore-notice").hidden = true;
            $("#store-saved-name").textContent = createdStore.name;
            $("#store-saved-notice").hidden = false;
            window.scrollTo({ top: 0, behavior: "auto" });
        } catch (_) {
            // 状态已先写入草稿，刷新页面仍会回到拜访并选中已保存门店。
        }
    }

    async function submitVisit(event) {
        event.preventDefault();
        hideError();
        syncStateFromForm();
        if (state.recorder.starting) {
            cleanupRecorder();
            showAudioSelectionNotice("已停止等待麦克风授权；录音为选填，继续提交本次打卡。");
        }
        if (recordingBusy()) {
            setFieldError("audio-file", "请先结束录音，确认音频已生成后再提交。" );
            scrollToFirstError();
            return;
        }
        if (state.submission.audioSegments.some((segment) =>
            ["UPLOADING", "DELETING"].includes(segment.uploadState))) {
            setFieldError("audio-file", "请等待当前录音上传或删除完成后再提交。" );
            scrollToFirstError();
            return;
        }
        if (!validateVisit()) {
            scrollToFirstError();
            return;
        }

        // 音频为选填：若手机文件提供器仍在异步识别文件，提交优先继续，丢弃迟到结果。
        state.audioFileSelectionSequence += 1;
        state.submitting = true;
        setFormsDisabled(true);
        prepareProgress();
        $("#upload-panel").hidden = false;
        $("#upload-panel").scrollIntoView({ behavior: "smooth", block: "start" });

        let activeStep = "draft";
        let skippedAudioCount = 0;
        let uncertainAudioCount = 0;
        let skippedWechatOutcome = null;
        let optionalMediaDeadlineMs = null;
        try {
            setProgressStep("draft", "active", state.submission.serverId
                ? "正在校验并恢复打卡草稿"
                : "正在创建打卡草稿");
            if (!state.submission.attemptedPayload) {
                state.submission.attemptedPayload = buildSubmissionPayload();
                state.submission.businessLocked = true;
                renderBusinessLock();
                persistDraft();
            }
            const previousServerId = state.submission.serverId;
            const unverifiedEndpoint = Boolean(
                cleanText(state.submission.attemptedPayload?.locationFailureReason)
                && isUuidValue(state.submission.attemptedPayload?.locationAttemptId));
            const response = normalizeResponse(await requestJson(
                unverifiedEndpoint ? "/submissions/unverified-location" : "/submissions", {
                method: "POST",
                headers: { "X-Submission-Key": state.submission.submissionKey },
                body: state.submission.attemptedPayload,
                timeout: 45000
            })) || {};
            if (!response.id) throw new Error("服务端未返回提交编号，请重试。" );
            if (previousServerId && String(previousServerId) !== String(response.id)) {
                throw new Error("幂等草稿编号不一致，已停止上传，请联系管理员。" );
            }
            state.submission.serverId = response.id;
            state.submission.status = response.status || "DRAFT";
            state.submission.createdAt = response.createdAt || state.submission.createdAt || new Date().toISOString();
            state.submission.businessLocked = true;
            renderBusinessLock();
            persistDraft();
            setProgressStep("draft", "done");

            const uploads = [
                { step: MEDIA.photo, file: state.files.photo, required: true },
                { step: MEDIA.wechat, file: state.files.wechat, required: false }
            ];
            for (const upload of uploads) {
                activeStep = upload.step;
                if (state.submission.uploadedMedia.includes(upload.step)) {
                    setProgressStep(upload.step, "done");
                    continue;
                }
                if (!upload.file) {
                    if (state.submission.mediaUploadAttempts.includes(upload.step)) {
                        const label = upload.step === MEDIA.photo ? "现场照片"
                            : upload.step === MEDIA.wechat ? "企微截图" : "拜访录音";
                        if (upload.required) {
                            setProgressStep(upload.step, "error", `${label}上传结果待确认`);
                            throw new Error(`${label}上次上传结果未确认。请重新选择原文件继续重试，或点击删除明确放弃。`);
                        }
                        skippedWechatOutcome = "UNKNOWN";
                        setProgressStep(upload.step, "skipped",
                            `${label}上传结果未确认，服务端可能已收到；已继续打卡`);
                        continue;
                    }
                    setProgressStep(upload.step, upload.required ? "error" : "skipped");
                    if (upload.required) throw new Error("刷新后需要重新选择现场照片，再继续提交。" );
                    continue;
                }
                setProgressStep(upload.step, "active", progressTitleForMedia(upload.step));
                if (!state.submission.mediaUploadAttempts.includes(upload.step)) {
                    state.submission.mediaUploadAttempts.push(upload.step);
                    persistDraft();
                }
                try {
                    if (!upload.required && !Number.isFinite(optionalMediaDeadlineMs)) {
                        optionalMediaDeadlineMs = Date.now() + OPTIONAL_MEDIA_UPLOAD_BUDGET_MS;
                    }
                    await uploadMedia(upload.step, upload.file, undefined, {}, upload.required
                        ? {} : { optionalDeadlineMs: optionalMediaDeadlineMs });
                } catch (error) {
                    if (upload.required) throw error;
                    skippedWechatOutcome = optionalUploadOutcome(error);
                    if (skippedWechatOutcome !== "UNKNOWN") {
                        state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts
                            .filter((item) => item !== upload.step);
                    }
                    persistDraft();
                    renderUploadedBadges();
                    setProgressStep(upload.step, "skipped", optionalUploadFailureMessage(
                        error, "企微截图", skippedWechatOutcome === "UNKNOWN"));
                    continue;
                }
                state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts
                    .filter((item) => item !== upload.step);
                if (!state.submission.uploadedMedia.includes(upload.step)) {
                    state.submission.uploadedMedia.push(upload.step);
                }
                persistDraft();
                renderUploadedBadges();
                setProgressStep(upload.step, "done");
            }

            activeStep = MEDIA.audio;
            const audioSegments = [...state.submission.audioSegments];
            if (!audioSegments.length) {
                setProgressStep(MEDIA.audio, "skipped");
            } else {
                let uploadedAudioCount = 0;
                for (let index = 0; index < audioSegments.length; index += 1) {
                    const segment = audioSegments[index];
                    if (segment.uploadState === "UPLOADED") {
                        uploadedAudioCount += 1;
                        continue;
                    }
                    if (segment.uploadState === "SKIPPED") {
                        skippedAudioCount += 1;
                        if (segment.mayExistRemotely) uncertainAudioCount += 1;
                        continue;
                    }
                    const local = localAudioFile(segment.segmentId);
                    if (!local) {
                        const uploadMayExist = segment.mayExistRemotely === true
                            || segment.uploadState === "UNKNOWN";
                        segment.mayExistRemotely = uploadMayExist;
                        markAudioSegmentSkipped(segment, uploadMayExist
                            ? "录音上传结果未确认，服务端可能已收到；已继续打卡"
                            : "刷新后未能恢复原录音文件，已自动跳过，不影响打卡");
                        skippedAudioCount += 1;
                        if (uploadMayExist) uncertainAudioCount += 1;
                        renderAudioSegments();
                        persistDraft();
                        continue;
                    }
                    try {
                        if (!Number.isFinite(optionalMediaDeadlineMs)) {
                            optionalMediaDeadlineMs = Date.now() + OPTIONAL_MEDIA_UPLOAD_BUDGET_MS;
                        }
                        await uploadAudioSegment(segment, local.file, index + 1,
                            audioSegments.length, optionalMediaDeadlineMs);
                        uploadedAudioCount += 1;
                    } catch (error) {
                        const uploadMayExist = segment.mayExistRemotely === true;
                        markAudioSegmentSkipped(segment, optionalUploadFailureMessage(
                            error, "录音", uploadMayExist));
                        skippedAudioCount += 1;
                        if (uploadMayExist) uncertainAudioCount += 1;
                        renderAudioSegments();
                        renderUploadedBadges();
                        persistDraft();
                    }
                }
                renderUploadedBadges();
                if (uploadedAudioCount) {
                    setProgressStep(MEDIA.audio, "done", skippedAudioCount
                        ? `已上传 ${uploadedAudioCount} 段录音，${skippedAudioCount} 段未影响打卡`
                        : `已上传 ${uploadedAudioCount} 段现场录音`);
                } else {
                    setProgressStep(MEDIA.audio, "skipped", skippedAudioCount
                        ? `${skippedAudioCount} 段选填录音未影响打卡，继续完成提交`
                        : undefined);
                }
            }

            activeStep = "complete";
            setProgressStep("complete", "active", "正在完成拜访打卡");
            const completed = normalizeResponse(await requestJson(
                `/submissions/${encodeURIComponent(state.submission.serverId)}/complete`,
                {
                    method: "POST",
                    headers: { "X-Submission-Key": state.submission.submissionKey },
                    timeout: 45000
                }
            )) || {};
            setProgressStep("complete", "done", "提交完成");
            showSuccess(completed, {
                skippedAudioCount,
                uncertainAudioCount,
                skippedWechatOutcome
            });
        } catch (error) {
            setProgressStep(activeStep, "error", "提交中断，可修正后继续");
            $("#progress-detail").textContent = errorMessage(error, "提交失败，请稍后重试。" );
            showError(errorMessage(error, "提交失败，请稍后重试。"));
            state.submitting = false;
            setFormsDisabled(false);
            persistDraft();
        }
    }

    async function uploadAudioSegment(segment, file, index, total, optionalMediaDeadlineMs) {
        const metadataPromise = localAudioFile(segment.segmentId)?.metadataPromise;
        if (metadataPromise) {
            try {
                await metadataPromise;
            } catch (_) {
                // 可选证据读取失败不阻断录音上传。
            }
        }
        const mayExistBeforeUpload = segment.mayExistRemotely === true;
        segment.uploadState = "UPLOADING";
        segment.mayExistRemotely = true;
        segment.errorMessage = "";
        renderAudioSegments();
        persistDraft();
        const title = `正在上传现场录音 ${index}/${total}`;
        setProgressStep(MEDIA.audio, "active", title);
        try {
            const response = normalizeResponse(await uploadMedia(
                `audio/${encodeURIComponent(segment.segmentId)}`, file, title, {
                    captureSource: normalizeAudioCaptureSource(segment.captureSource, segment.source),
                    clientStartedAt: normalizeOptionalInstant(segment.clientStartedAt),
                    clientDurationMs: normalizePositiveDurationMs(
                        segment.clientDurationMs ?? segment.durationMs),
                    fileLastModifiedAt: normalizeOptionalInstant(segment.fileLastModifiedAt)
                }, { optionalDeadlineMs: optionalMediaDeadlineMs })) || {};
            if (response.segmentId && String(response.segmentId) !== String(segment.segmentId)) {
                throw new Error("服务端返回的录音分段编号不一致，已停止提交。");
            }
            segment.uploadState = "UPLOADED";
            segment.originalFilename = response.originalFilename || segment.originalFilename;
            segment.sizeBytes = Number.isFinite(Number(response.sizeBytes))
                ? Number(response.sizeBytes) : segment.sizeBytes;
            segment.captureSource = normalizeAudioCaptureSource(
                response.captureSource || segment.captureSource, segment.source);
            segment.clientStartedAt = normalizeOptionalInstant(
                response.clientStartedAt || segment.clientStartedAt);
            segment.clientDurationMs = normalizePositiveDurationMs(
                response.clientDurationMs ?? segment.clientDurationMs ?? segment.durationMs);
            segment.fileLastModifiedAt = normalizeOptionalInstant(
                response.fileLastModifiedAt || segment.fileLastModifiedAt);
            segment.errorMessage = "";
            renderAudioSegments();
            renderUploadedBadges();
            persistDraft();
            return response;
        } catch (error) {
            const outcome = optionalUploadOutcome(error);
            segment.mayExistRemotely = mayExistBeforeUpload || outcome === "UNKNOWN";
            segment.uploadState = segment.mayExistRemotely ? "UNKNOWN" : "ERROR";
            segment.errorMessage = optionalUploadFailureMessage(
                error, "录音", segment.mayExistRemotely);
            renderAudioSegments();
            renderUploadedBadges();
            persistDraft();
            throw error;
        }
    }

    function uploadMedia(kind, file, progressTitle, optionalFormFields = {}, uploadOptions = {}) {
        return new Promise((resolve, reject) => {
            const optionalDeadlineMs = Number(uploadOptions.optionalDeadlineMs);
            const hasOptionalDeadline = Number.isFinite(optionalDeadlineMs);
            const remainingOptionalMs = hasOptionalDeadline
                ? optionalDeadlineMs - Date.now() : null;
            if (hasOptionalDeadline && remainingOptionalMs <= 0) {
                const error = new Error("选填材料上传等待已达30秒，本文件未再上传，已继续打卡。");
                error.code = "OPTIONAL_MEDIA_BUDGET_EXHAUSTED";
                error.uploadOutcome = "NOT_ATTEMPTED";
                reject(error);
                return;
            }
            const xhr = new XMLHttpRequest();
            const formData = new FormData();
            let settled = false;
            let optionalTimeoutId = null;
            const settle = (callback, value) => {
                if (settled) return false;
                settled = true;
                window.clearTimeout(optionalTimeoutId);
                callback(value);
                return true;
            };
            const rejectUpload = (error, outcome = "UNKNOWN") => {
                if (!error.uploadOutcome) error.uploadOutcome = outcome;
                settle(reject, error);
            };
            formData.append("file", file, file.name || kind);
            Object.entries(optionalFormFields).forEach(([name, value]) => {
                if (value !== null && value !== undefined && value !== "") {
                    formData.append(name, String(value));
                }
            });
            xhr.open("PUT",
                `${API_BASE}/submissions/${encodeURIComponent(state.submission.serverId)}/media/${kind}`,
                true);
            xhr.withCredentials = true;
            // 必填门头照仍不设前端总时长；选填媒体由共享软预算主动结束等待，避免 QQ/X5 半开连接卡死提交。
            xhr.timeout = 0;
            xhr.setRequestHeader("Accept", "application/json");
            xhr.setRequestHeader("X-Submission-Key", state.submission.submissionKey);
            if (xhr.upload) {
                xhr.upload.addEventListener("progress", (event) => {
                    if (!event.lengthComputable || event.total <= 0) return;
                    const percent = Math.min(99, Math.round((event.loaded / event.total) * 100));
                    const title = progressTitle || progressTitleForMedia(kind);
                    $("#progress-title").textContent = `${title} ${percent}%`;
                });
            }
            xhr.addEventListener("load", () => {
                const payload = parseResponsePayload(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) {
                    settle(resolve, payload);
                    return;
                }
                const error = new Error(extractApiMessage(payload) || `上传失败（HTTP ${xhr.status}）`);
                error.status = xhr.status;
                error.payload = payload;
                rejectUpload(error, xhr.status >= 400 && xhr.status < 500
                    ? "REJECTED" : "UNKNOWN");
            });
            xhr.addEventListener("timeout", () => rejectUpload(
                new Error("上传连接超时，文件仍保留在本页，可直接重试。")));
            xhr.addEventListener("error", () => rejectUpload(
                new Error("上传网络中断，文件仍保留在本页，可直接重新提交。")));
            xhr.addEventListener("abort", () => rejectUpload(
                new Error("上传已中断，文件仍保留在本页，可直接重新提交。")));
            if (hasOptionalDeadline) {
                optionalTimeoutId = window.setTimeout(() => {
                    if (settled) return;
                    const error = new Error("选填材料上传等待超过30秒，已停止等待并继续打卡。");
                    error.code = "OPTIONAL_MEDIA_SOFT_TIMEOUT";
                    error.uploadOutcome = "UNKNOWN";
                    settled = true;
                    window.clearTimeout(optionalTimeoutId);
                    try {
                        xhr.abort();
                    } catch (_) {
                        // 某些旧 WebView 在连接已由系统回收时再次 abort 会抛错。
                    }
                    reject(error);
                }, Math.max(1, remainingOptionalMs));
            }
            try {
                xhr.send(formData);
            } catch (error) {
                rejectUpload(error, "NOT_ATTEMPTED");
            }
        });
    }

    function optionalUploadOutcome(error) {
        if (["REJECTED", "NOT_ATTEMPTED", "UNKNOWN"].includes(error?.uploadOutcome)) {
            return error.uploadOutcome;
        }
        if (Number.isFinite(error?.status) && error.status >= 400 && error.status < 500) {
            return "REJECTED";
        }
        return "UNKNOWN";
    }

    function optionalUploadFailureMessage(error, label, mayExistRemotely = false) {
        const detail = errorMessage(error, `${label}上传失败`);
        const outcome = optionalUploadOutcome(error);
        if (mayExistRemotely || outcome === "UNKNOWN") {
            return `${detail}；上传结果未确认，服务端可能已收到，已继续打卡`;
        }
        if (outcome === "NOT_ATTEMPTED") {
            return `${detail}；本文件未上传，已跳过，不影响打卡`;
        }
        return `${detail}；服务端未接收，已跳过，不影响打卡`;
    }

    function parseResponsePayload(text) {
        if (!text) return null;
        try {
            return JSON.parse(text);
        } catch (_) {
            return { message: String(text).slice(0, 300) };
        }
    }

    function buildStorePayload() {
        const location = withCurrentLocationNote("store");
        const unverified = locationExceptionReady(state.store.locationContext);
        return compactObject({
            clientStoreId: state.store.clientStoreId,
            city: state.store.city,
            salespersonId: state.store.salespersonId,
            attribute: state.store.attribute,
            name: state.store.name.trim(),
            operatingStatus: state.store.operatingStatus,
            contactName: state.store.contactName.trim(),
            contactPhone: optionalText(state.store.contactPhone),
            areaRange: state.store.areaRange,
            facilityCount: state.store.facilityCount.trim(),
            businessTypes: [...state.store.businessTypes],
            intendedBusinesses: [...state.store.intendedBusinesses],
            cooperationIntent: state.store.cooperationIntent,
            storeGrade: optionalText(state.store.storeGrade),
            tags: [...state.store.tags],
            sourcePoiToken: unverified ? undefined : optionalText(state.store.sourcePoiToken),
            manualEntryToken: !unverified && state.store.sourceMode === "MANUAL"
                ? optionalText(state.store.manualEntryToken) : undefined,
            locationVerificationToken: unverified ? undefined : optionalText(
                state.store.locationContext?.locationVerificationToken),
            sourcePoiId: unverified ? undefined : optionalText(state.store.sourcePoiId),
            sourcePoiName: unverified ? undefined : optionalText(state.store.sourcePoiName),
            sourcePoiAddress: unverified ? undefined : optionalText(state.store.sourcePoiAddress),
            sourcePoiLongitude: unverified ? undefined : finiteNumberOrNull(state.store.sourcePoiLongitude),
            sourcePoiLatitude: unverified ? undefined : finiteNumberOrNull(state.store.sourcePoiLatitude),
            locationFailureReason: unverified
                ? state.store.locationContext.locationFailureReason : undefined,
            locationAttemptId: unverified
                ? state.store.locationContext.locationAttemptId : undefined,
            location
        });
    }

    function buildSubmissionPayload() {
        const unverified = locationExceptionReady(state.visit.locationContext);
        return compactObject({
            clientSubmissionId: state.submission.clientSubmissionId,
            submissionKey: state.submission.submissionKey,
            city: state.visit.city,
            salespersonId: state.visit.salespersonId,
            storeId: state.visit.selectedStore.id,
            customerName: state.visit.customerName.trim(),
            customerPhone: optionalText(state.visit.customerPhone),
            visitResult: state.visit.visitResult.trim(),
            location: withCurrentLocationNote("visit"),
            locationVerificationToken: unverified ? undefined : optionalText(
                state.visit.locationContext?.locationVerificationToken),
            locationFailureReason: unverified
                ? state.visit.locationContext.locationFailureReason : undefined,
            locationAttemptId: unverified
                ? state.visit.locationContext.locationAttemptId : undefined,
            privacyAccepted: state.visit.privacyAccepted === true,
            privacyNoticeVersion: PRIVACY_NOTICE_VERSION
        });
    }

    function withCurrentLocationNote(scope) {
        const location = state[scope].location ? { ...state[scope].location } : null;
        if (!location) return null;
        const note = $(`#${scope}-location-note`).value.trim();
        if (note) location.note = note;
        else delete location.note;
        state[scope].location = location;
        return location;
    }

    function validateVisit() {
        clearAllErrors();
        let valid = true;
        valid = requireValue(state.visit.city, "visit-city", "请选择业务归属城市。") && valid;
        valid = requireValue(state.visit.salespersonId, "visit-salesperson", "请选择销售。") && valid;
        valid = requireValue(state.visit.selectedStore?.id, "selected-store", "请搜索并选择拜访门店。") && valid;
        if (state.visit.selectedStore?.id && !visitSelectedStoreReady()) {
            setFieldError("selected-store", "当前已选门店不在本次定位允许范围，请重新选择。" );
            valid = false;
        }
        valid = requireValue(state.visit.customerName.trim(), "customer-name", "请输入客户姓名。") && valid;
        valid = requireValue(state.visit.visitResult.trim(), "visit-result", "请填写拜访结果。") && valid;
        if (!isBusinessLocked() && !locationFlowReady(state.visit.locationContext)) {
            setFieldError("visit-location", state.visit.locationContext?.errorMessage
                || "请先尝试定位；定位失败或等待较久时可按页面提示继续录入。");
            valid = false;
        }
        if (!state.files.photo && !state.submission.uploadedMedia.includes(MEDIA.photo)) {
            setFieldError("storefront-photo", state.submission.serverId
                ? "请重新选择现场照片后继续上传。"
                : "请拍摄或选择一张现场照片。" );
            valid = false;
        }
        if (!state.visit.privacyAccepted) {
            setFieldError("privacy-accepted", "请确认隐私提示后再提交。" );
            valid = false;
        }
        if (state.files.photo && state.files.photo.size > MAX_IMAGE_BYTES) valid = false;
        if (state.files.wechat && state.files.wechat.size > MAX_IMAGE_BYTES) valid = false;
        return valid;
    }

    function validateStore() {
        clearAllErrors();
        let valid = true;
        valid = requireValue(state.store.city, "store-city", "请选择业务归属城市。") && valid;
        valid = requireValue(state.store.salespersonId, "store-salesperson", "请选择销售。") && valid;
        valid = requireValue(state.store.sourceMode, "store-source",
            locationExceptionReady(state.store.locationContext)
                ? "定位未核验，请选择手动录入门店。"
                : "请先从当前位置300米内高德搜索结果选择；确认没有后再手动录入。") && valid;
        if (state.store.sourceMode === "POI" && !cleanText(state.store.sourcePoiToken)) {
            setFieldError("store-source", "高德候选凭证已失效，请重新搜索并选择门店。");
            valid = false;
        }
        if (state.store.sourceMode === "MANUAL"
                && !locationExceptionReady(state.store.locationContext)
                && !cleanText(state.store.manualEntryToken)) {
            setFieldError("store-source", "人工建店凭证已失效，请重新搜索确认无结果。");
            valid = false;
        }
        valid = requireValue(state.store.name.trim(), "store-name", "请输入门店名称。") && valid;
        valid = requireValue(state.store.attribute, "store-attribute", "请选择门店属性。") && valid;
        valid = requireValue(state.store.operatingStatus, "operating-status", "请选择营业状态。") && valid;
        valid = requireValue(state.store.contactName.trim(), "contact-name", "请输入联系人。") && valid;
        valid = requireValue(state.store.areaRange, "area-range", "请选择面积范围。") && valid;
        valid = requireValue(state.store.facilityCount.trim(), "facility-count", "请输入设施数量，如：10张球桌。") && valid;
        valid = requireValue(state.store.cooperationIntent, "cooperation-intent", "请选择合作意向。") && valid;
        if (!state.store.businessTypes.length) {
            setFieldError("business-types", "请至少选择一项业务类型。" );
            valid = false;
        }
        if (!state.store.intendedBusinesses.length) {
            setFieldError("intended-businesses", "请至少选择一项意向业务。" );
            valid = false;
        }
        if (!state.store.tags.length) {
            setFieldError("store-tags", "请至少选择一个门店标签。" );
            valid = false;
        }
        if (!locationFlowReady(state.store.locationContext)) {
            setFieldError("store-location", state.store.locationContext?.errorMessage
                || "请先尝试定位；定位失败或等待较久时可按页面提示继续录入。");
            valid = false;
        }
        return valid;
    }

    function requireValue(value, key, message) {
        if (value == null || value === "") {
            setFieldError(key, message);
            return false;
        }
        return true;
    }

    function setFieldError(key, message) {
        const error = document.querySelector(`[data-error-for="${key}"]`);
        if (!error) return;
        error.textContent = message;
        const disclosure = error.closest("details");
        if (disclosure) disclosure.open = true;
        error.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
            ?.classList.add("has-error");
    }

    function clearFieldError(key) {
        const error = document.querySelector(`[data-error-for="${key}"]`);
        if (!error) return;
        error.textContent = "";
        error.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
            ?.classList.remove("has-error");
    }

    function clearAllErrors() {
        $$(".field__error").forEach((error) => {
            error.textContent = "";
            error.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
                ?.classList.remove("has-error");
        });
    }

    function scrollToFirstError() {
        const first = $$(".field__error").find((error) => error.textContent.trim());
        if (!first) return;
        const flowPanel = first.closest("[data-flow-step-panel]");
        const inputWasActive = document.body.classList.contains("has-mobile-input-focus")
            || isMobileTextEntryControl(document.activeElement);
        let changedStep = false;
        if (flowPanel) {
            const flow = flowPanel.dataset.flowStepPanel;
            const targetStep = normalizeFlowStep(Number(flowPanel.dataset.stepValue));
            changedStep = normalizeFlowStep(state.ui[flowStateKey(flow)]) !== targetStep;
            goToFlowStep(flow, targetStep, {
                validateForward: false,
                scroll: false
            });
        }
        const disclosure = first?.closest("details");
        if (disclosure) disclosure.open = true;
        const revealError = () => window.requestAnimationFrame(() => {
            const container = first.closest(".form-card, .consent-card, .flow-step-panel");
            container?.scrollIntoView({ behavior: "smooth", block: "center" });
            const viewportWidth = window.visualViewport?.width || window.innerWidth;
            const avoidTextEntryFocus = changedStep || viewportWidth <= 700;
            if (avoidTextEntryFocus) {
                first.tabIndex = -1;
                first.focus({ preventScroll: true });
                first.addEventListener("blur", () => first.removeAttribute("tabindex"), { once: true });
                return;
            }
            first.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
                ?.querySelector("input, select, textarea, button")
                ?.focus({ preventScroll: true });
        });
        runAfterMobileInputSettles(revealError, inputWasActive);
    }

    function prepareProgress() {
        $$("[data-progress-step]").forEach((item) => item.className = "");
        $("#progress-title").textContent = "正在创建打卡草稿";
        $("#progress-detail").textContent = "请勿关闭页面。";
        updateProgressBar();
    }

    function setProgressStep(step, status, title) {
        const item = document.querySelector(`[data-progress-step="${step}"]`);
        if (item) item.className = `is-${status}`;
        if (title) $("#progress-title").textContent = title;
        updateProgressBar();
    }

    function updateProgressBar() {
        const steps = $$("[data-progress-step]");
        const complete = steps.filter((item) => item.classList.contains("is-done") || item.classList.contains("is-skipped")).length;
        const percent = Math.round((complete / steps.length) * 100);
        $("#progress-percent").textContent = `${percent}%`;
        $("#progress-bar").style.width = `${percent}%`;
    }

    function progressTitleForMedia(kind) {
        if (kind === MEDIA.photo) return "正在上传现场照片";
        if (kind === MEDIA.wechat) return "正在上传企微截图";
        return "正在上传现场录音";
    }

    function showSuccess(completed, optionalMedia = {}) {
        const skippedAudioCount = Number(optionalMedia.skippedAudioCount) || 0;
        const uncertainAudioCount = Math.min(skippedAudioCount,
            Number(optionalMedia.uncertainAudioCount) || 0);
        const definiteAudioCount = Math.max(0, skippedAudioCount - uncertainAudioCount);
        const skippedWechatOutcome = cleanText(optionalMedia.skippedWechatOutcome).toUpperCase();
        state.submitting = false;
        state.completed = true;
        $("#upload-panel").hidden = true;
        $("#visit-panel").hidden = true;
        $("#store-panel").hidden = true;
        $(".tabs").hidden = true;
        $("#restore-notice").hidden = true;
        $("#success-submission-id").textContent = completed.id || state.submission.serverId;
        $("#success-submitted-at").textContent = completed.submittedAt
            ? `提交时间：${formatDateTime(completed.submittedAt)}`
            : "";
        $("#success-location-note").hidden = !locationExceptionReady(state.visit.locationContext);
        const mediaNote = $("#success-media-note");
        const definiteItems = [];
        const uncertainItems = [];
        if (["REJECTED", "NOT_ATTEMPTED"].includes(skippedWechatOutcome)) {
            definiteItems.push("企微截图");
        } else if (skippedWechatOutcome === "UNKNOWN") {
            uncertainItems.push("企微截图");
        }
        if (definiteAudioCount) definiteItems.push(`${definiteAudioCount} 段录音`);
        if (uncertainAudioCount) uncertainItems.push(`${uncertainAudioCount} 段录音`);
        const mediaMessages = ["打卡记录和现场照片已正常保存。"];
        if (definiteItems.length) {
            mediaMessages.push(`选填的${definiteItems.join("和")}未上传，已跳过。`);
        }
        if (uncertainItems.length) {
            mediaMessages.push(`选填的${uncertainItems.join("和")}上传结果未确认，服务端可能已收到。`);
        }
        mediaNote.textContent = definiteItems.length || uncertainItems.length
            ? mediaMessages.join("") : "";
        mediaNote.hidden = definiteItems.length === 0 && uncertainItems.length === 0;
        $("#success-panel").hidden = false;
        removeStoredDraft();
        $("#success-panel").scrollIntoView({ behavior: "smooth", block: "center" });
    }

    function renderBusinessLock() {
        const locked = isBusinessLocked();
        state.submission.businessLocked = locked;
        if (locked) {
            state.activeTab = "visit";
            state.ui.visitStep = 3;
            abortPoiSearch();
            hideStoreResults();
            hidePoiResults();
        }
        $("#draft-lock-notice").hidden = !locked;
        $("#visit-form").classList.toggle("is-business-locked", locked);
        LOCKED_BUSINESS_SELECTORS.forEach((selector) => {
            const element = $(selector);
            if (!element) return;
            if (locked) {
                if (!element.disabled) element.dataset.businessLocked = "true";
                element.disabled = true;
            } else if (element.dataset.businessLocked === "true") {
                element.disabled = false;
                delete element.dataset.businessLocked;
            }
        });
        lockIdentitySelectors();
        $("#identity-switch").disabled = state.submitting || locked;
        renderFlowSteps();
    }

    function isBusinessLocked() {
        return state.submission.businessLocked === true
            || Boolean(state.submission.serverId)
            || Boolean(state.submission.attemptedPayload);
    }

    function setFormsDisabled(disabled) {
        if (disabled) {
            cancelLocationCapture("visit");
            cancelLocationCapture("store");
        }
        $("#submit-visit-button").disabled = disabled;
        $("#submit-store-button").disabled = disabled;
        [$("#visit-form"), $("#store-form")].forEach((form) => {
            form.setAttribute("aria-busy", String(disabled));
            if (disabled) form.setAttribute("inert", "");
            else form.removeAttribute("inert");
        });
        if (!disabled) renderBusinessLock();
    }

    function startNewSubmission() {
        cleanupRecorder();
        state.recorder.elapsedMs = 0;
        $("#recording-clock").textContent = "00:00";
        abortPoiSearch();
        abortStoreDirectorySearch();
        Object.keys(state.files).forEach(resetLocalFile);
        Object.values(state.locationControllers).forEach((controller) => controller?.abort());
        state.locationControllers.visit = null;
        state.locationControllers.store = null;
        cancelLocationCapture("visit");
        cancelLocationCapture("store");
        state.activeTab = "visit";
        state.ui = freshUiState();
        state.visit = freshVisit();
        state.store = freshStore();
        if (state.identity?.authenticated) {
            state.visit.salespersonId = String(state.identity.salespersonId);
            state.store.salespersonId = String(state.identity.salespersonId);
            if (!isHeadquartersIdentity()) {
                state.visit.city = state.identity.city;
                state.store.city = state.identity.city;
            }
        }
        state.submission = freshSubmission();
        state.submitting = false;
        state.completed = false;
        state.restoredAt = null;
        removeStoredDraft();
        setFormsDisabled(false);
        $("#visit-form").reset();
        $("#store-form").reset();
        $("#recording-consent").checked = false;
        $("#success-panel").hidden = true;
        $("#restore-notice").hidden = true;
        $(".tabs").hidden = false;
        populateCitySelects();
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
        renderDictionaryControls();
        renderSelectedStore();
        renderLocation("visit");
        renderLocation("store");
        renderNearbyStores();
        renderStoreSource();
        renderStorePrefillMessage();
        renderStoreOwnerSummary();
        renderUploadedBadges();
        renderTab("visit");
        clearAllErrors();
        hideError();
        checkRecorderSupport();
        renderIdentityState();
        window.scrollTo({ top: 0, behavior: "smooth" });
    }

    async function discardDraft() {
        if (state.submitting) return;
        if (recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再放弃草稿。");
            return;
        }
        // 服务端草稿可能在 PUT 成功后丢失响应，旧版页面也可能未留下本地标记。
        // DELETE 本身是幂等的：只要已有服务端草稿，放弃时就尝试清理全部三类媒体。
        const uploadedMedia = state.submission.serverId
            ? Object.values(MEDIA)
            : [...new Set([
                ...state.submission.uploadedMedia,
                ...state.submission.mediaUploadAttempts
            ])];
        const uploadedAudioSegments = state.submission.audioSegments
            .filter((segment) => segment.mayExistRemotely);
        const warning = state.submission.serverId
            ? "将先永久清理草稿中可能存在的照片、截图和录音，再清除本机表单。服务端未完成草稿记录仍会保留，确定继续吗？"
            : "确定放弃当前未提交的表单内容吗？";
        if (!window.confirm(warning)) return;

        if (uploadedMedia.length || uploadedAudioSegments.length) {
            if (!state.submission.serverId) {
                showError("草稿缺少服务端编号，无法安全清理已上传文件；请刷新页面后重试。" );
                return;
            }
            const button = $("#discard-draft-button");
            const originalLabel = button.textContent;
            state.submitting = true;
            setFormsDisabled(true);
            button.disabled = true;
            try {
                const cleanupTotal = uploadedMedia.length + uploadedAudioSegments.length;
                for (let index = 0; index < uploadedMedia.length; index += 1) {
                    const mediaKind = uploadedMedia[index];
                    button.textContent = `清理文件 ${index + 1}/${cleanupTotal}…`;
                    await deleteUploadedMedia(mediaKind);
                }
                for (let index = 0; index < uploadedAudioSegments.length; index += 1) {
                    button.textContent = `清理录音 ${uploadedMedia.length + index + 1}/${cleanupTotal}…`;
                    await deleteAudioSegmentRemote(uploadedAudioSegments[index].segmentId);
                }
            } catch (error) {
                const message = errorMessage(error, "已上传文件清理失败，草稿仍保留，请稍后重试。" );
                const failedKind = uploadedMedia.find((item) => hasRemoteMediaState(item));
                if (failedKind) setFieldError(mediaErrorKeyForMediaKind(failedKind), message);
                showError(message);
                state.submitting = false;
                setFormsDisabled(false);
                button.disabled = false;
                button.textContent = originalLabel;
                persistDraft();
                return;
            }
            button.disabled = false;
            button.textContent = originalLabel;
        }
        startNewSubmission();
        $("#restore-notice").hidden = true;
    }

    function resetStoreDraft(city, salespersonId) {
        abortPoiSearch();
        hidePoiResults();
        state.store = freshStore();
        state.ui.storeStep = 1;
        state.store.city = city || "";
        state.store.salespersonId = salespersonId || "";
        $("#store-form").reset();
        $("#poi-search").value = "";
        $("#poi-search-help").textContent = "定位成功后，输入至少 2 个字并点击搜索。";
    }

    function hideStoreSavedNotice() {
        const notice = $("#store-saved-notice");
        if (notice) notice.hidden = true;
    }

    function persistFromForm() {
        syncStateFromForm();
        renderFlowActions();
        persistDraft();
    }

    function syncStateFromForm() {
        state.visit.city = $("#visit-city").value;
        state.visit.salespersonId = $("#visit-salesperson").value;
        state.visit.customerName = $("#customer-name").value;
        state.visit.customerPhone = $("#customer-phone").value;
        state.visit.visitResult = $("#visit-result").value;
        state.visit.privacyAccepted = $("#privacy-accepted").checked;
        if (state.visit.location) withCurrentLocationNote("visit");

        state.store.city = $("#store-city").value;
        state.store.salespersonId = $("#store-salesperson").value;
        state.store.name = $("#store-name").value;
        state.store.attribute = $("#store-attribute").value;
        state.store.operatingStatus = $("#operating-status").value;
        state.store.contactName = $("#contact-name").value;
        state.store.contactPhone = $("#contact-phone").value;
        state.store.areaRange = $("#area-range").value;
        state.store.facilityCount = $("#facility-count").value;
        state.store.cooperationIntent = $("#cooperation-intent").value;
        state.store.storeGrade = $("#store-grade").value;
        state.store.businessTypes = checkedValues("business-types");
        state.store.intendedBusinesses = checkedValues("intended-businesses");
        state.store.tags = checkedValues("store-tags");
        if (state.store.location) withCurrentLocationNote("store");
    }

    function checkedValues(name) {
        return $$(`input[name="${name}"]:checked`).map((input) => input.value);
    }

    function renderRestoredValues() {
        setValue("#visit-city", state.visit.city);
        setValue("#visit-salesperson", state.visit.salespersonId);
        setValue("#customer-name", state.visit.customerName);
        setValue("#customer-phone", state.visit.customerPhone);
        setValue("#visit-result", state.visit.visitResult);
        $("#privacy-accepted").checked = Boolean(state.visit.privacyAccepted);

        setValue("#store-city", state.store.city);
        setValue("#store-salesperson", state.store.salespersonId);
        setValue("#poi-search", state.store.poiSearchQuery);
        setValue("#store-name", state.store.name);
        setValue("#store-attribute", state.store.attribute);
        setValue("#operating-status", state.store.operatingStatus);
        setValue("#contact-name", state.store.contactName);
        setValue("#contact-phone", state.store.contactPhone);
        setValue("#area-range", state.store.areaRange);
        setValue("#facility-count", state.store.facilityCount);
        setValue("#cooperation-intent", state.store.cooperationIntent);
        setValue("#store-grade", state.store.storeGrade);
        renderStorePrefillMessage();
        renderStoreOwnerSummary();
        renderStoreSource();
        updateVisitResultCount();
    }

    function setValue(selector, value) {
        const element = $(selector);
        if (!element) return;
        const normalized = value == null ? "" : String(value);
        if (element.tagName === "SELECT" && normalized
            && !Array.from(element.options).some((option) => option.value === normalized)) return;
        element.value = normalized;
    }

    function persistDraft() {
        try {
            const payload = {
                version: STORAGE_VERSION,
                savedAt: new Date().toISOString(),
                activeTab: state.activeTab,
                ui: state.ui,
                visit: state.visit,
                store: state.store,
                submission: state.submission
            };
            sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
        } catch (error) {
            if (!state.storageUnavailableShown) {
                state.storageUnavailableShown = true;
                showError("浏览器未允许当前标签页临时保存，刷新后可能无法恢复草稿。请勿在提交完成前关闭页面。" );
            }
        }
    }

    function restoreDraft() {
        let raw;
        try {
            // 清理旧版本曾写入的长期敏感草稿；新版本只在当前标签页保存并设置有效期。
            localStorage.removeItem(STORAGE_KEY);
            raw = sessionStorage.getItem(STORAGE_KEY);
        } catch (_) {
            return;
        }
        if (!raw) return;
        let saved;
        try {
            saved = JSON.parse(raw);
        } catch (_) {
            removeStoredDraft();
            return;
        }
        const savedAt = Date.parse(saved?.savedAt || "");
        const age = Date.now() - savedAt;
        if (!saved || saved.version !== STORAGE_VERSION || !Number.isFinite(savedAt)
            || age < -5 * 60 * 1000 || age > DRAFT_TTL_MS) {
            removeStoredDraft();
            return;
        }
        state.activeTab = saved.activeTab === "store" ? "store" : "visit";
        state.visit = { ...freshVisit(), ...(saved.visit || {}) };
        state.store = { ...freshStore(), ...(saved.store || {}) };
        state.submission = { ...freshSubmission(), ...(saved.submission || {}) };
        state.submission.uploadedMedia = Array.isArray(state.submission.uploadedMedia)
            ? [...new Set(state.submission.uploadedMedia)]
            : [];
        state.submission.mediaUploadAttempts = Array.isArray(state.submission.mediaUploadAttempts)
            ? [...new Set(state.submission.mediaUploadAttempts)]
            : [];
        const legacyAudioUploaded = state.submission.uploadedMedia.includes(MEDIA.audio);
        const legacyAudioAttempted = state.submission.mediaUploadAttempts.includes(MEDIA.audio);
        const rawAudioSegments = Array.isArray(state.submission.audioSegments)
            ? state.submission.audioSegments : [];
        const audioSegmentsById = new Map();
        rawAudioSegments.forEach((rawSegment) => {
            const segmentId = cleanText(rawSegment?.segmentId);
            if (!isUuidValue(segmentId) || audioSegmentsById.has(segmentId)) return;
            let uploadState = cleanText(rawSegment.uploadState).toUpperCase();
            if (["UPLOADING", "DELETING"].includes(uploadState)) uploadState = "UNKNOWN";
            if (["LOCAL", "ERROR"].includes(uploadState)) uploadState = "NEEDS_FILE";
            if (!["UPLOADED", "UNKNOWN", "NEEDS_FILE", "SKIPPED"].includes(uploadState)) {
                uploadState = "NEEDS_FILE";
            }
            const mayExistRemotely = rawSegment.mayExistRemotely === true
                || ["UPLOADED", "UNKNOWN"].includes(uploadState);
            audioSegmentsById.set(segmentId, {
                segmentId,
                originalFilename: cleanText(rawSegment.originalFilename) || "现场录音",
                sizeBytes: finiteNumberOrNull(rawSegment.sizeBytes),
                captureSource: normalizeAudioCaptureSource(rawSegment.captureSource, rawSegment.source),
                clientStartedAt: normalizeOptionalInstant(rawSegment.clientStartedAt),
                clientDurationMs: normalizePositiveDurationMs(
                    rawSegment.clientDurationMs ?? rawSegment.durationMs),
                fileLastModifiedAt: normalizeOptionalInstant(rawSegment.fileLastModifiedAt),
                uploadState,
                mayExistRemotely,
                errorMessage: uploadState === "SKIPPED"
                    ? mayExistRemotely
                        ? "上传结果未确认，服务端可能已收到；不影响本次打卡"
                        : "已跳过此段，不影响本次打卡"
                    : ""
            });
        });
        const legacySegmentId = cleanText(state.submission.serverId);
        if ((legacyAudioUploaded || legacyAudioAttempted) && isUuidValue(legacySegmentId)
                && !audioSegmentsById.has(legacySegmentId)) {
            audioSegmentsById.set(legacySegmentId, {
                segmentId: legacySegmentId,
                originalFilename: "历史拜访录音",
                sizeBytes: null,
                captureSource: "FILE_UPLOAD",
                clientStartedAt: null,
                clientDurationMs: null,
                fileLastModifiedAt: null,
                uploadState: legacyAudioUploaded ? "UPLOADED" : "UNKNOWN",
                mayExistRemotely: true,
                errorMessage: ""
            });
        }
        state.submission.audioSegments = [...audioSegmentsById.values()];
        if (isUuidValue(legacySegmentId)) {
            state.submission.uploadedMedia = state.submission.uploadedMedia
                .filter((item) => item !== MEDIA.audio);
            state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts
                .filter((item) => item !== MEDIA.audio);
        }
        state.submission.attemptedPayload = state.submission.attemptedPayload
            && typeof state.submission.attemptedPayload === "object"
            ? state.submission.attemptedPayload
            : null;
        state.submission.businessLocked = Boolean(
            state.submission.serverId || state.submission.businessLocked || state.submission.attemptedPayload
        );
        if (!isBusinessLocked()) {
            repairRestoredGeolocationTimestamp("visit", savedAt);
            repairRestoredGeolocationTimestamp("store", savedAt);
        }
        state.visit.nearbyStores = Array.isArray(state.visit.nearbyStores)
            ? state.visit.nearbyStores
                .filter((store) => store?.source === "REGISTERED")
                .filter(isUsableNearbyStore)
            : [];
        state.visit.directoryStores = Array.isArray(state.visit.directoryStores)
            ? state.visit.directoryStores
                .filter((store) => store?.source === "REGISTERED")
                .filter(isUsableNearbyStore)
            : [];
        state.visit.directoryQuery = cleanText(state.visit.directoryQuery);
        state.store.nearbyPois = Array.isArray(state.store.nearbyPois)
            ? state.store.nearbyPois
                .filter((store) => store?.source === "REGISTERED")
                .filter(isUsableNearbyStore)
            : [];
        state.store.poiSearchResults = null;
        state.store.poiSearchQuery = cleanText(state.store.poiSearchQuery);
        if (!state.visit.locationContext || typeof state.visit.locationContext !== "object"
                || (!state.visit.location && !locationExceptionReady(state.visit.locationContext))) {
            state.visit.locationContext = null;
        }
        if (!state.store.locationContext || typeof state.store.locationContext !== "object"
                || (!state.store.location && !locationExceptionReady(state.store.locationContext))) {
            state.store.locationContext = null;
        }
        if (!isBusinessLocked()) {
            invalidateExpiredRestoredLocation("visit");
            invalidateExpiredRestoredLocation("store");
        }
        if (!isBusinessLocked() && !locationFlowReady(state.visit.locationContext)) {
            state.visit.selectedStore = null;
            state.visit.nearbyStores = [];
            state.visit.directoryStores = [];
        }
        state.store.sourcePoiToken = cleanText(state.store.sourcePoiToken);
        state.store.sourcePoiId = cleanText(state.store.sourcePoiId);
        state.store.sourcePoiLongitude = finiteNumberOrNull(state.store.sourcePoiLongitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(state.store.sourcePoiLatitude);
        const restoredLookupStatus = cleanText(state.store.poiSearchLookupStatus);
        const restoredUnverifiedLocation = locationExceptionReady(state.store.locationContext);
        if (restoredUnverifiedLocation) {
            state.store.sourceMode = "MANUAL";
            state.store.nearbyPois = [];
            state.store.sourcePoiToken = "";
            state.store.sourcePoiId = "";
            state.store.sourcePoiName = "";
            state.store.sourcePoiAddress = "";
            state.store.sourcePoiLongitude = null;
            state.store.sourcePoiLatitude = null;
            state.store.poiSearchLookupStatus = "UNAVAILABLE";
        }
        const manualAuthorized = restoredUnverifiedLocation
            || restoredLookupStatus === "EMPTY" || restoredLookupStatus === "UNAVAILABLE";
        if (state.store.sourceMode === "POI"
            && (!state.store.sourcePoiId || !state.store.sourcePoiToken)) {
            state.store.sourceMode = "";
            state.store.sourcePoiToken = "";
            state.store.sourcePoiId = "";
            state.store.sourcePoiName = "";
            state.store.sourcePoiAddress = "";
            state.store.sourcePoiLongitude = null;
            state.store.sourcePoiLatitude = null;
        }
        if (state.store.sourceMode === "MANUAL" && !manualAuthorized) {
            state.store.sourceMode = "";
        }
        state.store.manualEntryToken = cleanText(state.store.manualEntryToken);
        state.store.manualEntryAllowed = state.store.sourceMode === "MANUAL"
            && manualAuthorized
            && (restoredUnverifiedLocation
                || Boolean(state.store.manualEntryToken));
        if (!state.store.manualEntryAllowed) {
            state.store.poiSearchLookupStatus = null;
            state.store.manualEntryToken = "";
        }
        if (!state.store.clientStoreId) state.store.clientStoreId = secureUuid();
        if (!state.submission.clientSubmissionId) state.submission.clientSubmissionId = secureUuid();
        if (!state.submission.submissionKey || state.submission.submissionKey.length < 32) {
            state.submission.submissionKey = secureSubmissionKey();
        }
        state.ui = sanitizeRestoredUi(saved.ui);
        if (isBusinessLocked()) {
            state.activeTab = "visit";
            state.ui.visitStep = 3;
        }
        state.restoredAt = saved.savedAt || null;
    }

    function hasRestoredDraft() {
        return Boolean(state.restoredAt);
    }

    function showRestoreNotice() {
        const uploadedAudio = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "UPLOADED").length;
        const pendingAudio = state.submission.audioSegments
            .filter((segment) => !["UPLOADED", "SKIPPED"].includes(segment.uploadState)).length;
        const uploaded = new Set(state.submission.uploadedMedia).size + uploadedAudio;
        const pending = state.submission.mediaUploadAttempts
            .filter((item) => !state.submission.uploadedMedia.includes(item)).length + pendingAudio;
        let message = `已恢复 ${formatDateTime(state.restoredAt)} 保存的表单。`;
        if (isBusinessLocked()) {
            message += uploaded
                ? ` 业务信息已锁定，服务端草稿和 ${uploaded} 个已上传文件会继续复用。刷新前未上传的文件需重新选择。`
                : state.submission.serverId
                    ? " 业务信息已锁定，服务端草稿会继续复用；照片、截图和录音需重新选择后上传。"
                    : " 首次草稿响应未确认，业务信息已锁定；重试会使用完全相同的内容恢复服务端草稿。";
            if (pending) message += ` ${pending} 个文件的上次上传结果未确认，可重新选择后重试。`;
        }
        $("#restore-notice strong").textContent = "已恢复未完成草稿";
        $("#restore-message").textContent = message;
        $("#discard-draft-button").hidden = false;
        $("#restore-notice").hidden = false;
    }

    function renderUploadedBadges() {
        const uploadedPhoto = hasRemoteMediaState(MEDIA.photo);
        const uploadedWechat = hasRemoteMediaState(MEDIA.wechat);
        const audioCount = state.submission.audioSegments.length;
        const uploadedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "UPLOADED").length;
        const skippedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "SKIPPED").length;
        const uncertainSkippedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "SKIPPED" && segment.mayExistRemotely).length;
        const pendingPhoto = state.submission.mediaUploadAttempts.includes(MEDIA.photo) && !uploadedPhoto;
        const pendingWechat = state.submission.mediaUploadAttempts.includes(MEDIA.wechat) && !uploadedWechat;
        $("#photo-uploaded-badge").textContent = uploadedPhoto ? "草稿已上传" : "可重新选择并重试";
        $("#wechat-uploaded-badge").textContent = uploadedWechat ? "草稿已上传" : "可重新选择并重试";
        $("#audio-uploaded-badge").textContent = skippedAudioCount
            ? uncertainSkippedAudioCount
                ? uploadedAudioCount
                    ? `已上传 ${uploadedAudioCount} 段 · ${uncertainSkippedAudioCount} 段结果待确认`
                    : `${uncertainSkippedAudioCount} 段上传结果待确认`
                : uploadedAudioCount
                    ? `已上传 ${uploadedAudioCount} 段 · 跳过 ${skippedAudioCount} 段`
                    : `已跳过 ${skippedAudioCount} 段`
            : uploadedAudioCount === audioCount
            ? `已上传 ${audioCount} 段`
            : uploadedAudioCount === 0
                ? `已添加 ${audioCount} 段`
                : `已上传 ${uploadedAudioCount}/${audioCount} 段`;
        $("#photo-uploaded-badge").hidden = !uploadedPhoto && !pendingPhoto;
        $("#wechat-uploaded-badge").hidden = !uploadedWechat && !pendingWechat;
        $("#audio-uploaded-badge").hidden = audioCount === 0;
        $("#delete-uploaded-photo-button").hidden = !mayHaveRemoteMediaState(MEDIA.photo)
            || Boolean(state.files.photo);
        $("#delete-uploaded-wechat-button").hidden = !mayHaveRemoteMediaState(MEDIA.wechat)
            || Boolean(state.files.wechat);
        checkRecorderSupport();
        renderRecordingStatus();
    }

    function hasRemoteMediaState(mediaKind) {
        if (mediaKind === MEDIA.audio) {
            return state.submission.audioSegments.some((segment) => segment.uploadState === "UPLOADED")
                || state.submission.uploadedMedia.includes(mediaKind);
        }
        return state.submission.uploadedMedia.includes(mediaKind);
    }

    function mayHaveRemoteMediaState(mediaKind) {
        if (mediaKind === MEDIA.audio) {
            return state.submission.audioSegments.some((segment) => segment.mayExistRemotely)
                || state.submission.uploadedMedia.includes(mediaKind)
                || state.submission.mediaUploadAttempts.includes(mediaKind);
        }
        return hasRemoteMediaState(mediaKind)
            || state.submission.mediaUploadAttempts.includes(mediaKind);
    }

    function createRequestController() {
        if (typeof window.AbortController === "function") return new window.AbortController();
        // 旧版 QQ/X5 可能有 fetch 却没有 AbortController；占位对象仍可用于丢弃过期响应。
        return { signal: undefined, abort() {} };
    }

    function invalidateExpiredRestoredLocation(scope) {
        const location = state[scope].location;
        const context = state[scope].locationContext;
        if (locationExceptionReady(context)) return;
        const capturedAt = Date.parse(location?.capturedAt || "");
        const maxAgeMinutes = Number(context?.maxLocationAgeMinutes);
        if (!location || !context || !Number.isFinite(capturedAt) || !Number.isFinite(maxAgeMinutes)) return;
        if (Date.now() - capturedAt <= maxAgeMinutes * 60 * 1000) return;
        const message = "定位已过期，请重新获取当前位置。";
        state[scope].locationContext = {
            ...context,
            freshnessAccepted: false,
            locationMessage: message,
            errorMessage: message
        };
    }

    function removeStoredDraft() {
        try {
            sessionStorage.removeItem(STORAGE_KEY);
            localStorage.removeItem(STORAGE_KEY);
        } catch (_) {
            // 页面仍可继续使用；存储不可用已在保存阶段提示。
        }
    }

    function emitClientDiagnostic(event, result, details = {}, clientEventId = secureUuid()) {
        const salespersonId = state.identity?.salespersonId
            || state.visit.salespersonId || state.store.salespersonId;
        if (!isUuidValue(salespersonId) || !isUuidValue(clientEventId)) return clientEventId;
        const payload = compactObject({
            salespersonId,
            clientEventId,
            event,
            result,
            itemCount: Number.isInteger(details.itemCount) ? details.itemCount : undefined,
            fileSizeBytes: Number.isFinite(details.fileSizeBytes)
                ? Math.max(0, Math.round(details.fileSizeBytes)) : undefined
        });
        const body = JSON.stringify(payload);
        const endpoint = `${API_BASE}/diagnostics/events`;
        try {
            if (typeof navigator.sendBeacon === "function") {
                const blob = new Blob([body], { type: "application/json" });
                if (navigator.sendBeacon(endpoint, blob)) return clientEventId;
            }
        } catch (_) {
            // 部分 X5/XWeb 不允许 Blob beacon，继续使用 keepalive fetch。
        }
        if (typeof window.fetch === "function") {
            window.fetch(endpoint, {
                method: "POST",
                headers: { "Content-Type": "application/json", "Accept": "application/json" },
                body,
                credentials: "same-origin",
                cache: "no-store",
                keepalive: true
            }).catch(() => {});
        }
        return clientEventId;
    }

    async function requestJson(path, options = {}) {
        if (typeof window.fetch !== "function" || typeof window.Headers !== "function") {
            return requestJsonWithXhr(path, options);
        }
        const controller = typeof window.AbortController === "function" ? new window.AbortController() : null;
        const externalSignal = options.signal;
        const timeout = options.timeout || 30000;
        let timedOut = false;
        let rejectTimeout;
        const timeoutPromise = new Promise((_, reject) => {
            rejectTimeout = reject;
        });
        const timeoutId = window.setTimeout(() => {
            timedOut = true;
            if (controller) controller.abort();
            else rejectTimeout(new Error("请求超时，请检查网络后重试。"));
        }, timeout);
        const abortFromExternal = () => {
            if (controller) controller.abort();
        };
        if (controller && externalSignal) {
            externalSignal.addEventListener("abort", abortFromExternal, { once: true });
        }

        const headers = new Headers(options.headers || {});
        let body = options.body;
        if (body && !(body instanceof FormData) && typeof body !== "string") {
            headers.set("Content-Type", "application/json");
            body = JSON.stringify(body);
        }
        headers.set("Accept", "application/json");

        try {
            const request = fetch(`${API_BASE}${path}`, {
                method: options.method || "GET",
                headers,
                body,
                credentials: "same-origin",
                cache: "no-store",
                signal: controller ? controller.signal : undefined
            });
            const response = await Promise.race([request, timeoutPromise]);
            const text = await response.text();
            let payload = null;
            if (text) {
                try {
                    payload = JSON.parse(text);
                } catch (_) {
                    payload = { message: text.slice(0, 300) };
                }
            }
            if (!response.ok) {
                const error = new Error(extractApiMessage(payload) || `请求失败（HTTP ${response.status}）`);
                error.status = response.status;
                error.payload = payload;
                throw error;
            }
            return payload;
        } catch (error) {
            if (error.name === "AbortError" && timedOut) {
                throw new Error("请求超时，请检查网络后重试。" );
            }
            throw error;
        } finally {
            window.clearTimeout(timeoutId);
            if (controller && externalSignal) {
                externalSignal.removeEventListener("abort", abortFromExternal);
            }
        }
    }

    function requestJsonWithXhr(path, options) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const method = options.method || "GET";
            let body = options.body;
            xhr.open(method, `${API_BASE}${path}`, true);
            xhr.withCredentials = true;
            xhr.timeout = options.timeout || 30000;
            xhr.setRequestHeader("Accept", "application/json");
            Object.entries(options.headers || {}).forEach(([name, value]) => {
                xhr.setRequestHeader(name, value);
            });
            if (body && !(body instanceof FormData) && typeof body !== "string") {
                xhr.setRequestHeader("Content-Type", "application/json");
                body = JSON.stringify(body);
            }
            xhr.addEventListener("load", () => {
                const payload = parseResponsePayload(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(payload);
                    return;
                }
                const error = new Error(extractApiMessage(payload) || `请求失败（HTTP ${xhr.status}）`);
                error.status = xhr.status;
                error.payload = payload;
                reject(error);
            });
            xhr.addEventListener("timeout", () => reject(new Error("请求超时，请检查网络后重试。")));
            xhr.addEventListener("error", () => reject(new Error("网络连接失败，请检查网络后重试。")));
            xhr.addEventListener("abort", () => reject(new Error("请求已中断，请重试。")));
            if (options.signal) {
                options.signal.addEventListener("abort", () => xhr.abort(), { once: true });
            }
            xhr.send(body || null);
        });
    }

    function normalizeResponse(payload) {
        if (payload && typeof payload === "object" && Object.prototype.hasOwnProperty.call(payload, "data")) {
            return payload.data;
        }
        return payload;
    }

    function extractApiMessage(payload) {
        if (!payload) return "";
        if (typeof payload === "string") return payload;
        if (typeof payload.message === "string" && payload.message.trim()) return payload.message.trim();
        if (typeof payload.error?.message === "string") return payload.error.message;
        if (Array.isArray(payload.details) && payload.details[0]?.message) return payload.details[0].message;
        return "";
    }

    function showError(message) {
        $("#global-error-message").textContent = message;
        $("#global-error").hidden = false;
        $("#global-error").scrollIntoView({ behavior: "smooth", block: "center" });
    }

    function hideError() {
        $("#global-error").hidden = true;
        $("#global-error-message").textContent = "";
    }

    function errorMessage(error, fallback) {
        if (error && typeof error.message === "string" && error.message.trim()) return error.message.trim();
        return fallback;
    }

    function secureUuid() {
        if (crypto.randomUUID) return crypto.randomUUID();
        if (!crypto.getRandomValues) throw new Error("浏览器不支持安全随机数。" );
        const bytes = new Uint8Array(16);
        crypto.getRandomValues(bytes);
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function isUuidValue(value) {
        return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
            .test(cleanText(value));
    }

    function secureSubmissionKey() {
        if (!crypto.getRandomValues) throw new Error("浏览器不支持安全随机数。" );
        const bytes = new Uint8Array(32);
        crypto.getRandomValues(bytes);
        const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
        return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
    }

    function compactObject(value) {
        return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined && item !== null));
    }

    function optionalText(value) {
        const normalized = typeof value === "string" ? value.trim() : value;
        return normalized ? normalized : undefined;
    }

    function cleanText(value) {
        return value == null ? "" : String(value).trim();
    }

    function finiteNumberOrNull(value) {
        if (value === null || value === undefined || value === "") return null;
        const number = Number(value);
        return Number.isFinite(number) ? number : null;
    }

    function roundCoordinate(value) {
        return Number(Number(value).toFixed(7));
    }

    function roundAccuracy(value) {
        return Number(Number(value).toFixed(2));
    }

    function numericGeolocationTimestamp(value) {
        if (typeof value === "number") return Number.isFinite(value) && value !== 0 ? value : null;
        if (typeof value !== "string" || !value.trim()) return null;
        const numeric = Number(value.trim());
        return Number.isFinite(numeric) && numeric !== 0 ? numeric : null;
    }

    function uniqueTimestampCandidates(candidates) {
        const seen = new Set();
        return candidates.filter((candidate) => {
            if (!Number.isFinite(candidate.value)) return false;
            const key = Math.round(candidate.value * 1000) / 1000;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });
    }

    function epochTimestampCandidates(value) {
        const numeric = numericGeolocationTimestamp(value);
        if (numeric !== null) {
            return uniqueTimestampCandidates([
                { value: numeric, source: "EPOCH_MILLISECONDS", clockScale: 1 },
                { value: numeric * 1000, source: "EPOCH_SECONDS", clockScale: 1000 },
                { value: numeric / 1000, source: "EPOCH_MICROSECONDS", clockScale: 0.001 },
                { value: numeric / 1000000, source: "EPOCH_NANOSECONDS", clockScale: 0.000001 },
                { value: numeric + APPLE_REFERENCE_EPOCH_OFFSET_MS,
                    source: "APPLE_MILLISECONDS", clockScale: 1 },
                { value: numeric * 1000 + APPLE_REFERENCE_EPOCH_OFFSET_MS,
                    source: "APPLE_SECONDS", clockScale: 1000 },
                { value: numeric / 1000 + APPLE_REFERENCE_EPOCH_OFFSET_MS,
                    source: "APPLE_MICROSECONDS", clockScale: 0.001 },
                { value: numeric / 1000000 + APPLE_REFERENCE_EPOCH_OFFSET_MS,
                    source: "APPLE_NANOSECONDS", clockScale: 0.000001 }
            ]);
        }
        const parsed = typeof value === "string" ? Date.parse(value) : NaN;
        return Number.isFinite(parsed)
            ? [{ value: parsed, source: "DATE_STRING", clockScale: 1 }] : [];
    }

    function monotonicTimestampCandidates(value) {
        const numeric = numericGeolocationTimestamp(value);
        if (numeric === null) return [];
        return uniqueTimestampCandidates([
            { value: numeric, source: "MONOTONIC_MILLISECONDS", clockScale: 1 },
            { value: numeric * 1000, source: "MONOTONIC_SECONDS", clockScale: 1000 },
            { value: numeric / 1000, source: "MONOTONIC_MICROSECONDS", clockScale: 0.001 },
            { value: numeric / 1000000, source: "MONOTONIC_NANOSECONDS", clockScale: 0.000001 }
        ]);
    }

    function plausibleMonotonicClockScales(value) {
        return monotonicTimestampCandidates(value)
            .filter((candidate) => candidate.value >= 0
                && candidate.value <= GEOLOCATION_MAX_MONOTONIC_UPTIME_MS)
            .map((candidate) => candidate.clockScale);
    }

    function assessGeolocationTimestamp(value, referenceMs, pastWindowMs) {
        if (!Number.isFinite(referenceMs) || !Number.isFinite(pastWindowMs) || pastWindowMs < 0) {
            return { capturedAtMs: null, kind: "UNSUPPORTED" };
        }
        const minimum = referenceMs - pastWindowMs;
        const maximum = referenceMs + LOCATION_CAPTURE_FUTURE_SKEW_MS;
        const epochCandidates = epochTimestampCandidates(value);
        const freshEpoch = epochCandidates
            .filter((candidate) => candidate.value >= minimum && candidate.value <= maximum)
            .sort((left, right) => Math.abs(referenceMs - left.value)
                - Math.abs(referenceMs - right.value));
        if (freshEpoch.length) {
            return {
                capturedAtMs: freshEpoch[0].value,
                kind: freshEpoch[0].source === "EPOCH_MILLISECONDS" ? "FRESH" : "NORMALIZED"
            };
        }

        const plausibleEpochMinimum = Date.UTC(2000, 0, 1);
        const hasPlausibleEpoch = epochCandidates.some((candidate) =>
            candidate.value >= plausibleEpochMinimum
                && candidate.value <= referenceMs + 10 * 365.25 * 24 * 60 * 60 * 1000);
        return {
            capturedAtMs: null,
            kind: hasPlausibleEpoch ? "STALE" : "UNSUPPORTED"
        };
    }

    function resolveGeolocationCapturedAtMs(value, referenceMs, pastWindowMs) {
        return assessGeolocationTimestamp(value, referenceMs, pastWindowMs).capturedAtMs;
    }

    function resolveAdvancingGeolocationClockCapturedAtMs(previous, current) {
        const receiptElapsedMs = Number(current?.receivedAtMs) - Number(previous?.receivedAtMs);
        if (!Number.isFinite(receiptElapsedMs)
                || receiptElapsedMs < GEOLOCATION_CLOCK_PROGRESS_MIN_MS
                || receiptElapsedMs > GEOLOCATION_REFRESH_TIMEOUT_MS + GEOLOCATION_ATTEMPT_TIMEOUT_MS) {
            return null;
        }
        const previousRaw = numericGeolocationTimestamp(previous?.value)
            ?? (typeof previous?.value === "string" ? Date.parse(previous.value) : NaN);
        const currentRaw = numericGeolocationTimestamp(current?.value)
            ?? (typeof current?.value === "string" ? Date.parse(current.value) : NaN);
        if (!Number.isFinite(previousRaw) || !Number.isFinite(currentRaw)) return null;
        const currentScales = new Set(plausibleMonotonicClockScales(current?.value));
        const clockScales = plausibleMonotonicClockScales(previous?.value)
            .filter((clockScale) => currentScales.has(clockScale));
        const minimumSourceProgressMs = Math.max(
            GEOLOCATION_CLOCK_PROGRESS_MIN_MS, receiptElapsedMs * 0.5);
        const maximumSourceProgressMs = Math.max(1000, receiptElapsedMs * 2);
        const clockAdvanced = clockScales.some((clockScale) => {
            const sourceElapsedMs = (currentRaw - previousRaw) * clockScale;
            return sourceElapsedMs >= minimumSourceProgressMs
                && sourceElapsedMs <= maximumSourceProgressMs;
        });
        return clockAdvanced && Number.isFinite(Number(current.receivedAtMs))
            ? Number(current.receivedAtMs) : null;
    }

    function resolveCompatibleGeolocationCapturedAtMs({
        capturedAtMs,
        compatibleAttempted,
        visibilityState,
        receivedAtMs,
        captureDeadlineMs
    }) {
        if (capturedAtMs !== null) {
            return Number.isFinite(Number(capturedAtMs)) ? Number(capturedAtMs) : null;
        }
        // 部分手机 WebView 会返回持续不变的旧时间戳；仅在已进入兼容定位且页面仍在前台时，
        // 使用当前回调的接收时间。精度、位置凭证和300米门店范围仍由服务端继续校验。
        if (!compatibleAttempted || visibilityState === "hidden") {
            return null;
        }
        if (!Number.isFinite(Number(receivedAtMs))
                || !Number.isFinite(Number(captureDeadlineMs))
                || Number(receivedAtMs) >= Number(captureDeadlineMs)) {
            return null;
        }
        return Number(receivedAtMs);
    }

    function repairRestoredGeolocationTimestamp(scope, savedAtMs) {
        const location = state[scope].location;
        if (locationExceptionReady(state[scope].locationContext)) return;
        if (!location) return;
        const raw = Date.parse(location.capturedAt || "");
        const repaired = location.capturedAt
            ? resolveGeolocationCapturedAtMs(location.capturedAt, savedAtMs, DRAFT_TTL_MS)
            : null;
        if (repaired === null) {
            state[scope].location = null;
            state[scope].locationContext = null;
        } else if (!Number.isFinite(raw) || repaired !== raw) {
            location.capturedAt = new Date(repaired).toISOString();
            state[scope].locationContext = null;
        } else {
            return;
        }
        if (scope === "visit") {
            state.visit.nearbyStores = [];
        } else {
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchLookupStatus = null;
            state.store.poiSearchQuery = "";
            state.store.manualEntryAllowed = false;
            state.store.manualEntryToken = "";
        }
    }

    function stopGeolocationRefresh(scope) {
        const watchId = state.geolocationWatchIds[scope];
        if (watchId !== null && typeof navigator.geolocation?.clearWatch === "function") {
            navigator.geolocation.clearWatch(watchId);
        }
        state.geolocationWatchIds[scope] = null;
        const timeoutId = state.geolocationTimeoutIds[scope];
        if (timeoutId !== null) window.clearTimeout(timeoutId);
        state.geolocationTimeoutIds[scope] = null;
        const continueId = state.geolocationContinueIds[scope];
        if (continueId !== null) window.clearTimeout(continueId);
        state.geolocationContinueIds[scope] = null;
        const lifecycleCleanup = state.geolocationLifecycleCleanups[scope];
        if (typeof lifecycleCleanup === "function") lifecycleCleanup();
        state.geolocationLifecycleCleanups[scope] = null;
    }

    function cancelLocationCapture(scope) {
        state.locationCaptureSequence[scope] += 1;
        stopGeolocationRefresh(scope);
        if (state[scope].locationContext?.geocodeStatus === "CAPTURING") {
            state[scope].locationContext = null;
        }
        const button = $(`#${scope}-location-button`);
        if (button) {
            button.disabled = false;
            renderLocation(scope);
        }
    }

    function formatBytes(bytes) {
        if (!Number.isFinite(bytes)) return "";
        if (bytes < 1024) return `${bytes} B`;
        if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
        return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }

    function formatDuration(milliseconds) {
        const totalSeconds = Math.max(0, Math.floor((milliseconds || 0) / 1000));
        const minutes = Math.floor(totalSeconds / 60).toString().padStart(2, "0");
        const seconds = (totalSeconds % 60).toString().padStart(2, "0");
        return `${minutes}:${seconds}`;
    }

    function formatDateTime(value) {
        if (!value) return "";
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat("zh-CN", {
            year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", second: "2-digit",
            hour12: false
        }).format(date);
    }

    function formatFilenameTime(date) {
        const pad = (value) => String(value).padStart(2, "0");
        return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
    }

    function audioExtension(mimeType) {
        const normalized = cleanText(mimeType).toLowerCase();
        if (normalized.includes("mp4") || normalized.includes("m4a")) return "m4a";
        if (normalized.includes("mpeg") || normalized.includes("mp3")) return "mp3";
        if (normalized.includes("wav")) return "wav";
        if (normalized.includes("ogg")) return "ogg";
        if (normalized.includes("opus")) return "opus";
        if (normalized.includes("aac")) return "aac";
        if (normalized.includes("amr")) return "amr";
        if (normalized.includes("3gpp2")) return "3g2";
        if (normalized.includes("3gpp")) return "3gp";
        if (normalized.includes("flac")) return "flac";
        if (normalized.includes("caf")) return "caf";
        if (normalized.includes("aiff") || normalized.includes("aifc")) return "aiff";
        if (normalized.includes("silk")) return "silk";
        return "webm";
    }

    function microphoneErrorMessage(error) {
        if (error?.code === "MICROPHONE_PERMISSION_TIMEOUT") {
            return "麦克风授权等待超过12秒，已取消本次录音等待；录音为选填，可直接提交打卡。";
        }
        if (error?.name === "NotAllowedError" || error?.name === "PermissionDeniedError") {
            return "麦克风权限被拒绝，请在浏览器设置中允许后重试，或选择已有音频文件。";
        }
        if (error?.name === "NotFoundError") return "未检测到可用麦克风，请选择已有音频文件。";
        return "无法开始录音，请检查麦克风权限或选择已有音频文件。";
    }

    function updateVisitResultCount() {
        $("#visit-result-count").textContent = String($("#visit-result").value.length);
    }
})();
