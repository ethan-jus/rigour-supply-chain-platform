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
    const PRIVACY_NOTICE_VERSION = "2026-08-25-ai-v1";

    const MEDIA = Object.freeze({
        photo: "storefront-photo",
        wechat: "wechat-screenshot",
        audio: "audio"
    });

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
        uploadedMedia: []
    });

    const state = {
        activeTab: "visit",
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
        renderStorePrefillMessage();
        renderUploadedBadges();
        updateVisitResultCount();
        checkRecorderSupport();

        try {
            const initialCity = state.visit.city || state.store.city || "";
            await fetchOptions(initialCity);
            populateCitySelects();
            await restoreDependentOptions();
            renderDictionaryControls();
            renderRestoredValues();
            ["visit", "store"].forEach((scope) => {
                const context = state[scope].locationContext;
                if (state[scope].city && state[scope].location
                    && (!context || context.geocodeStatus === "RESOLVING")) {
                    resolveLocationContext(scope);
                }
            });
        } catch (error) {
            showError(errorMessage(error, "加载城市和下拉选项失败，请检查网络后刷新页面。"));
        }

        if (hasRestoredDraft()) {
            showRestoreNotice();
        }
    }

    function bindEvents() {
        $$("[data-tab]").forEach((button) => {
            button.addEventListener("click", () => switchTab(button.dataset.tab));
            button.addEventListener("keydown", handleTabKeydown);
        });

        $("#dismiss-error-button").addEventListener("click", hideError);
        $("#discard-draft-button").addEventListener("click", discardDraft);
        $("#new-submission-button").addEventListener("click", startNewSubmission);

        $("#visit-city").addEventListener("change", () => handleCityChange("visit"));
        $("#store-city").addEventListener("change", () => handleCityChange("store"));
        $("#visit-salesperson").addEventListener("change", persistFromForm);
        $("#store-salesperson").addEventListener("change", persistFromForm);

        $("#store-search").addEventListener("input", scheduleStoreSearch);
        $("#store-search").addEventListener("keydown", handleStoreSearchKeydown);
        $("#clear-store-button").addEventListener("click", clearSelectedStore);
        $("#create-store-link").addEventListener("click", () => prepareNewStore());
        $("#cancel-store-button").addEventListener("click", () => switchTab("visit"));

        $("#visit-location-button").addEventListener("click", () => captureLocation("visit"));
        $("#store-location-button").addEventListener("click", () => captureLocation("store"));

        $("#storefront-photo").addEventListener("change", (event) => handleImageSelection("photo", event));
        $("#wechat-screenshot").addEventListener("change", (event) => handleImageSelection("wechat", event));
        $("#remove-photo-button").addEventListener("click", () => clearFile("photo"));
        $("#remove-wechat-button").addEventListener("click", () => clearFile("wechat"));

        $("#record-audio-button").addEventListener("click", toggleRecording);
        $("#audio-file").addEventListener("change", handleAudioFileSelection);
        $("#remove-audio-button").addEventListener("click", () => clearFile("audio"));

        $("#visit-form").addEventListener("input", persistFromForm);
        $("#visit-form").addEventListener("change", persistFromForm);
        $("#store-form").addEventListener("input", persistFromForm);
        $("#store-form").addEventListener("change", persistFromForm);
        $("#visit-result").addEventListener("input", updateVisitResultCount);

        $("#visit-form").addEventListener("submit", submitVisit);
        $("#store-form").addEventListener("submit", submitStore);

        document.addEventListener("click", (event) => {
            if (!event.target.closest(".store-search-field")) {
                hideStoreResults();
            }
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
        state.activeTab = tab === "store" ? "store" : "visit";
        renderTab(state.activeTab);
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
        select.disabled = people.length === 0;
        if (scope === "store") renderStoreOwnerSummary();
    }

    async function handleCityChange(scope) {
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
        } else if (state.store.sourcePoiId) {
            clearSourcePoi();
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
        abortStoreSearch();
        const query = $("#store-search").value.trim();
        if (query.length < 2) {
            hideStoreResults();
            $("#store-search-help").textContent = query.length === 1 ? "请再输入 1 个字。" : "选择城市后，可按门店名称搜索。";
            return;
        }
        if (!$("#visit-city").value) {
            hideStoreResults();
            $("#store-search-help").textContent = "请先选择城市。";
            return;
        }
        state.searchTimer = window.setTimeout(searchStores, SEARCH_DELAY_MS);
    }

    async function searchStores() {
        const city = $("#visit-city").value;
        const query = $("#store-search").value.trim();
        if (!city || query.length < 2) return;

        abortStoreSearch();
        const controller = new AbortController();
        state.searchController = controller;
        $("#store-search-spinner").hidden = false;
        $("#store-search-help").textContent = "正在搜索门店…";
        try {
            const params = new URLSearchParams({ city, q: query, limit: "20" });
            const response = await requestJson(`/stores?${params.toString()}`, {
                signal: controller.signal,
                timeout: 15000
            });
            const stores = normalizeResponse(response);
            renderStoreResults(Array.isArray(stores) ? stores : []);
            $("#store-search-help").textContent = Array.isArray(stores) && stores.length
                ? `找到 ${stores.length} 家门店`
                : "没有匹配门店，可点击下方新增。";
        } catch (error) {
            if (error.name !== "AbortError") {
                renderStoreResults([]);
                $("#store-search-help").textContent = errorMessage(error, "门店搜索失败，请稍后重试。");
            }
        } finally {
            if (state.searchController === controller) {
                $("#store-search-spinner").hidden = true;
                state.searchController = null;
            }
        }
    }

    function abortStoreSearch() {
        if (state.searchController) {
            state.searchController.abort();
            state.searchController = null;
        }
        $("#store-search-spinner").hidden = true;
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

                const city = document.createElement("span");
                city.className = "search-result__city";
                city.textContent = store.city || state.visit.city;
                button.append(detail, city);
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
        if (event.key === "Escape") hideStoreResults();
    }

    function selectStore(store) {
        const storeId = store.id || store.storeId;
        if (!storeId) return;
        state.visit.selectedStore = {
            id: storeId,
            name: store.name || "未命名门店",
            city: store.city || state.visit.city,
            locationSummary: store.locationSummary || store.address || ""
        };
        $("#store-search").value = state.visit.selectedStore.name;
        hideStoreResults();
        const disclosure = $(".store-search-disclosure");
        if (disclosure) disclosure.open = false;
        renderSelectedStore();
        renderNearbyStores();
        clearFieldError("selected-store");
        persistDraft();
    }

    function clearSelectedStore(persist = true, focusSearch = true) {
        state.visit.selectedStore = null;
        renderSelectedStore();
        renderNearbyStores();
        if (persist) persistDraft();
        if (focusSearch) {
            const disclosure = $(".store-search-disclosure");
            if (disclosure) disclosure.open = true;
            $("#store-search").focus();
        }
    }

    function renderSelectedStore() {
        const selected = state.visit.selectedStore;
        const selectedIsVisibleNearby = Boolean(selected && state.visit.nearbyStores.some((store) =>
            store.source === "REGISTERED" && String(store.storeId) === String(selected.id)));
        $("#selected-store-card").hidden = !selected || selectedIsVisibleNearby;
        if (!selected) {
            $("#store-search").value = "";
            return;
        }
        $("#selected-store-name").textContent = selected.name || "未命名门店";
        $("#selected-store-location").textContent = selected.locationSummary || selected.city || "";
        $("#store-search").value = selected.name || "";
    }

    function renderNearbyStores() {
        const panel = $("#nearby-stores-panel");
        const root = $("#nearby-store-results");
        const context = state.visit.locationContext;
        const stores = Array.isArray(state.visit.nearbyStores) ? state.visit.nearbyStores : [];
        panel.hidden = !state.visit.location;
        root.replaceChildren();
        if (!state.visit.location) return;

        if (context?.geocodeStatus === "RESOLVING") {
            $("#nearby-stores-summary").textContent = "正在解析地址并查找…";
            return;
        }
        if (context && context.geocodeStatus !== "RESOLVED" && context.geocodeStatus !== "RESOLVING"
            && !stores.length) {
            $("#nearby-stores-summary").textContent = "附近门店暂不可用，可直接搜索";
            return;
        }
        if (!stores.length) {
            $("#nearby-stores-summary").textContent = context
                ? "附近未找到门店，可搜索或新增"
                : "正在查找…";
            return;
        }

        const visibleStores = stores.slice(0, 6);
        $("#nearby-stores-summary").textContent = stores.length > visibleStores.length
            ? `显示最近 ${visibleStores.length} 个地点`
            : `找到 ${stores.length} 个附近地点`;
        visibleStores.forEach((store) => {
            const registered = store.source === "REGISTERED" && store.storeId;
            const selected = registered
                && String(state.visit.selectedStore?.id || "") === String(store.storeId);
            const button = document.createElement("button");
            button.type = "button";
            button.className = `nearby-store${selected ? " is-selected" : ""}`;
            button.setAttribute("aria-pressed", String(selected));

            const content = document.createElement("span");
            content.className = "nearby-store__content";
            const title = document.createElement("span");
            title.className = "nearby-store__title";
            const name = document.createElement("strong");
            name.textContent = store.name || "未命名门店";
            const badge = document.createElement("span");
            badge.className = `nearby-store__badge${registered ? " is-registered" : ""}`;
            badge.textContent = registered ? "已录入" : "未录入";
            title.append(name, badge);
            const address = document.createElement("span");
            address.textContent = store.address || "暂无详细地址";
            content.append(title, address);

            const meta = document.createElement("span");
            meta.className = "nearby-store__meta";
            const distance = formatDistance(store.distanceMeters);
            meta.textContent = selected
                ? "已选择"
                : registered
                    ? distance
                    : ["选择后补录", distance].filter(Boolean).join(" · ");
            button.append(content, meta);
            button.addEventListener("click", () => {
                if (registered) {
                    selectStore(store);
                    $("#selected-store-card").scrollIntoView({ behavior: "smooth", block: "center" });
                } else if (store.source === "AMAP_POI" && store.poiId) {
                    prepareNewStore(store);
                }
            });
            root.appendChild(button);
        });
    }

    function formatDistance(value) {
        const meters = Number(value);
        if (!Number.isFinite(meters) || meters < 0) return "";
        if (meters < 1000) return `${Math.max(1, Math.round(meters))} 米`;
        return `${(meters / 1000).toFixed(meters < 10000 ? 1 : 0)} 公里`;
    }

    async function prepareNewStore(sourcePoi = null) {
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
        if (sourcePoi?.poiId && state.store.sourcePoiId !== sourcePoi.poiId) {
            state.store = freshStore();
        }
        state.store.city = state.visit.city || state.store.city;
        state.store.salespersonId = state.visit.salespersonId || state.store.salespersonId;
        if (sourcePoi) {
            state.store.name = sourcePoi.name || state.store.name;
            state.store.sourcePoiId = sourcePoi.poiId || "";
            state.store.sourcePoiName = sourcePoi.name || "";
            state.store.sourcePoiAddress = sourcePoi.address || "";
            state.store.sourcePoiLongitude = finiteNumberOrNull(sourcePoi.longitude);
            state.store.sourcePoiLatitude = finiteNumberOrNull(sourcePoi.latitude);
        } else {
            state.store.name = $("#store-search").value.trim() || state.store.name;
            clearSourcePoi();
        }
        if (sourcePoi && state.visit.location) {
            state.store.location = { ...state.visit.location };
            state.store.locationContext = state.visit.locationContext
                ? { ...state.visit.locationContext }
                : null;
        } else if (!state.store.location && state.visit.location) {
            state.store.location = { ...state.visit.location };
            state.store.locationContext = state.visit.locationContext
                ? { ...state.visit.locationContext }
                : null;
        }
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
        renderStorePrefillMessage();
        switchTab("store");
        window.setTimeout(() => $("#store-name").focus(), 250);
    }

    function clearSourcePoi() {
        state.store.sourcePoiId = "";
        state.store.sourcePoiName = "";
        state.store.sourcePoiAddress = "";
        state.store.sourcePoiLongitude = null;
        state.store.sourcePoiLatitude = null;
    }

    function renderStorePrefillMessage() {
        const message = $("#store-prefill-message");
        if (!state.store.sourcePoiId) {
            message.textContent = "保存后会自动返回打卡并选中这家门店。";
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
                    capturedAt: new Date(position.timestamp || Date.now()).toISOString(),
                    ...(note ? { note } : {})
                };
                state[scope].locationContext = { geocodeStatus: "RESOLVING" };
                if (scope === "visit") state.visit.nearbyStores = [];
                button.disabled = false;
                renderLocation(scope);
                if (scope === "visit") renderNearbyStores();
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
        if (scope === "visit") state.visit.nearbyStores = [];
        renderLocation(scope);
        if (scope === "visit") renderNearbyStores();

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
            state[scope].locationContext = {
                geocodeStatus: cleanText(payload.geocodeStatus) || (address || formattedAddress ? "RESOLVED" : "FAILED"),
                address,
                formattedAddress,
                adcode: cleanText(payload.adcode)
            };
            if (scope === "visit") {
                state.visit.nearbyStores = Array.isArray(payload.nearbyStores)
                    ? payload.nearbyStores.filter(isUsableNearbyStore)
                    : [];
            }
        } catch (error) {
            if (error.name === "AbortError") return;
            state[scope].locationContext = { geocodeStatus: "FAILED" };
            if (scope === "visit") state.visit.nearbyStores = [];
        } finally {
            if (state.locationControllers[scope] === controller) {
                state.locationControllers[scope] = null;
                renderLocation(scope);
                if (scope === "visit") renderNearbyStores();
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
        if (store.source === "REGISTERED") return Boolean(store.storeId && store.name);
        return store.source === "AMAP_POI" && Boolean(store.poiId && store.name);
    }

    function renderLocation(scope) {
        const location = state[scope].location;
        const context = state[scope].locationContext;
        const status = $(`#${scope}-location-status`);
        const detail = $(`#${scope}-location-detail`);
        const button = $(`#${scope}-location-button`);
        button.closest(".location-card")?.classList.toggle("is-located", Boolean(location));
        if (!location) {
            status.textContent = "未定位";
            status.className = "status-pill";
            detail.hidden = true;
            $(`#${scope}-location-button-label`).textContent = "获取当前位置";
            return;
        }
        const resolving = context?.geocodeStatus === "RESOLVING";
        status.textContent = resolving ? "解析地址中" : "已定位";
        status.className = resolving ? "status-pill is-loading" : "status-pill is-ready";
        detail.hidden = false;
        const address = cleanText(context?.address || context?.formattedAddress);
        const addressElement = $(`#${scope}-location-address`);
        addressElement.textContent = address || (resolving ? "正在解析实际地址…" : "地址暂未解析，定位已记录");
        addressElement.classList.toggle("is-missing", !address && !resolving);
        $(`#${scope}-location-accuracy`).textContent = `约 ${location.accuracyMeters} 米`;
        $(`#${scope}-location-time`).textContent = formatDateTime(location.capturedAt);
        $(`#${scope}-location-note`).value = location.note || "";
        $(`#${scope}-location-button-label`).textContent = "重新定位";
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
            "audio/webm", "video/webm", "video/mp4"
        ]);
        return supportedMimeTypes.has(mimeType) || /\.(aac|amr|m4a|mp3|mp4|ogg|wav|webm)$/i.test(file.name || "");
    }

    function checkRecorderSupport() {
        const supported = Boolean(window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
        $("#record-audio-button").disabled = !supported;
        if (!supported) {
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

    function clearFile(kind) {
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

    function releaseAudioPreview() {
        const audio = $("#audio-preview");
        audio.pause();
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
        try {
            const payload = buildStorePayload();
            const response = normalizeResponse(await requestJson("/stores", {
                method: "POST",
                body: payload,
                timeout: 45000
            })) || {};
            if (!response.id) throw new Error("门店保存成功但未返回门店编号，请联系管理员。" );
            state.visit.city = payload.city;
            state.visit.salespersonId = payload.salespersonId;
            state.visit.selectedStore = {
                id: response.id,
                name: response.name || payload.name,
                city: response.city || payload.city,
                locationSummary: response.locationSummary || payload.sourcePoiAddress
                    || payload.location.note || "位置已采集"
            };
            if (!state.visit.customerName) state.visit.customerName = payload.contactName;
            if (!state.visit.customerPhone && payload.contactPhone) state.visit.customerPhone = payload.contactPhone;
            if (!state.visit.location) state.visit.location = { ...payload.location };
            resetStoreDraft(payload.city, payload.salespersonId);
            await ensureSalespersons(state.visit.city);
            populateCitySelects();
            renderSalespersonSelect("visit");
            renderRestoredValues();
            renderSelectedStore();
            renderLocation("visit");
            persistDraft();
            switchTab("visit");
            $("#selected-store-card").scrollIntoView({ behavior: "smooth", block: "center" });
        } catch (error) {
            showError(errorMessage(error, "保存门店失败，请检查信息后重试。"));
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
            if (!state.submission.serverId) {
                setProgressStep("draft", "active", "正在创建打卡草稿");
                const response = normalizeResponse(await requestJson("/submissions", {
                    method: "POST",
                    headers: { "X-Submission-Key": state.submission.submissionKey },
                    body: buildSubmissionPayload(),
                    timeout: 45000
                })) || {};
                if (!response.id) throw new Error("服务端未返回提交编号，请重试。" );
                state.submission.serverId = response.id;
                state.submission.status = response.status || "DRAFT";
                state.submission.createdAt = response.createdAt || new Date().toISOString();
                persistDraft();
            }
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

    function setFormsDisabled(disabled) {
        $("#submit-visit-button").disabled = disabled;
        $("#submit-store-button").disabled = disabled;
        [$("#visit-form"), $("#store-form")].forEach((form) => {
            form.setAttribute("aria-busy", String(disabled));
            if (disabled) form.setAttribute("inert", "");
            else form.removeAttribute("inert");
        });
    }

    function startNewSubmission() {
        cleanupRecorder();
        Object.keys(state.files).forEach(clearFile);
        Object.values(state.locationControllers).forEach((controller) => controller?.abort());
        state.locationControllers.visit = null;
        state.locationControllers.store = null;
        state.activeTab = "visit";
        state.visit = freshVisit();
        state.store = freshStore();
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
        renderStorePrefillMessage();
        renderStoreOwnerSummary();
        renderUploadedBadges();
        renderTab("visit");
        clearAllErrors();
        hideError();
        checkRecorderSupport();
        window.scrollTo({ top: 0, behavior: "smooth" });
    }

    function discardDraft() {
        const warning = state.submission.serverId
            ? "将清除本机表单并生成新的提交凭据。服务端已创建的未完成草稿不会自动删除，确定继续吗？"
            : "确定放弃当前未提交的表单内容吗？";
        if (!window.confirm(warning)) return;
        startNewSubmission();
        $("#restore-notice").hidden = true;
    }

    function resetStoreDraft(city, salespersonId) {
        state.store = freshStore();
        state.store.city = city || "";
        state.store.salespersonId = salespersonId || "";
        $("#store-form").reset();
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
        state.visit.nearbyStores = Array.isArray(state.visit.nearbyStores)
            ? state.visit.nearbyStores.filter(isUsableNearbyStore)
            : [];
        if (!state.visit.locationContext || typeof state.visit.locationContext !== "object") {
            state.visit.locationContext = null;
        }
        if (!state.store.locationContext || typeof state.store.locationContext !== "object") {
            state.store.locationContext = null;
        }
        state.store.sourcePoiLongitude = finiteNumberOrNull(state.store.sourcePoiLongitude);
        state.store.sourcePoiLatitude = finiteNumberOrNull(state.store.sourcePoiLatitude);
        state.submission.uploadedMedia = Array.isArray(state.submission.uploadedMedia)
            ? [...new Set(state.submission.uploadedMedia)]
            : [];
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
        const uploaded = state.submission.uploadedMedia.length;
        let message = `已恢复 ${formatDateTime(state.restoredAt)} 保存的表单。`;
        if (state.submission.serverId) {
            message += uploaded
                ? ` 服务端草稿和 ${uploaded} 个已上传文件也会继续复用。刷新前未上传的文件需重新选择。`
                : " 服务端草稿会继续复用；照片、截图和录音需重新选择后上传。";
        }
        $("#restore-message").textContent = message;
        $("#restore-notice").hidden = false;
    }

    function renderUploadedBadges() {
        $("#photo-uploaded-badge").hidden = !state.submission.uploadedMedia.includes(MEDIA.photo);
        $("#wechat-uploaded-badge").hidden = !state.submission.uploadedMedia.includes(MEDIA.wechat);
        $("#audio-uploaded-badge").hidden = !state.submission.uploadedMedia.includes(MEDIA.audio);
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
                credentials: "omit",
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
