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
    const PRIVACY_NOTICE_VERSION = "2026-08-25-identity-v2";
    const HEADQUARTERS_CITY = "总部";
    const FLOW_STEPS = Object.freeze({ visit: 3, store: 3 });

    const MEDIA = Object.freeze({
        photo: "storefront-photo",
        wechat: "wechat-screenshot",
        audio: "audio"
    });

    const LOCKED_BUSINESS_SELECTORS = Object.freeze([
        "#visit-city", "#visit-salesperson", "#visit-location-button", "#visit-location-retry",
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
            stopping: false
        },
        objectUrls: {
            photo: null,
            wechat: null,
            audio: new Map()
        },
        audioRetrySegmentId: null,
        poiSearchController: null,
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
        storageUnavailableShown: false
    };

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

    document.addEventListener("DOMContentLoaded", init);

    async function init() {
        try {
            restoreDraft();
            invalidateUnlockedVisitLocationForFreshEntry();
        } catch (error) {
            showError(errorMessage(error, "无法生成安全的提交凭据，请使用新版浏览器并通过 HTTPS 打开页面。"));
            $("#submit-visit-button").disabled = true;
        }

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
                    && !(scope === "store"
                        ? locationContextCityVerified(context)
                        : locationContextReady(context))) {
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

    function invalidateUnlockedVisitLocationForFreshEntry() {
        if (!hasRestoredDraft() || isBusinessLocked()) return;
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
                || isBusinessLocked()) return;
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
            setFieldError("identity-city", "请选择城市。");
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
            ? "本机保存的未完成表单与当前身份或实际拜访城市不一致，未带入当前账号；原服务端草稿未做任何修改。"
            : "本机保存的表单与当前身份或实际拜访城市不一致，已安全清除。请选择本次拜访城市后重新定位。";
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
                ? `· ${state.identity.city}`
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
        $("#visit-step-2-next").addEventListener("click", () => goToFlowStep("visit", 3));
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

        $("#store-search").addEventListener("input", showVisitStoreOptions);
        $("#store-search").addEventListener("focus", () => showVisitStoreOptions());
        $("#store-search").addEventListener("keydown", handleStoreSearchKeydown);
        $("#store-search-toggle").addEventListener("click", toggleVisitStoreOptions);
        $("#clear-store-button").addEventListener("click", clearSelectedStore);
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

        window.addEventListener("beforeunload", (event) => {
            if (!state.completed) {
                syncStateFromForm();
                persistDraft();
            }
            if (isRecording() || state.submitting) {
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
        if (isBusinessLocked() && tab === "store") return;
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
            window.scrollTo({ top: 0, behavior: "smooth" });
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
        $("#hero-title").textContent = visitActive ? "拜访打卡" : "新门店建档";
        $("#hero-description").textContent = visitActive
            ? "现场拜访记录"
            : "搜索并建立本次拜访门店";
        document.title = visitActive ? "销售拜访打卡" : "新门店建档";
        $("meta[name=\"theme-color\"]")?.setAttribute("content", "#133c3f");
        renderFlowSteps();
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
            return Boolean(state.store.manualEntryAllowed && cleanText(state.store.manualEntryToken));
        }
        return false;
    }

    function isVisitStepReady(step) {
        if (step === 1) {
            return Boolean(state.visit.city
                && state.visit.salespersonId
                && state.visit.selectedStore?.id
                && state.visit.location
                && locationContextReady(state.visit.locationContext));
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
                && state.store.location
                && locationContextCityVerified(state.store.locationContext)
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
                button.disabled = state.submitting || step > maximum;
            });
        });
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
            valid = requireValue(state.visit.city, "visit-city", "请选择城市。") && valid;
            valid = requireValue(state.visit.salespersonId, "visit-salesperson", "请选择销售。") && valid;
            valid = requireValue(state.visit.location, "visit-location", "请刷新并获取本次现场定位。") && valid;
            if (state.visit.location && !locationContextReady(state.visit.locationContext)) {
                setFieldError("visit-location", state.visit.locationContext?.errorMessage
                    || "当前定位未通过城市、精度或时效校验，请重新定位。");
                valid = false;
            }
            valid = requireValue(state.visit.selectedStore?.id, "selected-store", "请选择本次拜访门店。") && valid;
        } else if (flow === "visit" && step === 2) {
            valid = requireValue(cleanText(state.visit.customerName), "customer-name", "请输入客户姓名。") && valid;
            valid = requireValue(cleanText(state.visit.visitResult), "visit-result", "请填写拜访结果。") && valid;
        } else if (flow === "store" && step === 1) {
            valid = requireValue(state.store.city, "store-city", "请选择城市。") && valid;
            valid = requireValue(state.store.salespersonId, "store-salesperson", "请选择销售。") && valid;
            valid = requireValue(state.store.location, "store-location", "请刷新并获取本次现场定位。") && valid;
            if (state.store.location && !locationContextCityVerified(state.store.locationContext)) {
                setFieldError("store-location", state.store.locationContext?.errorMessage
                    || "当前定位未通过城市、精度或时效校验，请重新定位。");
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

    function goToFlowStep(flow, requestedStep, options = {}) {
        if (!Object.prototype.hasOwnProperty.call(FLOW_STEPS, flow) || state.submitting) return false;
        const key = flowStateKey(flow);
        const current = normalizeFlowStep(state.ui[key]);
        const target = normalizeFlowStep(requestedStep, current);
        if (flow === "visit" && current === 3 && target !== 3 && isRecording()) {
            setFieldError("audio-file", "请先停止录音，再返回修改前面的内容。");
            $(".optional-evidence-group").open = true;
            scrollToFirstError();
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
        state.ui[key] = target;
        renderFlowSteps();
        if (options.persist !== false) {
            syncStateFromForm();
            persistDraft();
        }
        if (options.scroll !== false) {
            const panel = document.querySelector(
                '[data-flow-step-panel="' + flow + '"][data-step-value="' + target + '"]');
            window.requestAnimationFrame(() => panel?.scrollIntoView({
                behavior: "smooth", block: "start"
            }));
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
        renderSelect($("#visit-city"), workCities, "请选择本次拜访城市", state.visit.city);
        renderSelect($("#store-city"), workCities, "请选择本次拜访城市", state.store.city);
        renderSelect($("#identity-city"), state.options.cities, "请选择城市",
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
            hideStoreResults();
            clearSelectedStore(false, false);
            state.visit.nearbyStores = [];
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
        if (!locationContextCityVerified(state.store.locationContext) || !state.store.location) {
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

    function showVisitStoreOptions() {
        const input = $("#store-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        const options = visitNearbyOptions();
        const stores = options.filter((store) => {
            if (!query) return true;
            return [store.name, store.address, store.locationSummary]
                .some((value) => cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        renderStoreResults(stores);
        $("#store-search-help").textContent = stores.length
            ? `${visitRadiusLabel()}已加载 ${options.length} 家已建档门店；当前筛选显示 ${stores.length} 家`
            : options.length
                ? "已加载的门店中没有匹配项，可换关键词或新增门店。"
                : "附近没有已建档门店，可点击下方“新增门店”。";
    }

    function toggleVisitStoreOptions() {
        if ($("#store-search").disabled) return;
        if ($("#store-search-results").hidden) {
            showVisitStoreOptions();
            $("#store-search").focus();
        } else {
            hideStoreResults();
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
                location.textContent = store.locationSummary || store.address || "暂无位置摘要";
                detail.append(name, location);

                const meta = document.createElement("span");
                meta.className = "visit-store-result__meta";
                const status = document.createElement("strong");
                status.textContent = "已建档 · 直接打卡";
                const distance = document.createElement("small");
                distance.textContent = formatDistance(store.distanceMeters) || "附近";
                meta.append(status, distance);

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
        if (event.key === "Escape") {
            hideStoreResults();
            event.currentTarget.blur();
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
        hideStoreSavedNotice();
        state.visit.selectedStore = {
            id: storeId,
            name: store.name || "未命名门店",
            city: store.city || state.visit.city,
            locationSummary: store.locationSummary || store.address || ""
        };
        $("#store-search").value = state.visit.selectedStore.name;
        hideStoreResults();
        renderSelectedStore();
        renderNearbyStores();
        clearFieldError("selected-store");
        persistDraft();
    }

    function clearSelectedStore(persist = true, focusSearch = true) {
        if (isBusinessLocked()) return;
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
        $("#selected-store-card").hidden = !selected;
        $("#visit-step-store-name").textContent = selected?.name || "尚未选择门店";
        $("#visit-step-store-address").textContent = selected
            ? selected.locationSummary || selected.address || selected.city || "位置已采集"
            : "请返回第一步选择门店";
        if (!selected) {
            $("#store-search").value = "";
            renderFlowActions();
            return;
        }
        $("#selected-store-name").textContent = selected.name || "未命名门店";
        $("#selected-store-location").textContent = selected.locationSummary || selected.city || "";
        $("#store-search").value = selected.name || "";
        renderFlowActions();
    }

    function locationContextReady(context) {
        return Boolean(context
            && context.cityMatched !== false
            && context.accuracyAccepted === true
            && context.freshnessAccepted === true
            && Boolean(cleanText(context.locationVerificationToken))
            && Number.isFinite(Number(context.maxCheckinDistanceMeters))
            && Number.isFinite(Number(context.maxCheckinAccuracyMeters))
            && Number.isFinite(Number(context.maxLocationAgeMinutes)));
    }

    function locationContextCityVerified(context) {
        return locationContextReady(context)
            && context.geocodeStatus === "RESOLVED"
            && context.cityMatched === true;
    }

    function renderNearbyStores() {
        const panel = $("#nearby-stores-panel");
        const context = state.visit.locationContext;
        const options = visitNearbyOptions();
        const input = $("#store-search");
        const toggle = $("#store-search-toggle");
        const createButton = $("#create-store-link");
        const createUnavailable = state.submitting || isBusinessLocked();
        panel.hidden = false;
        $("#nearby-stores-empty").hidden = Boolean(state.visit.location);
        $(".store-search-field", panel).hidden = !state.visit.location;
        hideStoreResults();
        $("#nearby-stores-scope").textContent = locationContextReady(context)
            ? `${visitRadiusLabel(context)}的已建档门店，可直接选择打卡`
            : "仅显示当前定位允许范围内的已建档门店";
        if (!state.visit.location) {
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = createUnavailable;
            $("#nearby-stores-summary").textContent = "等待定位";
            renderFlowActions();
            return;
        }

        if (context?.geocodeStatus === "RESOLVING") {
            $("#nearby-stores-summary").textContent = "正在解析地址并加载…";
            $("#store-search-help").textContent = "正在查找当前位置附近的已录入门店。";
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = createUnavailable;
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
            ? `本次返回：${registeredCount} 家已建档门店、${amapCount} 个城市内高德候选；请选择一项继续。`
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
        persistDraft();
        window.setTimeout(() => $("#selected-store-card").scrollIntoView({
            behavior: "smooth", block: "center"
        }), 100);
    }

    function clearSelectedPoi() {
        clearSourcePoi(true, true);
        state.ui.storeStep = 1;
        $("#poi-search").value = state.store.poiSearchQuery;
        renderStoreSource();
        renderStorePrefillMessage();
        persistDraft();
        if (!$("#poi-search").disabled) {
            $("#poi-search").focus();
            renderPoiOptions(true);
        }
    }

    function enableManualStoreEntry() {
        if (state.store.sourceMode === "MANUAL") {
            state.store.sourceMode = "";
            state.store.name = "";
            state.ui.storeStep = 1;
            $("#store-name").value = "";
            renderStoreSource();
            persistDraft();
            if (!$("#poi-search").disabled) $("#poi-search").focus();
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
        window.setTimeout(() => $("#store-name").focus(), 100);
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
        const ready = locationContextCityVerified(context);
        const lookupStatus = storePoiLookupStatus();
        const canSearch = ready && !selected;
        const searching = Boolean(state.poiSearchController);
        const searchQuery = input.value.trim();

        input.disabled = !canSearch || searching;
        searchButton.hidden = selected;
        searchButton.disabled = !canSearch || searching || searchQuery.length < 2;
        searchButton.textContent = searching ? "搜索中…" : "搜索";
        $(".poi-search-field").hidden = selected;
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
            ? "已选择手动录入"
            : state.store.manualEntryAllowed
                ? "仍未找到，手动录入门店"
                : "高德城市搜索没找到？";
        manualButton.querySelector("span").textContent = manual
            ? "点击可返回高德城市搜索结果"
            : state.store.manualEntryAllowed
                ? "继续补全门店名称和基础资料"
                : "确认搜索无结果后，再手动录入门店名称";

        if (!state.store.location) {
            input.placeholder = "先获取定位";
            $("#poi-search-help").textContent = "先完成上方现场定位，才能搜索高德新门店。";
        } else if (context?.geocodeStatus === "RESOLVING") {
            input.placeholder = "正在解析定位";
            $("#poi-search-help").textContent = "正在解析定位并加载已建档门店…";
        } else if (!locationContextCityVerified(context)) {
            input.placeholder = "定位解析未完成";
            $("#poi-search-help").textContent = context?.errorMessage
                || "请使用上方重试按钮，或重新定位。";
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
                ? `已加载 ${registeredCount} 家已建档门店，可直接筛选选择；搜索高德需点击“搜索”。`
                : "输入至少 2 个字并点击“搜索”；输入本身不会请求高德。";
        }
        renderFlowSteps();
    }

    async function prepareNewStore() {
        if (state.submitting) return;
        if (isBusinessLocked()) return;
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
        if (!state.visit.location || !locationContextReady(state.visit.locationContext)) {
            setFieldError("visit-location", state.visit.locationContext?.errorMessage
                || "请先刷新并确认本次现场定位，再新增门店。");
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
        if (state.visit.location) {
            state.store.location = { ...state.visit.location };
            state.store.locationContext = state.visit.locationContext
                ? { ...state.visit.locationContext }
                : null;
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
        window.setTimeout(() => {
            if (state.store.sourceMode === "MANUAL") $("#store-name").focus();
            else if (!$("#poi-search").disabled) $("#poi-search").focus();
        }, 250);
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
                ? "已明确选择手动录入；保存后会自动返回打卡并选中这家门店。"
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
        if (!window.isSecureContext) {
            setFieldError(`${scope}-location`, "浏览器要求通过 HTTPS 才能获取定位，请使用正式网页地址打开。" );
            return;
        }
        if (!navigator.geolocation) {
            setFieldError(`${scope}-location`, "当前浏览器不支持定位，请更换手机浏览器。" );
            return;
        }

        const diagnosticId = secureUuid();
        emitClientDiagnostic("LOCATION_CLICK", "STARTED", {}, diagnosticId);
        const button = $(`#${scope}-location-button`);
        const status = $(`#${scope}-location-status`);
        const captureSequence = ++state.locationCaptureSequence[scope];
        let captureSettled = false;
        const captureIsActive = () => !captureSettled
            && state.locationCaptureSequence[scope] === captureSequence;
        stopGeolocationRefresh(scope);
        state.locationControllers[scope]?.abort();
        state.locationControllers[scope] = null;
        state[scope].location = null;
        state[scope].locationContext = {
            geocodeStatus: "CAPTURING",
            errorMessage: "正在刷新手机当前位置，请稍候。"
        };
        if (scope === "visit") {
            clearSelectedStore(false, false);
            state.visit.nearbyStores = [];
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

        let compatibleAttempted = false;
        let stalePositionReceived = false;
        let geolocationAttemptSequence = 0;
        let usingWatch = typeof navigator.geolocation.watchPosition === "function"
            && typeof navigator.geolocation.clearWatch === "function";

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
                clearSelectedStore(false, false);
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
            emitClientDiagnostic("LOCATION_RESULT", "SUCCEEDED", {}, diagnosticId);
            resolveLocationContext(scope, diagnosticId);
        };
        const failLocation = (error, staleOnly = stalePositionReceived) => {
            if (!captureIsActive()) return;
            captureSettled = true;
            stopGeolocationRefresh(scope);
            const message = staleOnly
                ? "手机持续返回历史定位，本次没有使用旧位置。请打开系统定位、保持页面在前台后重试。"
                : geolocationErrorMessage(error, compatibleAttempted);
            state[scope].locationContext = {
                geocodeStatus: "FAILED",
                errorMessage: message
            };
            button.disabled = false;
            renderLocation(scope);
            setFieldError(`${scope}-location`, message);
            persistDraft();
            emitClientDiagnostic("LOCATION_RESULT", "FAILED", {}, diagnosticId);
        };

        const handlePosition = (position) => {
            if (!captureIsActive()) return true;
            if (!Number.isFinite(Number(position?.coords?.longitude))
                    || !Number.isFinite(Number(position?.coords?.latitude))
                    || !Number.isFinite(Number(position?.coords?.accuracy))) {
                failLocation({ code: 2 }, false);
                return true;
            }
            const receivedAtMs = Date.now();
            const capturedAtMs = resolveGeolocationCapturedAtMs(
                position?.timestamp, receivedAtMs, GEOLOCATION_FRESH_MAX_AGE_MS);
            if (capturedAtMs === null) {
                stalePositionReceived = true;
                state[scope].locationContext = {
                    geocodeStatus: "CAPTURING",
                    stalePosition: true,
                    errorMessage: "检测到历史定位，正在等待手机刷新当前位置。"
                };
                setFieldError(`${scope}-location`, "检测到历史定位，正在等待手机刷新当前位置…");
                renderLocation(scope);
                return false;
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
                    if (!compatibleAttempted) {
                        compatibleAttempted = true;
                        status.textContent = "兼容刷新中";
                        startSinglePosition(false);
                    } else {
                        failLocation({ code: 3 }, true);
                    }
                },
                (error) => {
                    if (attemptIsActive()) retryCompatibleLocation(error);
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
                        if (attemptIsActive()) handlePosition(position);
                    },
                    (error) => {
                        if (attemptIsActive()) retryCompatibleLocation(error);
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
            compatibleAttempted = true;
            if (usingWatch && state.geolocationWatchIds[scope] !== null) {
                navigator.geolocation.clearWatch(state.geolocationWatchIds[scope]);
                state.geolocationWatchIds[scope] = null;
            }
            status.textContent = "兼容刷新中";
            status.className = "status-pill is-loading";
            clearFieldError(`${scope}-location`);
            if (usingWatch) startWatch(false);
            else startSinglePosition(false);
        };

        state.geolocationTimeoutIds[scope] = window.setTimeout(
            () => failLocation({ code: 3 }), GEOLOCATION_REFRESH_TIMEOUT_MS);
        if (usingWatch) startWatch(true);
        else startSinglePosition(true);
    }

    async function resolveLocationContext(scope, clientEventId = secureUuid()) {
        const city = state[scope].city;
        const location = state[scope].location;
        if (!city || !location) return;

        state.locationControllers[scope]?.abort();
        const controller = createRequestController();
        state.locationControllers[scope] = controller;
        state[scope].locationContext = { geocodeStatus: "RESOLVING" };
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
            state[scope].locationContext = {
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
            const ready = scope === "store"
                ? locationContextCityVerified(state[scope].locationContext)
                : locationContextReady(state[scope].locationContext);
            if (ready) clearFieldError(`${scope}-location`);
            else setFieldError(`${scope}-location`, locationMessage
                || (payload.cityMatched === false
                    ? "当前位置与所选城市不一致，请修正城市后重新定位。"
                    : payload.freshnessAccepted === false
                        ? "定位已过期，请重新获取当前位置。"
                        : "定位精度不足，请移到信号较好的位置后重新定位。"));
            if (scope === "visit") {
                state.visit.nearbyStores = ready && Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores
                        .filter((store) => store?.source === "REGISTERED")
                        .filter(isUsableNearbyStore)
                    : [];
                const selectedId = state.visit.selectedStore?.id;
                if (!isBusinessLocked() && selectedId && !registeredNearbyStores().some((store) =>
                    String(store.storeId || store.id) === String(selectedId))) {
                    state.visit.selectedStore = null;
                    $("#store-search").value = "";
                    renderSelectedStore();
                }
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
            const message = errorMessage(error, "地址与附近门店解析失败，请重试。");
            state[scope].locationContext = { geocodeStatus: "FAILED", errorMessage: message };
            setFieldError(`${scope}-location`, message);
            if (scope === "visit") state.visit.nearbyStores = [];
            else state.store.nearbyPois = [];
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
        const capturing = context?.geocodeStatus === "CAPTURING";
        const awaitingFreshPosition = capturing && context?.stalePosition === true;
        const failed = context?.geocodeStatus === "FAILED";
        button.closest(".location-card")?.classList.toggle("is-located", Boolean(location));
        if (scope === "store") {
            button.closest(".location-card")?.classList.toggle("has-inherited-location", Boolean(location));
        }
        if (!location) {
            status.textContent = awaitingFreshPosition ? "刷新定位中" : capturing ? "定位中" : failed ? "定位失败" : "未定位";
            status.className = capturing ? "status-pill is-loading" : failed ? "status-pill is-warning" : "status-pill";
            detail.hidden = true;
            retry.hidden = true;
            $(`#${scope}-location-button-label`).textContent = awaitingFreshPosition
                ? "正在等待手机刷新…"
                : capturing ? "正在刷新当前位置…" : "刷新当前位置";
            renderFlowActions();
            return;
        }
        const resolving = context?.geocodeStatus === "RESOLVING";
        const ready = scope === "store"
            ? locationContextCityVerified(context)
            : locationContextReady(context);
        const cityMismatch = context?.cityMatched === false;
        const cityUnverified = context?.cityMatched == null
            && Boolean(cleanText(context?.locationVerificationToken));
        const inaccurate = context?.accuracyAccepted === false;
        const expired = context?.freshnessAccepted === false;
        status.textContent = capturing
            ? "定位中"
            : resolving
            ? "解析地址中"
            : cityMismatch
                ? "城市不一致"
                : inaccurate
                    ? "精度不足"
                    : expired
                        ? "定位已过期"
                        : ready
                            ? cityUnverified ? "已定位·地址待恢复" : "已定位"
                            : "需重试";
        status.className = capturing || resolving
            ? "status-pill is-loading"
            : ready && !cityUnverified ? "status-pill is-ready" : "status-pill is-warning";
        detail.hidden = false;
        const address = cleanText(context?.address || context?.formattedAddress);
        const addressElement = $(`#${scope}-location-address`);
        addressElement.textContent = address
            || (capturing ? "正在重新获取当前位置…"
                : resolving ? "正在解析实际地址…"
                : context?.errorMessage || "地址解析失败，请重试");
        addressElement.classList.toggle("is-missing", !address && !capturing && !resolving);
        retry.hidden = capturing || resolving || (ready && !cityUnverified);
        $(`#${scope}-location-accuracy`).textContent = `约 ${location.accuracyMeters} 米`;
        $(`#${scope}-location-time`).textContent = formatDateTime(location.capturedAt);
        $(`#${scope}-location-note`).value = location.note || "";
        $(`#${scope}-location-button-label`).textContent = scope === "store"
            ? "定位不准？重新获取"
            : "重新定位";
        renderFlowActions();
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

    function handleAudioFileSelection(event) {
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
        let validFiles = files.filter((file) => Number.isFinite(file.size) && file.size > 0);
        if (validFiles.length !== files.length) {
            setFieldError("audio-file", "已忽略无法读取的空音频文件。" );
        }
        if (!validFiles.length) return;

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

    function checkRecorderSupport() {
        const supported = Boolean(window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
        $("#record-audio-button").disabled = !supported;
        if (!supported) {
            $("#recorder-help").textContent = window.isSecureContext
                ? "当前浏览器不支持网页录音，可直接从手机文件中多选录音上传。"
                : "网页录音需要 HTTPS，可直接从手机文件中多选录音上传。";
        } else {
            updateRecorderHelp(preferredRecorderOptions()?.mimeType);
        }
    }

    function updateRecorderHelp(mimeType) {
        const format = (mimeType || "").toLowerCase().includes("webm") ? "WebM" : "音频";
        $("#recorder-help").textContent = `本机将录制 ${format}；中断后可继续添加下一段，也可多选已有音频。自动转文字与摘要当前已暂停。`;
    }

    async function toggleRecording() {
        if (isRecording()) {
            stopRecording();
            return;
        }
        hideError();
        clearFieldError("audio-file");
        if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
            setFieldError("audio-file", "当前环境无法录音，请通过 HTTPS 打开或选择已有音频文件。" );
            return;
        }
        pauseAllAudioPreviews();
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
            const options = preferredRecorderOptions();
            let recorder;
            try {
                recorder = options ? new MediaRecorder(stream, options) : new MediaRecorder(stream);
            } catch (_) {
                recorder = new MediaRecorder(stream);
            }
            state.recorder.stream = stream;
            state.recorder.instance = recorder;
            state.recorder.chunks = [];
            const startedAt = Date.now();
            state.recorder.startedAt = startedAt;
            state.recorder.clientStartedAt = new Date(startedAt).toISOString();
            state.recorder.elapsedMs = 0;
            state.recorder.stopping = false;

            recorder.addEventListener("dataavailable", (event) => {
                if (event.data && event.data.size > 0) state.recorder.chunks.push(event.data);
            });
            recorder.addEventListener("error", () => {
                setFieldError("audio-file", "录音意外中断，请重新录制或选择已有音频。" );
                cleanupRecorder();
            });
            recorder.addEventListener("stop", finishRecording, { once: true });
            recorder.start(1000);
            setRecordingUi(true);
            updateRecordingClock();
            state.recorder.timer = window.setInterval(updateRecordingClock, 500);
        } catch (error) {
            cleanupRecorder();
            setFieldError("audio-file", microphoneErrorMessage(error));
        }
    }

    function preferredRecorderOptions() {
        const candidates = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm"];
        const mimeType = candidates.find((value) => MediaRecorder.isTypeSupported?.(value));
        return mimeType ? { mimeType, audioBitsPerSecond: 64000 } : undefined;
    }

    function stopRecording() {
        const recorder = state.recorder.instance;
        if (!recorder || recorder.state === "inactive" || state.recorder.stopping) return;
        state.recorder.stopping = true;
        state.recorder.elapsedMs = Date.now() - state.recorder.startedAt;
        recorder.stop();
        setRecordingUi(false, true);
    }

    function finishRecording() {
        const recorder = state.recorder.instance;
        const duration = state.recorder.elapsedMs || Date.now() - state.recorder.startedAt;
        const clientStartedAt = state.recorder.clientStartedAt;
        const mimeType = recorder?.mimeType || state.recorder.chunks[0]?.type || "audio/webm";
        const blob = new Blob(state.recorder.chunks, { type: mimeType });
        cleanupRecorder();
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
        appendAudioFile(file, {
            captureSource: "BROWSER_RECORDER",
            clientStartedAt,
            clientDurationMs: duration
        });
        renderAudioSegments();
        renderUploadedBadges();
        persistDraft();
    }

    function cleanupRecorder() {
        clearInterval(state.recorder.timer);
        state.recorder.stream?.getTracks().forEach((track) => track.stop());
        state.recorder.instance = null;
        state.recorder.stream = null;
        state.recorder.chunks = [];
        state.recorder.startedAt = 0;
        state.recorder.clientStartedAt = null;
        state.recorder.timer = null;
        state.recorder.stopping = false;
        setRecordingUi(false);
    }

    function setRecordingUi(recording, stopping = false) {
        const button = $("#record-audio-button");
        button.classList.toggle("is-recording", recording);
        button.disabled = stopping;
        $("#record-button-label").textContent = recording ? "结束录音" : stopping ? "正在保存" : "开始录音";
        $("#recording-meter").hidden = !recording;
        if (!recording && !stopping) button.disabled = false;
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
        if (segment.uploadState === "UNKNOWN") return "上次上传结果待确认，可重选原文件重试或移除";
        if (segment.uploadState === "NEEDS_FILE") return "刷新后需重新选择原文件";
        if (segment.uploadState === "ERROR") return segment.errorMessage || "上传失败，可重试";
        return "待上传";
    }

    function audioSegmentNeedsRetry(segment) {
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
                await uploadAudioSegment(segment, local.file, 1, 1);
                renderAudioSegments();
                renderUploadedBadges();
            } catch (error) {
                const message = errorMessage(error, "录音上传失败，可继续重试。");
                segment.uploadState = segment.mayExistRemotely ? "UNKNOWN" : "ERROR";
                segment.errorMessage = message;
                renderAudioSegments();
                setFieldError("audio-file", message);
            }
            persistDraft();
            return;
        }
        state.audioRetrySegmentId = segmentId;
        $("#audio-file").click();
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
            releaseAllAudioPreviews();
            state.files.audio = [];
            state.submission.audioSegments = [];
            $("#audio-file").value = "";
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
            const response = normalizeResponse(await requestJson("/stores", {
                method: "POST",
                headers: { "X-Sales-Checkin-Client-Event-Id": diagnosticId },
                body: payload,
                timeout: 45000
            })) || {};
            if (!response.id) {
                throw new Error("门店保存请求已完成，但服务端未返回门店编号。当前表单已保留，请勿重复填写并联系管理员。" );
            }
            const locationSummary = response.locationSummary || payload.sourcePoiAddress
                || payload.location.note || state.visit.locationContext?.address || "位置已采集";
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
                nextAction: "CHECK_IN"
            };
            savedStore = createdStore;
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
            button.textContent = "保存并选中门店";
        }
    }

    function completeStoreSaveTransition(createdStore, payload) {
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
        if (!state.visit.location || !locationContextReady(state.visit.locationContext)) {
            state.visit.location = { ...payload.location };
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
        $("#store-saved-name").textContent = createdStore.name;
        $("#store-saved-notice").hidden = false;
        persistDraft();
        window.requestAnimationFrame(() => {
            $("#selected-store-card")?.scrollIntoView({ behavior: "smooth", block: "center" });
        });
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
        if (!state.visit.location || !locationContextReady(state.visit.locationContext)) {
            state.visit.location = { ...payload.location };
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
        } catch (_) {
            // 状态已先写入草稿，刷新页面仍会回到拜访并选中已保存门店。
        }
    }

    async function submitVisit(event) {
        event.preventDefault();
        hideError();
        syncStateFromForm();
        if (isRecording()) {
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

        state.submitting = true;
        setFormsDisabled(true);
        prepareProgress();
        $("#upload-panel").hidden = false;
        $("#upload-panel").scrollIntoView({ behavior: "smooth", block: "start" });

        let activeStep = "draft";
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
            const response = normalizeResponse(await requestJson("/submissions", {
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
                        setProgressStep(upload.step, "error", `${label}上传结果待确认`);
                        throw new Error(`${label}上次上传结果未确认。请重新选择原文件继续重试，或点击删除明确放弃。`);
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
                await uploadMedia(upload.step, upload.file);
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
                for (let index = 0; index < audioSegments.length; index += 1) {
                    const segment = audioSegments[index];
                    if (segment.uploadState === "UPLOADED") continue;
                    const local = localAudioFile(segment.segmentId);
                    if (!local) {
                        segment.uploadState = segment.uploadState === "UNKNOWN" ? "UNKNOWN" : "NEEDS_FILE";
                        renderAudioSegments();
                        persistDraft();
                        setProgressStep(MEDIA.audio, "error", `第 ${index + 1}/${audioSegments.length} 段录音需重新选择`);
                        throw new Error(`第${index + 1}段录音需重新选择原文件后重试，或移除该段后继续。`);
                    }
                    await uploadAudioSegment(segment, local.file, index + 1, audioSegments.length);
                }
                renderUploadedBadges();
                setProgressStep(MEDIA.audio, "done", `已上传 ${audioSegments.length} 段现场录音`);
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
            showSuccess(completed);
        } catch (error) {
            setProgressStep(activeStep, "error", "提交中断，可修正后继续");
            $("#progress-detail").textContent = errorMessage(error, "提交失败，请稍后重试。" );
            showError(errorMessage(error, "提交失败，请稍后重试。"));
            state.submitting = false;
            setFormsDisabled(false);
            persistDraft();
        }
    }

    async function uploadAudioSegment(segment, file, index, total) {
        const metadataPromise = localAudioFile(segment.segmentId)?.metadataPromise;
        if (metadataPromise) {
            try {
                await metadataPromise;
            } catch (_) {
                // 可选证据读取失败不阻断录音上传。
            }
        }
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
                })) || {};
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
            segment.uploadState = Number.isFinite(error.status) && error.status >= 400 && error.status < 500
                ? "ERROR" : "UNKNOWN";
            segment.errorMessage = errorMessage(error, "录音上传中断，可复用原文件重试。");
            renderAudioSegments();
            renderUploadedBadges();
            persistDraft();
            throw error;
        }
    }

    function uploadMedia(kind, file, progressTitle, optionalFormFields = {}) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const formData = new FormData();
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
            // 不设置总时长截止，避免 QQ/X5 在慢网大文件上传时被前端计时器主动中断。
            // 连接失活仍由 Nginx/服务端超时和浏览器网络错误处理。
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
                    resolve(payload);
                    return;
                }
                const error = new Error(extractApiMessage(payload) || `上传失败（HTTP ${xhr.status}）`);
                error.status = xhr.status;
                error.payload = payload;
                reject(error);
            });
            xhr.addEventListener("timeout", () => reject(new Error("上传连接超时，文件仍保留在本页，可直接重试。")));
            xhr.addEventListener("error", () => reject(new Error("上传网络中断，文件仍保留在本页，可直接重新提交。")));
            xhr.addEventListener("abort", () => reject(new Error("上传已中断，文件仍保留在本页，可直接重新提交。")));
            xhr.send(formData);
        });
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
            sourcePoiToken: optionalText(state.store.sourcePoiToken),
            manualEntryToken: state.store.sourceMode === "MANUAL"
                ? optionalText(state.store.manualEntryToken) : undefined,
            locationVerificationToken: optionalText(
                state.store.locationContext?.locationVerificationToken),
            sourcePoiId: optionalText(state.store.sourcePoiId),
            sourcePoiName: optionalText(state.store.sourcePoiName),
            sourcePoiAddress: optionalText(state.store.sourcePoiAddress),
            sourcePoiLongitude: finiteNumberOrNull(state.store.sourcePoiLongitude),
            sourcePoiLatitude: finiteNumberOrNull(state.store.sourcePoiLatitude),
            location
        });
    }

    function buildSubmissionPayload() {
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
            locationVerificationToken: optionalText(
                state.visit.locationContext?.locationVerificationToken),
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
        valid = requireValue(state.visit.city, "visit-city", "请选择城市。") && valid;
        valid = requireValue(state.visit.salespersonId, "visit-salesperson", "请选择销售。") && valid;
        valid = requireValue(state.visit.selectedStore?.id, "selected-store", "请搜索并选择拜访门店。") && valid;
        valid = requireValue(state.visit.customerName.trim(), "customer-name", "请输入客户姓名。") && valid;
        valid = requireValue(state.visit.visitResult.trim(), "visit-result", "请填写拜访结果。") && valid;
        valid = requireValue(state.visit.location, "visit-location", "请在现场获取拜访定位。") && valid;
        if (state.visit.location && !isBusinessLocked() && !locationContextReady(state.visit.locationContext)) {
            setFieldError("visit-location", state.visit.locationContext?.errorMessage
                || "当前定位未通过城市和精度校验，请修正后重新定位。");
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
        valid = requireValue(state.store.city, "store-city", "请选择城市。") && valid;
        valid = requireValue(state.store.salespersonId, "store-salesperson", "请选择销售。") && valid;
        valid = requireValue(state.store.sourceMode, "store-source", "请先从高德城市搜索结果选择；搜索确认没有后再手动录入。") && valid;
        if (state.store.sourceMode === "POI" && !cleanText(state.store.sourcePoiToken)) {
            setFieldError("store-source", "高德候选凭证已失效，请重新搜索并选择门店。");
            valid = false;
        }
        if (state.store.sourceMode === "MANUAL" && !cleanText(state.store.manualEntryToken)) {
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
        valid = requireValue(state.store.location, "store-location", "请在现场获取门店定位。") && valid;
        if (state.store.location && !locationContextCityVerified(state.store.locationContext)) {
            setFieldError("store-location", state.store.locationContext?.errorMessage
                || "当前定位未通过城市和精度校验，请重新定位。");
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
        if (flowPanel) {
            goToFlowStep(flowPanel.dataset.flowStepPanel, Number(flowPanel.dataset.stepValue), {
                validateForward: false,
                scroll: false
            });
        }
        const disclosure = first?.closest("details");
        if (disclosure) disclosure.open = true;
        window.requestAnimationFrame(() => {
            const container = first.closest(".form-card, .consent-card, .flow-step-panel");
            container?.scrollIntoView({ behavior: "smooth", block: "center" });
            first.closest(".field, .choice-fieldset, .upload-item, .audio-recorder, .consent-card, .location-card")
                ?.querySelector("input, select, textarea, button")
                ?.focus({ preventScroll: true });
        });
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

    function showSuccess(completed) {
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
        abortPoiSearch();
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
            if (!["UPLOADED", "UNKNOWN", "NEEDS_FILE"].includes(uploadState)) uploadState = "NEEDS_FILE";
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
                mayExistRemotely: rawSegment.mayExistRemotely === true
                    || ["UPLOADED", "UNKNOWN"].includes(uploadState),
                errorMessage: ""
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
        state.store.nearbyPois = Array.isArray(state.store.nearbyPois)
            ? state.store.nearbyPois
                .filter((store) => store?.source === "REGISTERED")
                .filter(isUsableNearbyStore)
            : [];
        state.store.poiSearchResults = null;
        state.store.poiSearchQuery = cleanText(state.store.poiSearchQuery);
        if (!state.visit.location || !state.visit.locationContext || typeof state.visit.locationContext !== "object") {
            state.visit.locationContext = null;
        }
        if (!state.store.location || !state.store.locationContext || typeof state.store.locationContext !== "object") {
            state.store.locationContext = null;
        }
        if (!isBusinessLocked()) {
            invalidateExpiredRestoredLocation("visit");
            invalidateExpiredRestoredLocation("store");
        }
        if (!isBusinessLocked() && !locationContextReady(state.visit.locationContext)) {
            state.visit.selectedStore = null;
            state.visit.nearbyStores = [];
        }
        state.store.sourcePoiToken = cleanText(state.store.sourcePoiToken);
        state.store.sourcePoiId = cleanText(state.store.sourcePoiId);
        state.store.sourcePoiLongitude = finiteNumberOrNull(state.store.sourcePoiLongitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(state.store.sourcePoiLatitude);
        const restoredLookupStatus = cleanText(state.store.poiSearchLookupStatus);
        const manualAuthorized = restoredLookupStatus === "EMPTY" || restoredLookupStatus === "UNAVAILABLE";
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
            && manualAuthorized && Boolean(state.store.manualEntryToken);
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
        const pendingAudio = state.submission.audioSegments.length - uploadedAudio;
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
        const pendingPhoto = state.submission.mediaUploadAttempts.includes(MEDIA.photo) && !uploadedPhoto;
        const pendingWechat = state.submission.mediaUploadAttempts.includes(MEDIA.wechat) && !uploadedWechat;
        $("#photo-uploaded-badge").textContent = uploadedPhoto ? "草稿已上传" : "可重新选择并重试";
        $("#wechat-uploaded-badge").textContent = uploadedWechat ? "草稿已上传" : "可重新选择并重试";
        $("#audio-uploaded-badge").textContent = uploadedAudioCount === audioCount
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

    function resolveGeolocationCapturedAtMs(value, referenceMs, pastWindowMs) {
        const raw = typeof value === "number" ? value : Date.parse(value || "");
        if (!Number.isFinite(raw) || !Number.isFinite(referenceMs)) return null;
        const minimum = referenceMs - pastWindowMs;
        const maximum = referenceMs + LOCATION_CAPTURE_FUTURE_SKEW_MS;
        const candidates = [raw, raw + APPLE_REFERENCE_EPOCH_OFFSET_MS]
            .filter((candidate) => candidate >= minimum && candidate <= maximum)
            .sort((left, right) => Math.abs(referenceMs - left) - Math.abs(referenceMs - right));
        return candidates.length ? candidates[0] : null;
    }

    function repairRestoredGeolocationTimestamp(scope, savedAtMs) {
        const location = state[scope].location;
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
        if (mimeType.includes("mp4") || mimeType.includes("m4a")) return "m4a";
        if (mimeType.includes("mpeg")) return "mp3";
        if (mimeType.includes("wav")) return "wav";
        return "webm";
    }

    function microphoneErrorMessage(error) {
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
