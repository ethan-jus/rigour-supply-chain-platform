(function () {
    "use strict";

    const API_BASE = "/sales-checkin/api/v1";
    const STORAGE_KEY = "rigour.sales-checkin.draft.v1";
    const STORAGE_VERSION = 1;
    const DRAFT_TTL_MS = 8 * 60 * 60 * 1000;
    const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    const DEFAULT_MAX_AUDIO_BYTES = 256 * 1024 * 1024;
    const LOCATION_CAPTURE_FUTURE_SKEW_MS = 2 * 60 * 1000;
    const GEOLOCATION_FRESH_MAX_AGE_MS = 2 * 60 * 1000;
    const GEOLOCATION_REFRESH_TIMEOUT_MS = 8000;
    const locationCaptureWaiters = { visit: null, store: null };
    const MICROPHONE_PERMISSION_TIMEOUT_MS = 12 * 1000;
    const OPTIONAL_MEDIA_UPLOAD_MAX_MS = 20 * 60 * 1000;
    const OPTIONAL_MEDIA_IDLE_TIMEOUT_MS = 90 * 1000;
    const PRIVACY_NOTICE_VERSION = "2026-08-25-identity-v2";
    const HEADQUARTERS_CITY = "总部";
    const FLOW_STEPS = Object.freeze({ visit: 3, store: 3 });
    const MOBILE_INPUT_SETTLE_MS = 320;
    const MOBILE_INPUT_FOCUS_GRACE_MS = 650;
    const MOBILE_KEYBOARD_MIN_DELTA = 120;
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
        "#visit-city", "#visit-salesperson", "#visit-location-button",
        "#store-search", "#store-search-toggle", "#clear-store-button",
        "#create-store-link", "#customer-name", "#customer-phone", "#visit-result",
        "#store-tab"
    ]);

    let storePickerOpen = false;
    let storePickerScrollY = 0;
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
        photos: [],
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
            maxAudioBytes: DEFAULT_MAX_AUDIO_BYTES,
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
            photos: [],
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
            stopFallbackTimer: null,
            recoveries: [],
            recoveryOwner: null,
            recoveryLoading: false
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
        submitting: false,
        completed: false,
        storageOwner: null,
        persistence: Promise.resolve(true),
        pendingMedia: new Set(),
        unsavedMedia: new Set(),
        resumeTimer: null,
        recordsBusy: false,
        recordsPendingOnly: false,
        evidenceSyncIds: new Set(),
        lastSavedFingerprint: "",
        pickerUnsavedFingerprint: ""
    };
    let historyView = null;
    let historyScreen = "closed";
    let initialized = false;
    let recoveryInFlight = null;
    let draftRestoreSequence = 0;
    let photoSelectionSequence = 0;
    let photoPreviewQueue = null;

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
                    && !context && state[scope].location?.capturedAt) {
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
        initPersonalHistory();
        initialized = true;
        // pageshow 可能早于身份和 IndexedDB 恢复。初始化完成后主动核对上次提交，
        // 不依赖移动系统一定会发出 unload/visibilitychange。
        await recoverInterruptedSubmission();

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
        // 恢复后保留门店、字段和原始定位证据；新的前台采集不得抹掉已经录入的内容。
        if (!hasRestoredDraft() || isBusinessLocked()) return;
        invalidateExpiredRestoredLocation("visit");
    }

    function scheduleInitialVisitLocationCapture() {
        if (state.activeTab !== "visit" || !state.identity?.authenticated
                || isBusinessLocked() || state.completed) return;
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
            scheduleInitialVisitLocationCapture();
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
        const restoredMismatch = salespersonMismatch;
        if (restoredMismatch) {
            const hadServerDraft = Boolean(state.submission.serverId
                || state.submission.attemptedPayload
                || state.submission.uploadedMedia.length
                || state.submission.mediaUploadAttempts.length
                || state.submission.audioSegments.length);
            state.identity = identity;
            startNewSubmission({ preserveCurrent: false });
            showIdentityDraftResetNotice(hadServerDraft);
        }
        state.identity = identity;
        state.visit.city = identityAllowsWorkCity(identity, state.visit.city) && state.visit.city
            ? state.visit.city : identity.city;
        state.store.city = identityAllowsWorkCity(identity, state.store.city) && state.store.city
            ? state.store.city : identity.city;
        state.visit.salespersonId = String(identity.salespersonId);
        state.store.salespersonId = String(identity.salespersonId);
        await ensureSalespersons(identity.city);
        populateCitySelects();
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
        renderRestoredValues();
        renderStoreOwnerSummary();
        lockIdentitySelectors();
        await restoreOwnedDraft();
        renderNearbyStores();
        renderSelectedStore();
        if (workCityMismatch && !salespersonMismatch && isBusinessLocked()) {
            showError("业务归属已变化，原提交记录仍保留；请先确认原提交结果。");
        }
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
        return Boolean(city && state.options.cities.includes(city));
    }

    function identityAllowsWorkCity(identity, city) {
        if (!city) return true;
        return state.options.cities.includes(city) || city === identity?.city;
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
        if ($("#my-records-button")) $("#my-records-button").hidden = !state.identity?.authenticated;
        lockIdentitySelectors();
        renderFlowSteps();
    }

    function lockIdentitySelectors() {
        if (!state.identity?.authenticated) return;
        const lockWorkCity = state.submitting || isBusinessLocked();
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
        if (state.evidenceSyncIds.size) { showError("证据正在同步，请稍后切换身份"); return; }
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
            const owner = currentStorageOwner();
            await state.persistence;
            await requestJson("/identity/logout", { method: "POST" });
            if (owner && window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.clearOwner(owner);
            state.storageOwner = null;
            historyView?.resetIdentity();
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
                showRecordingNavigationError("请先点击录音区的停止按钮保存录音");
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
        $("#new-submission-button").addEventListener("click", () => { if (!state.submitting) startNewSubmission(); });

        $("#visit-city").addEventListener("change", () => handleCityChange("visit"));
        $("#store-city").addEventListener("change", () => handleCityChange("store"));
        $("#visit-salesperson").addEventListener("change", persistFromForm);
        $("#store-salesperson").addEventListener("change", persistFromForm);

        $("#store-search").addEventListener("focus", openStorePicker);
        $("#store-search").addEventListener("input", handleVisitStoreSearchInput);
        $("#store-picker-close").addEventListener("click", () => closeStorePicker(true));
        $("#nearby-stores-panel").addEventListener("keydown", handleStorePickerKeydown);
        window.visualViewport?.addEventListener("resize", sizeStorePicker);
        window.visualViewport?.addEventListener("scroll", sizeStorePicker);
        window.addEventListener("resize", sizeStorePicker);
        $("#store-search").addEventListener("keydown", handleStoreSearchKeydown);
        $("#store-search-toggle").addEventListener("click", toggleVisitStoreOptions);
        $("#clear-store-button").addEventListener("click", () => {
            if (!$("#store-search").disabled) $("#store-search").focus();
        });
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
        $("#store-location-continue").addEventListener("click", () => continueWithoutVerifiedLocation("store"));
        $("#store-location-retry").addEventListener("click", () => resolveLocationContext("store"));

        $("#storefront-photo").addEventListener("change", (event) => handleImageSelection("photo", event));
        $("#wechat-screenshot").addEventListener("change", (event) => handleImageSelection("wechat", event));
        $("#storefront-photo").addEventListener("click", preparePhotoPicker);
        $("#storefront-photo").addEventListener("cancel", resumeActiveVisit);
        $("#photo-camera-button")?.addEventListener("click", () => $("#storefront-photo").click());
        $("#photo-album-button")?.addEventListener("click", () => $("#photo-album-input").click());
        $("#photo-album-input")?.addEventListener("click", preparePhotoPicker);
        $("#photo-album-input")?.addEventListener("cancel", resumeActiveVisit);
        $("#photo-album-input")?.addEventListener("change", (event) => handleImageSelection("photo", event));
        $("#my-records-button")?.addEventListener("click", () => showMyRecords(false));
        $("#pending-records-button")?.addEventListener("click", () => showMyRecords(true));
        $("#nav-records-button")?.addEventListener("click", () => showMyRecords(false));
        $("#nav-visit-button")?.addEventListener("click", () => historyView?.close());
        $("#app-back-button")?.addEventListener("click", () => {
            if (historyScreen === "detail") { historyView?.backToList(); return; }
            if (historyScreen !== "closed") { historyView?.close(); return; }
            if (state.submitting) return;
            if (state.completed) { void showMyRecords(false); return; }
            if (state.activeTab === "store" && state.ui.storeStep === 1) switchTab("visit");
            else goToFlowStep(state.activeTab, state.ui[flowStateKey(state.activeTab)] - 1);
        });
        $("#success-view-record-button")?.addEventListener("click", () => {
            if (state.submission.serverId) void historyView?.showDetail(state.submission.serverId);
        });
        $("#success-retry-button")?.addEventListener("click", () => {
            if (state.submission.photos.some(photo => [413, 415].includes(photo.uploadErrorStatus) || photo.uploadState === "NEEDS_FILE")
                    || state.submission.wechatUploadErrorStatus === 413 || state.submission.audioSegments.some(segment =>
                    ["TOO_LARGE", "NEEDS_FILE"].includes(segment.uploadState))) openEvidenceEditor();
            else void supplementCurrentEvidence();
        });
        $("#photo-grid")?.addEventListener("click", (event) => {
            const card = event.target.closest("[data-photo-item]");
            if (!card) return;
            if (event.target.closest("[data-photo-remove]")) void removePhoto(card.dataset.photoId);
            else if (event.target.closest("[data-photo-open]")) openPhotoPreview(card.dataset.photoId);
        });
        $("#local-photo-close")?.addEventListener("click", () => $("#local-photo-dialog").close());
        $("#local-photo-dialog")?.addEventListener("close", () => $("#local-photo-full").removeAttribute("src"));
        $("#remove-wechat-button").addEventListener("click", async () => clearFile("wechat"));
        $("#delete-uploaded-wechat-button").addEventListener("click", async () => clearFile("wechat"));

        $("#record-audio-button").addEventListener("click", toggleRecording);
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
            if (!event.target.closest(".poi-search-field")) hidePoiResults();
        });
        document.addEventListener("focusin", handleMobileFocusIn);
        document.addEventListener("focusout", scheduleMobileInputStateSync);
        window.addEventListener("resize", handleMobileViewportResize);
        window.visualViewport?.addEventListener("resize", handleMobileViewportResize);

        window.addEventListener("pageshow", () => { resumeRecordingLifecycle(); resumeActiveVisit(); });
        window.addEventListener("online", resumeActiveVisit);
        window.addEventListener("pagehide", () => {
            checkpointRecording("PAGE_HIDDEN");
            if (currentStorageOwner()) { syncStateFromForm(); void persistDraft(); }
            pauseAllAudioPreviews();
        });
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "hidden") {
                if (!state.completed) { syncStateFromForm(); void persistDraft(); }
                checkpointRecording("BACKGROUND");
                cancelLocationCapture("visit"); cancelLocationCapture("store");
            } else { resumeRecordingLifecycle(); resumeActiveVisit(); }
        });
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
        syncAppScreen();
    }

    function syncAppScreen() {
        const screen = !state.identity?.authenticated ? "identity"
            : historyScreen === "detail" ? "history-detail" : historyScreen === "list" ? "history"
                : state.completed && !state.editingEvidence ? "result" : state.activeTab === "store" ? "store"
                    : state.ui.visitStep === 1 ? "visit-home" : state.ui.visitStep === 2 ? "visit-form" : "visit-photo";
        if (screen !== "visit-home") closeStorePicker();
        document.body.dataset.screen = screen;
        const titles = { identity: "拜访打卡", "visit-home": "拜访打卡", "visit-form": "记录拜访",
            "visit-photo": "拍照提交", store: "新增门店", result: "提交结果", history: "我的打卡记录", "history-detail": "打卡明细" };
        $("#hero-title").textContent = titles[screen];
        $("#hero-description").textContent = "销售拜访记录";
        document.title = titles[screen];
        $("meta[name=\"theme-color\"]")?.setAttribute("content", "#ffffff");
        const navigation = $("#app-bottom-nav");
        if (navigation) navigation.hidden = !["visit-home", "history"].includes(screen);
        const back = $("#app-back-button");
        if (back) back.hidden = ["identity", "visit-home", "history"].includes(screen);
        $("#nav-visit-button")?.classList.toggle("is-active", screen === "visit-home");
        $("#nav-records-button")?.classList.toggle("is-active", screen === "history");
        const historyOpen = historyScreen !== "closed";
        const result = screen === "result";
        $("#visit-panel").hidden = historyOpen || result || state.activeTab === "store";
        $("#store-panel").hidden = historyOpen || result || state.activeTab !== "store";
        $("#success-panel").hidden = !result;
        $("#nav-visit-button")?.setAttribute("aria-current", screen === "visit-home" ? "page" : "false");
        $("#nav-records-button")?.setAttribute("aria-current", historyOpen ? "page" : "false");
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
            return true;
        }
        return false;
    }

    function visitSelectedStoreReady() {
        // 目录由服务端按身份过滤；设备距离只是复核证据，不决定能否拜访。
        return Boolean(state.visit.selectedStore?.id);
    }

    function isVisitStepReady(step) {
        if (step === 1) {
            return Boolean(state.visit.city
                && state.visit.salespersonId
                && visitSelectedStoreReady());
        }
        if (step === 2) {
            return Boolean(cleanText(state.visit.customerName) && cleanText(state.visit.visitResult));
        }
        return state.submission.photos.some(photo => photo.uploadState === "UPLOADED"
            || (photoFile(photo.photoId) && ![413, 415].includes(photo.uploadErrorStatus)));
    }

    function isStoreStepReady(step) {
        if (step === 1) {
            return Boolean(state.store.city
                && state.store.salespersonId
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
        renderVisitReview();
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
        recordingWorkspace.hidden = state.activeTab !== "visit" || visitStep === 1
            || (visitStep === 3 && !state.completed && !state.submission.audioSegments.length && !recordingBusy());
        recordingWorkspace.classList.toggle("is-review-mode", visitStep === 3);
        recordingWorkspace.classList.toggle("is-locked-recovery", visitStep === 3 && isBusinessLocked());
        renderRecordingDisclosure();
        $("#recording-workspace-copy").textContent = visitStep === 3
            ? isBusinessLocked()
                ? "草稿已保留；可重试或跳过失败录音，继续提交"
                : "在此回放确认；需要补录时请先返回第2步"
            : "录音时可继续填写";
        if (!recordingBusy()) {
            setStableText($("#record-button-label"), visitStep === 3 && !isBusinessLocked()
                ? "返回第2步补录" : "开始录音");
        }
        renderFlowHeader();
        renderFlowActions();
    }

    function setStableText(element, value) {
        if (element.textContent !== value) element.textContent = value;
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
        visitNextTwo.disabled = state.submitting || recorderBusy || !isVisitStepReady(2);
        // Replacing an unchanged text node during input blur cancels the pending
        // native tap in WebKit. Keep action labels stable until their value changes.
        setStableText(visitNextTwo, "下一步 · 拍照提交");
        ["#visit-step-2-back", "#visit-step-2-edit-store", "#visit-step-3-back"].forEach((selector) => {
            const button = $(selector);
            if (button) button.disabled = state.submitting || recorderBusy || isBusinessLocked();
        });
        ["#storefront-photo", "#wechat-screenshot", "#audio-file"].forEach((selector) => {
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
        const required = (value, key, message) => { valid = requireValue(value, key, message) && valid; };
        if (flow === "visit" && step === 1) {
            required(state.visit.city, "visit-city", "请选择业务归属城市。");
            required(state.visit.salespersonId, "visit-salesperson", "请选择销售。");
            required(state.visit.selectedStore?.id, "selected-store", "请选择本次拜访门店。");
        } else if (flow === "visit" && step === 2) {
            required(cleanText(state.visit.customerName), "customer-name", "请输入客户姓名。");
            required(cleanText(state.visit.visitResult), "visit-result", "请填写拜访结果。");
        } else if (flow === "store" && step === 1) {
            required(state.store.city, "store-city", "请选择业务归属城市。");
            required(state.store.salespersonId, "store-salesperson", "请选择销售。");
            required(hasValidStoreSource() ? "selected" : "", "store-source", "请选择地图门店或手动录入。");
        } else if (flow === "store" && step === 2) {
            required(cleanText(state.store.name), "store-name", "请输入门店名称。");
            required(state.store.attribute, "store-attribute", "请选择门店属性。");
            required(state.store.operatingStatus, "operating-status", "请选择营业状态。");
            required(cleanText(state.store.contactName), "contact-name", "请输入联系人。");
            required(state.store.areaRange, "area-range", "请选择面积范围。");
            required(cleanText(state.store.facilityCount), "facility-count", "请输入设施数量。");
            required(state.store.cooperationIntent, "cooperation-intent", "请选择合作意向。");
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
        state.files.photos.forEach(item => deleteLocalMedia(`photo:${item.photoId}`));
        deleteLocalMedia("photo"); deleteLocalMedia("wechat");
        state.submission.audioSegments.forEach((segment) => deleteLocalMedia(`audio:${segment.segmentId}`));
        resetLocalFile("photo");
        resetLocalFile("wechat");
        resetLocalFile("audio");
        state.submission.uploadedMedia = [];
        state.submission.mediaUploadAttempts = [];
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
        if (Number.isFinite(normalized.maxAudioBytes) && normalized.maxAudioBytes >= 0) {
            state.options.maxAudioBytes = normalized.maxAudioBytes;
        }

        if (city && Array.isArray(normalized.salespersons)) {
            state.salespersonsByCity.set(city, normalized.salespersons);
        }
        return normalized;
    }

    function mergeArrayOption(name, values) {
        if (Array.isArray(values)) state.options[name] = values;
    }

    function populateCitySelects() {
        const workCities = [...new Set([...state.options.cities,
            ...(state.identity?.city ? [state.identity.city] : [])])];
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
        if (state.identity?.authenticated) {
            if (scope === "visit") {
                abortStoreDirectorySearch();
                state.visit.directoryStores = state.visit.directoryStores.filter(item => item.source === "REGISTERED");
                state.visit.directoryIncludesMap = false;
                showVisitStoreOptions();
            }
            renderSalespersonSelect(scope);
            renderStoreOwnerSummary();
            renderSelectedStore();
            clearFieldError(`${scope}-city`);
            persistDraft();
            return;
        }
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

    function visitDirectoryOptions() {
        return (Array.isArray(state.visit.directoryStores) ? state.visit.directoryStores : []).filter(isUsableNearbyStore);
    }

    function abortStoreDirectorySearch() {
        window.clearTimeout(state.directorySearchTimer);
        state.storeDirectoryController?.abort();
        state.storeDirectoryController = null;
        const toggle = $("#store-search-toggle");
        if (toggle) {
            toggle.disabled = false;
            $("#store-search-button-label").textContent = "搜索";
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
        await captureLocation("store", { maxWaitMs: 5000, resolveTimeoutMs: 4000 });
        if (!state.store.location) {
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

    function sizeStorePicker() {
        if (!storePickerOpen) return;
        const viewport = window.visualViewport;
        const panel = $("#nearby-stores-panel");
        panel.style.setProperty("--store-picker-height", `${viewport?.height || window.innerHeight}px`);
        panel.style.setProperty("--store-picker-top", `${viewport?.offsetTop || 0}px`);
    }

    function openStorePicker() {
        if (storePickerOpen || $("#store-search").disabled || state.submitting || isBusinessLocked()) return;
        storePickerOpen = true;
        storePickerScrollY = window.scrollY;
        const panel = $("#nearby-stores-panel");
        panel.classList.add("is-picker-open");
        panel.setAttribute("role", "dialog");
        panel.setAttribute("aria-modal", "true");
        panel.setAttribute("aria-labelledby", "store-picker-title");
        $("#nearby-stores-title").textContent = "搜索结果";
        document.body.classList.add("is-store-picker-open");
        sizeStorePicker();
        showVisitStoreOptions();
    }

    function closeStorePicker(restoreFocus = false) {
        if (!storePickerOpen) {
            if (restoreFocus) $("#store-search-toggle").focus({preventScroll: true});
            return;
        }
        storePickerOpen = false;
        abortStoreDirectorySearch();
        if (state.visit.selectedStore) hideStoreResults();
        releaseActiveInput();
        const panel = $("#nearby-stores-panel");
        panel.classList.remove("is-picker-open");
        panel.removeAttribute("role");
        panel.removeAttribute("aria-modal");
        panel.setAttribute("aria-labelledby", "nearby-stores-title");
        $("#nearby-stores-title").textContent = "选择门店";
        document.body.classList.remove("is-store-picker-open");
        window.scrollTo({top: storePickerScrollY, behavior: "auto"});
        if (restoreFocus) $("#store-search-toggle").focus({preventScroll: true});
    }

    function handleStorePickerKeydown(event) {
        if (!storePickerOpen) return;
        if (event.key === "Escape") {
            event.preventDefault();
            closeStorePicker(true);
        } else if (event.key === "Tab") {
            const controls = [...$("#nearby-stores-panel").querySelectorAll("button:not(:disabled),input:not(:disabled)")]
                .filter(element => element.getClientRects().length);
            const first = controls[0], last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault(); last?.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault(); first?.focus();
            }
        }
    }

    function handleVisitStoreSearchInput() {
        openStorePicker();
        abortStoreDirectorySearch();
        const query = $("#store-search").value.trim();
        if (query !== state.visit.directoryQuery) {
            state.visit.directoryStores = [];
            state.visit.directoryQuery = "";
        }
        showVisitStoreOptions();
        $("#store-search-help").textContent = query ? "正在查找已建档门店…" : "点击搜索，查找当前位置附近门店";
        if (query) state.directorySearchTimer = window.setTimeout(() =>
            void searchVisitStoreDirectory({ includeMap: false }), 350);
    }

    function showVisitStoreOptions() {
        const input = $("#store-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        const candidates = [...visitDirectoryOptions(), ...visitNearbyOptions()];
        const seen = new Set();
        const stores = candidates.filter((store) => {
            const id = store.source === "REGISTERED" ? `store:${store.id || store.storeId}` : `poi:${store.poiId}`;
            if (seen.has(id)) return false;
            seen.add(id);
            return !query || (state.visit.directoryIncludesMap && query === state.visit.directoryQuery.toLocaleLowerCase("zh-CN"))
                || [store.name, store.address, store.locationSummary].some((value) =>
                cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        renderStoreResults(mergeVisitSearchResults(stores));
        $("#store-search-help").textContent = stores.length
            ? `找到 ${stores.length} 家，请核对门店地址` : "输入门店名称搜索；找不到可新增门店";
    }

    function toggleVisitStoreOptions() {
        if ($("#store-search").disabled) return;
        void searchVisitStoreDirectory();
    }

    async function searchVisitStoreDirectory({ includeMap = true } = {}) {
        window.clearTimeout(state.directorySearchTimer);
        if (includeMap && state.storeDirectoryController && !state.directorySearchExplicit) abortStoreDirectorySearch();
        if (state.submitting || isBusinessLocked() || state.storeDirectoryController
                || !state.identity?.authenticated) return;
        openStorePicker();
        if (includeMap) releaseActiveInput();
        const query = $("#store-search").value.trim();
        const controller = createRequestController();
        state.storeDirectoryController = controller;
        state.directorySearchExplicit = includeMap;
        const toggle = $("#store-search-toggle");
        toggle.disabled = includeMap;
        $("#store-search-button-label").textContent = includeMap ? "搜索中…" : "搜索";
        $("#store-search-help").textContent = includeMap ? "正在更新位置并搜索门店…" : "正在查找已建档门店…";
        try {
            if (includeMap) await captureLocation("visit", { maxWaitMs: 5000, resolveTimeoutMs: 4000 });
            if (state.storeDirectoryController !== controller) return;
            const location = locationRequestValue("visit");
            const coordinates = location ? `&longitude=${encodeURIComponent(location.longitude)}&latitude=${encodeURIComponent(location.latitude)}` : "";
            const path = `/stores?city=${encodeURIComponent(state.visit.city || state.identity.city)}&salespersonId=${encodeURIComponent(state.visit.salespersonId)}&q=${encodeURIComponent(query)}&limit=50${coordinates}`;
            const internalRequest = requestJson(path, { signal: controller.signal, timeout: 20000 });
            const mapRequest = includeMap && location ? requestJson("/locations/search-new-store", {
                method: "POST", headers: { "X-Sales-Checkin-Client-Event-Id": secureUuid() },
                body: { clientStoreId: state.store.clientStoreId, city: state.visit.city,
                    salespersonId: state.visit.salespersonId, location, q: query,
                    locationVerificationToken: state.visit.locationContext?.locationVerificationToken || "" },
                signal: controller.signal, timeout: 20000
            }) : Promise.resolve(null);
            const [internal, map] = await Promise.allSettled([internalRequest, mapRequest]);
            if (state.storeDirectoryController !== controller) return;
            const internalPayload = internal.status === "fulfilled" ? normalizeResponse(internal.value) : null;
            const mapPayload = map.status === "fulfilled" ? normalizeResponse(map.value) : null;
            const registered = (Array.isArray(internalPayload) ? internalPayload : []).map(store => ({
                ...store, source: "REGISTERED", storeId: store.storeId || store.id,
                checkinEligible: true, nextAction: "CHECK_IN", directoryMatch: true
            }));
            const candidates = [...registered, ...(Array.isArray(mapPayload?.nearbyStores) ? mapPayload.nearbyStores : [])];
            const stores = mergeVisitSearchResults(candidates.filter(isUsableNearbyStore));
            state.visit.directoryStores = stores;
            state.visit.directoryQuery = query;
            state.visit.directoryIncludesMap = includeMap;
            state.visit.directoryClientStoreId = state.store.clientStoreId;
            renderStoreResults(stores);
            const hints = [];
            if (registered.length >= 50) hints.push("内部门店显示前50家，可补充名称缩小范围");
            if (internal.status === "rejected") hints.push("内部档案暂不可用");
            if (includeMap && !location) hints.push("未获取定位，本次仅展示已建档门店");
            else if (includeMap && (map.status === "rejected" || mapPayload?.poiLookupStatus === "UNAVAILABLE")) hints.push("地图搜索暂不可用，可稍后重试");
            $("#store-search-help").textContent = [stores.length
                ? `${stores.length} 家${includeMap ? " · 按距离排序" : "已建档门店"}，点击选择`
                : "未找到门店，可换名称或地址搜索", ...hints].join("；");
            persistDraft();
        } catch (error) {
            if (state.storeDirectoryController !== controller || error.name === "AbortError") return;
            $("#store-search-help").textContent = errorMessage(error, "门店搜索失败，请重试");
        } finally {
            if (state.storeDirectoryController === controller) {
                state.storeDirectoryController = null;
                toggle.disabled = state.submitting || isBusinessLocked();
                $("#store-search-button-label").textContent = "搜索";
            }
        }
    }

    function mergeVisitSearchResults(candidates) {
        const unique = new Map();
        for (const candidate of candidates) {
            const key = candidate.source === "REGISTERED" ? `store:${candidate.storeId || candidate.id}` : `poi:${candidate.poiId}`;
            if (!unique.has(key) || candidate.source === "REGISTERED") unique.set(key, candidate);
        }
        const registeredPoiIds = new Set([...unique.values()].filter(item => item.source === "REGISTERED")
            .map(item => item.sourcePoiId || item.poiId).filter(Boolean));
        const distance = item => finiteNumberOrNull(item.distanceMeters) ?? Infinity;
        return [...unique.values()].filter(item => item.source === "REGISTERED" || !registeredPoiIds.has(item.poiId))
            .sort((a, b) => distance(a) - distance(b) || (a.source === "REGISTERED" ? 0 : 1) - (b.source === "REGISTERED" ? 0 : 1));
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
                const registered = store.source === "REGISTERED";
                const selected = registered && String(state.visit.selectedStore?.id) === String(store.id || store.storeId);
                button.className = "search-result visit-store-result " + (registered ? "is-registered" : "is-map-store") + (selected ? " is-selected" : "");
                button.setAttribute("aria-pressed", String(selected));

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
                status.textContent = registered ? "已建档" : "地图门店 · 待补资料";
                const distance = document.createElement("small");
                distance.textContent = formatDistance(store.distanceMeters) || "距离未知";
                meta.append(status, distance);
                detail.appendChild(meta);
                button.appendChild(detail);
                button.addEventListener("click", async () => {
                    if (registered) {
                        selectStore(store);
                        if (String(state.visit.selectedStore?.id) === String(store.id || store.storeId)) {
                            closeStorePicker();
                            hideStoreResults();
                            $("#visit-step-1-next").focus({preventScroll: true});
                        }
                        releaseActiveInput();
                    } else {
                        const clientStoreId = state.visit.directoryClientStoreId;
                        await prepareNewStore();
                        if (state.activeTab !== "store") return;
                        if (clientStoreId) state.store.clientStoreId = clientStoreId;
                        selectSourcePoi(store);
                        persistDraft();
                    }
                });
                button.addEventListener("keydown", handleStoreResultKeydown);
                root.appendChild(button);
            });
        }
        root.hidden = false;
        $("#store-search-toggle").setAttribute("aria-controls", "store-search-results");
        $("#store-search-toggle").setAttribute("aria-expanded", "true");
    }

    function hideStoreResults() {
        $("#store-search-results").hidden = true;
        $("#store-search-toggle").setAttribute("aria-expanded", "false");
    }

    function handleStoreSearchKeydown(event) {
        if (event.isComposing) return;
        if (event.key === "Escape") {
            event.preventDefault();
            hideStoreResults();
            closeStorePicker(true);
            return;
        }
        if (event.key === "Enter") {
            event.preventDefault();
            void searchVisitStoreDirectory();
            return;
        }
        if (event.key === "ArrowDown") {
            event.preventDefault();
            if ($("#store-search-results").hidden) showVisitStoreOptions();
            $("#store-search-results").querySelector("button.visit-store-result")?.focus();
        }
    }

    function handleStoreResultKeydown(event) {
        if (event.isComposing) return;
        if (event.key === "Escape") {
            event.preventDefault();
            hideStoreResults();
            closeStorePicker(true);
            return;
        }
        const buttons = [...$("#store-search-results").querySelectorAll("button.visit-store-result")];
        const current = buttons.indexOf(event.currentTarget);
        if (current < 0) return;
        let target;
        if (event.key === "ArrowDown") target = buttons[Math.min(current + 1, buttons.length - 1)];
        else if (event.key === "ArrowUp") target = current === 0 ? $("#store-search") : buttons[current - 1];
        else if (event.key === "Home") target = buttons[0];
        else if (event.key === "End") target = buttons[buttons.length - 1];
        if (target) {
            event.preventDefault();
            target.focus();
        }
        // Enter and Space retain the browser's native button activation.
    }

    function selectStore(store) {
        if (state.submitting || isBusinessLocked()) return;
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
        $("#store-search").value = state.visit.directoryQuery || "";
        renderSelectedStore();
        renderNearbyStores();
        clearFieldError("selected-store");
        persistDraft();
        showVisitStoreOptions();
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

    function renderVisitReview() {
        if ($("#review-customer")) $("#review-customer").textContent = state.visit.customerName
            ? `${state.visit.customerName}${state.visit.customerPhone ? " · " + state.visit.customerPhone : ""}` : "尚未填写";
        if ($("#review-visit-result")) $("#review-visit-result").textContent = state.visit.visitResult || "尚未填写";
        const element = $("#review-location-quality");
        if (!element) return;
        const location = state.visit.location;
        if (!location) { element.textContent = "未取得设备位置 · 待核验"; return; }
        if (location.userReportedInaccurate) { element.textContent = "已声明位置不准确 · 待核验"; return; }
        if (!location.capturedAt || location.timeStatus === "UNKNOWN") {
            element.textContent = "已保留设备坐标，采集时间未知 · 待核验"; return;
        }
        if (location.timeStatus === "STALE" || Date.now() - Date.parse(location.capturedAt) > GEOLOCATION_FRESH_MAX_AGE_MS) {
            element.textContent = "已保留较早的设备定位 · 待核验"; return;
        }
        element.textContent = location.accuracyMeters === null ? "设备定位精度未知 · 待核验"
            : location.accuracyMeters > 100 ? `设备定位精度约 ${Math.round(location.accuracyMeters)} 米 · 待核验`
                : `设备上报位置 · 精度约 ${Math.round(location.accuracyMeters)} 米`;
    }

    function renderSelectedStore() {
        const selected = state.visit.selectedStore;
        const businessCityHint = cleanText(selected?.city)
            && cleanText(selected?.city) !== cleanText(state.visit.city)
            ? ` · 门店归属${cleanText(selected.city)}` : "";
        if ($("#review-store-name")) $("#review-store-name").textContent = selected?.name || "尚未选择门店";
        if ($("#review-store-address")) $("#review-store-address").textContent = selected
            ? `${selected.locationSummary || selected.address || selected.city || "地址未完善"}${businessCityHint}` : "";
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
        $("#store-search").value = state.visit.directoryQuery || "";
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



    function renderUnverifiedBanner(scope) {
        const unverified = locationExceptionReady(state[scope].locationContext);
        const banner = $(`#${scope}-unverified-banner`);
        if (banner) banner.hidden = !unverified;
    }

    function renderNearbyStores() {
        const panel = $("#nearby-stores-panel");
        const locked = state.submitting || isBusinessLocked();
        panel.hidden = false;
        $("#nearby-stores-empty").hidden = true;
        $(".store-search-field", panel).hidden = false;
        $("#store-search").disabled = locked || !state.identity?.authenticated;
        $("#store-search").placeholder = "门店名称或地址";
        $("#store-search-toggle").disabled = locked || Boolean(state.storeDirectoryController && state.directorySearchExplicit);
        $("#store-search-button-label").textContent = state.storeDirectoryController ? "搜索中…" : "搜索";
        $("#create-store-link").disabled = locked;
        $("#nearby-stores-scope").textContent = "按名称搜索可拜访门店";
        $("#nearby-stores-summary").textContent = visitNearbyOptions().length
            ? `${visitNearbyOptions().length} 家附近推荐` : "全部门店可搜索";
        if (!state.storeDirectoryController) {
            $("#store-search-help").textContent = "输入门店名称或地址，点击搜索";
            if (!$("#store-search").value.trim()) renderStoreResults(visitNearbyOptions());
        }
        renderFlowActions();
    }

    function formatDistance(value) {
        const meters = finiteNumberOrNull(value);
        if (meters === null || meters < 0) return "";
        if (meters < 1000) return `${Math.max(1, Math.round(meters))} 米`;
        return `${(meters / 1000).toFixed(meters < 10000 ? 1 : 0)} 公里`;
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
                    : "没有匹配的已建档门店；点击搜索可查高德新门店。";
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
                        ? "未录入 · 位置待复核"
                        : "未录入 · 可建档";
                const distance = document.createElement("small");
                distance.textContent = formatDistance(poi.distanceMeters) || "附近";
                meta.append(badge, distance);
                detail.appendChild(meta);
                button.appendChild(detail);
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
        $("#poi-search-help").textContent = pois.length
            ? `找到 ${registeredCount} 家已建档门店、${amapCount} 个地图候选`
            : "未找到候选，可直接手动录入";
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
        const suggestedName = state.store.name || $("#poi-search").value.trim();
        clearSourcePoi(false, false);
        state.store.sourceMode = "MANUAL";
        state.store.manualEntryAllowed = true;
        state.store.name = suggestedName;
        $("#store-name").value = suggestedName;
        hidePoiResults(); clearFieldError("store-source");
        renderStoreSource(); renderStorePrefillMessage();
        goToFlowStep("store", 2, { validateForward: false });
    }

    function renderStoreSource() {
        const selected = state.store.sourceMode === "POI" && Boolean(state.store.sourcePoiToken);
        const manual = state.store.sourceMode === "MANUAL";
        const searching = Boolean(state.poiSearchController);
        const canSearch = Boolean(state.store.location?.capturedAt) && !selected;
        state.store.manualEntryAllowed = true;
        const input = $("#poi-search");
        input.disabled = !canSearch || searching;
        input.placeholder = "输入名称搜索地图门店（选用）";
        $("#poi-search-button").disabled = !canSearch || searching;
        $("#poi-search-button").textContent = searching ? "搜索中…" : "搜索";
        $("#poi-search-button").hidden = selected;
        $(".poi-search-field").hidden = selected;
        $("#selected-poi-card").hidden = !selected;
        $("#store-profile-card").hidden = !selected && !manual;
        $(".button-row").hidden = !selected && !manual;
        $("#submit-store-button").disabled = !selected && !manual;
        $("#store-name").readOnly = selected;
        $("#store-name-field").classList.toggle("is-readonly", selected);
        $("#store-source-description").textContent = "直接填写门店资料，也可选择地图门店";
        $("#store-name-help").textContent = selected ? "名称来自地图，请核对" : "请填写门店完整名称";
        $("#poi-search-help").textContent = searching ? "正在搜索…"
            : canSearch ? "地图搜索为选用，手动录入可随时继续" : "未取得设备位置，可直接手动录入";
        if (selected) {
            $("#selected-poi-name").textContent = state.store.sourcePoiName || state.store.name;
            $("#selected-poi-address").textContent = state.store.sourcePoiAddress || "暂无地址";
        }
        const button = $("#manual-store-button");
        button.disabled = state.submitting;
        button.classList.toggle("is-active", manual);
        button.querySelector("strong").textContent = manual ? "继续填写门店" : "手动录入门店";
        button.querySelector("span").textContent = "定位不准也可保存";
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
        if (state.store.city && state.store.city !== state.visit.city) {
            state.store = freshStore();
        }
        state.store.city = state.visit.city || state.store.city;
        state.store.salespersonId = state.visit.salespersonId || state.store.salespersonId;
        state.store.location = state.visit.location ? { ...state.visit.location } : null;
        state.store.locationContext = state.visit.locationContext
            ? { ...state.visit.locationContext }
            : null;
        if (!state.store.sourceMode) {
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
                : "可搜索地图门店，也可直接手工录入。";
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
        const rawTimestamp = position?.timestamp;
        // 标准 H5 使用 epoch 毫秒。非标准时钟保留原值，不推测偏移或用接收时间造新定位。
        const numeric = typeof rawTimestamp === "number" ? rawTimestamp : NaN;
        const known = Number.isFinite(numeric) && numeric >= Date.UTC(2000, 0, 1)
            && numeric <= receivedAtMs + LOCATION_CAPTURE_FUTURE_SKEW_MS;
        return normalizeUnverifiedLocationEvidence({
            longitude: position?.coords?.longitude,
            latitude: position?.coords?.latitude,
            accuracyMeters: position?.coords?.accuracy,
            capturedAt: known ? new Date(numeric).toISOString() : null,
            receivedAt: new Date(receivedAtMs).toISOString(),
            rawTimestamp: rawTimestamp == null ? null : String(rawTimestamp).slice(0, 120),
            source: "BROWSER_GEOLOCATION",
            timeStatus: !known ? "UNKNOWN" : receivedAtMs - numeric > GEOLOCATION_FRESH_MAX_AGE_MS
                ? "STALE" : "KNOWN"
        });
    }

    function normalizeUnverifiedLocationEvidence(value) {
        const longitude = finiteNumberOrNull(value?.longitude);
        const latitude = finiteNumberOrNull(value?.latitude);
        const accuracy = finiteNumberOrNull(value?.accuracyMeters);
        if (longitude === null || latitude === null || longitude < -180 || longitude > 180
                || latitude < -90 || latitude > 90) return null;
        return {
            longitude, latitude,
            accuracyMeters: accuracy !== null && accuracy >= 0 ? accuracy : null,
            capturedAt: normalizeOptionalInstant(value?.capturedAt),
            receivedAt: normalizeOptionalInstant(value?.receivedAt),
            rawTimestamp: value?.rawTimestamp == null ? null : String(value.rawTimestamp).slice(0, 120),
            source: cleanText(value?.source) || "BROWSER_GEOLOCATION",
            timeStatus: ["KNOWN", "UNKNOWN", "STALE"].includes(value?.timeStatus) ? value.timeStatus : "UNKNOWN",
            userReportedInaccurate: value?.userReportedInaccurate === true,
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
        } else {
            abortPoiSearch();
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.poiSearchQuery = "";
            state.store.poiSearchLookupStatus = "UNAVAILABLE";
            state.store.manualEntryAllowed = true;
            state.store.manualEntryToken = "";
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
        if (state[scope].location) state[scope].location.userReportedInaccurate = true;
        state[scope].locationContext = { ...state[scope].locationContext,
            userReportedInaccurate: true, locationVerificationStatus: "UNVERIFIED",
            locationFailureReason: "USER_CONTINUED_AFTER_WAIT",
            locationAttemptId: state[scope].locationContext?.locationAttemptId || secureUuid(),
            locationMessage: "已标记位置不准确，可继续打卡" };
        renderLocation(scope);
        persistDraft();
    }

    async function captureLocation(scope, options = {}) {
        const submissionCapture = options.forSubmission === true && state.preparingSubmission === true;
        if ((state.submitting && !submissionCapture) || state.completed || (scope === "visit" && isBusinessLocked())
                || !state.identity?.authenticated || document.visibilityState === "hidden") return;
        cancelLocationCapture(scope);
        state.locationControllers[scope]?.abort();
        state.locationControllers[scope] = null;
        const sequence = ++state.locationCaptureSequence[scope];
        const attemptId = secureUuid();
        let best = null;
        let finished = false;
        let complete;
        const captured = new Promise(resolve => { complete = resolve; });
        locationCaptureWaiters[scope] = complete;
        const owner = currentStorageOwner();
        const draftId = state.submission.clientSubmissionId;
        const active = () => !finished && sequence === state.locationCaptureSequence[scope]
            && (!state.submitting || submissionCapture) && !state.completed
            && owner === currentStorageOwner() && draftId === state.submission.clientSubmissionId;
        // A previous address/token belongs to the previous sample. Never attach it to a new GPS fix.
        state[scope].locationContext = { geocodeStatus: "CAPTURING", locationAttemptId: attemptId };
        renderLocation(scope);
        const finish = async error => {
            if (!active()) {
                if (sequence === state.locationCaptureSequence[scope]) stopGeolocationRefresh(scope);
                if (locationCaptureWaiters[scope] === complete) locationCaptureWaiters[scope] = null;
                complete(null); return;
            }
            finished = true;
            stopGeolocationRefresh(scope);
            state[scope].locationContext = { geocodeStatus: "FAILED",
                locationVerificationStatus: "UNVERIFIED", locationAttemptId: attemptId,
                locationFailureReason: best ? best.timeStatus !== "KNOWN" ? "TIMESTAMP_UNUSABLE"
                    : best.accuracyMeters > 100 ? "ACCURACY_INSUFFICIENT" : "RESOLVE_FAILED"
                    : locationFailureReason(error),
                errorMessage: best ? "设备位置已记录" : "暂未获取当前位置" };
            renderLocation(scope);
            persistDraft();
            if (best) {
                await resolveLocationContext(scope, attemptId, options.resolveTimeoutMs || 6000);
            }
            if (locationCaptureWaiters[scope] === complete) locationCaptureWaiters[scope] = null;
            complete(best);
        };
        const receive = position => {
            if (!active()) return;
            const candidate = locationEvidenceFromPosition(position);
            if (!candidate || !shouldReplaceLocationSample(best, candidate)) return;
            best = candidate;
            const note = $(`#${scope}-location-note`)?.value.trim();
            state[scope].location = { ...candidate, ...(note ? { note } : {}) };
            renderLocation(scope);
            persistDraft();
            // Fresh usable fixes can continue immediately; coarse/stale samples get the bounded watch window.
            if (candidate.timeStatus === "KNOWN" && candidate.accuracyMeters !== null
                    && candidate.accuracyMeters <= 60) void finish();
        };
        if (!window.isSecureContext || !navigator.geolocation) {
            void finish({ code: 2 }); return captured;
        }
        const budgetMs = options.maxWaitMs || GEOLOCATION_REFRESH_TIMEOUT_MS;
        state.geolocationTimeoutIds[scope] = window.setTimeout(() => void finish({ code: 3 }), budgetMs);
        const geoOptions = { enableHighAccuracy: true, maximumAge: 0, timeout: budgetMs };
        try {
            if (typeof navigator.geolocation.watchPosition === "function") {
                const watchId = navigator.geolocation.watchPosition(receive,
                    error => { if (error?.code === 1) void finish(error); }, geoOptions);
                if (finished) navigator.geolocation.clearWatch(watchId);
                else state.geolocationWatchIds[scope] = watchId;
            } else {
                navigator.geolocation.getCurrentPosition(receive, error => void finish(error), geoOptions);
            }
        } catch (_) { void finish({ code: 2 }); }
        return captured;
    }

    function shouldReplaceLocationSample(previous, candidate) {
        if (!candidate) return false;
        if (!previous) return true;
        const rank = (sample) => sample.timeStatus === "KNOWN" ? 2 : sample.timeStatus === "STALE" ? 1 : 0;
        if (rank(candidate) !== rank(previous)) return rank(candidate) > rank(previous);
        const previousTime = Date.parse(previous.capturedAt || "");
        const candidateTime = Date.parse(candidate.capturedAt || "");
        if (Number.isFinite(previousTime) && Number.isFinite(candidateTime) && candidateTime < previousTime) return false;
        return (candidate.accuracyMeters ?? Infinity) <= (previous.accuracyMeters ?? Infinity)
            || (Number.isFinite(candidateTime) && candidateTime - previousTime > 30000);
    }

    async function resolveLocationContext(scope, clientEventId = secureUuid(), timeoutMs = 6000) {
        const city = state[scope].city || state.identity?.city;
        const location = state[scope].location;
        if (!location) return;
        const previousVerifiedContext = locationContextReady(state[scope].locationContext)
            ? { ...state[scope].locationContext }
            : null;
        const previousRegisteredStores = previousVerifiedContext
            ? [...(scope === "visit" ? state.visit.nearbyStores : state.store.nearbyPois)]
            : [];

        state.locationControllers[scope]?.abort();
        const controller = createRequestController();
        state.locationControllers[scope] = controller;
        const owner = currentStorageOwner();
        const draftId = state.submission.clientSubmissionId;
        const sampleKey = value => JSON.stringify([value?.longitude, value?.latitude, value?.capturedAt, value?.receivedAt, value?.accuracyMeters]);
        const originalSample = sampleKey(location);
        const current = () => state.locationControllers[scope] === controller && currentStorageOwner() === owner
            && state.submission.clientSubmissionId === draftId && sampleKey(state[scope].location) === originalSample;
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
                timeout: timeoutMs
            })) || {};
            if (!current()) return;
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
                state.visit.nearbyStores = Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores
                        .filter((store) => store?.source === "REGISTERED")
                        .filter(isUsableNearbyStore)
                    : [];
            } else {
                state.store.nearbyPois = Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores
                        .filter((store) => store?.source === "REGISTERED")
                        .filter(isUsableNearbyStore)
                    : [];
            }
        } catch (error) {
            if (!current()) return;
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
            if (current()) {
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
        return withCurrentLocationNote(scope);
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
        if (scope === "visit") {
            const location = state.visit.location;
            const context = state.visit.locationContext;
            const busy = ["CAPTURING", "RESOLVING"].includes(context?.geocodeStatus);
            const address = cleanText(context?.formattedAddress || context?.address);
            const status = $("#visit-location-status");
            status.textContent = busy ? "正在定位" : address ? "已定位" : "待重试";
            status.className = `status-pill${busy ? " is-loading" : location ? " is-ready" : " is-warning"}`;
            $("#visit-location-address").textContent = address || (busy ? "正在获取当前位置…"
                : location ? "暂未取得当前位置地址，请重新定位" : "请允许浏览器定位，或点击重新定位");
            $("#visit-location-button-label").textContent = busy ? "定位中…" : "重新定位";
            $("#visit-location-button").disabled = busy || state.submitting || isBusinessLocked();
            $("#visit-location-button").closest(".location-card")?.classList.toggle("is-located", Boolean(location));
            renderFlowActions();
            return;
        }
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
        continueButton.hidden = scope !== "visit" || isBusinessLocked();
        continueButton.textContent = "位置不准确";
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
                ? "定位中…"
                : awaitingFreshPosition ? "定位中…"
                : capturing ? "定位中…" : "刷新当前位置";
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
        $(`#${scope}-location-accuracy`).textContent = location.accuracyMeters === null ? "未知" : `约 ${location.accuracyMeters} 米`;
        const timeLabel = $(`#${scope}-location-time`)?.closest("div")?.querySelector("span");
        if (timeLabel) timeLabel.textContent = "采集时间";
        $(`#${scope}-location-time`).textContent = location.capturedAt
            ? `${formatDateTime(location.capturedAt)}${location.timeStatus === "STALE" ? " · 较早" : ""}`
            : "设备未提供";
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

    function findPhoto(photoId) {
        return (state.submission.photos || []).find(photo => photo.photoId === photoId) || null;
    }

    function photoFile(photoId) {
        return (state.files.photos || []).find(item => item.photoId === photoId)?.file || null;
    }

    function syncLegacyPhotoAlias() {
        state.files.photo = state.files.photos[0]?.file || null;
    }

    function photoAppendAllowed() {
        if (state.submitting || state.addingPhotos || !currentStorageOwner()) return false;
        if (!state.completed) return true;
        const until = Date.parse(state.submission.supplementUntil || "");
        return Number.isFinite(until) && until > Date.now();
    }

    function photoPreviewService() {
        if (!photoPreviewQueue) photoPreviewQueue = window.SalesCheckinPhotos.createPreviewQueue({
            readPrefix: readFilePrefix, dimensions: imageHeaderDimensions,
            createImageBitmap: typeof window.createImageBitmap === "function" ? window.createImageBitmap.bind(window) : null,
            Image: window.Image, URL, document,
            setTimeout: window.setTimeout.bind(window), clearTimeout: window.clearTimeout.bind(window)
        });
        return photoPreviewQueue;
    }

    function photoSelectionNote(message = "", warning = false) {
        const note = $("#photo-selection-note");
        if (!note) return;
        note.hidden = !message;
        setStableText(note, message);
        note.classList.toggle("is-warning", warning);
    }

    async function handlePhotoSelection(event) {
        const files = Array.from(event.target.files || []);
        const source = event.target.id === "storefront-photo" ? "CAMERA" : "FILE_IMPORT";
        event.target.value = "";
        if (!files.length) { emitClientDiagnostic("PHOTO_REJECTED", "CANCELLED"); return; }
        if (!photoAppendAllowed()) return;
        const owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId;
        const sequence = photoSelectionSequence;
        const stillCurrent = () => sequence === photoSelectionSequence && owner === currentStorageOwner()
            && draftId === state.submission.clientSubmissionId;
        state.addingPhotos = true;
        clearFieldError("storefront-photo");
        const rejected = [];
        let added = 0, overflow = 0;
        try {
            for (const file of files) {
                if (!stillCurrent()) return;
                if ((state.submission.photos || []).length >= window.SalesCheckinPhotos.MAX_PHOTOS) { overflow++; continue; }
                if (!isSupportedImageFile(file) || !file.size || file.size > MAX_IMAGE_BYTES) {
                    rejected.push(file.name || "图片");
                    emitClientDiagnostic("PHOTO_REJECTED", file.size > MAX_IMAGE_BYTES ? "TOO_LARGE" : "UNSUPPORTED");
                    continue;
                }
                const photo = window.SalesCheckinPhotos.record(file, secureUuid(), source);
                state.submission.photos.push(photo);
                state.files.photos.push({photoId: photo.photoId, file});
                syncLegacyPhotoAlias();
                renderPhotos();
                emitClientDiagnostic("PHOTO_SELECTED", "ACCEPTED", {fileSizeBytes: file.size}, photo.photoId);
                // Persist the original Blob before any decoder work, with one stable key per photo.
                const saved = await saveLocalMedia(`photo:${photo.photoId}`, file);
                if (!stillCurrent()) return;
                added++;
                if (!saved) photoSelectionNote("照片尚未在本机保存，请保留手机原件并及时提交。", true);
                renderPhotos();
                void prepareSafePhotoPreview(file, photo.photoId);
                emitClientDiagnostic("PHOTO_READY", "SUCCEEDED", {fileSizeBytes: file.size}, photo.photoId);
            }
            if (!stillCurrent()) return;
            const messages = [];
            if (overflow) messages.push(`最多 9 张，另 ${overflow} 张未添加`);
            if (rejected.length) messages.push(`${rejected.length} 张未添加，请选择 10MB 内的常见图片格式`);
            if (messages.length) photoSelectionNote(messages.join("；"), true);
            else if (added && !state.files.photos.some(item => state.unsavedMedia.has(localMediaStorageKey(`photo:${item.photoId}`)))) photoSelectionNote();
            await persistDraft();
        } finally {
            if (stillCurrent()) { state.addingPhotos = false; renderPhotos(); renderFlowActions(); resumeActiveVisit(); }
        }
    }

    function safePhotoMediaUrl(value) {
        if (!value) return null;
        try {
            const parsed = new URL(value, window.location.href);
            return parsed.origin === window.location.origin && parsed.pathname.startsWith(`${API_BASE}/submissions/`)
                ? parsed.href : null;
        } catch (_) { return null; }
    }

    function photoStatusLabel(photo) {
        if (photo.uploadState === "UPLOADED") return "已收到";
        if (photo.uploadState === "UPLOADING") return "正在上传…";
        if (photo.uploadState === "DELETING") return "正在删除…";
        if (photo.uploadErrorStatus === 413) return "文件过大，删除后重选";
        if (photo.uploadErrorStatus === 415) return "格式不支持，删除后重选";
        if (photo.uploadState === "UNKNOWN") return "待核对上传结果";
        if (photo.uploadState === "ERROR") return photo.errorMessage || "上传失败，可重试";
        if (!photoFile(photo.photoId)) return "原图未恢复，删除后重选";
        if (state.unsavedMedia.has(localMediaStorageKey(`photo:${photo.photoId}`))) return "本机未保存";
        if (state.addingPhotos) return "正在保存…";
        return "待提交";
    }

    function renderPhotos() {
        const grid = $("#photo-grid"), template = $("#photo-item-template");
        if (!grid || !template) return;
        const photos = state.submission.photos || [];
        const ids = new Set(photos.map(photo => photo.photoId));
        for (const card of $$('[data-photo-item]', grid)) if (!ids.has(card.dataset.photoId)) card.remove();
        photos.forEach((photo, index) => {
            let card = $$('[data-photo-item]', grid).find(item => item.dataset.photoId === photo.photoId);
            if (!card) {
                card = template.content.firstElementChild.cloneNode(true);
                card.dataset.photoId = photo.photoId;
                grid.appendChild(card);
            }
            const status = $("[data-photo-status]", card);
            setStableText(status, photoStatusLabel(photo));
            status.classList.toggle("is-uploaded", photo.uploadState === "UPLOADED");
            status.classList.toggle("is-error", ["ERROR", "NEEDS_FILE"].includes(photo.uploadState));
            setStableText($("[data-photo-number]", card), String(index + 1));
            setStableText($("[data-photo-name]", card), photo.originalFilename);
            const open = $("[data-photo-open]", card), remove = $("[data-photo-remove]", card);
            open.setAttribute("aria-label", `查看第 ${index + 1} 张照片，${photo.originalFilename}`);
            remove.setAttribute("aria-label", `删除第 ${index + 1} 张照片`);
            remove.hidden = state.completed && photo.uploadState === "UPLOADED";
            remove.disabled = state.submitting || state.addingPhotos || ["UPLOADING", "DELETING"].includes(photo.uploadState);
            const thumbnail = $("[data-photo-thumbnail]", card);
            const url = photoPreviewQueue?.get(photo.photoId) || safePhotoMediaUrl(photo.thumbnailUrl);
            if (url && thumbnail.dataset.source !== url) {
                thumbnail.dataset.source = url;
                thumbnail.hidden = true;
                thumbnail.onload = () => { if (findPhoto(photo.photoId)) thumbnail.hidden = false; };
                thumbnail.onerror = () => { thumbnail.hidden = true; thumbnail.removeAttribute("src"); };
                thumbnail.src = url;
            } else if (!url && thumbnail.dataset.source) {
                thumbnail.hidden = true; thumbnail.removeAttribute("src"); delete thumbnail.dataset.source;
            }
            setStableText($("[data-photo-preview-note]", card), photo.uploadState === "UPLOADED"
                ? "原图已收到" : photoFile(photo.photoId) ? "原图已选择" : "需要原图");
        });
        setStableText($("#photo-count"), `${photos.length} / 9 张`);
        setStableText($("#photo-camera-button-label"), photos.length ? "继续拍照" : "拍照添加");
        const blocked = !photoAppendAllowed() || photos.length >= window.SalesCheckinPhotos.MAX_PHOTOS;
        ["#storefront-photo", "#photo-album-input", "#photo-camera-button", "#photo-album-button"]
            .forEach(selector => { const control = $(selector); if (control) control.disabled = blocked; });
    }

    async function prepareSafePhotoPreview(file, photoId = state.files.photos.find(item => item.file === file)?.photoId) {
        if (!photoId) return;
        const owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId;
        await photoPreviewService().enqueue(photoId, file);
        if (owner === currentStorageOwner() && draftId === state.submission.clientSubmissionId && photoFile(photoId) === file) renderPhotos();
    }

    function openPhotoPreview(photoId) {
        const photo = findPhoto(photoId);
        const url = photoPreviewQueue?.get(photoId) || safePhotoMediaUrl(photo?.thumbnailUrl);
        if (!photo || !url) {
            photoSelectionNote(photo?.uploadState === "UPLOADED"
                ? "原图已收到，可在本次记录中查看。" : "原图已选择，提交后可查看大图。");
            return;
        }
        $("#local-photo-full").src = url;
        $("#local-photo-dialog").showModal();
    }

    function mergePhotoReceipt(receipt) {
        state.submission.photos = window.SalesCheckinPhotos.merge(state.submission.photos, receipt);
        for (const photo of state.submission.photos) {
            if (photo.uploadState === "NEEDS_FILE" && photoFile(photo.photoId)) photo.uploadState = "LOCAL";
        }
        syncLegacyPhotoAlias();
        renderPhotos();
    }

    async function removePhoto(photoId) {
        const photo = findPhoto(photoId);
        if (!photo || state.submitting || state.addingPhotos) return false;
        if (state.completed && photo.uploadState === "UPLOADED") {
            photoSelectionNote("已提交的原始照片保留，可在补传期限内添加照片。", true); return false;
        }
        const owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId;
        const stillCurrent = () => owner === currentStorageOwner() && draftId === state.submission.clientSubmissionId;
        clearFieldError("storefront-photo");
        if (photo.mayExistRemotely || ["UPLOADED", "UNKNOWN"].includes(photo.uploadState)) {
            if (!state.submission.serverId) { photoSelectionNote("请先核对上次提交结果，再删除这张照片。", true); return false; }
            const previous = photo.uploadState;
            photo.uploadState = "DELETING"; renderPhotos();
            try {
                await requestJson(`/submissions/${encodeURIComponent(state.submission.serverId)}/media/photos/${encodeURIComponent(photoId)}`, {
                    method: "DELETE", headers: {"X-Submission-Key": state.submission.submissionKey}, timeout: 45000});
            } catch (error) {
                if (stillCurrent()) { photo.uploadState = previous; photoSelectionNote(errorMessage(error, "删除结果未确认，照片仍保留，请重试。"), true); renderPhotos(); }
                return false;
            }
            if (!stillCurrent()) return false;
        }
        deleteLocalMedia(`photo:${photoId}`);
        if (state.submission.legacyPhotoId === photoId) deleteLocalMedia("photo");
        state.files.photos = state.files.photos.filter(item => item.photoId !== photoId);
        state.submission.photos = state.submission.photos.filter(item => item.photoId !== photoId);
        photoPreviewQueue?.release(photoId);
        syncLegacyPhotoAlias();
        state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts.filter(item => item !== `photo:${photoId}`);
        if (!state.submission.photos.some(item => item.uploadState === "UPLOADED")) state.submission.uploadedMedia = state.submission.uploadedMedia.filter(item => item !== MEDIA.photo);
        photoSelectionNote(); renderPhotos(); renderFlowActions();
        await persistDraft();
        return true;
    }

    function restorePhotoMetadata() {
        state.submission.photos = window.SalesCheckinPhotos?.normalize(state.submission.photos, true) || [];
        const legacyRemote = state.submission.uploadedMedia.includes(MEDIA.photo);
        if (!state.submission.photos.length && legacyRemote && isUuidValue(state.submission.serverId)) {
            state.submission.legacyPhotoId = state.submission.serverId;
            state.submission.photos = window.SalesCheckinPhotos.normalize([{photoId: state.submission.serverId,
                originalFilename: "历史拜访照片", captureSource: null, uploadState: "UPLOADED", mayExistRemotely: true}]);
        }
    }

    function restorePhotoMedia(item, file) {
        let photoId = item.mediaId === "photo" ? state.submission.legacyPhotoId
            || (isUuidValue(state.submission.serverId) ? state.submission.serverId : secureUuid()) : item.mediaId.slice(6);
        if (!isUuidValue(photoId)) return;
        let photo = findPhoto(photoId);
        if (!photo) {
            // A deleted file can outlive its queued Blob removal. Do not resurrect that file.
            if (item.mediaId !== "photo" && !state.restoredLocalMediaIds?.includes(item.mediaId)) return;
            if (state.submission.photos.length >= window.SalesCheckinPhotos.MAX_PHOTOS) return;
            photo = window.SalesCheckinPhotos.record(file, photoId, null);
            state.submission.photos.push(photo);
        }
        if (!photoFile(photoId)) state.files.photos.push({photoId, file});
        if (photo.uploadState === "NEEDS_FILE") photo.uploadState = "LOCAL";
        syncLegacyPhotoAlias();
        if (item.mediaId === "photo") {
            state.submission.legacyPhotoId = photoId;
            // Keep the old Blob until the new key is durably written.
            const owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId;
            void saveLocalMedia(`photo:${photoId}`, file).then(saved => {
                if (saved && owner === currentStorageOwner() && draftId === state.submission.clientSubmissionId) deleteLocalMedia("photo");
            });
        }
        void prepareSafePhotoPreview(file, photoId);
    }

    async function handleImageSelection(kind, event) {
        if (kind === "photo") return handlePhotoSelection(event);
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
        if (kind === "wechat") state.submission.wechatUploadErrorStatus = null;
        // 持久化原文件先于预览/其他处理，不做 base64 或 canvas 扩容复制。
        await saveLocalMedia(kind, file);
        if (state.files[kind] !== file) return;
        renderImagePreview(kind, file);
        event.target.value = "";
        resumeActiveVisit();
        if (diagnosticId) {
            emitClientDiagnostic("PHOTO_READY", "SUCCEEDED", {
                fileSizeBytes: file.size
            }, diagnosticId);
        }
        state.submission.pendingWechat = Boolean(state.files.wechat
            && !state.submission.uploadedMedia.includes(MEDIA.wechat));
        persistDraft();
    }

    function renderImagePreview(kind, file) {
        if (kind === "photo") { renderPhotos(); return; }
        revokeObjectUrl(kind);
        $("#wechat-file-name").textContent = file.name || "待上传图片";
        $("#wechat-file-size").textContent = `${formatBytes(file.size)} · 已选择，提交时上传原图`;
        $("#wechat-preview-card").hidden = false;
    }

    function imageHeaderDimensions(bytes) {
        if (!bytes || bytes.length < 24) return null;
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        const valid = (width, height) => width > 0 && height > 0 ? {width, height} : null;
        if (view.getUint32(0) === 0x89504e47 && view.getUint32(4) === 0x0d0a1a0a
                && view.getUint32(8) === 13 && view.getUint32(12) === 0x49484452) {
            // 动画 PNG 不作自动本地预览，避免累积解码多帧。
            let offset = 8;
            while (offset + 12 <= bytes.length) {
                const size = view.getUint32(offset), type = view.getUint32(offset + 4);
                if (type === 0x6163544c) return null;
                if (type === 0x49444154) return valid(view.getUint32(16), view.getUint32(20));
                offset += size + 12;
            }
            return null;
        }
        if (bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
        let offset = 2;
        while (offset + 4 <= bytes.length) {
            if (bytes[offset] !== 0xff) return null;
            while (bytes[offset] === 0xff) offset++;
            const marker = bytes[offset++];
            if (marker === 0xda || marker === 0xd9) return null;
            if (marker === 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (offset + 2 > bytes.length) return null;
            const size = view.getUint16(offset);
            if (size < 2 || offset + size > bytes.length) return null;
            if ([0xc0, 0xc1, 0xc2].includes(marker) && size >= 8) {
                return valid(view.getUint16(offset + 5), view.getUint16(offset + 3));
            }
            offset += size;
        }
        return null;
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
        const nonemptyFiles = files.filter((file) => Number.isFinite(file.size) && file.size > 0);
        const readableFiles = nonemptyFiles.filter(audioFileSizeAllowed);
        const emptyCount = files.length - nonemptyFiles.length;
        const oversizedCount = nonemptyFiles.length - readableFiles.length;
        const imageFlags = await Promise.all(readableFiles.map(isImageSelectedAsAudio));
        if (selectionSequence !== state.audioFileSelectionSequence) return;
        const imageCount = imageFlags.filter(Boolean).length;
        let validFiles = readableFiles.filter((_, index) => !imageFlags[index]);
        if (imageCount || emptyCount || oversizedCount) {
            const ignored = [];
            if (imageCount) ignored.push(`${imageCount} 张图片`);
            if (emptyCount) ignored.push(`${emptyCount} 个空文件`);
            if (oversizedCount) ignored.push(`${oversizedCount} 段超过 ${formatBytes(audioSizeLimit())} 的录音`);
            showAudioSelectionNotice(`${ignored.join("和")}未添加；请选择较小录音，不影响打卡。`);
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

    function audioSizeLimit() {
        return Number.isFinite(state.options.maxAudioBytes) && state.options.maxAudioBytes >= 0
            ? state.options.maxAudioBytes : DEFAULT_MAX_AUDIO_BYTES;
    }

    function audioFileSizeAllowed(file) {
        return Number.isFinite(file?.size) && file.size > 0 && file.size <= audioSizeLimit();
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
        const segmentId = evidence.segmentId || secureUuid();
        if (findAudioSegment(segmentId)) return segmentId;
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
        void saveLocalMedia(`audio:${segmentId}`, file);
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
        segment.uploadErrorStatus = null;
        segment.errorMessage = "";
        state.files.audio.push({ segmentId, file });
        ensureAudioObjectUrl(segmentId, file);
        void saveLocalMedia(`audio:${segmentId}`, file);
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

    async function readAudioDurationMs(file) {
        // 高码率 WAV 可能只有十几分钟却接近200MB；先用固定长度文件头计算PCM时长。
        // 大文件不为“时长”启动浏览器媒体解码器，未知时长由服务端派生任务补全。
        const wavDuration = await readPcmWaveDurationMs(file);
        if (wavDuration) return wavDuration;
        if (file.size > 16 * 1024 * 1024) return null;
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

    async function readPcmWaveDurationMs(file) {
        try {
            const prefix = await readFilePrefix(file, 64 * 1024);
            if (!prefix || prefix.byteLength < 44) return null;
            const view = new DataView(prefix.buffer, prefix.byteOffset, prefix.byteLength);
            const ascii = (start, value) => [...value].every((char, i) => view.getUint8(start + i) === char.charCodeAt(0));
            if (!ascii(0, "RIFF") || !ascii(8, "WAVE")) return null;
            let byteRate = 0;
            for (let offset = 12; offset + 8 <= view.byteLength;) {
                const size = view.getUint32(offset + 4, true);
                if (ascii(offset, "fmt ") && size >= 16 && offset + 24 <= view.byteLength) {
                    const encoding = view.getUint16(offset + 8, true);
                    if (encoding !== 1 && encoding !== 3) return null;
                    byteRate = view.getUint32(offset + 16, true);
                }
                if (ascii(offset, "data") && byteRate > 0 && size > 0 && offset + 8 + size <= file.size) {
                    return normalizePositiveDurationMs(size * 1000 / byteRate);
                }
                offset += 8 + size + (size % 2);
            }
        } catch (_) { /* 无法读取头部不影响上传，保留未知时长。 */ }
        return null;
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
        $("#recorder-help").hidden = supported;
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
        $("#recorder-help").textContent = `本机录制 ${format}；支持常见手机录音格式，单文件上限 ${formatBytes(audioSizeLimit())}。`;
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
            setFieldError("audio-file", "请先选择本次拜访的门店，再开始录音。");
            return;
        }
        if (visitStep === 3 && !isBusinessLocked()) {
            goToFlowStep("visit", 2);
            return;
        }
        if (state.submission.audioSegments.length >= 20) {
            setFieldError("audio-file", "本次已添加20段录音，请先移除不需要的录音。");
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
        const previousAudioSessionType = prepareAudioSession();
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
                restoreAudioSession(previousAudioSessionType);
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
                finished: false,
                owner: currentStorageOwner(),
                draftId: state.submission.clientSubmissionId,
                bytes: 0,
                activeDurationMs: 0,
                activeSince: startedAt,
                backgrounded: false,
                interrupted: false,
                integrityLost: false,
                journalFailed: false,
                previousAudioSessionType,
                listeners: []
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

            try {
                session.journal = window.SalesCheckinRecordingJournal?.create(session.owner, session.draftId,
                    session.id, {mimeType: recorder.mimeType, clientStartedAt, maxBytes: audioSizeLimit()});
                if (!session.journal) throw new Error("分块保存不可用");
                void session.journal.ready.catch(() => recordingJournalFailed(session));
            } catch (_) { recordingJournalFailed(session); }

            recorder.addEventListener("dataavailable", (event) => {
                if (session.finished || state.recorder.sessionId !== session.id || !event.data?.size) return;
                if (session.bytes + event.data.size > audioSizeLimit()) {
                    session.integrityLost = true;
                    interruptRecordingSession(session, "录音达到单文件上限，已停止。保留片段需试听确认。");
                    return;
                }
                session.bytes += event.data.size;
                session.chunks.push(event.data);
                if (session.journal && !session.journalFailed) {
                    void session.journal.append(event.data).catch(() => recordingJournalFailed(session));
                }
            });
            recorder.addEventListener("error", () => {
                interruptRecordingSession(session, "录音意外中断；如已生成音频，请回放确认后再提交。");
            });
            recorder.addEventListener("pause", () => {
                if (session.finished || state.recorder.sessionId !== session.id) return;
                markRecordingInterrupted(session);
                setRecordingUi(false);
            });
            recorder.addEventListener("resume", () => {
                if (session.finished || state.recorder.sessionId !== session.id) return;
                if (recordingTracksAvailable(session)) session.activeSince = Date.now();
                setRecordingUi(isRecording());
            });
            recorder.addEventListener("stop", () => finishRecording(session), { once: true });
            stream.getAudioTracks?.().forEach((track) => {
                listenRecording(session, track, "mute", () => {
                    markRecordingInterrupted(session);
                    if (session.recorder.state === "recording") {
                        try { session.recorder.pause(); } catch (_) { /* Keep already returned data when the platform refuses pause. */ }
                    }
                    renderRecordingStatus();
                });
                listenRecording(session, track, "unmute", () => resumeRecordingLifecycle());
                listenRecording(session, track, "ended", () => {
                    if (!session.finished && !state.recorder.stopping) {
                        interruptRecordingSession(session, "麦克风被系统中断，已停止并尝试保留录到的内容。");
                    }
                });
            });
            if (navigator.audioSession?.addEventListener) listenRecording(session, navigator.audioSession, "statechange", () => {
                if (navigator.audioSession.state === "interrupted") {
                    markRecordingInterrupted(session);
                    if (recorder.state === "recording") { try { recorder.pause(); } catch (_) {} }
                    renderRecordingStatus();
                } else resumeRecordingLifecycle();
            });
            recorder.start(1000);
            if (!recordingTracksAvailable(session)) markRecordingInterrupted(session);
            setRecordingUi(true);
            updateRecordingClock();
            state.recorder.timer = window.setInterval(updateRecordingClock, 500);
            void acquireRecordingWakeLock(session);
        } catch (error) {
            stopRecorderStream(stream);
            if (requestSequence !== state.recorder.startSequence) { restoreAudioSession(previousAudioSessionType); return; }
            cleanupRecorder(session?.id || null);
            if (!session) restoreAudioSession(previousAudioSessionType);
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

    function listenRecording(session, target, event, listener) {
        target.addEventListener(event, listener);
        session.listeners.push(() => target.removeEventListener?.(event, listener));
    }

    function prepareAudioSession() {
        try {
            const target = navigator.audioSession;
            if (!target) return null;
            const intent = {target, previousType: target.type, token: secureUuid()};
            target.type = "play-and-record";
            state.recorder.audioSessionOwner = intent.token;
            return intent;
        } catch (_) { return null; }
    }

    function restoreAudioSession(intent) {
        if (!intent || state.recorder.audioSessionOwner !== intent.token) return;
        state.recorder.audioSessionOwner = null;
        try { intent.target.type = intent.previousType || "auto"; } catch (_) {}
    }

    async function acquireRecordingWakeLock(session) {
        if (!navigator.wakeLock?.request || document.visibilityState !== "visible"
                || session.finished || session.wakeLock || session.wakeLockPending) return;
        session.wakeLockPending = true;
        try {
            const lock = await navigator.wakeLock.request("screen");
            if (session.finished || state.recorder.sessionId !== session.id || document.visibilityState !== "visible") {
                await lock.release(); return;
            }
            session.wakeLock = lock;
            lock.addEventListener?.("release", () => { if (session.wakeLock === lock) session.wakeLock = null; });
        } catch (_) { /* Screen wake lock is optional and cannot authorize background capture. */ }
        finally { session.wakeLockPending = false; }
    }

    function recordingTracksAvailable(session) {
        const tracks = session?.stream?.getAudioTracks?.() || [];
        return tracks.length > 0 && tracks.every(track => track.readyState !== "ended" && !track.muted && track.enabled !== false)
            && navigator.audioSession?.state !== "interrupted";
    }

    function recordingActiveDuration(session) {
        return Math.max(0, session.activeDurationMs + (session.activeSince === null ? 0 : Date.now() - session.activeSince));
    }

    function markRecordingInterrupted(session) {
        if (!session || session.finished) return;
        session.activeDurationMs = recordingActiveDuration(session);
        session.activeSince = null;
        session.interrupted = true;
        checkpointRecording("INTERRUPTED");
    }

    function recordingJournalFailed(session) {
        if (session.journalFailed) return;
        session.journalFailed = true;
        if (!session.finished && state.recorder.sessionId === session.id) {
            showAudioSelectionNotice("录音分块未能在本机保存；请保留当前页面，结束后及时提交或下载录音。");
            renderRecordingStatus();
        }
    }

    function checkpointRecording(reason) {
        const session = state.recorder.activeSession;
        if (!session || session.finished) return;
        if (reason === "BACKGROUND" || reason === "PAGE_HIDDEN") session.backgrounded = true;
        try { if (session.recorder.state !== "inactive") session.recorder.requestData?.(); } catch (_) {}
        if (session.journal && !session.journalFailed) {
            void session.journal.checkpoint({interrupted: session.interrupted, backgrounded: session.backgrounded,
                activeDurationMs: recordingActiveDuration(session)}).catch(() => recordingJournalFailed(session));
        }
    }

    function resumeRecordingLifecycle() {
        const session = state.recorder.activeSession;
        if (!session || session.finished) {
            if (initialized && currentStorageOwner()) void recoverRecordingJournals();
            return;
        }
        const ended = session.stream.getAudioTracks?.().some(track => track.readyState === "ended");
        if (ended || session.recorder.state === "inactive") {
            interruptRecordingSession(session, "录音已被系统中断，正在保留浏览器返回的片段。");
            return;
        }
        if (recordingTracksAvailable(session) && !state.recorder.stopping) {
            if (session.recorder.state === "paused") {
                try { session.recorder.resume(); } catch (_) { markRecordingInterrupted(session); }
            }
            if (session.recorder.state === "recording" && session.activeSince === null) session.activeSince = Date.now();
        } else markRecordingInterrupted(session);
        updateRecordingClock();
        setRecordingUi(isRecording());
        if (document.visibilityState === "visible") void acquireRecordingWakeLock(session);
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
        state.recorder.elapsedMs = recordingActiveDuration(session);
        session.activeDurationMs = state.recorder.elapsedMs;
        session.activeSince = null;
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
        markRecordingInterrupted(session);
        setFieldError("audio-file", message);
        if (!state.recorder.stopping) {
            state.recorder.stopping = true;
            state.recorder.elapsedMs = recordingActiveDuration(session);
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
                finishRecording(session, false);
                return;
            }
            session.finished = true;
            cleanupRecorder(session.id);
            setFieldError("audio-file", "录音停止超时且未生成有效音频，请重新录制或选择已有音频。");
        }, 5000);
    }

    function finishRecording(session, finalized = true) {
        if (!session || session.finished || state.recorder.sessionId !== session.id) {
            stopRecorderStream(session?.stream);
            return;
        }
        window.clearTimeout(state.recorder.stopFallbackTimer);
        state.recorder.stopFallbackTimer = null;
        session.finished = true;
        const duration = recordingActiveDuration(session);
        const mimeType = session.recorder?.mimeType || session.chunks[0]?.type || "audio/webm";
        const blob = new Blob(session.chunks, { type: mimeType });
        const complete = finalized && !session.integrityLost;
        const journalFinish = session.journal && !session.journalFailed
            ? session.journal.finish({finalized: complete, interrupted: session.interrupted || session.failed,
                backgrounded: session.backgrounded, activeDurationMs: duration}) : Promise.resolve(false);
        void journalFinish.catch(() => recordingJournalFailed(session));
        cleanupRecorder(session.id);
        updateRecorderHelp(mimeType);
        if (!blob.size) {
            setFieldError("audio-file", "没有录到有效音频，请重新录制。" );
            return;
        }
        if (!complete) {
            showUnfinishedRecording({sessionId: session.id, owner: session.owner, draftId: session.draftId,
                blob, complete: false, clientStartedAt: session.clientStartedAt, createdAt: session.startedAt,
                interrupted: true, mimeType});
            setFieldError("audio-file", "系统未完成录音文件封装。保留片段可尝试试听或下载检查，不会自动作为完整录音上传。");
            return;
        }
        const extension = audioExtension(mimeType);
        const filename = `${session.interrupted || session.failed ? "中断后保留" : session.backgrounded ? "后台录音待回放" : "现场录音"}-${formatFilenameTime(new Date())}.${extension}`;
        const file = typeof window.File === "function"
            ? new File([blob], filename, { type: mimeType, lastModified: Date.now() })
            : Object.assign(blob, { name: filename, lastModified: Date.now() });
        $("#audio-file").value = "";
        hideAudioSelectionNotice();
        const savedSegmentId = appendAudioFile(file, {
            segmentId: session.id,
            captureSource: "BROWSER_RECORDER",
            clientStartedAt: session.clientStartedAt,
            // A wall clock or active-track interval is not proof of encoded audio duration.
            clientDurationMs: null
        });
        const mediaSaved = state.persistence;
        void Promise.all([journalFinish, mediaSaved]).then(([, saved]) => {
            if (saved === true && session.journal) return window.SalesCheckinRecordingJournal.remove(session.owner, session.draftId, session.id);
        }).catch(() => {});
        beginAudioFileMetadataRead(savedSegmentId, file);
        renderAudioSegments();
        renderUploadedBadges();
        if (session.failed || session.interrupted || session.backgrounded) {
            setFieldError("audio-file", "录音曾转到后台或被系统中断，已保留返回的音频；请回放确认是否完整。");
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
        const session = state.recorder.activeSession;
        session?.listeners?.forEach(remove => remove());
        if (session?.wakeLock) void session.wakeLock.release().catch(() => {});
        if (session) restoreAudioSession(session.previousAudioSessionType);
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
        $("#record-button-label").textContent = !stopping && !starting && state.recorder.activeSession
            ? "结束并保存"
            : starting ? "等待麦克风权限"
                : stopping ? "正在生成录音"
                    : normalizeFlowStep(state.ui.visitStep) === 3 && !isBusinessLocked()
                        ? "返回第2步补录" : "开始录音";
        $("#recording-meter").hidden = !recording;
        $("#visit-recording-workspace").classList.toggle("is-recording", recording || stopping);
        renderRecordingStatus();
        renderFlowSteps();
    }

    function renderRecordingDisclosure() {
        const workspace = $("#visit-recording-workspace");
        const busy = recordingBusy();
        const count = state.submission.audioSegments.length;
        const step = normalizeFlowStep(state.ui.visitStep);
        const context = `${state.submission.clientSubmissionId}:${step}:${count > 0}`;
        workspace.open = true;
        workspace.dataset.disclosureContext = context;
        const badge = $("#recording-stage-badge");
        const attentionCount = state.submission.audioSegments.filter(segment =>
            ["UNKNOWN", "NEEDS_FILE", "ERROR", "TOO_LARGE"].includes(segment.uploadState)).length;
        badge.hidden = !busy && count === 0;
        badge.textContent = state.recorder.starting ? "等待权限"
            : state.recorder.stopping ? "保存中"
                : state.recorder.activeSession && !isRecording() ? "系统暂停"
                    : isRecording() ? "正在录音"
                    : attentionCount ? `${attentionCount} 段待处理` : `已添加 ${count} 段`;
        $("#recording-workspace-summary").removeAttribute("aria-disabled");
    }

    function renderRecordingStatus() {
        renderRecordingDisclosure();
        const note = $("#recording-status-note");
        note.hidden = !recordingBusy() && !state.submission.audioSegments.length;
        if (state.recorder.starting) {
            note.textContent = "正在等待系统麦克风权限，请不要重复点击或切换页面。";
            return;
        }
        const session = state.recorder.activeSession;
        if (session && !state.recorder.stopping) {
            note.textContent = !isRecording() || !recordingTracksAvailable(session)
                ? "系统暂停了麦克风，计时已暂停；恢复可用后会继续，也可结束保存已有录音。"
                : session.journalFailed ? "正在录音，本机分块尚未保存。请保留页面，结束后及时提交或下载。"
                    : session.interrupted ? "已继续录音；曾发生系统中断，请结束后回放检查。"
                        : session.backgrounded ? "正在录音；曾转到后台，完整性请在结束后回放确认。"
                            : "正在录音，可继续填写。切到后台会尽量保持，系统可能暂停麦克风。";
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
            note.textContent = `已添加 ${audioCount} 段录音，请回放确认。`;
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
            : "录音选填，停止后保存；失败可稍后补传。";
    }

    function updateRecordingClock() {
        const session = state.recorder.activeSession;
        if (session && !session.finished) {
            if (!recordingTracksAvailable(session) && session.activeSince !== null) markRecordingInterrupted(session);
            state.recorder.elapsedMs = recordingActiveDuration(session);
        }
        $("#recording-clock").textContent = formatDuration(state.recorder.elapsedMs);
    }

    function isRecording() {
        return Boolean(state.recorder.instance && state.recorder.instance.state === "recording"
            && (!state.recorder.activeSession || recordingTracksAvailable(state.recorder.activeSession)));
    }

    function showUnfinishedRecording(entry) {
        if (entry.owner !== currentStorageOwner() || entry.draftId !== state.submission.clientSubmissionId) return;
        if (!state.recorder.recoveries.some(item => item.sessionId === entry.sessionId)) state.recorder.recoveries.push(entry);
        renderRecordingRecoveries();
    }

    function renderRecordingRecoveries() {
        const target = $("#recording-recovery-list");
        if (!target) return;
        target.querySelectorAll("audio").forEach(releaseAudioElement);
        target.replaceChildren();
        state.recorder.recoveries = state.recorder.recoveries.filter(entry => {
            const current = entry.owner === currentStorageOwner() && entry.draftId === state.submission.clientSubmissionId;
            if (!current && entry.objectUrl) URL.revokeObjectURL(entry.objectUrl);
            return current;
        });
        state.recorder.recoveries.forEach(entry => {
            const card = document.createElement("article");
            card.className = "audio-segment-card recording-recovery-card";
            const title = document.createElement("strong");
            title.textContent = `待检查的录音片段 · ${formatDateTime(entry.clientStartedAt)}`;
            const note = document.createElement("p");
            note.textContent = "页面曾被系统中断，文件可能不完整。可尝试试听或下载；未自动加入提交附件。";
            const audio = document.createElement("audio");
            audio.controls = true; audio.preload = "none";
            entry.objectUrl ||= URL.createObjectURL(entry.blob);
            audio.src = entry.objectUrl;
            audio.setAttribute("aria-label", "试听待检查的录音片段");
            audio.addEventListener("play", () => pauseOtherAudioPreviews(audio));
            audio.addEventListener("error", () => { note.textContent = "浏览器无法播放这份恢复文件。可下载保留检查，也可删除；不会阻止打卡。"; });
            const download = document.createElement("a");
            download.href = entry.objectUrl;
            download.download = `未完成录音-${entry.sessionId}.${audioExtension(entry.mimeType || entry.blob.type)}`;
            download.textContent = "下载保留文件";
            const remove = document.createElement("button");
            remove.type = "button"; remove.className = "button button-secondary"; remove.textContent = "删除恢复件";
            remove.addEventListener("click", async () => {
                remove.disabled = true;
                try {
                    await window.SalesCheckinRecordingJournal?.remove(entry.owner, entry.draftId, entry.sessionId);
                    releaseAudioElement(audio);
                    URL.revokeObjectURL(entry.objectUrl);
                    state.recorder.recoveries = state.recorder.recoveries.filter(item => item !== entry);
                    renderRecordingRecoveries();
                } catch (_) { note.textContent = "本机删除尚未完成，请重试。"; remove.disabled = false; }
            });
            card.append(title, note, audio, download, remove); target.appendChild(card);
        });
    }

    async function recoverRecordingJournals() {
        const owner = currentStorageOwner();
        const draftId = state.submission.clientSubmissionId;
        if (!owner || !window.SalesCheckinRecordingJournal || state.recorder.recoveryLoading || recordingBusy()) return;
        const scope = `${owner}/${draftId}`;
        if (state.recorder.recoveryOwner !== scope) {
            state.recorder.recoveries.forEach(entry => { if (entry.objectUrl) URL.revokeObjectURL(entry.objectUrl); });
            state.recorder.recoveries = [];
            state.recorder.recoveryOwner = scope;
            renderRecordingRecoveries();
        }
        state.recorder.recoveryLoading = true;
        try {
            const entries = await window.SalesCheckinRecordingJournal.list(owner, draftId);
            for (const entry of entries) {
                if (owner !== currentStorageOwner() || draftId !== state.submission.clientSubmissionId || recordingBusy()) return;
                const existing = findAudioSegment(entry.sessionId);
                if (!entry.complete || (!existing && state.submission.audioSegments.length >= 20)) {
                    showUnfinishedRecording(entry); continue;
                }
                if (existing?.uploadState === "UPLOADED") {
                    await window.SalesCheckinRecordingJournal.remove(owner, draftId, entry.sessionId); continue;
                }
                const filename = `恢复录音${entry.interrupted || entry.backgrounded ? "-请回放检查" : ""}-${entry.sessionId}.${audioExtension(entry.mimeType)}`;
                const file = new File([entry.blob], filename, {type: entry.mimeType || entry.blob.type});
                if (existing && !localAudioFile(entry.sessionId)) {
                    state.files.audio.push({segmentId: entry.sessionId, file});
                    if (existing.uploadState === "NEEDS_FILE") existing.uploadState = "LOCAL";
                    ensureAudioObjectUrl(entry.sessionId, file);
                    void saveLocalMedia(`audio:${entry.sessionId}`, file);
                } else if (existing) {
                    void saveLocalMedia(`audio:${entry.sessionId}`, localAudioFile(entry.sessionId).file);
                } else appendAudioFile(file, {segmentId: entry.sessionId,
                    captureSource: "BROWSER_RECORDER", clientStartedAt: entry.clientStartedAt});
                const saved = await state.persistence;
                if (saved === true) await window.SalesCheckinRecordingJournal.remove(owner, draftId, entry.sessionId);
                if (owner !== currentStorageOwner() || draftId !== state.submission.clientSubmissionId) return;
                beginAudioFileMetadataRead(entry.sessionId, localAudioFile(entry.sessionId)?.file || file);
                renderAudioSegments(); renderUploadedBadges();
                showAudioSelectionNotice("已恢复浏览器完成封装的录音，请回放确认；系统中断期间的声音无法补录。");
            }
        } catch (_) {
            showAudioSelectionNotice("本机录音恢复件暂未读取成功，请保留页面，稍后返回再试。");
        } finally { state.recorder.recoveryLoading = false; }
    }

    function renderAudioSegments() {
        renderRecordingDisclosure();
        const root = $("#audio-preview-list");
        const template = $("#audio-preview-template");
        root.querySelectorAll("audio").forEach(releaseAudioElement);
        root.replaceChildren();
        state.submission.audioSegments.forEach((segment, index) => {
            const card = template.content.firstElementChild.cloneNode(true);
            card.dataset.segmentId = segment.segmentId;
            const audio = card.querySelector("audio");
            audio.preload = "none";
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
            retry.textContent = segment.uploadErrorStatus === 413 || segment.uploadState === "TOO_LARGE" ? "换小文件"
                : local && state.submission.serverId ? "重试上传" : "重新选择";
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
        if (segment.uploadState === "TOO_LARGE") return optionalUploadFailureMessage({status: 413}, "录音");
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
        return ["UNKNOWN", "NEEDS_FILE", "ERROR", "SKIPPED", "TOO_LARGE"].includes(segment.uploadState);
    }

    function audioSegmentCanSkip(segment) {
        return ["UNKNOWN", "NEEDS_FILE", "ERROR", "TOO_LARGE"].includes(segment.uploadState);
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
        if (local && state.submission.serverId && segment.uploadState !== "TOO_LARGE" && segment.uploadErrorStatus !== 413) {
            clearFieldError("audio-file");
            try {
                await uploadAudioSegment(segment, local.file, 1, 1,
                    Date.now() + OPTIONAL_MEDIA_UPLOAD_MAX_MS);
                renderAudioSegments();
                renderUploadedBadges();
            } catch (error) {
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
        deleteLocalMedia(`audio:${segmentId}`);
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
        if (kind === "photo") return state.submission.photos.length
            ? removePhoto(state.submission.photos[0].photoId) : true;
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

        deleteLocalMedia(kind);
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
        if (kind === "photos") return;
        if (kind === "photo") {
            photoSelectionSequence++;
            state.addingPhotos = false;
            state.files[kind] = null;
            state.files.photos = [];
            state.submission.photos = [];
            photoPreviewQueue?.releaseAll();
            revokeObjectUrl(kind);
            $("#storefront-photo").value = "";
            $("#photo-album-input").value = "";
            $("#photo-grid")?.replaceChildren();
            const dialog = $("#local-photo-dialog");
            if (dialog?.open) dialog.close();
            photoSelectionNote();
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
        const previewButton = $("#remove-wechat-button");
        if (!previewButton.closest("[hidden]")) return previewButton;
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
) {
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
) {
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
        if (state.submitting) return;
        if (state.completed) { await supplementCurrentEvidence(); return; }
        hideError(); syncStateFromForm();
        if (state.recorder.starting) cleanupRecorder();
        if (recordingBusy()) {
            setFieldError("audio-file", "请先结束并保存录音"); return;
        }
        pauseAllAudioPreviews();
        if (!state.submission.attemptedPayload && !state.submission.serverId) {
            if (state.preparingSubmission) return;
            if (!validateVisit()) { scrollToFirstError(); return; }
            const intendedOwner = currentStorageOwner();
            const intendedDraft = state.submission.clientSubmissionId;
            const intendedStore = state.visit.selectedStore?.id;
            state.preparingSubmission = true;
            state.submitting = true;
            setFormsDisabled(true);
            renderDraftSaveStatus("正在刷新当前位置…");
            try { await captureLocation("visit", { maxWaitMs: 5000, resolveTimeoutMs: 3000, forSubmission: true }); }
            finally { state.preparingSubmission = false; state.submitting = false; }
            if (intendedOwner !== currentStorageOwner() || intendedDraft !== state.submission.clientSubmissionId
                    || intendedStore !== state.visit.selectedStore?.id) {
                setFormsDisabled(false);
                showError("当前拜访已变化，请确认后再提交");
                return;
            }
        }
        state.submitting = true;
        setFormsDisabled(true);
        let activeStep = "draft";
        try {
            // 响应丢失时先查稳定ID；不先要求用户重选可能已经收到的照片。
            let receipt = null;
            if (state.submission.attemptedPayload || state.submission.serverId) {
                renderDraftSaveStatus("正在查询提交结果…");
                receipt = await lookupSubmissionReceipt();
                if (receipt?.status === "SUBMITTED") {
                    showSuccess(receipt);
                    void supplementCurrentEvidence();
                    return;
                }
            }
            if (!validateVisit()) { scrollToFirstError(); return; }
            state.submission.syncRequested = true;
            state.submission.syncState = "PENDING";
            await persistDraft();
            await Promise.all([...state.pendingMedia]);
            prepareProgress();
            $("#upload-panel").hidden = false;
            setProgressStep("draft", "active", "正在保存拜访");
            if (!state.submission.attemptedPayload) {
                state.submission.attemptedPayload = buildSubmissionPayload();
                await persistDraft();
            }
            if (!receipt) {
                state.submission.syncState = "UNKNOWN";
                await persistDraft();
                receipt = normalizeResponse(await requestJson("/submissions", {
                    method: "POST", headers: { "X-Submission-Key": state.submission.submissionKey },
                    body: state.submission.attemptedPayload, timeout: 30000
                }));
                if (!receipt?.id) throw new Error("未取得服务器回执，请重试确认");
                mergeSubmissionReceipt(receipt);
            }
            state.submission.syncState = "PENDING";
            await persistDraft();
            renderBusinessLock();
            setProgressStep("draft", "done");
            if (receipt.status === "SUBMITTED") {
                showSuccess(receipt); void supplementCurrentEvidence(); return;
            }
            activeStep = MEDIA.photo;
            if (!state.submission.photos.some(photo => photo.uploadState === "UPLOADED")) {
                const photo = state.submission.photos.find(item => photoFile(item.photoId) && ![413, 415].includes(item.uploadErrorStatus));
                if (!photo) throw new Error("现场照片尚未收到，请从相册重新选择");
                const mayExistBefore = photo.mayExistRemotely === true;
                photo.uploadState = "UPLOADING";
                photo.mayExistRemotely = true;
                await persistDraft();
                renderPhotos();
                setProgressStep(MEDIA.photo, "active", "正在保存现场照片");
                try {
                    const uploaded = normalizeResponse(await uploadMedia(`photos/${encodeURIComponent(photo.photoId)}`,
                        photoFile(photo.photoId), "正在保存现场照片", { captureSource: photo.captureSource }));
                    Object.assign(photo, uploaded, { uploadState: "UPLOADED", mayExistRemotely: true });
                    state.submission.uploadedMedia = [...new Set([...state.submission.uploadedMedia, MEDIA.photo])];
                } catch (error) {
                    photo.uploadState = optionalUploadOutcome(error) === "UNKNOWN" ? "UNKNOWN" : "ERROR";
                    photo.mayExistRemotely = mayExistBefore || optionalUploadOutcome(error) === "UNKNOWN";
                    photo.errorMessage = errorMessage(error, "照片尚未确认，可重试");
                    photo.uploadErrorStatus = error.status || null;
                    throw error;
                } finally { renderPhotos(); await persistDraft(); }
            }
            setProgressStep(MEDIA.photo, "done");
            setProgressStep(MEDIA.wechat, "skipped", "选填证据独立同步");
            setProgressStep(MEDIA.audio, "skipped", "选填证据独立同步");
            activeStep = "complete";
            setProgressStep("complete", "active", "正在确认打卡");
            state.submission.syncState = "UNKNOWN";
            await persistDraft();
            const completed = normalizeResponse(await requestJson(
                `/submissions/${encodeURIComponent(state.submission.serverId)}/complete`, {
                    method: "POST", headers: { "X-Submission-Key": state.submission.submissionKey }, timeout: 30000
                }));
            if (!completed?.id || String(completed.id) !== String(state.submission.serverId)
                    || completed.status !== "SUBMITTED"
                    || (completed.clientSubmissionId && completed.clientSubmissionId !== state.submission.clientSubmissionId)) {
                throw new Error("提交结果尚未确认，请点击重试查询");
            }
            mergeSubmissionReceipt(completed);
            setProgressStep("complete", "done", "提交完成");
            state.submission.pendingWechat = Boolean(state.files.wechat
                && !state.submission.uploadedMedia.includes(MEDIA.wechat));
            showSuccess(completed, { skippedAudioCount: state.submission.audioSegments
                .filter((segment) => segment.uploadState !== "UPLOADED").length,
                skippedWechatOutcome: state.submission.pendingWechat ? "NOT_ATTEMPTED" : null });
            void supplementCurrentEvidence();
        } catch (error) {
            // 网络中断只表示客户端没有收到响应。先读原回执，避免已完成的打卡一直被标为失败。
            if (optionalUploadOutcome(error) === "UNKNOWN"
                    && (state.submission.attemptedPayload || state.submission.serverId)) {
                try {
                    const confirmed = await lookupSubmissionReceipt();
                    if (confirmed?.status === "SUBMITTED") {
                        showSuccess(confirmed);
                        void supplementCurrentEvidence();
                        return;
                    }
                } catch (_) { /* 保留同一提交ID和文件，等待网络恢复；不伪造成功。 */ }
            }
            if (!state.submission.serverId && [400, 422].includes(error.status)) {
                state.submission.attemptedPayload = null;
                state.submission.businessLocked = false;
                state.submission.syncRequested = false;
                state.submission.syncState = "NEEDS_ACTION";
            } else if ([400, 401, 403, 409, 413, 415, 422].includes(error.status)) {
                state.submission.syncRequested = false;
                state.submission.syncState = "NEEDS_ACTION";
                if (activeStep === MEDIA.photo && error.status === 413) {
                    state.submission.mediaUploadAttempts = state.submission.mediaUploadAttempts.filter(kind => kind !== MEDIA.photo);
                }
            } else {
                state.submission.syncRequested = true;
                state.submission.syncState = "UNKNOWN";
            }
            setProgressStep(activeStep, "error", "已保留，可继续重试");
            $("#progress-detail").textContent = errorMessage(error, "同步未完成");
            showError(errorMessage(error, "同步未完成，可在待同步记录中重试"));
            await persistDraft();
        } finally {
            state.submitting = false;
            setFormsDisabled(false);
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
            segment.uploadState = error.status === 413 ? "TOO_LARGE"
                : segment.mayExistRemotely ? "UNKNOWN" : "ERROR";
            segment.uploadErrorStatus = error.status || null;
            segment.errorMessage = optionalUploadFailureMessage(
                error, "录音", segment.mayExistRemotely);
            setProgressStep(MEDIA.audio, "error", error.status === 413 ? "录音文件过大" : "录音待补传");
            $("#progress-detail").textContent = segment.errorMessage;
            renderAudioSegments();
            renderUploadedBadges();
            persistDraft();
            throw error;
        }
    }

    function uploadMedia(kind, file, progressTitle, optionalFormFields = {}, uploadOptions = {}) {
        const targetSubmissionId = uploadOptions.submissionId || state.submission.serverId;
        const targetSubmissionKey = uploadOptions.submissionKey || state.submission.submissionKey;
        return new Promise((resolve, reject) => {
            if (kind.startsWith("audio/") && !audioFileSizeAllowed(file)) {
                const error = new Error(friendlyHttpError(413));
                error.status = 413;
                error.uploadOutcome = "REJECTED";
                reject(error);
                return;
            }
            const optionalDeadlineMs = Number(uploadOptions.optionalDeadlineMs);
            const hasOptionalDeadline = Number.isFinite(optionalDeadlineMs);
            const remainingOptionalMs = hasOptionalDeadline
                ? Math.min(optionalDeadlineMs - Date.now(), OPTIONAL_MEDIA_UPLOAD_MAX_MS) : null;
            if (hasOptionalDeadline && remainingOptionalMs <= 0) {
                const error = new Error("选填证据尚未上传，可稍后重试。");
                error.code = "OPTIONAL_MEDIA_BUDGET_EXHAUSTED";
                error.uploadOutcome = "NOT_ATTEMPTED";
                reject(error);
                return;
            }
            const xhr = new XMLHttpRequest();
            const formData = new FormData();
            let settled = false;
            let optionalTimeoutId = null;
            let idleTimeoutId = null;
            let uploadedBytes = 0;
            const settle = (callback, value) => {
                if (settled) return false;
                settled = true;
                window.clearTimeout(optionalTimeoutId);
                window.clearTimeout(idleTimeoutId);
                callback(value);
                return true;
            };
            const rejectUpload = (error, outcome = "UNKNOWN") => {
                if (!error.uploadOutcome) error.uploadOutcome = outcome;
                settle(reject, error);
            };
            const interruptOptionalUpload = (message, code) => {
                if (settled) return;
                const error = new Error(message);
                error.code = code;
                rejectUpload(error, "UNKNOWN");
                try { xhr.abort(); } catch (_) { /* The connection may already have been reclaimed. */ }
            };
            const refreshIdleTimeout = () => {
                if (!hasOptionalDeadline || settled) return;
                window.clearTimeout(idleTimeoutId);
                idleTimeoutId = window.setTimeout(() => interruptOptionalUpload(
                    "选填证据上传长时间没有进展，已暂停；打卡已保留，可稍后查询重试",
                    "OPTIONAL_MEDIA_IDLE_TIMEOUT"), OPTIONAL_MEDIA_IDLE_TIMEOUT_MS);
            };
            formData.append("file", file, file.name || kind);
            Object.entries(optionalFormFields).forEach(([name, value]) => {
                if (value !== null && value !== undefined && value !== "") {
                    formData.append(name, String(value));
                }
            });
            xhr.open("PUT",
                `${API_BASE}/submissions/${encodeURIComponent(targetSubmissionId)}/media/${kind}`,
                true);
            xhr.withCredentials = true;
            // 必填照片超时后查回执恢复，避免移动端半开连接永远挂住。
            xhr.timeout = hasOptionalDeadline ? Math.max(1, remainingOptionalMs) : 90000;
            xhr.setRequestHeader("Accept", "application/json");
            xhr.setRequestHeader("X-Submission-Key", targetSubmissionKey);
            if (xhr.upload) {
                xhr.upload.addEventListener("progress", (event) => {
                    if (Number.isFinite(event.loaded) && event.loaded > uploadedBytes) {
                        uploadedBytes = event.loaded;
                        refreshIdleTimeout();
                    }
                    if (!event.lengthComputable || event.total <= 0) return;
                    const percent = Math.min(99, Math.round((event.loaded / event.total) * 100));
                    const title = progressTitle || progressTitleForMedia(kind);
                    if (!uploadOptions.background) $("#progress-title").textContent = `${title} ${percent}%`;
                });
            }
            xhr.addEventListener("load", () => {
                const payload = parseResponsePayload(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) {
                    if (!validMediaReceipt(payload, kind, targetSubmissionId)) {
                        rejectUpload(new Error("上传结果尚未确认，文件已保留，请重试查询"), "UNKNOWN");
                        return;
                    }
                    settle(resolve, payload);
                    return;
                }
                const error = new Error(xhr.status === 413 ? friendlyHttpError(413)
                    : extractApiMessage(payload) || friendlyHttpError(xhr.status));
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
                optionalTimeoutId = window.setTimeout(() => interruptOptionalUpload(
                    "选填证据上传已达到时限；打卡已保留，可稍后查询重试",
                    "OPTIONAL_MEDIA_UPLOAD_TIMEOUT"), Math.max(1, remainingOptionalMs));
                refreshIdleTimeout();
            }
            try {
                xhr.send(formData);
            } catch (error) {
                rejectUpload(error, "NOT_ATTEMPTED");
            }
        });
    }

    function validMediaReceipt(payload, kind, submissionId) {
        const receipt = normalizeResponse(payload);
        const [mediaKind, segmentId] = kind.split("/");
        if (mediaKind === "photos") return Boolean(receipt && String(receipt.id) === String(submissionId)
            && receipt.kind === MEDIA.photo && String(receipt.photoId) === decodeURIComponent(segmentId));
        return Boolean(receipt && String(receipt.id) === String(submissionId)
            && receipt.kind === mediaKind && (!segmentId || String(receipt.segmentId) === decodeURIComponent(segmentId)));
    }

    function optionalUploadOutcome(error) {
        if (error?.status === 413) return "REJECTED";
        if (["REJECTED", "NOT_ATTEMPTED", "UNKNOWN"].includes(error?.uploadOutcome)) {
            return error.uploadOutcome;
        }
        if (Number.isFinite(error?.status) && error.status >= 400 && error.status < 500) {
            return "REJECTED";
        }
        return "UNKNOWN";
    }

    function optionalUploadFailureMessage(error, label, mayExistRemotely = false) {
        if (error?.status === 413) return `${label}文件过大，请换较小文件；重试同一文件无效，不影响打卡`;
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
            const value = JSON.parse(text);
            return value && typeof value === "object" ? value : null;
        } catch (_) {
            // 网关 HTML / 纯文本错误不是业务提示，绝不能把服务器源码展示给销售。
            return null;
        }
    }

    function friendlyHttpError(status) {
        if (status === 401) return "身份已失效，请重新验证后重试";
        if (status === 403) return "当前身份无法操作，请联系管理员";
        if (status === 413) return "文件过大，请换较小文件；重试同一文件无效";
        if (status === 415) return "文件格式无法处理，请重新选择";
        if (status === 429) return "操作较多，请稍后重试";
        if (status >= 500) return "服务暂时不可用，内容已保留，可重试";
        return "本次操作未完成，内容已保留，请检查后重试";
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
        const note = $(`#${scope}-location-note`)?.value.trim();
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
        valid = requireValue(state.visit.customerName.trim(), "customer-name", "请输入客户姓名。") && valid;
        valid = requireValue(state.visit.visitResult.trim(), "visit-result", "请填写拜访结果。") && valid;
        const photos = state.submission.photos || [];
        if (!photos.some(photo => photo.uploadState === "UPLOADED" || (photoFile(photo.photoId) && ![413, 415].includes(photo.uploadErrorStatus)))) {
            setFieldError("storefront-photo", "请添加至少一张现场照片");
            valid = false;
        }
        if (photos.length > 9) {
            setFieldError("storefront-photo", "每次拜访最多添加9张照片");
            valid = false;
        }
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
                : "请选择地图门店或手动录入。") && valid;
        if (state.store.sourceMode === "POI" && !cleanText(state.store.sourcePoiToken)) {
            setFieldError("store-source", "高德候选凭证已失效，请重新搜索并选择门店。");
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
        historyView?.invalidateList();
        state.editingEvidence = false;
        const skippedAudioCount = Number(optionalMedia.skippedAudioCount) || 0;
        const uncertainAudioCount = Math.min(skippedAudioCount,
            Number(optionalMedia.uncertainAudioCount) || 0);
        const definiteAudioCount = Math.max(0, skippedAudioCount - uncertainAudioCount);
        const skippedWechatOutcome = cleanText(optionalMedia.skippedWechatOutcome).toUpperCase();
        state.submitting = false;
        state.completed = true;
        state.submission.status = completed.status || "SUBMITTED";
        state.submission.submittedAt = completed.submittedAt || state.submission.submittedAt;
        state.submission.supplementUntil = completed.supplementUntil || state.submission.supplementUntil;
        state.submission.syncRequested = false;
        state.submission.syncState = "CONFIRMED";
        $("#upload-panel").hidden = true;
        $("#visit-panel").hidden = true;
        $("#store-panel").hidden = true;
        $(".tabs").hidden = true;
        $("#restore-notice").hidden = true;
        $("#success-submission-id").textContent = completed.id || state.submission.serverId;
        $("#success-submitted-at").textContent = completed.submittedAt
            ? formatDateTime(completed.submittedAt)
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
            mediaMessages.push(`选填的${definiteItems.join("和")}待补传，可在“我的记录”重试。`);
        }
        if (uncertainItems.length) {
            mediaMessages.push(`选填的${uncertainItems.join("和")}上传结果未确认，服务端可能已收到。`);
        }
        mediaNote.textContent = definiteItems.length || uncertainItems.length
            ? mediaMessages.join("") : "";
        mediaNote.hidden = definiteItems.length === 0 && uncertainItems.length === 0;
        $("#success-panel").hidden = false;
        state.submission.pendingWechat = Boolean(state.files.wechat
            && !state.submission.uploadedMedia.includes(MEDIA.wechat));
        void persistDraft();
        deleteLocalMedia("photo");
        state.submission.photos.filter(photo => photo.uploadState === "UPLOADED")
            .forEach(photo => deleteLocalMedia(`photo:${photo.photoId}`));
        if (state.submission.uploadedMedia.includes(MEDIA.wechat)) deleteLocalMedia("wechat");
        state.submission.audioSegments.filter((segment) => segment.uploadState === "UPLOADED")
            .forEach((segment) => deleteLocalMedia(`audio:${segment.segmentId}`));
        renderSuccessEvidence();
        syncAppScreen();
        $("#success-panel").scrollIntoView({ behavior: "smooth", block: "center" });
    }

    function renderSuccessEvidence() {
        const put = (selector, text) => { const element = $(selector); if (element) element.textContent = text; };
        put("#success-store-name", state.visit.selectedStore?.name || "本次拜访");
        put("#success-identity", [state.visit.city, state.identity?.salespersonName].filter(Boolean).join(" · "));
        const savedPhotos = state.submission.photos.filter(photo => photo.uploadState === "UPLOADED").length;
        const pendingPhotos = state.submission.photos.length - savedPhotos;
        put("#success-photo-status", `${savedPhotos} 张已收到${pendingPhotos ? ` · ${pendingPhotos} 张待补传` : ""}`);
        put("#success-supplement-until", state.submission.supplementUntil
            ? `补传截止 ${formatDateTime(state.submission.supplementUntil)}` : "");
        const list = $("#success-audio-list");
        if (list) {
            list.replaceChildren();
            state.submission.audioSegments.filter(segment => !["SKIPPED", "DISCARDED"].includes(segment.uploadState)).forEach((segment, index) => {
                const row = document.createElement("div"); row.className = "receipt-media-row";
                const title = document.createElement("span");
                title.textContent = `现场录音${state.submission.audioSegments.length > 1 ? ` ${index + 1}` : ""}`
                    + (segment.clientDurationMs ? ` · ${formatDuration(segment.clientDurationMs)}` : "");
                const status = document.createElement("strong");
                status.textContent = segment.uploadState === "UPLOADED" ? "已收到"
                    : segment.uploadState === "UPLOADING" ? "上传中"
                        : segment.uploadState === "UNKNOWN" ? "结果待核对"
                            : segment.uploadState === "TOO_LARGE" ? "文件过大，请更换" : "待补传";
                status.className = segment.uploadState === "UPLOADED" ? "is-success" : "is-warning";
                row.append(title, status); list.appendChild(row);
            });
            list.hidden = !list.children.length;
        }
        const retry = $("#success-retry-button");
        if (retry) {
            retry.hidden = !hasPendingEvidence();
            retry.disabled = state.evidenceSyncIds.has(state.submission.clientSubmissionId);
            retry.textContent = retry.disabled ? "正在核对与同步…"
                : state.submission.photos.some(photo => [413, 415].includes(photo.uploadErrorStatus) || photo.uploadState === "NEEDS_FILE")
                    || state.submission.wechatUploadErrorStatus === 413 || state.submission.audioSegments.some(segment =>
                    ["TOO_LARGE", "NEEDS_FILE"].includes(segment.uploadState)) ? "处理待补附件"
                    : state.submission.audioSegments.some(segment => segment.uploadState === "UNKNOWN") ? "核对录音结果" : "重试附件";
            if (state.submission.supplementUntil && Date.parse(state.submission.supplementUntil) <= Date.now()) {
                retry.hidden = true;
            }
        }
        const note = $("#success-media-note");
        if (note && !hasPendingEvidence()) { note.hidden = true; note.textContent = ""; }
        const wechat = $("#success-wechat-status");
        if (wechat) {
            wechat.hidden = !state.submission.pendingWechat && !state.submission.uploadedMedia.includes(MEDIA.wechat);
            const row = $("#success-wechat-row");
            if (row) row.hidden = wechat.hidden;
            wechat.textContent = state.submission.pendingWechat ? "企微截图 · 待补传" : "企微截图 · 已收到";
        }
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
        renderPhotos();
        $("#wechat-screenshot").disabled = state.completed && state.submission.uploadedMedia.includes(MEDIA.wechat);
        setStableText($("#submit-visit-button"), state.preparingSubmission ? "正在定位并保存…"
            : state.completed ? "补传附件" : "提交拜访");
        $("#identity-switch").disabled = state.submitting || locked;
        renderFlowSteps();
    }

    function isBusinessLocked() {
        return state.submission.businessLocked === true
            || Boolean(state.submission.serverId)
            || Boolean(state.submission.attemptedPayload);
    }

    function setFormsDisabled(disabled) {
        setStableText($("#submit-visit-button"), disabled
            ? state.preparingSubmission ? "正在定位并保存…" : "正在保存…"
            : state.completed ? "补传附件" : "提交拜访");
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

    function startNewSubmission({ preserveCurrent = true } = {}) {
        // 保留前一条可恢复记录，“下一家”只切换当前表单。
        if (preserveCurrent && currentStorageOwner() && state.visit.selectedStore) void persistDraft();
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
            state.visit.city = state.identity.city;
            state.store.city = state.identity.city;
        }
        state.submission = freshSubmission();
        renderRecordingRecoveries();
        state.submitting = false;
        state.completed = false;
        state.editingEvidence = false;
        state.restoredAt = null;
        state.restoredLocalMediaIds = [];
        removeStoredDraft();
        setFormsDisabled(false);
        $("#visit-form").reset();
        $("#store-form").reset();
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
        scheduleInitialVisitLocationCapture();
        window.scrollTo({ top: 0, behavior: "smooth" });
    }

    async function discardDraft() {
        if (state.submitting || state.addingPhotos) return;
        if (recordingBusy()) {
            showRecordingNavigationError("请先结束录音，再放弃草稿。"); return;
        }
        const mayBeRemote = Boolean(state.submission.serverId || state.submission.attemptedPayload
            || state.submission.uploadedMedia.length || state.submission.mediaUploadAttempts.length);
        const warning = mayBeRemote
            ? "将先清理服务端草稿中的照片、截图和录音，再清除本机表单。未完成草稿记录仍会保留，确定继续吗？"
            : "确定放弃当前未提交的表单内容吗？";
        if (!window.confirm(warning)) return;
        const owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId;
        const button = $("#discard-draft-button"), originalLabel = button.textContent;
        const hadServerId = Boolean(state.submission.serverId);
        state.submitting = true; setFormsDisabled(true); button.disabled = true;
        let discarded = false;
        try {
            if (mayBeRemote) {
                setStableText(button, "正在核对草稿…");
                const receipt = await lookupSubmissionReceipt();
                if (owner !== currentStorageOwner() || draftId !== state.submission.clientSubmissionId) return;
                if (!receipt && hadServerId) throw new Error("暂未核实服务端草稿，文件和表单已保留，请稍后重试。");
                if (receipt?.status === "SUBMITTED") {
                    showSuccess(receipt);
                    throw new Error("这次拜访已经提交，原始记录和照片已保留。");
                }
                if (receipt) {
                    if (receipt.status !== "DRAFT" || !Array.isArray(receipt.photoIds)) {
                        throw new Error("草稿照片清单尚未核实，原文件已保留，请稍后重试。");
                    }
                    const photoIds = [...new Set(receipt.photoIds.filter(isUuidValue))];
                    const audioIds = [...new Set((receipt.audioSegmentIds || []).filter(isUuidValue))];
                    const cleanup = photoIds.map(photoId => ({label: "照片", run: () => requestJson(
                        `/submissions/${encodeURIComponent(receipt.id)}/media/photos/${encodeURIComponent(photoId)}`, {
                            method: "DELETE", headers: {"X-Submission-Key": state.submission.submissionKey}, timeout: 45000})}));
                    cleanup.push({label: "截图", run: () => deleteUploadedMedia(MEDIA.wechat)});
                    for (const segmentId of audioIds) cleanup.push({label: "录音", run: () => deleteAudioSegmentRemote(segmentId)});
                    // The legacy audio endpoint is idempotent and covers an old single-slot draft.
                    if (!audioIds.length && (receipt.uploadedMedia || []).includes(MEDIA.audio)) cleanup.push({label: "录音", run: () => deleteUploadedMedia(MEDIA.audio)});
                    for (let index = 0; index < cleanup.length; index++) {
                        if (owner !== currentStorageOwner() || draftId !== state.submission.clientSubmissionId) return;
                        setStableText(button, `清理${cleanup[index].label} ${index + 1}/${cleanup.length}…`);
                        await cleanup[index].run();
                    }
                }
            }
            await state.persistence;
            if (owner && window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.remove(owner, draftId);
            discarded = true;
            startNewSubmission({preserveCurrent: false});
            $("#restore-notice").hidden = true;
        } catch (error) {
            showError(errorMessage(error, "文件清理未完成，草稿已保留，请稍后重试。"));
            await persistDraft();
        } finally {
            if (!discarded) { state.submitting = false; setFormsDisabled(false); }
            button.disabled = false; setStableText(button, originalLabel);
        }
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

    function currentStorageOwner() {
        if (!state.identity?.authenticated || !state.identity.salespersonId) return null;
        return `${state.identity.tenantId || "same-origin"}:${state.identity.salespersonId}`;
    }

    function snapshotDraft() {
        return JSON.parse(JSON.stringify({ version: STORAGE_VERSION,
            savedAt: new Date().toISOString(), activeTab: state.activeTab, ui: state.ui,
            visit: state.visit, store: state.store, submission: state.submission,
            localMediaIds: [...state.files.photos.map(item => `photo:${item.photoId}`), state.files.wechat ? "wechat" : null,
                ...state.files.audio.map((item) => `audio:${item.segmentId}`)].filter(Boolean) }));
    }

    function draftFingerprint(snapshot) {
        // 定位回调和附近推荐会在后台变化，不应因此反复丢掉打开相机的用户手势。
        const { location: visitLocation, locationContext: visitContext, nearbyStores,
            directoryStores, directoryQuery, ...visit } = snapshot.visit;
        const { location: storeLocation, locationContext: storeContext, nearbyPois,
            poiSearchResults, poiSearchLookupStatus, ...store } = snapshot.store;
        return JSON.stringify({ visit, store, ui: snapshot.ui, submission: snapshot.submission,
            localMediaIds: snapshot.localMediaIds });
    }

    function renderDraftSaveStatus(text, failed = false) {
        if (text === "本机已保存" && [...state.unsavedMedia].some(key => key.startsWith(localMediaStorageKey("")))) {
            text = "附件未在本机保存 · 请保留手机原件";
            failed = true;
        }
        const element = $("#draft-save-status");
        if (element) {
            element.textContent = text;
            element.classList.toggle("is-warning", failed);
        }
    }

    function localMediaStorageKey(mediaId, owner = currentStorageOwner(), draftId = state.submission.clientSubmissionId) {
        return `${owner}/${draftId}/${mediaId}`;
    }

    function saveLocalMedia(mediaId, file) {
        const owner = currentStorageOwner();
        const draftId = state.submission.clientSubmissionId;
        if (!owner || !window.SalesCheckinDraftStore) return Promise.resolve(false);
        const snapshot = snapshotDraft();
        const mediaKey = localMediaStorageKey(mediaId, owner, draftId);
        const operation = state.persistence.then(async () => {
            await window.SalesCheckinDraftStore.save(owner, snapshot);
            await window.SalesCheckinDraftStore.saveMedia(owner, draftId, mediaId, file);
            state.unsavedMedia.delete(mediaKey);
            if (currentStorageOwner() === owner && state.submission.clientSubmissionId === draftId) {
                renderDraftSaveStatus("本机已保存");
            }
            return true;
        }).catch(() => {
            state.unsavedMedia.add(mediaKey);
            if (currentStorageOwner() === owner && state.submission.clientSubmissionId === draftId) {
                renderDraftSaveStatus("附件未在本机保存 · 请保留手机原件", true);
                const message = "本机空间不足或浏览器禁止保存。请保留手机原文件；当前页面仍可上传，不影响先提交拜访。";
                if (mediaId.startsWith("audio:")) showAudioSelectionNotice(message);
                else showError(message);
            }
            return false;
        });
        state.persistence = operation;
        state.pendingMedia.add(operation);
        void operation.finally(() => state.pendingMedia.delete(operation));
        return operation;
    }

    function deleteLocalMedia(mediaId) {
        const owner = currentStorageOwner();
        const id = state.submission.clientSubmissionId;
        state.unsavedMedia.delete(localMediaStorageKey(mediaId, owner, id));
        if (!owner || !window.SalesCheckinDraftStore) return;
        state.persistence = state.persistence.then(() =>
            window.SalesCheckinDraftStore.removeMedia(owner, id, mediaId)).catch(() => false);
    }

    async function restoreOwnedDraft() {
        const owner = currentStorageOwner();
        if (!owner || owner === state.storageOwner || !window.SalesCheckinDraftStore) return;
        state.storageOwner = owner;
        try {
            await window.SalesCheckinDraftStore.prune();
            const records = await window.SalesCheckinDraftStore.list(owner);
            if (owner !== currentStorageOwner()) return;
            const current = hasRestoredDraft() && records.find(item =>
                item.snapshot.submission.clientSubmissionId === state.submission.clientSubmissionId);
            const record = current || (!hasRestoredDraft() && records.find((item) => !["SUBMITTED", "COMPLETED"].includes(item.snapshot.submission.status)
                && (item.snapshot.visit?.selectedStore || item.snapshot.visit?.customerName || item.snapshot.store?.name || item.snapshot.localMediaIds?.length)));
            if (record) await openSavedDraft(record, false);
            await recoverRecordingJournals();
        } catch (_) {
            renderDraftSaveStatus("本机保存不可用", true);
        }
    }

    async function openSavedDraft(record, display = true) {
        const owner = currentStorageOwner();
        if (!owner || record.owner !== owner || state.submitting || recordingBusy()) return false;
        const sequence = ++draftRestoreSequence;
        const current = draftFingerprint(snapshotDraft());
        const stillAllowed = () => sequence === draftRestoreSequence && owner === currentStorageOwner()
            && !state.submitting && !recordingBusy() && current === draftFingerprint(snapshotDraft());
        await state.persistence;
        if (!stillAllowed()) return false;
        const media = await window.SalesCheckinDraftStore.mediaFor(owner, record.snapshot.submission.clientSubmissionId);
        if (!stillAllowed()) return false;
        ["visit", "store"].forEach(scope => {
            cancelLocationCapture(scope);
            state.locationControllers[scope]?.abort();
            state.locationControllers[scope] = null;
        });
        Object.keys(state.files).forEach(resetLocalFile);
        restoreDraft(record.snapshot);
        renderRecordingRecoveries();
        state.completed = ["SUBMITTED", "COMPLETED"].includes(state.submission.status);
        for (const item of media) {
            const file = typeof window.File === "function"
                ? new File([item.file], item.filename, { type: item.file.type, lastModified: item.lastModified || 0 })
                : Object.assign(item.file, { name: item.filename, lastModified: item.lastModified || 0 });
            if (item.mediaId === "photo" || item.mediaId.startsWith("photo:")) {
                restorePhotoMedia(item, file);
            } else if (item.mediaId === "wechat") {
                state.files[item.mediaId] = file;
                renderImagePreview(item.mediaId, file);
            } else if (item.mediaId.startsWith("audio:")) {
                const segmentId = item.mediaId.slice(6);
                const segment = findAudioSegment(segmentId);
                if (!segment) continue;
                state.files.audio.push({ segmentId, file });
                if (["NEEDS_FILE", "ERROR"].includes(segment.uploadState)) segment.uploadState = "LOCAL";
                ensureAudioObjectUrl(segmentId, file);
            }
        }
        renderRestoredValues(); renderSelectedStore(); renderLocation("visit"); renderLocation("store");
        renderAudioSegments(); renderUploadedBadges(); renderBusinessLock();
        renderTab(state.activeTab);
        renderDraftSaveStatus(state.completed ? "已提交" : "已恢复本机记录");
        if (display) {
            $("#records-panel").hidden = true;
            $("#success-panel").hidden = true;
            $(".tabs").hidden = false;
            if (state.completed) showSuccess(state.submission);
            else { setFormsDisabled(false); showRestoreNotice(); }
        }
        await recoverRecordingJournals();
        return true;
    }

    function hasPendingEvidence(snapshot = snapshotDraft()) {
        const submission = snapshot.submission;
        return submission.audioSegments.some((segment) => !['UPLOADED', 'SKIPPED', 'DISCARDED'].includes(segment.uploadState))
            || (submission.photos || []).some(photo => photo.uploadState !== "UPLOADED")
            || submission.pendingWechat === true;
    }

    function mergeSubmissionReceipt(receipt) {
        if (!receipt?.id) return;
        if (receipt.clientSubmissionId && String(receipt.clientSubmissionId) !== String(state.submission.clientSubmissionId)) {
            throw new Error("回执与本次拜访不一致，原记录已保留");
        }
        if (state.submission.serverId && String(state.submission.serverId) !== String(receipt.id)) {
            throw new Error("回执编号不一致，请保留页面并联系管理员");
        }
        Object.assign(state.submission, { serverId: receipt.id,
            status: receipt.status || state.submission.status,
            createdAt: receipt.createdAt || state.submission.createdAt,
            submittedAt: receipt.submittedAt || state.submission.submittedAt,
            supplementUntil: receipt.supplementUntil || state.submission.supplementUntil });
        if (Array.isArray(receipt.uploadedMedia)) state.submission.uploadedMedia = [...receipt.uploadedMedia];
        mergePhotoReceipt(receipt);
        for (const id of receipt.audioSegmentIds || []) {
            const segment = findAudioSegment(id);
            if (segment) { segment.uploadState = "UPLOADED"; segment.mayExistRemotely = true; }
        }
        state.submission.pendingWechat = Boolean(state.files.wechat
            && !state.submission.uploadedMedia.includes(MEDIA.wechat));
        state.submission.businessLocked = true;
    }

    async function lookupSubmissionReceipt() {
        const id = state.submission.clientSubmissionId;
        const owner = currentStorageOwner();
        try {
            const receipt = normalizeResponse(await requestJson(
                `/submissions/by-client/${encodeURIComponent(id)}`, {
                    headers: { "X-Submission-Key": state.submission.submissionKey }, timeout: 15000
                }));
            if (id !== state.submission.clientSubmissionId || owner !== currentStorageOwner()) return null;
            if (!receipt?.id || !["DRAFT", "SUBMITTED"].includes(receipt.status)) {
                throw new Error("未取得有效提交回执，原记录已保留");
            }
            mergeSubmissionReceipt(receipt);
            return receipt;
        } catch (error) {
            if (error.status === 404) return null;
            throw error;
        }
    }

    function initPersonalHistory() {
        if (!window.SalesCheckinHistory || historyView) return;
        historyView = window.SalesCheckinHistory.init({
            requestJson, getIdentity: () => state.identity,
            getLocalRecords: async () => {
                const owner = currentStorageOwner();
                if (!owner || !window.SalesCheckinDraftStore) return [];
                if (state.visit.selectedStore || state.store.name) await persistDraft();
                const rows = await window.SalesCheckinDraftStore.list(owner);
                return owner === currentStorageOwner() ? rows : [];
            },
            onResumeLocal: async (record, context = {}) => {
                if (state.submitting || recordingBusy()) return false;
                if (!await openSavedDraft(record)) return false;
                if (context.receipt) mergeSubmissionReceipt(context.receipt);
                historyView.close();
                await recoverInterruptedSubmission();
                return true;
            },
            onBack: () => syncAppScreen(),
            onViewChange: (view) => { if (view === "closed") draftRestoreSequence++; historyScreen = view; syncAppScreen(); },
            onAuthRequired: () => { historyView?.resetIdentity(); state.identity = null; renderIdentityState(); }
        });
    }

    async function showMyRecords(pendingOnly = false) {
        if (!currentStorageOwner()) return;
        if (state.submitting || recordingBusy()) {
            showError(state.submitting ? "正在提交，请稍候" : "请先结束并保存录音");
            return;
        }
        initPersonalHistory();
        if (!historyView) { showError("记录页面暂未加载，请刷新后重试"); return; }
        pauseAllAudioPreviews();
        await historyView.open({ pendingOnly });
    }

    function openEvidenceEditor() {
        if (!state.completed) return;
        state.editingEvidence = true;
        $("#success-panel").hidden = true;
        state.activeTab = "visit";
        state.ui.visitStep = 3;
        renderTab("visit");
        $(".tabs").hidden = true;
        $("#restore-notice strong").textContent = "本次拜访已提交";
        $("#restore-message").textContent = state.submission.supplementUntil
            ? `可补充照片、录音和截图，截止 ${formatDateTime(state.submission.supplementUntil)}` : "可补充照片、录音和截图，以服务端补传期限为准";
        $("#restore-notice").hidden = false;
        $("#discard-draft-button").hidden = true;
        setFormsDisabled(false);
        renderBusinessLock();
        syncAppScreen();
    }

    async function supplementCurrentEvidence() {
        if (!state.completed || !state.submission.serverId) return;
        const owner = currentStorageOwner();
        const snapshot = snapshotDraft();
        const id = snapshot.submission.clientSubmissionId;
        if (!owner || state.evidenceSyncIds.has(id) || !hasPendingEvidence(snapshot)) return;
        state.evidenceSyncIds.add(id);
        pauseAllAudioPreviews();
        renderSuccessEvidence();
        const submission = snapshot.submission;
        // 捕获原拜访的文件和凭据。销售点“下一家”后绝不能把补证写入新拜访。
        const files = { wechat: state.files.wechat,
            photos: new Map(state.files.photos.map(item => [item.photoId, item.file])),
            audio: new Map(state.files.audio.map((item) => [item.segmentId, item.file])) };
        const requestOptions = { headers: { "X-Submission-Key": submission.submissionKey }, timeout: 15000 };
        const receiptPath = `/submissions/by-client/${encodeURIComponent(id)}`;
        const sameOwner = () => currentStorageOwner() === owner;
        const stillCurrent = () => sameOwner() && state.submission.clientSubmissionId === id;
        const save = async () => {
            snapshot.savedAt = new Date().toISOString();
            let toSave = snapshot;
            if (stillCurrent()) {
                // 同步期间销售可以另加一段录音；只更新本次捕获的分段，不覆盖新附件。
                const updated = new Map(submission.audioSegments.map(segment => [segment.segmentId, segment]));
                state.submission.audioSegments = state.submission.audioSegments.map(segment => updated.get(segment.segmentId) || segment);
                const updatedPhotos = new Map((submission.photos || []).map(photo => [photo.photoId, photo]));
                state.submission.photos = state.submission.photos.map(photo => updatedPhotos.get(photo.photoId) || photo);
                renderPhotos();
                state.submission.uploadedMedia = submission.uploadedMedia;
                state.submission.pendingWechat = submission.pendingWechat;
                state.submission.wechatUploadErrorStatus = submission.wechatUploadErrorStatus;
                renderAudioSegments(); renderUploadedBadges();
                renderSuccessEvidence();
                toSave = snapshotDraft();
            }
            if (window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.save(owner, toSave);
        };
        const confirmReceived = async (kind, segmentId = null) => {
            if (!sameOwner() || navigator.onLine === false) return false;
            try {
                const received = normalizeResponse(await requestJson(receiptPath, requestOptions));
                if (!sameOwner() || String(received?.id) !== String(submission.serverId)
                        || received.status !== "SUBMITTED"
                        || (received.clientSubmissionId && received.clientSubmissionId !== id)) return false;
                return segmentId ? (kind === MEDIA.photo ? received.photoIds || [] : received.audioSegmentIds || []).includes(segmentId)
                    : (received.uploadedMedia || []).includes(kind);
            } catch (_) { return false; }
        };
        try {
            await state.persistence;
            const receipt = normalizeResponse(await requestJson(receiptPath, requestOptions));
            if (!sameOwner()) return;
            if (String(receipt?.id) !== String(submission.serverId) || receipt.status !== "SUBMITTED"
                    || (receipt.clientSubmissionId && receipt.clientSubmissionId !== id)) throw new Error("未查询到本次拜访的完成回执");
            submission.uploadedMedia = Array.isArray(receipt.uploadedMedia) ? receipt.uploadedMedia : submission.uploadedMedia;
            submission.photos = window.SalesCheckinPhotos.merge(submission.photos || [], receipt);
            submission.audioSegments.forEach((segment) => {
                if ((receipt.audioSegmentIds || []).includes(segment.segmentId)) {
                    segment.uploadState = "UPLOADED"; segment.uploadErrorStatus = null; segment.errorMessage = "";
                }
            });
            if (submission.uploadedMedia.includes(MEDIA.wechat)) submission.pendingWechat = false;
            await save();
            // 补传已到期仍允许只读核对已经收到的附件，绝不再次上传。
            if (receipt.supplementUntil && Date.parse(receipt.supplementUntil) <= Date.now()) {
                if (hasPendingEvidence(snapshot)) throw new Error("补传时间已结束，请联系管理员");
                return;
            }
            const options = {
                submissionId: submission.serverId, submissionKey: submission.submissionKey, background: true };
            for (const photo of submission.photos || []) {
                if (!sameOwner()) return;
                if (photo.uploadState === "UPLOADED" || [413, 415].includes(photo.uploadErrorStatus)) continue;
                const file = files.photos.get(photo.photoId);
                if (!file) { photo.uploadState = "NEEDS_FILE"; continue; }
                const mayExistBefore = photo.mayExistRemotely === true;
                photo.uploadState = "UPLOADING"; photo.mayExistRemotely = true;
                await save();
                try {
                    const response = normalizeResponse(await uploadMedia(`photos/${encodeURIComponent(photo.photoId)}`,
                        file, "补传现场照片", { captureSource: photo.captureSource }, options));
                    Object.assign(photo, response, { uploadState: "UPLOADED", mayExistRemotely: true, errorMessage: "" });
                    if (window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.removeMedia(owner, id, `photo:${photo.photoId}`);
                } catch (error) {
                    if (optionalUploadOutcome(error) === "UNKNOWN" && await confirmReceived(MEDIA.photo, photo.photoId)) {
                        photo.uploadState = "UPLOADED"; photo.errorMessage = "";
                    } else {
                        photo.uploadState = optionalUploadOutcome(error) === "UNKNOWN" ? "UNKNOWN" : "ERROR";
                        photo.mayExistRemotely = mayExistBefore || optionalUploadOutcome(error) === "UNKNOWN";
                        photo.uploadErrorStatus = error.status || null;
                        photo.errorMessage = errorMessage(error, "照片待补传");
                    }
                }
                await save();
            }
            if (files.wechat && !submission.uploadedMedia.includes(MEDIA.wechat) && submission.wechatUploadErrorStatus !== 413) {
                try {
                    await uploadMedia(MEDIA.wechat, files.wechat, "补传截图", {},
                        {...options, optionalDeadlineMs: Date.now() + OPTIONAL_MEDIA_UPLOAD_MAX_MS});
                    submission.uploadedMedia.push(MEDIA.wechat);
                    submission.pendingWechat = false;
                    submission.wechatUploadErrorStatus = null;
                    state.unsavedMedia.delete(localMediaStorageKey("wechat", owner, id));
                    if (window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.removeMedia(owner, id, "wechat");
                } catch (error) {
                    if (optionalUploadOutcome(error) === "UNKNOWN" && await confirmReceived(MEDIA.wechat)) {
                        submission.uploadedMedia.push(MEDIA.wechat);
                        submission.pendingWechat = false;
                        submission.wechatUploadErrorStatus = null;
                    } else {
                        submission.pendingWechat = true;
                        submission.wechatUploadErrorStatus = error.status || null;
                    }
                }
                await save();
            }
            for (const segment of submission.audioSegments) {
                if (!sameOwner()) return;
                if (["UPLOADED", "SKIPPED", "DISCARDED", "TOO_LARGE"].includes(segment.uploadState)) continue;
                const file = files.audio.get(segment.segmentId);
                if (!file) { segment.uploadState = "NEEDS_FILE"; continue; }
                segment.uploadState = "UPLOADING";
                segment.mayExistRemotely = true;
                await save();
                try {
                    const response = normalizeResponse(await uploadMedia(`audio/${encodeURIComponent(segment.segmentId)}`,
                        file, "补传录音", {
                            captureSource: normalizeAudioCaptureSource(segment.captureSource),
                            clientStartedAt: normalizeOptionalInstant(segment.clientStartedAt),
                            clientDurationMs: normalizePositiveDurationMs(segment.clientDurationMs),
                            fileLastModifiedAt: normalizeOptionalInstant(segment.fileLastModifiedAt)
                        }, {...options, optionalDeadlineMs: Date.now() + OPTIONAL_MEDIA_UPLOAD_MAX_MS}));
                    if (response?.segmentId && response.segmentId !== segment.segmentId) throw new Error("录音回执不一致");
                    segment.uploadState = "UPLOADED"; segment.mayExistRemotely = true; segment.errorMessage = "";
                    state.unsavedMedia.delete(localMediaStorageKey(`audio:${segment.segmentId}`, owner, id));
                    if (window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.removeMedia(owner, id, `audio:${segment.segmentId}`);
                } catch (error) {
                    if (optionalUploadOutcome(error) === "UNKNOWN" && await confirmReceived(MEDIA.audio, segment.segmentId)) {
                        segment.uploadState = "UPLOADED";
                        segment.uploadErrorStatus = null;
                        segment.errorMessage = "";
                        if (window.SalesCheckinDraftStore) await window.SalesCheckinDraftStore.removeMedia(owner, id, `audio:${segment.segmentId}`);
                    } else {
                        segment.uploadState = error.status === 413 ? "TOO_LARGE"
                            : optionalUploadOutcome(error) === "UNKNOWN" ? "UNKNOWN" : "ERROR";
                        segment.uploadErrorStatus = error.status || null;
                        if (error.status === 413) segment.mayExistRemotely = false;
                        segment.errorMessage = segment.uploadState === "UNKNOWN"
                            ? "尚未核实录音结果，原文件已保留；点击核对结果" : optionalUploadFailureMessage(error, "录音");
                    }
                }
                await save();
            }
            await save();
            if (stillCurrent()) {
                renderDraftSaveStatus(hasPendingEvidence(snapshot) ? "已提交 · 证据待补传" : "已提交 · 证据已保存");
                const note = $("#success-media-note");
                if (note) {
                    note.hidden = !hasPendingEvidence(snapshot);
                    note.textContent = submission.wechatUploadErrorStatus === 413
                        || submission.audioSegments.some(segment => segment.uploadState === "TOO_LARGE")
                        ? "打卡已完成；选填文件过大，请在我的记录中换小文件，同一文件无需重试"
                        : "打卡已完成，选填证据可在我的记录中继续补传";
                }
            }
        } catch (error) {
            if (stillCurrent()) renderDraftSaveStatus(errorMessage(error, "已提交 · 证据待补传"), true);
        } finally {
            state.evidenceSyncIds.delete(id);
            historyView?.invalidateList();
            if (stillCurrent()) renderSuccessEvidence();
        }
    }

    async function preparePhotoPicker(event) {
        if (!photoAppendAllowed() || state.submission.photos.length >= 9) { event.preventDefault(); return; }
        syncStateFromForm();
        const fingerprint = draftFingerprint(snapshotDraft());
        // 常规路径直接保留本次原生点击/用户手势；避免异步 input.click 在鸿蒙等浏览器失效。
        if ((state.lastSavedFingerprint === fingerprint && !state.pendingMedia.size)
                || state.pickerUnsavedFingerprint === fingerprint) {
            state.pickerUnsavedFingerprint = "";
            emitClientDiagnostic("PHOTO_PICKER_OPEN", "STARTED");
            return;
        }
        event.preventDefault();
        const saved = await persistDraft();
        await Promise.all([...state.pendingMedia]);
        if (!photoAppendAllowed() || state.submission.photos.length >= 9) return;
        if (!saved) {
            state.pickerUnsavedFingerprint = draftFingerprint(snapshotDraft());
            renderDraftSaveStatus("本机未保存 · 再点拍照继续，请保留页面", true);
            return;
        }
        if (navigator.userActivation?.isActive === true) event.target.click();
        else renderDraftSaveStatus("表单已保存，请再点一次拍照");
    }

    function resumeActiveVisit() {
        if (document.visibilityState === "hidden") return;
        window.clearTimeout(state.resumeTimer);
        state.resumeTimer = window.setTimeout(async () => {
            if (!initialized || state.submitting || recordingBusy() || !currentStorageOwner()) return;
            await recoverInterruptedSubmission();
            if (!isBusinessLocked() && !state.completed && !state.storeDirectoryController) {
                const scope = state.activeTab === "store" ? "store" : "visit";
                const recentlyReceived = Date.parse(state[scope].location?.receivedAt || "");
                const busy = ["CAPTURING", "RESOLVING"].includes(state[scope].locationContext?.geocodeStatus);
                if (!busy && !(Number.isFinite(recentlyReceived) && Date.now() - recentlyReceived < 30000)) {
                    void captureLocation(scope);
                }
            }
        }, 500);
    }

    async function recoverInterruptedSubmission() {
        if (!currentStorageOwner() || state.submitting || recordingBusy() || navigator.onLine === false) return;
        if (!state.submission.attemptedPayload && !state.submission.serverId) return;
        if (recoveryInFlight) return recoveryInFlight;
        const owner = currentStorageOwner();
        const id = state.submission.clientSubmissionId;
        recoveryInFlight = (async () => {
            renderDraftSaveStatus("正在核对上次提交结果…");
            try {
                const receipt = await lookupSubmissionReceipt();
                if (owner !== currentStorageOwner() || id !== state.submission.clientSubmissionId) return;
                if (receipt?.status === "SUBMITTED") {
                    showSuccess(receipt);
                    if (hasPendingEvidence()) void supplementCurrentEvidence();
                } else if (state.submission.syncRequested && !state.completed) {
                    // 仅续传用户已经明确点过提交的记录，不自动提交普通草稿。
                    await submitVisit({ preventDefault() {} });
                } else {
                    showRestoreNotice();
                    renderDraftSaveStatus("上次记录已恢复，可继续完成提交");
                }
            } catch (error) {
                if (owner !== currentStorageOwner() || id !== state.submission.clientSubmissionId) return;
                renderDraftSaveStatus("提交结果暂未核实 · 原记录已保留", true);
                showError(errorMessage(error, "网络未恢复，请稍后核对提交结果"));
            }
        })();
        try { await recoveryInFlight; } finally { recoveryInFlight = null; }
    }

    function persistDraft() {
        const payload = snapshotDraft();
        try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload)); } catch (_) {}
        const owner = currentStorageOwner();
        if (!owner || !window.SalesCheckinDraftStore) return Promise.resolve(false);
        renderDraftSaveStatus("正在保存…");
        state.persistence = state.persistence.then(async () => {
            await window.SalesCheckinDraftStore.save(owner, payload);
            if (owner === currentStorageOwner() && state.submission.clientSubmissionId === payload.submission.clientSubmissionId) {
                state.lastSavedFingerprint = draftFingerprint(payload);
                renderDraftSaveStatus(["SUBMITTED", "COMPLETED"].includes(payload.submission.status)
                    ? hasPendingEvidence(payload) ? "已提交 · 证据待补传" : "已提交"
                    : payload.submission.syncRequested ? "本机已保存 · 待同步" : "本机已保存");
            }
            return true;
        }).catch(() => { renderDraftSaveStatus("本机未保存 · 请保留页面并提交", true); return false; });
        return state.persistence;
    }

    function restoreDraft(snapshot = null) {
        let raw;
        try {
            // 清理旧版本曾写入的长期敏感草稿；新版本只在当前标签页保存并设置有效期。
            localStorage.removeItem(STORAGE_KEY);
            raw = snapshot ? JSON.stringify(snapshot) : sessionStorage.getItem(STORAGE_KEY);
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
            || age < -5 * 60 * 1000 || age > (snapshot ? 7 * 24 * 60 * 60 * 1000 : DRAFT_TTL_MS)) {
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
        restorePhotoMetadata();
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
            if (!["UPLOADED", "UNKNOWN", "NEEDS_FILE", "SKIPPED", "TOO_LARGE"].includes(uploadState)) {
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
                uploadErrorStatus: rawSegment.uploadErrorStatus === 413 ? 413 : null,
                mayExistRemotely,
                errorMessage: uploadState === "TOO_LARGE" ? optionalUploadFailureMessage({status: 413}, "录音")
                    : uploadState === "SKIPPED"
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
        state.store.sourcePoiToken = cleanText(state.store.sourcePoiToken);
        state.store.sourcePoiId = cleanText(state.store.sourcePoiId);
        state.store.sourcePoiLongitude = finiteNumberOrNull(state.store.sourcePoiLongitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(state.store.sourcePoiLatitude);
        if (state.store.sourceMode === "POI" && (!state.store.sourcePoiId || !state.store.sourcePoiToken)) {
            state.store.sourceMode = "MANUAL";
        }
        state.store.manualEntryAllowed = true;
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
        state.restoredLocalMediaIds = saved.localMediaIds || [];
    }

    function hasRestoredDraft() {
        return Boolean(state.restoredAt && (state.visit.selectedStore || state.visit.customerName
            || state.visit.visitResult || state.store.name || state.files.photo || state.files.wechat
            || state.submission.serverId || state.submission.audioSegments.length || state.restoredLocalMediaIds?.length));
    }

    function showRestoreNotice() {
        if (!hasRestoredDraft()) { $("#restore-notice").hidden = true; return; }
        const files = state.files.photos.length + Number(Boolean(state.files.wechat)) + state.files.audio.length;
        const message = state.submission.syncState === "UNKNOWN"
            ? "上次提交结果待确认，重试会先查询回执"
            : files ? `表单和 ${files} 个本机文件已恢复，可继续填写`
                : "表单已恢复，可继续填写";
        $("#restore-notice strong").textContent = "已恢复未完成记录";
        $("#restore-message").textContent = message;
        $("#discard-draft-button").hidden = false;
        $("#restore-notice").hidden = false;
    }

    function renderUploadedBadges() {
        renderPhotos();
        const uploadedWechat = hasRemoteMediaState(MEDIA.wechat);
        const audioCount = state.submission.audioSegments.length;
        const uploadedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "UPLOADED").length;
        const skippedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "SKIPPED").length;
        const uncertainSkippedAudioCount = state.submission.audioSegments
            .filter((segment) => segment.uploadState === "SKIPPED" && segment.mayExistRemotely).length;
        const pendingWechat = state.submission.mediaUploadAttempts.includes(MEDIA.wechat) && !uploadedWechat;
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
        $("#wechat-uploaded-badge").hidden = !uploadedWechat && !pendingWechat;
        $("#audio-uploaded-badge").hidden = audioCount === 0;
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
        if (!location) return;
        const time = Date.parse(location.capturedAt || "");
        if (!Number.isFinite(time)) location.timeStatus = "UNKNOWN";
        else if (Date.now() - time > GEOLOCATION_FRESH_MAX_AGE_MS) location.timeStatus = "STALE";
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
            const payload = parseResponsePayload(text);
            if (!response.ok) {
                const error = new Error(extractApiMessage(payload) || friendlyHttpError(response.status));
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
                const error = new Error(extractApiMessage(payload) || friendlyHttpError(xhr.status));
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
        if (!payload || typeof payload !== "object") return "";
        const candidate = typeof payload.message === "string" ? payload.message
            : typeof payload.error?.message === "string" ? payload.error.message
                : Array.isArray(payload.details) && typeof payload.details[0]?.message === "string"
                    ? payload.details[0].message : "";
        if (/<(?:!doctype|html|head|body|script|style|title|pre)\b/i.test(candidate)) return "";
        return candidate.trim().slice(0, 240);
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

    function repairRestoredGeolocationTimestamp(scope) {
        const location = state[scope].location;
        if (!location) return;
        if (!location.rawTimestamp) location.timeStatus = "UNKNOWN";
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
    }

    function cancelLocationCapture(scope) {
        const waiter = locationCaptureWaiters[scope];
        locationCaptureWaiters[scope] = null;
        waiter?.(null);
        state.locationCaptureSequence[scope] += 1;
        stopGeolocationRefresh(scope);
        if (state[scope].locationContext?.geocodeStatus === "CAPTURING") {
            state[scope].locationContext = { ...state[scope].locationContext, geocodeStatus: "IDLE" };
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
        const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {
            timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
        }).formatToParts(date).map(part => [part.type, part.value]));
        return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
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
