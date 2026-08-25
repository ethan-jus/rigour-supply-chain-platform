(function () {
    "use strict";

    const API_BASE = "/sales-checkin/api/v1";
    const STORAGE_KEY = "rigour.sales-checkin.draft.v1";
    const STORAGE_VERSION = 1;
    const DRAFT_TTL_MS = 8 * 60 * 60 * 1000;
    const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
    const MAX_RECORDING_MS = 20 * 60 * 1000;
    const SEARCH_DELAY_MS = 350;
    const APPLE_REFERENCE_EPOCH_OFFSET_MS = 978307200000;
    const LOCATION_CAPTURE_PAST_WINDOW_MS = 60 * 60 * 1000;
    const LOCATION_CAPTURE_FUTURE_SKEW_MS = 2 * 60 * 1000;
    const PRIVACY_NOTICE_VERSION = "2026-08-25-identity-v2";

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
        nearbySearchResults: null,
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
        manualEntryAllowed: false,
        sourceMode: "",
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
        uploadedMedia: []
    });

    const state = {
        activeTab: "visit",
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
            audio: null
        },
        recorder: {
            instance: null,
            stream: null,
            chunks: [],
            startedAt: 0,
            elapsedMs: 0,
            timer: null,
            limitTimer: null,
            stopping: false
        },
        objectUrls: {
            photo: null,
            wechat: null,
            audio: null
        },
        searchTimer: null,
        searchController: null,
        poiSearchTimer: null,
        poiSearchController: null,
        locationControllers: {
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
                    && !locationContextReady(context)) {
                    resolveLocationContext(scope);
                }
            });
        } catch (error) {
            showError(errorMessage(error, "加载城市和下拉选项失败，请检查网络后刷新页面。"));
        }

        renderIdentityState();

        if (hasRestoredDraft()) {
            showRestoreNotice();
        }
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
        const restoredMismatch = (state.visit.salespersonId
                && state.visit.salespersonId !== String(identity.salespersonId))
            || (state.visit.city && state.visit.city !== identity.city)
            || (state.store.salespersonId
                && state.store.salespersonId !== String(identity.salespersonId))
            || (state.store.city && state.store.city !== identity.city);
        if (restoredMismatch) {
            removeStoredDraft();
            state.activeTab = "visit";
            state.visit = freshVisit();
            state.store = freshStore();
            state.submission = freshSubmission();
            state.restoredAt = null;
            $("#restore-notice").hidden = true;
            showError("本机恢复的草稿属于另一销售，已清除本机草稿，服务端已上传内容未被删除。");
        }
        state.identity = identity;
        state.visit.city = identity.city;
        state.visit.salespersonId = String(identity.salespersonId);
        state.store.city = identity.city;
        state.store.salespersonId = String(identity.salespersonId);
        await ensureSalespersons(identity.city);
        populateCitySelects();
        renderSalespersonSelect("visit");
        renderSalespersonSelect("store");
        renderRestoredValues();
        renderStoreOwnerSummary();
        lockIdentitySelectors();
    }

    function renderIdentityState() {
        const authenticated = state.identity?.authenticated === true;
        const legacyMode = state.identity?.enforcementEnabled === false;
        $("#identity-gate").hidden = authenticated || legacyMode;
        $("#checkin-workspace").hidden = !authenticated && !legacyMode;
        $("#identity-summary").hidden = !authenticated;
        if (authenticated) {
            $("#identity-summary-name").textContent = state.identity.salespersonName || "--";
            $("#identity-summary-city").textContent = state.identity.city || "--";
        }
        $("#identity-switch").disabled = state.submitting || isBusinessLocked();
        lockIdentitySelectors();
    }

    function lockIdentitySelectors() {
        if (!state.identity?.authenticated) return;
        ["#visit-city", "#visit-salesperson", "#store-city", "#store-salesperson"].forEach((selector) => {
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
            button.addEventListener("click", () => switchTab(button.dataset.tab));
            button.addEventListener("keydown", handleTabKeydown);
        });

        $("#dismiss-error-button").addEventListener("click", hideError);
        $("#discard-draft-button").addEventListener("click", async () => discardDraft());
        $("#new-submission-button").addEventListener("click", startNewSubmission);

        $("#visit-city").addEventListener("change", () => handleCityChange("visit"));
        $("#store-city").addEventListener("change", () => handleCityChange("store"));
        $("#visit-salesperson").addEventListener("change", persistFromForm);
        $("#store-salesperson").addEventListener("change", persistFromForm);

        $("#store-search").addEventListener("input", scheduleStoreSearch);
        $("#store-search").addEventListener("focus", () => showRegisteredStoreOptions());
        $("#store-search").addEventListener("keydown", handleStoreSearchKeydown);
        $("#store-search-toggle").addEventListener("click", toggleRegisteredStoreOptions);
        $("#clear-store-button").addEventListener("click", clearSelectedStore);
        $("#create-store-link").addEventListener("click", () => prepareNewStore());
        $("#cancel-store-button").addEventListener("click", () => switchTab("visit"));

        $("#poi-search").addEventListener("input", schedulePoiSearch);
        $("#poi-search").addEventListener("focus", () => renderPoiOptions(true));
        $("#poi-search").addEventListener("keydown", handlePoiSearchKeydown);
        $("#poi-search-toggle").addEventListener("click", togglePoiOptions);
        $("#clear-poi-button").addEventListener("click", clearSelectedPoi);
        $("#manual-store-button").addEventListener("click", enableManualStoreEntry);

        $("#visit-location-button").addEventListener("click", () => captureLocation("visit"));
        $("#store-location-button").addEventListener("click", () => captureLocation("store"));
        $("#visit-location-retry").addEventListener("click", () => resolveLocationContext("visit"));
        $("#store-location-retry").addEventListener("click", () => resolveLocationContext("store"));

        $("#storefront-photo").addEventListener("change", (event) => handleImageSelection("photo", event));
        $("#wechat-screenshot").addEventListener("change", (event) => handleImageSelection("wechat", event));
        $("#remove-photo-button").addEventListener("click", async () => clearFile("photo"));
        $("#remove-wechat-button").addEventListener("click", async () => clearFile("wechat"));
        $("#delete-uploaded-photo-button").addEventListener("click", async () => clearFile("photo"));
        $("#delete-uploaded-wechat-button").addEventListener("click", async () => clearFile("wechat"));

        $("#record-audio-button").addEventListener("click", toggleRecording);
        $("#audio-file").addEventListener("change", handleAudioFileSelection);
        $("#remove-audio-button").addEventListener("click", async () => clearFile("audio"));
        $("#delete-uploaded-audio-button").addEventListener("click", async () => clearFile("audio"));

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
    }

    function handleTabKeydown(event) {
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
        event.preventDefault();
        const next = event.currentTarget.dataset.tab === "visit" ? "store" : "visit";
        switchTab(next, true);
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
        renderSelect($("#visit-city"), state.options.cities, "请选择城市", state.visit.city);
        renderSelect($("#store-city"), state.options.cities, "请选择城市", state.store.city);
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
        if (scope === "visit" && isBusinessLocked()) return;
        hideError();
        const city = $(`#${scope}-city`).value;
        state[scope].city = city;
        state[scope].salespersonId = "";
        state.locationControllers[scope]?.abort();
        state.locationControllers[scope] = null;
        state[scope].locationContext = null;
        if (scope === "visit") {
            abortStoreSearch();
            hideStoreResults();
            clearSelectedStore(false, false);
            state.visit.nearbyStores = [];
            $("#store-search").value = "";
            renderNearbyStores();
        } else {
            state.store.nearbyPois = [];
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

    function scheduleStoreSearch() {
        clearTimeout(state.searchTimer);
        state.searchController?.abort();
        state.searchController = null;
        const query = $("#store-search").value.trim();
        if (query.length < 2) {
            state.visit.nearbySearchResults = null;
            showRegisteredStoreOptions();
            return;
        }
        showRegisteredStoreOptions();
        state.searchTimer = window.setTimeout(() => searchNearbyWithQuery("visit", query), SEARCH_DELAY_MS);
    }

    function registeredNearbyStores() {
        const source = Array.isArray(state.visit.nearbySearchResults)
            ? state.visit.nearbySearchResults
            : state.visit.nearbyStores;
        return (Array.isArray(source) ? source : [])
            .filter((store) => store?.source === "REGISTERED" && (store.storeId || store.id) && store.name)
            .filter((store) => store.checkinEligible === true && store.nextAction === "CHECK_IN");
    }

    function abortStoreSearch() {
        clearTimeout(state.searchTimer);
        state.searchTimer = null;
        state.searchController?.abort();
        state.searchController = null;
        $("#store-search-spinner").hidden = true;
    }

    async function searchNearbyWithQuery(scope, query) {
        const isVisit = scope === "visit";
        const controllerKey = isVisit ? "searchController" : "poiSearchController";
        const spinner = isVisit ? $("#store-search-spinner") : $("#poi-search-spinner");
        state[controllerKey]?.abort();
        const controller = new AbortController();
        state[controllerKey] = controller;
        spinner.hidden = false;
        try {
            const payload = normalizeResponse(await requestJson("/locations/resolve", {
                method: "POST",
                body: { city: state[scope].city, location: locationRequestValue(scope), q: query },
                signal: controller.signal,
                timeout: 20000
            })) || {};
            if (state[controllerKey] !== controller) return;
            if (payload.cityMatched !== true || payload.accuracyAccepted !== true
                || payload.freshnessAccepted !== true) {
                throw new Error(cleanText(payload.locationMessage)
                    || "当前定位未通过城市、精度或时效校验，请重新定位。");
            }
            const stores = Array.isArray(payload.nearbyStores)
                ? payload.nearbyStores.filter(isUsableNearbyStore)
                : [];
            if (isVisit) {
                state.visit.nearbySearchResults = stores;
                showRegisteredStoreOptions();
            } else {
                state.store.poiSearchResults = stores;
                state.store.manualEntryAllowed = state.store.poiSearchResults.length === 0;
                renderStoreSource();
                if (state.store.manualEntryAllowed) {
                    hidePoiResults();
                    window.requestAnimationFrame(() => {
                        $("#manual-store-button").scrollIntoView({ behavior: "smooth", block: "nearest" });
                    });
                } else {
                    renderPoiOptions(true);
                }
            }
        } catch (error) {
            if (error.name === "AbortError") return;
            if (isVisit) {
                $("#store-search-help").textContent = errorMessage(error, "附近门店搜索失败，请稍后重试。");
            } else {
                state.store.manualEntryAllowed = false;
                renderStoreSource();
                $("#poi-search-help").textContent = errorMessage(error, "高德附近地点搜索失败，请稍后重试。");
            }
        } finally {
            if (state[controllerKey] === controller) {
                state[controllerKey] = null;
                spinner.hidden = true;
            }
        }
    }

    function showRegisteredStoreOptions() {
        const input = $("#store-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        const stores = registeredNearbyStores().filter((store) => {
            if (!query) return true;
            return [store.name, store.address, store.locationSummary]
                .some((value) => cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        renderStoreResults(stores);
        const total = registeredNearbyStores().length;
        $("#store-search-help").textContent = stores.length
            ? `当前显示 ${stores.length} 家，共 ${total} 家可打卡门店`
            : "附近已录入门店中没有匹配结果。";
    }

    function toggleRegisteredStoreOptions() {
        if ($("#store-search").disabled) return;
        if ($("#store-search-results").hidden) {
            showRegisteredStoreOptions();
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
            empty.textContent = "未找到门店，请继续确认名称或新增门店。";
            root.appendChild(empty);
        } else {
            stores.forEach((store) => {
                const button = document.createElement("button");
                button.type = "button";
                button.className = "search-result";
                button.setAttribute("role", "option");

                const detail = document.createElement("span");
                const name = document.createElement("strong");
                name.textContent = store.name || "未命名门店";
                const location = document.createElement("span");
                location.textContent = store.locationSummary || store.address || "暂无位置摘要";
                detail.append(name, location);

                const distance = document.createElement("span");
                distance.className = "search-result__city";
                distance.textContent = formatDistance(store.distanceMeters) || "附近";
                button.append(detail, distance);
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
            showRegisteredStoreOptions();
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
            showRegisteredStoreOptions();
        }
    }

    function renderSelectedStore() {
        const selected = state.visit.selectedStore;
        $("#selected-store-card").hidden = !selected;
        if (!selected) {
            $("#store-search").value = "";
            return;
        }
        $("#selected-store-name").textContent = selected.name || "未命名门店";
        $("#selected-store-location").textContent = selected.locationSummary || selected.city || "";
        $("#store-search").value = selected.name || "";
    }

    function locationContextReady(context) {
        return Boolean(context
            && context.geocodeStatus === "RESOLVED"
            && context.cityMatched === true
            && context.accuracyAccepted === true
            && context.freshnessAccepted === true
            && Number.isFinite(Number(context.maxCheckinDistanceMeters))
            && Number.isFinite(Number(context.maxCheckinAccuracyMeters))
            && Number.isFinite(Number(context.maxLocationAgeMinutes)));
    }

    function renderNearbyStores() {
        const panel = $("#nearby-stores-panel");
        const context = state.visit.locationContext;
        const stores = registeredNearbyStores();
        const input = $("#store-search");
        const toggle = $("#store-search-toggle");
        const createButton = $("#create-store-link");
        panel.hidden = !state.visit.location;
        hideStoreResults();
        if (!state.visit.location) {
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = true;
            return;
        }

        if (context?.geocodeStatus === "RESOLVING") {
            $("#nearby-stores-summary").textContent = "正在解析地址并查找…";
            $("#store-search-help").textContent = "正在查找当前位置附近的已录入门店。";
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = true;
            return;
        }
        if (!locationContextReady(context)) {
            $("#nearby-stores-summary").textContent = "需要处理定位问题";
            $("#store-search-help").textContent = context?.errorMessage
                || "地址与附近门店未加载，请使用上方重试按钮。";
            input.disabled = true;
            toggle.disabled = true;
            createButton.disabled = true;
            return;
        }
        createButton.disabled = false;
        if (!stores.length) {
            $("#nearby-stores-summary").textContent = "附近暂无可打卡门店";
            $("#store-search-help").textContent = "如果是新门店，请先点击下方“录入新门店”补全资料。";
            input.placeholder = "附近暂无已录入门店";
            input.disabled = true;
            toggle.disabled = true;
            return;
        }
        input.disabled = false;
        toggle.disabled = false;
        input.placeholder = "点击选择，或输入名称搜索";
        $("#nearby-stores-summary").textContent = `附近 ${stores.length} 家可选`;
        $("#store-search-help").textContent = "下拉中仅显示当前定位附近的可打卡门店。";
    }

    function formatDistance(value) {
        const meters = Number(value);
        if (!Number.isFinite(meters) || meters < 0) return "";
        if (meters < 1000) return `${Math.max(1, Math.round(meters))} 米`;
        return `${(meters / 1000).toFixed(meters < 10000 ? 1 : 0)} 公里`;
    }

    function nearbyPoiStores() {
        if (Array.isArray(state.store.poiSearchResults)) {
            return state.store.poiSearchResults.filter(isUsableNearbyStore);
        }
        const candidates = [
            ...(Array.isArray(state.store.nearbyPois) ? state.store.nearbyPois : []),
            ...(Array.isArray(state.visit.nearbyStores) ? state.visit.nearbyStores : [])
        ].filter(isUsableNearbyStore);
        const seen = new Set();
        return candidates.filter((store) => {
            const id = `${store.source}:${store.storeId || store.poiId}`;
            if (seen.has(id)) return false;
            seen.add(id);
            return true;
        });
    }

    function schedulePoiSearch() {
        clearTimeout(state.poiSearchTimer);
        state.poiSearchController?.abort();
        state.poiSearchController = null;
        const query = $("#poi-search").value.trim();
        state.store.manualEntryAllowed = false;
        if (query.length < 2) {
            state.store.poiSearchResults = null;
            renderStoreSource();
            renderPoiOptions(true);
            $("#poi-search-help").textContent = query.length === 1
                ? "请再输入 1 个字，确认附近是否有这家门店。"
                : `附近找到 ${nearbyPoiStores().length} 个结果`;
            return;
        }
        renderPoiOptions(true);
        state.poiSearchTimer = window.setTimeout(
            () => searchNearbyWithQuery("store", query), SEARCH_DELAY_MS);
    }

    function renderPoiOptions(open = false) {
        const input = $("#poi-search");
        if (input.disabled) return;
        const query = input.value.trim().toLocaleLowerCase("zh-CN");
        const pois = nearbyPoiStores().filter((poi) => {
            if (!query) return true;
            return [poi.name, poi.address]
                .some((value) => cleanText(value).toLocaleLowerCase("zh-CN").includes(query));
        });
        const root = $("#poi-search-results");
        root.replaceChildren();
        if (!pois.length) {
            const empty = document.createElement("div");
            empty.className = "search-empty";
            empty.textContent = state.store.manualEntryAllowed
                ? "附近未找到匹配地点，现在可使用下方手动录入。"
                : "请输入至少 2 个字完成附近搜索；服务端确认无结果后才可手动录入。";
            root.appendChild(empty);
        } else {
            pois.forEach((poi) => {
                const registered = poi.source === "REGISTERED" && (poi.storeId || poi.id);
                const button = document.createElement("button");
                button.type = "button";
                button.className = `search-result poi-result${registered ? " is-registered" : ""}`;
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
                badge.textContent = registered ? "已录入 · 直接打卡" : "未录入 · 补资料";
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
        $("#poi-search-help").textContent = pois.length
            ? `当前显示 ${pois.length} 个附近结果`
            : state.store.manualEntryAllowed
                ? "服务端已确认附近无匹配地点，可手动录入。"
                : "输入至少 2 个字搜索，服务端确认无结果后才可手动录入。";
    }

    function hidePoiResults() {
        $("#poi-search-results").hidden = true;
        $("#poi-search").setAttribute("aria-expanded", "false");
    }

    function togglePoiOptions() {
        if ($("#poi-search").disabled) return;
        if ($("#poi-search-results").hidden) {
            renderPoiOptions(true);
            $("#poi-search").focus();
        } else {
            hidePoiResults();
        }
    }

    function handlePoiSearchKeydown(event) {
        if (event.key === "Escape") {
            hidePoiResults();
            event.currentTarget.blur();
        }
        if (event.key === "ArrowDown" && $("#poi-search-results").hidden) {
            event.preventDefault();
            renderPoiOptions(true);
        }
    }

    function selectSourcePoi(poi) {
        state.store.sourceMode = "POI";
        state.store.name = poi.name || "";
        state.store.sourcePoiId = poi.poiId || "";
        state.store.sourcePoiName = poi.name || "";
        state.store.sourcePoiAddress = poi.address || "";
        state.store.sourcePoiLongitude = finiteNumberOrNull(poi.longitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(poi.latitude);
        $("#store-name").value = state.store.name;
        clearFieldError("store-source");
        hidePoiResults();
        renderStoreSource();
        renderStorePrefillMessage();
        persistDraft();
        $("#store-profile-card").scrollIntoView({ behavior: "smooth", block: "start" });
    }

    function selectExistingStoreFromProfileFlow(store) {
        const exists = state.visit.nearbyStores.some((item) =>
            item.source === "REGISTERED"
            && String(item.storeId || item.id) === String(store.storeId || store.id));
        if (!exists) state.visit.nearbyStores.unshift(store);
        selectStore(store);
        state.activeTab = "visit";
        renderTab("visit");
        renderNearbyStores();
        persistDraft();
        window.setTimeout(() => $("#selected-store-card").scrollIntoView({
            behavior: "smooth", block: "center"
        }), 100);
    }

    function clearSelectedPoi() {
        clearSourcePoi(true, true);
        $("#poi-search").value = "";
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
            $("#store-name").value = "";
            renderStoreSource();
            persistDraft();
            if (!$("#poi-search").disabled) $("#poi-search").focus();
            return;
        }
        const suggestedName = $("#poi-search").value.trim();
        clearSourcePoi(false, false);
        state.store.sourceMode = "MANUAL";
        state.store.name = suggestedName;
        $("#store-name").value = suggestedName;
        hidePoiResults();
        clearFieldError("store-source");
        renderStoreSource();
        renderStorePrefillMessage();
        persistDraft();
        window.setTimeout(() => $("#store-name").focus(), 100);
    }

    function renderStoreSource() {
        if (!state.store.sourceMode) {
            if (state.store.sourcePoiId) state.store.sourceMode = "POI";
            else if (cleanText(state.store.name)) state.store.sourceMode = "MANUAL";
        }
        const context = state.store.locationContext;
        const pois = nearbyPoiStores();
        const input = $("#poi-search");
        const toggle = $("#poi-search-toggle");
        const selected = state.store.sourceMode === "POI" && Boolean(state.store.sourcePoiId);
        const manual = state.store.sourceMode === "MANUAL";
        const ready = locationContextReady(context);
        const canSearch = ready && !selected;

        input.disabled = !canSearch;
        toggle.disabled = !canSearch || pois.length === 0;
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
        manualButton.disabled = !manual && !state.store.manualEntryAllowed;
        manualButton.classList.toggle("is-active", manual);
        manualButton.classList.toggle("is-ready", !manual && state.store.manualEntryAllowed);
        manualButton.querySelector("strong").textContent = manual
            ? "已选择手动录入"
            : state.store.manualEntryAllowed
                ? "仍未找到，手动录入门店"
                : "高德附近地点没找到？";
        manualButton.querySelector("span").textContent = manual
            ? "点击可返回高德附近地点选择"
            : state.store.manualEntryAllowed
                ? "继续补全门店名称和基础资料"
                : "确认搜索无结果后，再手动录入门店名称";

        if (!state.store.location) {
            input.placeholder = "先获取定位";
            $("#poi-search-help").textContent = "先完成上方现场定位，才能查找附近地点。";
        } else if (context?.geocodeStatus === "RESOLVING") {
            input.placeholder = "正在查找附近地点";
            $("#poi-search-help").textContent = "正在解析地址并加载附近地点…";
        } else if (!locationContextReady(context)) {
            input.placeholder = "定位解析未完成";
            $("#poi-search-help").textContent = context?.errorMessage
                || "请使用上方重试按钮，或重新定位。";
        } else if (!pois.length) {
            input.placeholder = "输入至少 2 个字搜索附近地点";
            $("#poi-search-help").textContent = state.store.manualEntryAllowed
                ? "附近无匹配地点，可点击下方手动录入。"
                : "需先完成一次附近搜索，确认无结果后才可手动录入。";
        } else if (!selected) {
            input.placeholder = "点击选择，或输入名称搜索";
            $("#poi-search-help").textContent = `附近找到 ${pois.length} 个门店或高德地点`;
        }
    }

    async function prepareNewStore(sourcePoi = null) {
        if (isBusinessLocked()) return;
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
            .filter(isUsableNearbyStore);
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
        if (sourcePoi) selectSourcePoi(sourcePoi);
        window.setTimeout(() => {
            if (state.store.sourceMode === "MANUAL") $("#store-name").focus();
            else if (!$("#poi-search").disabled) $("#poi-search").focus();
        }, 250);
    }

    function clearSourcePoi(resetMode = false, clearName = false) {
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
        if (!state.store.sourcePoiId) {
            message.textContent = state.store.sourceMode === "MANUAL"
                ? "已明确选择手动录入；保存后会自动返回打卡并选中这家门店。"
                : "请先定位并从高德附近地点选择；确实找不到时再手动录入。";
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
        if (scope === "visit" && isBusinessLocked()) return;
        hideError();
        clearFieldError(`${scope}-location`);
        const city = $(`#${scope}-city`).value;
        if (!city) {
            setFieldError(`${scope}-city`, "请先选择城市，再获取定位。");
            $(`#${scope}-city`).focus();
            return;
        }
        state[scope].city = city;
        if (!window.isSecureContext) {
            setFieldError(`${scope}-location`, "浏览器要求通过 HTTPS 才能获取定位，请使用正式网页地址打开。" );
            return;
        }
        if (!navigator.geolocation) {
            setFieldError(`${scope}-location`, "当前浏览器不支持定位，请更换手机浏览器。" );
            return;
        }

        const button = $(`#${scope}-location-button`);
        const status = $(`#${scope}-location-status`);
        button.disabled = true;
        status.textContent = "定位中";
        status.className = "status-pill is-loading";
        navigator.geolocation.getCurrentPosition(
            (position) => {
                const note = $(`#${scope}-location-note`).value.trim();
                state[scope].location = {
                    longitude: roundCoordinate(position.coords.longitude),
                    latitude: roundCoordinate(position.coords.latitude),
                    accuracyMeters: roundAccuracy(position.coords.accuracy),
                    capturedAt: normalizeGeolocationCapturedAt(position.timestamp),
                    ...(note ? { note } : {})
                };
                state[scope].locationContext = { geocodeStatus: "RESOLVING" };
                if (scope === "visit") {
                    state.visit.nearbyStores = [];
                    state.visit.nearbySearchResults = null;
                    clearSelectedStore(false, false);
                } else {
                    state.store.nearbyPois = [];
                    state.store.poiSearchResults = null;
                    state.store.manualEntryAllowed = false;
                    clearSourcePoi(true, true);
                }
                button.disabled = false;
                renderLocation(scope);
                if (scope === "visit") renderNearbyStores();
                else renderStoreSource();
                renderBusinessLock();
                persistDraft();
                resolveLocationContext(scope);
            },
            (error) => {
                button.disabled = false;
                status.textContent = "定位失败";
                status.className = "status-pill";
                setFieldError(`${scope}-location`, geolocationErrorMessage(error));
            },
            { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
        );
    }

    async function resolveLocationContext(scope) {
        const city = state[scope].city;
        const location = state[scope].location;
        if (!city || !location) return;

        state.locationControllers[scope]?.abort();
        const controller = new AbortController();
        state.locationControllers[scope] = controller;
        state[scope].locationContext = { geocodeStatus: "RESOLVING" };
        if (scope === "visit") {
            state.visit.nearbyStores = [];
            state.visit.nearbySearchResults = null;
            abortStoreSearch();
        } else {
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
            state.store.manualEntryAllowed = false;
            state.poiSearchController?.abort();
        }
        renderLocation(scope);
        if (scope === "visit") renderNearbyStores();
        else renderStoreSource();

        try {
            const payload = normalizeResponse(await requestJson("/locations/resolve", {
                method: "POST",
                body: { city, location: locationRequestValue(scope) },
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
                cityMatched: payload.cityMatched === true,
                resolvedCity: cleanText(payload.resolvedCity),
                accuracyAccepted: payload.accuracyAccepted === true,
                freshnessAccepted: payload.freshnessAccepted === true,
                maxCheckinDistanceMeters: finiteNumberOrNull(payload.maxCheckinDistanceMeters),
                maxCheckinAccuracyMeters: finiteNumberOrNull(payload.maxCheckinAccuracyMeters),
                maxLocationAgeMinutes: finiteNumberOrNull(payload.maxLocationAgeMinutes),
                locationMessage,
                errorMessage: locationMessage
            };
            const ready = locationContextReady(state[scope].locationContext);
            if (ready) clearFieldError(`${scope}-location`);
            else setFieldError(`${scope}-location`, locationMessage
                || (payload.cityMatched === false
                    ? "当前位置与所选城市不一致，请修正城市后重新定位。"
                    : payload.freshnessAccepted === false
                        ? "定位已过期，请重新获取当前位置。"
                        : "定位精度不足，请移到信号较好的位置后重新定位。"));
            if (scope === "visit") {
                state.visit.nearbyStores = ready && Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores.filter(isUsableNearbyStore)
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
                    ? payload.nearbyStores.filter(isUsableNearbyStore)
                    : [];
            }
        } catch (error) {
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
        return store.source === "AMAP_POI" && Boolean(store.poiId && store.name
            && store.checkinEligible === false
            && store.nextAction === "COMPLETE_STORE_PROFILE");
    }

    function renderLocation(scope) {
        const location = state[scope].location;
        const context = state[scope].locationContext;
        const status = $(`#${scope}-location-status`);
        const detail = $(`#${scope}-location-detail`);
        const button = $(`#${scope}-location-button`);
        const retry = $(`#${scope}-location-retry`);
        button.closest(".location-card")?.classList.toggle("is-located", Boolean(location));
        if (scope === "store") {
            button.closest(".location-card")?.classList.toggle("has-inherited-location", Boolean(location));
        }
        if (!location) {
            status.textContent = "未定位";
            status.className = "status-pill";
            detail.hidden = true;
            retry.hidden = true;
            $(`#${scope}-location-button-label`).textContent = "获取当前位置";
            return;
        }
        const resolving = context?.geocodeStatus === "RESOLVING";
        const ready = locationContextReady(context);
        const cityMismatch = context?.cityMatched === false;
        const inaccurate = context?.accuracyAccepted === false;
        const expired = context?.freshnessAccepted === false;
        status.textContent = resolving
            ? "解析地址中"
            : cityMismatch
                ? "城市不一致"
                : inaccurate
                    ? "精度不足"
                    : expired
                        ? "定位已过期"
                        : ready ? "已定位" : "需重试";
        status.className = resolving
            ? "status-pill is-loading"
            : ready ? "status-pill is-ready" : "status-pill is-warning";
        detail.hidden = false;
        const address = cleanText(context?.address || context?.formattedAddress);
        const addressElement = $(`#${scope}-location-address`);
        addressElement.textContent = address
            || (resolving ? "正在解析实际地址…"
                : context?.errorMessage || "地址解析失败，请重试");
        addressElement.classList.toggle("is-missing", !address && !resolving);
        retry.hidden = resolving || ready;
        $(`#${scope}-location-accuracy`).textContent = `约 ${location.accuracyMeters} 米`;
        $(`#${scope}-location-time`).textContent = formatDateTime(location.capturedAt);
        $(`#${scope}-location-note`).value = location.note || "";
        $(`#${scope}-location-button-label`).textContent = scope === "store"
            ? "定位不准？重新获取"
            : "重新定位";
    }

    function geolocationErrorMessage(error) {
        if (error && error.code === 1) return "定位权限被拒绝。请在浏览器设置中允许位置访问后重试。";
        if (error && error.code === 2) return "暂时无法获取当前位置，请移动到信号较好的位置后重试。";
        if (error && error.code === 3) return "定位超时，请重试并保持页面在前台。";
        return "获取定位失败，请检查系统定位服务后重试。";
    }

    function handleImageSelection(kind, event) {
        const file = event.target.files && event.target.files[0];
        if (!file) return;
        const errorKey = kind === "photo" ? "storefront-photo" : "wechat-screenshot";
        clearFieldError(errorKey);
        if (hasRemoteMediaState(MEDIA[kind])) {
            setFieldError(errorKey, "请先删除草稿中已上传的文件，再选择替换文件。" );
            event.target.value = "";
            return;
        }
        if (!isSupportedImageFile(file)) {
            setFieldError(errorKey, "仅支持 JPG、PNG、WebP、HEIC/HEIF 或 AVIF 图片。" );
            event.target.value = "";
            return;
        }
        if (file.size > MAX_IMAGE_BYTES) {
            setFieldError(errorKey, "图片超过 10MB，请压缩或重新拍摄。" );
            event.target.value = "";
            return;
        }
        state.files[kind] = file;
        renderImagePreview(kind, file);
    }

    function renderImagePreview(kind, file) {
        revokeObjectUrl(kind);
        const url = URL.createObjectURL(file);
        state.objectUrls[kind] = url;
        const prefix = kind === "photo" ? "photo" : "wechat";
        $(`#${prefix}-preview`).src = url;
        $(`#${prefix}-file-name`).textContent = file.name || "待上传图片";
        $(`#${prefix}-file-size`).textContent = formatBytes(file.size);
        $(`#${prefix}-preview-card`).hidden = false;
    }

    function handleAudioFileSelection(event) {
        const file = event.target.files && event.target.files[0];
        if (!file) return;
        clearFieldError("audio-file");
        if (hasRemoteMediaState(MEDIA.audio)) {
            setFieldError("audio-file", "请先删除草稿中已上传的录音，再选择替换文件。" );
            event.target.value = "";
            return;
        }
        if (isRecording()) {
            setFieldError("audio-file", "请先结束当前录音，再选择已有音频文件。" );
            event.target.value = "";
            return;
        }
        if (!isSupportedAudioFile(file)) {
            setFieldError("audio-file", "仅支持 AAC、M4A/MP4、WAV、AMR、WebM、MP3 或 OGG 音频。" );
            event.target.value = "";
            return;
        }
        if (file.size > MAX_AUDIO_BYTES) {
            setFieldError("audio-file", "音频超过 25MB，请选择较短或压缩后的文件。" );
            event.target.value = "";
            return;
        }
        state.files.audio = file;
        renderAudioPreview(file);
    }

    function isSupportedImageFile(file) {
        const mimeType = (file.type || "").toLowerCase().split(";", 1)[0];
        const supportedMimeTypes = new Set([
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/heic", "image/heif", "image/avif"
        ]);
        return supportedMimeTypes.has(mimeType) || /\.(avif|heic|heif|jpe?g|png|webp)$/i.test(file.name || "");
    }

    function isSupportedAudioFile(file) {
        const mimeType = (file.type || "").toLowerCase().split(";", 1)[0];
        const supportedMimeTypes = new Set([
            "audio/aac", "audio/aacp", "audio/amr", "audio/mp4", "audio/m4a", "audio/x-m4a",
            "audio/mpeg", "audio/mp3", "audio/ogg", "application/ogg", "audio/wav", "audio/x-wav",
            "audio/webm"
        ]);
        return supportedMimeTypes.has(mimeType)
            || /\.(aac|amr|m4a|m4b|mp3|mp4|oga|ogg|opus|wav|wave|webm)$/i.test(file.name || "");
    }

    function checkRecorderSupport() {
        const supported = Boolean(window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
        const uploaded = hasRemoteMediaState(MEDIA.audio);
        $("#record-audio-button").disabled = !supported || uploaded;
        if (uploaded) {
            $("#recorder-help").textContent = "草稿中的录音需先删除，才能重新录制或选择替换文件。";
        } else if (!supported) {
            $("#recorder-help").textContent = window.isSecureContext
                ? "当前浏览器不支持网页录音，请使用下方文件选择上传音频。"
                : "网页录音需要 HTTPS，请使用正式地址打开，或选择已有音频文件。";
        } else {
            updateRecorderHelp(preferredRecorderOptions()?.mimeType);
        }
    }

    function updateRecorderHelp(mimeType) {
        $("#recorder-help").textContent = (mimeType || "").toLowerCase().includes("webm")
            ? "本机将录制 WebM：原音会正常保存和播放，但暂不自动转写；如需转写，请上传 M4A、MP3、WAV、AAC、AMR 或 OGG。"
            : "录音时请保持页面在前台；提交后录音会异步转写并生成 AI 摘要。";
    }

    async function toggleRecording() {
        if (isRecording()) {
            stopRecording();
            return;
        }
        hideError();
        clearFieldError("audio-file");
        if (hasRemoteMediaState(MEDIA.audio)) {
            setFieldError("audio-file", "请先删除草稿中已上传或待确认的录音，再重新录制。" );
            return;
        }
        if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
            setFieldError("audio-file", "当前环境无法录音，请通过 HTTPS 打开或选择已有音频文件。" );
            return;
        }
        $("#audio-preview").pause();
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
            state.recorder.startedAt = Date.now();
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
            state.recorder.limitTimer = window.setTimeout(stopRecording, MAX_RECORDING_MS);
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
        state.recorder.elapsedMs = Math.min(Date.now() - state.recorder.startedAt, MAX_RECORDING_MS);
        recorder.stop();
        setRecordingUi(false, true);
    }

    function finishRecording() {
        const recorder = state.recorder.instance;
        const duration = state.recorder.elapsedMs || Math.min(Date.now() - state.recorder.startedAt, MAX_RECORDING_MS);
        const mimeType = recorder?.mimeType || state.recorder.chunks[0]?.type || "audio/webm";
        const blob = new Blob(state.recorder.chunks, { type: mimeType });
        cleanupRecorder();
        if (hasRemoteMediaState(MEDIA.audio)) {
            setFieldError("audio-file", "草稿中已有录音，请先删除后再重新录制。" );
            return;
        }
        updateRecorderHelp(mimeType);
        if (!blob.size) {
            setFieldError("audio-file", "没有录到有效音频，请重新录制。" );
            return;
        }
        if (blob.size > MAX_AUDIO_BYTES) {
            setFieldError("audio-file", "录音超过 25MB，请缩短录音后重试。" );
            return;
        }
        const extension = audioExtension(mimeType);
        const file = new File([blob], `现场录音-${formatFilenameTime(new Date())}.${extension}`, {
            type: mimeType,
            lastModified: Date.now()
        });
        $("#audio-file").value = "";
        state.files.audio = file;
        renderAudioPreview(file, duration);
    }

    function cleanupRecorder() {
        clearInterval(state.recorder.timer);
        clearTimeout(state.recorder.limitTimer);
        state.recorder.stream?.getTracks().forEach((track) => track.stop());
        state.recorder.instance = null;
        state.recorder.stream = null;
        state.recorder.chunks = [];
        state.recorder.startedAt = 0;
        state.recorder.timer = null;
        state.recorder.limitTimer = null;
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
        if (isRecording()) state.recorder.elapsedMs = Math.min(Date.now() - state.recorder.startedAt, MAX_RECORDING_MS);
        $("#recording-clock").textContent = `${formatDuration(state.recorder.elapsedMs)} / 20:00`;
    }

    function isRecording() {
        return Boolean(state.recorder.instance && state.recorder.instance.state !== "inactive");
    }

    function renderAudioPreview(file, duration) {
        releaseAudioPreview();
        const url = URL.createObjectURL(file);
        state.objectUrls.audio = url;
        $("#audio-preview").src = url;
        $("#audio-file-name").textContent = file.name || "待上传音频";
        $("#audio-file-size").textContent = duration
            ? `${formatBytes(file.size)} · ${formatDuration(duration)}`
            : formatBytes(file.size);
        $("#audio-preview-card").hidden = false;
    }

    async function clearFile(kind) {
        const mediaKind = MEDIA[kind];
        const uploaded = hasRemoteMediaState(mediaKind);
        const errorKey = mediaErrorKey(kind);
        const button = mediaRemoveButton(kind);
        const originalLabel = button.textContent;
        clearFieldError(errorKey);
        if (kind === "audio") pauseAudioPreview();

        if (uploaded) {
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
        state.files[kind] = null;
        if (kind === "photo") {
            revokeObjectUrl(kind);
            $("#storefront-photo").value = "";
            $("#photo-preview-card").hidden = true;
        } else if (kind === "wechat") {
            revokeObjectUrl(kind);
            $("#wechat-screenshot").value = "";
            $("#wechat-preview-card").hidden = true;
        } else {
            $("#audio-file").value = "";
            releaseAudioPreview();
            $("#audio-preview-card").hidden = true;
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
            ? $("#remove-photo-button")
            : kind === "wechat" ? $("#remove-wechat-button") : $("#remove-audio-button");
        if (!previewButton.closest("[hidden]")) return previewButton;
        if (kind === "photo") return $("#delete-uploaded-photo-button");
        if (kind === "wechat") return $("#delete-uploaded-wechat-button");
        return $("#delete-uploaded-audio-button");
    }

    function pauseAudioPreview() {
        const audio = $("#audio-preview");
        audio.pause();
        try {
            audio.currentTime = 0;
        } catch (_) {
            // 尚未读取元数据时部分浏览器不允许修改 currentTime。
        }
    }

    function releaseAudioPreview() {
        const audio = $("#audio-preview");
        pauseAudioPreview();
        audio.removeAttribute("src");
        audio.load();
        revokeObjectUrl("audio");
    }

    function revokeObjectUrl(kind) {
        if (state.objectUrls[kind]) URL.revokeObjectURL(state.objectUrls[kind]);
        state.objectUrls[kind] = null;
    }

    async function submitStore(event) {
        event.preventDefault();
        hideError();
        syncStateFromForm();
        if (!validateStore()) {
            scrollToFirstError();
            return;
        }
        const button = $("#submit-store-button");
        button.disabled = true;
        button.textContent = "正在保存…";
        let storeSaved = false;
        try {
            const payload = buildStorePayload();
            const response = normalizeResponse(await requestJson("/stores", {
                method: "POST",
                body: payload,
                timeout: 45000
            })) || {};
            if (!response.id) throw new Error("门店保存成功但未返回门店编号，请联系管理员。" );
            storeSaved = true;
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
                locationSource: payload.sourcePoiId ? "AMAP_POI" : "STORE_LOCATION",
                checkinEligible: true,
                nextAction: "CHECK_IN"
            };
            state.visit.city = payload.city;
            state.visit.salespersonId = payload.salespersonId;
            state.visit.nearbyStores = [createdStore, ...state.visit.nearbyStores.filter((item) =>
                String(item.storeId || item.id) !== String(response.id))];
            state.visit.nearbySearchResults = null;
            if (!state.visit.customerName) state.visit.customerName = payload.contactName;
            if (!state.visit.customerPhone && payload.contactPhone) state.visit.customerPhone = payload.contactPhone;
            if (!state.visit.location) state.visit.location = { ...payload.location };
            resetStoreDraft(payload.city, payload.salespersonId);
            populateCitySelects();
            renderSalespersonSelect("visit");
            renderRestoredValues();
            switchTab("visit");
            selectStore(createdStore);
            $("#store-saved-name").textContent = createdStore.name;
            $("#store-saved-notice").hidden = false;
            persistDraft();
            $("#selected-store-card").scrollIntoView({ behavior: "smooth", block: "center" });
        } catch (error) {
            showError(errorMessage(error, storeSaved
                ? "门店已经保存，但页面回填失败。请返回拜访打卡并重新定位，门店会出现在附近列表中。"
                : "保存门店失败，请检查信息后重试。"));
        } finally {
            button.disabled = false;
            button.textContent = "保存并选中门店";
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
                { step: MEDIA.wechat, file: state.files.wechat, required: false },
                { step: MEDIA.audio, file: state.files.audio, required: false }
            ];
            for (const upload of uploads) {
                activeStep = upload.step;
                if (state.submission.uploadedMedia.includes(upload.step)) {
                    setProgressStep(upload.step, "done");
                    continue;
                }
                if (!upload.file) {
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
                state.submission.uploadedMedia.push(upload.step);
                persistDraft();
                renderUploadedBadges();
                setProgressStep(upload.step, "done");
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

    async function uploadMedia(kind, file) {
        const formData = new FormData();
        formData.append("file", file, file.name || kind);
        await requestJson(`/submissions/${encodeURIComponent(state.submission.serverId)}/media/${kind}`, {
            method: "PUT",
            headers: { "X-Submission-Key": state.submission.submissionKey },
            body: formData,
            timeout: 180000
        });
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
        if (state.files.audio && state.files.audio.size > MAX_AUDIO_BYTES) valid = false;
        return valid;
    }

    function validateStore() {
        clearAllErrors();
        let valid = true;
        valid = requireValue(state.store.city, "store-city", "请选择城市。") && valid;
        valid = requireValue(state.store.salespersonId, "store-salesperson", "请选择销售。") && valid;
        valid = requireValue(state.store.sourceMode, "store-source", "请先从附近地点选择；搜索确认没有后再手动录入。") && valid;
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
        if (state.store.location && !locationContextReady(state.store.locationContext)) {
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
        const disclosure = first?.closest("details");
        if (disclosure) disclosure.open = true;
        first?.closest(".form-card, .consent-card")?.scrollIntoView({ behavior: "smooth", block: "center" });
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
            abortStoreSearch();
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
    }

    function isBusinessLocked() {
        return state.submission.businessLocked === true
            || Boolean(state.submission.serverId)
            || Boolean(state.submission.attemptedPayload);
    }

    function setFormsDisabled(disabled) {
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
        abortStoreSearch();
        clearTimeout(state.poiSearchTimer);
        state.poiSearchController?.abort();
        state.poiSearchController = null;
        Object.keys(state.files).forEach(resetLocalFile);
        Object.values(state.locationControllers).forEach((controller) => controller?.abort());
        state.locationControllers.visit = null;
        state.locationControllers.store = null;
        state.activeTab = "visit";
        state.visit = freshVisit();
        state.store = freshStore();
        if (state.identity?.authenticated) {
            state.visit.city = state.identity.city;
            state.visit.salespersonId = String(state.identity.salespersonId);
            state.store.city = state.identity.city;
            state.store.salespersonId = String(state.identity.salespersonId);
        }
        state.submission = freshSubmission();
        state.submitting = false;
        state.completed = false;
        removeStoredDraft();
        setFormsDisabled(false);
        $("#visit-form").reset();
        $("#store-form").reset();
        $("#success-panel").hidden = true;
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
        const warning = state.submission.serverId
            ? "将先永久清理草稿中可能存在的照片、截图和录音，再清除本机表单。服务端未完成草稿记录仍会保留，确定继续吗？"
            : "确定放弃当前未提交的表单内容吗？";
        if (!window.confirm(warning)) return;

        if (uploadedMedia.length) {
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
                for (let index = 0; index < uploadedMedia.length; index += 1) {
                    const mediaKind = uploadedMedia[index];
                    button.textContent = `清理文件 ${index + 1}/${uploadedMedia.length}…`;
                    await deleteUploadedMedia(mediaKind);
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
        state.store = freshStore();
        state.store.city = city || "";
        state.store.salespersonId = salespersonId || "";
        $("#store-form").reset();
    }

    function hideStoreSavedNotice() {
        const notice = $("#store-saved-notice");
        if (notice) notice.hidden = true;
    }

    function persistFromForm() {
        syncStateFromForm();
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
            ? state.visit.nearbyStores.filter(isUsableNearbyStore)
            : [];
        state.visit.nearbySearchResults = null;
        state.store.nearbyPois = Array.isArray(state.store.nearbyPois)
            ? state.store.nearbyPois.filter(isUsableNearbyStore)
            : [];
        state.store.poiSearchResults = null;
        if (!state.visit.locationContext || typeof state.visit.locationContext !== "object") {
            state.visit.locationContext = null;
        }
        if (!state.store.locationContext || typeof state.store.locationContext !== "object") {
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
        state.store.sourcePoiLongitude = finiteNumberOrNull(state.store.sourcePoiLongitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(state.store.sourcePoiLatitude);
        state.store.manualEntryAllowed = Boolean(state.store.manualEntryAllowed || state.store.sourceMode === "MANUAL");
        if (!state.store.sourceMode) {
            if (state.store.sourcePoiId) state.store.sourceMode = "POI";
            else if (cleanText(state.store.name)) state.store.sourceMode = "MANUAL";
        }
        if (!state.store.clientStoreId) state.store.clientStoreId = secureUuid();
        if (!state.submission.clientSubmissionId) state.submission.clientSubmissionId = secureUuid();
        if (!state.submission.submissionKey || state.submission.submissionKey.length < 32) {
            state.submission.submissionKey = secureSubmissionKey();
        }
        state.restoredAt = saved.savedAt || null;
    }

    function hasRestoredDraft() {
        return Boolean(state.restoredAt);
    }

    function showRestoreNotice() {
        const uploaded = new Set([
            ...state.submission.uploadedMedia,
            ...state.submission.mediaUploadAttempts
        ]).size;
        let message = `已恢复 ${formatDateTime(state.restoredAt)} 保存的表单。`;
        if (isBusinessLocked()) {
            message += uploaded
                ? ` 业务信息已锁定，服务端草稿和 ${uploaded} 个已上传文件会继续复用。刷新前未上传的文件需重新选择。`
                : state.submission.serverId
                    ? " 业务信息已锁定，服务端草稿会继续复用；照片、截图和录音需重新选择后上传。"
                    : " 首次草稿响应未确认，业务信息已锁定；重试会使用完全相同的内容恢复服务端草稿。";
        }
        $("#restore-message").textContent = message;
        $("#restore-notice").hidden = false;
    }

    function renderUploadedBadges() {
        const uploadedPhoto = hasRemoteMediaState(MEDIA.photo);
        const uploadedWechat = hasRemoteMediaState(MEDIA.wechat);
        const uploadedAudio = hasRemoteMediaState(MEDIA.audio);
        $("#photo-uploaded-badge").textContent = state.submission.uploadedMedia.includes(MEDIA.photo)
            ? "草稿已上传" : "上传状态待确认";
        $("#wechat-uploaded-badge").textContent = state.submission.uploadedMedia.includes(MEDIA.wechat)
            ? "草稿已上传" : "上传状态待确认";
        $("#audio-uploaded-badge").textContent = state.submission.uploadedMedia.includes(MEDIA.audio)
            ? "草稿已上传" : "上传状态待确认";
        $("#photo-uploaded-badge").hidden = !uploadedPhoto;
        $("#wechat-uploaded-badge").hidden = !uploadedWechat;
        $("#audio-uploaded-badge").hidden = !uploadedAudio;
        $("#delete-uploaded-photo-button").hidden = !uploadedPhoto || Boolean(state.files.photo);
        $("#delete-uploaded-wechat-button").hidden = !uploadedWechat || Boolean(state.files.wechat);
        $("#delete-uploaded-audio-button").hidden = !uploadedAudio || Boolean(state.files.audio);
        checkRecorderSupport();
    }

    function hasRemoteMediaState(mediaKind) {
        return state.submission.uploadedMedia.includes(mediaKind)
            || state.submission.mediaUploadAttempts.includes(mediaKind);
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

    async function requestJson(path, options = {}) {
        const controller = new AbortController();
        const externalSignal = options.signal;
        const timeout = options.timeout || 30000;
        let timedOut = false;
        const timeoutId = window.setTimeout(() => {
            timedOut = true;
            controller.abort();
        }, timeout);
        const abortFromExternal = () => controller.abort();
        externalSignal?.addEventListener("abort", abortFromExternal, { once: true });

        const headers = new Headers(options.headers || {});
        let body = options.body;
        if (body && !(body instanceof FormData) && typeof body !== "string") {
            headers.set("Content-Type", "application/json");
            body = JSON.stringify(body);
        }
        headers.set("Accept", "application/json");

        try {
            const response = await fetch(`${API_BASE}${path}`, {
                method: options.method || "GET",
                headers,
                body,
                credentials: "same-origin",
                cache: "no-store",
                signal: controller.signal
            });
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
            externalSignal?.removeEventListener("abort", abortFromExternal);
        }
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

    function normalizeGeolocationCapturedAt(value, referenceMs = Date.now()) {
        const reference = Number.isFinite(Number(referenceMs)) ? Number(referenceMs) : Date.now();
        const resolved = resolveGeolocationCapturedAtMs(value, reference, LOCATION_CAPTURE_PAST_WINDOW_MS);
        return new Date(resolved ?? reference).toISOString();
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
        if (!location?.capturedAt) return;
        const raw = Date.parse(location.capturedAt);
        const repaired = resolveGeolocationCapturedAtMs(location.capturedAt, savedAtMs, DRAFT_TTL_MS);
        if (!Number.isFinite(raw) || repaired === null || repaired === raw) return;
        location.capturedAt = new Date(repaired).toISOString();
        state[scope].locationContext = null;
        if (scope === "visit") {
            state.visit.nearbyStores = [];
            state.visit.nearbySearchResults = null;
        } else {
            state.store.nearbyPois = [];
            state.store.poiSearchResults = null;
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
