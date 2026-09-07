(function () {
    "use strict";

    const API_BASE = "/sales-checkin/admin/api/v1";
    const EXPORT_PATH = "/sales-checkin/admin/export.xlsx";
    const MEDIA_PATH = "/sales-checkin/admin/submissions";
    const PAGE_SIZE = 20;
    const SORT_LABELS = { completedAt: "打卡时间", cityName: "城市", salespersonName: "销售", storeName: "门店" };
    const LOCATION_LABELS = { GOOD: "定位新鲜", LOW_ACCURACY: "低精度", STALE: "位置过期", TIME_UNKNOWN: "采样时间未知", MISSING: "未取得位置", USER_REPORTED: "销售报告异常", OUT_OF_RANGE: "超出门店范围", STORE_UNLOCATED: "门店未定位", LEGACY: "历史位置" };
    const REVIEW_LABELS = { PENDING: "待复核", APPROVED: "已核实拜访", FOLLOW_UP: "需补充说明", FLAGGED: "异常已确认" };

    const state = {
        scope: {
            username: "",
            allCities: false,
            city: "",
            canDeleteSubmissions: false,
            canManageSalespersons: false,
            canManageCities: false,
            csrfToken: ""
        },
        cities: [],
        salespersons: [],
        mediaStats: null,
        audioIntelligenceEnabled: false,
        filters: { q: "", from: "", to: "", city: "", salespersonId: "", status: "", visitType: "",
            locationStatus: "", reviewStatus: "", mediaStatus: "", sortBy: "completedAt", sortDirection: "desc" },
        page: 0,
        total: 0,
        firstVisitTotal: 0,
        revisitTotal: 0,
        totalPages: 1,
        loading: false,
        controller: null,
        attendance: { controller: null, loading: false, page: 0, totalPages: 0 },
        itemsById: new Map(),
        currentItemIds: [],
        selectedIds: new Set(),
        activeView: "records",
        detailId: null,
        detailTrigger: null,
        detailScroll: null,
        reviewBusy: false,
        reviewRequest: null,
        reviewController: null,
        activeAudio: null,
        previewTrigger: null,
        previewIdentity: null,
        pendingDelete: null,
        actionBusy: false,
        batchDeleteBusy: false,
        successTimer: null,
        sales: {
            filters: { q: "", city: "", status: "" },
            page: 0,
            total: 0,
            totalPages: 1,
            loading: false,
            loaded: false,
            controller: null,
            itemsById: new Map(),
            dialogTrigger: null,
            resetTarget: null,
            resetTrigger: null,
            actionBusy: false
        },
        cityDirectory: {
            items: [],
            accounts: [],
            loaded: false,
            loading: false,
            actionBusy: false
        }
    };

    const $ = (selector, root = document) => root.querySelector(selector);

    document.addEventListener("DOMContentLoaded", init);

    async function init() {
        bindEvents();
        readFiltersFromUrl();
        writeFiltersToForm();
        try {
            const identity = unwrap(await requestJson(`${API_BASE}/auth/me`));
            if (identity.mustChangePassword === true) {
                showChangePasswordDialog(identity);
                return;
            }
            await enterAdmin(identity);
        } catch (error) {
            if (error.status === 401) {
                showLoginDialog();
                return;
            }
            showError(errorMessage(error, "后台数据加载失败，请确认管理账号权限后重试。"));
            renderLoading(false);
            $("#admin-main").hidden = false;
        }
    }

    async function enterAdmin(identity) {
        applyAdminIdentity(identity);
        const options = unwrap(await requestJson(`${API_BASE}/options`));
        applyOptions(options);
        applyAdminIdentity(identity);
        closeAuthDialog("#login-dialog");
        closeAuthDialog("#change-password-dialog");
        $("#admin-main").hidden = false;
        $("#logout-button").hidden = false;
        await loadSubmissions();
    }

    function bindEvents() {
        $("#login-form").addEventListener("submit", loginAdmin);
        $("#login-dialog").addEventListener("cancel", (event) => event.preventDefault());
        $("#change-password-form").addEventListener("submit", changeAdminPassword);
        $("#change-password-dialog").addEventListener("cancel", (event) => event.preventDefault());
        $("#change-password-logout").addEventListener("click", logoutAdmin);
        $("#logout-button").addEventListener("click", logoutAdmin);
        document.querySelectorAll("[data-admin-view]").forEach((tab) => {
            tab.addEventListener("click", () => switchAdminView(tab.dataset.adminView));
            tab.addEventListener("keydown", handleTabKeyboard);
        });
        $("#filter-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            if (!readFiltersFromForm()) return;
            state.page = 0;
            updateBrowserUrl();
            updateExportLink();
            await loadSubmissions();
        });
        $("#filter-city").addEventListener("change", () => {
            const currentSalesperson = $("#filter-salesperson").value;
            renderSalespersonOptions($("#filter-city").value, currentSalesperson, false);
        });
        document.querySelectorAll("[data-attendance-range]").forEach(button => {
            button.addEventListener("click", () => applyAttendanceRange(button.dataset.attendanceRange));
        });
        $("#attendance-retry").addEventListener("click", () => loadAttendanceSummary(state.attendance.page));
        $("#attendance-previous").addEventListener("click", () => changeAttendancePage(state.attendance.page - 1));
        $("#attendance-next").addEventListener("click", () => changeAttendancePage(state.attendance.page + 1));
        document.querySelectorAll("[data-sort-by]").forEach((button) => {
            button.addEventListener("click", () => changeSort(button.dataset.sortBy));
        });
        $("#review-form").addEventListener("submit", saveReview);
        $("#shared-audio-close").addEventListener("click", stopSharedAudio);
        $("#shared-audio-segment").addEventListener("change", (event) => {
            if (state.activeAudio) playAudioSegment(state.activeAudio.item, event.target.value);
        });
        $("#shared-audio").addEventListener("loadedmetadata", updatePlayingDuration);
        $("#shared-audio").addEventListener("error", () => {
            if (!state.activeAudio) return;
            $("#shared-audio-status").textContent = "暂时无法播放：会话可能失效、文件不可用或浏览器不支持此格式。可重试播放或下载原录音。";
        });
        $("#reset-button").addEventListener("click", resetFilters);
        $("#retry-button").addEventListener("click", loadSubmissions);
        $("#success-close-button").addEventListener("click", hideSuccess);
        $("#previous-page").addEventListener("click", () => changePage(state.page - 1));
        $("#next-page").addEventListener("click", () => changePage(state.page + 1));
        $("#select-page").addEventListener("change", toggleCurrentPageSelection);
        $("#clear-selection-button").addEventListener("click", clearSelection);
        $("#bulk-delete-button").addEventListener("click", openBatchDeleteDialog);
        $("#submission-detail-dialog").addEventListener("click", (event) => {
            if (event.target === event.currentTarget) closeSubmissionDetail();
        });
        $("#submission-detail-dialog").addEventListener("close", () => {
            if (!$("#submission-detail-dialog").hasAttribute("open")) cleanupSubmissionDetail();
        });
        $("#detail-close").addEventListener("click", closeSubmissionDetail);
        $("#image-preview-close").addEventListener("click", closeImagePreview);
        $("#image-preview-dialog").addEventListener("click", (event) => {
            if (event.target === event.currentTarget) closeImagePreview();
        });
        $("#image-preview-dialog").addEventListener("close", cleanupImagePreview);
        $("#image-preview-content").addEventListener("load", () => {
            $("#image-preview-content").hidden = false;
            $("#image-preview-error").hidden = true;
        });
        $("#image-preview-content").addEventListener("error", () => {
            $("#image-preview-content").hidden = true;
            $("#image-preview-error").hidden = false;
        });
        $("#delete-media-form").addEventListener("submit", deletePendingMedia);
        $("#delete-media-close").addEventListener("click", closeDeleteDialog);
        $("#delete-media-cancel").addEventListener("click", closeDeleteDialog);
        $("#delete-media-dialog").addEventListener("click", (event) => {
            if (event.target === event.currentTarget && !state.actionBusy) closeDeleteDialog();
        });
        $("#delete-media-dialog").addEventListener("close", cleanupDeleteDialog);
        $("#delete-media-dialog").addEventListener("cancel", (event) => {
            if (state.actionBusy) event.preventDefault();
        });
        $("#delete-media-reason").addEventListener("input", updateDeleteConfirmation);
        $("#delete-media-acknowledge").addEventListener("change", updateDeleteConfirmation);
        $("#batch-delete-form").addEventListener("submit", deleteSelectedSubmissions);
        $("#batch-delete-close").addEventListener("click", closeBatchDeleteDialog);
        $("#batch-delete-cancel").addEventListener("click", closeBatchDeleteDialog);
        $("#batch-delete-dialog").addEventListener("click", (event) => {
            if (event.target === event.currentTarget && !state.batchDeleteBusy) closeBatchDeleteDialog();
        });
        $("#batch-delete-dialog").addEventListener("close", cleanupBatchDeleteDialog);
        $("#batch-delete-dialog").addEventListener("cancel", (event) => {
            if (state.batchDeleteBusy) event.preventDefault();
        });
        $("#batch-delete-reason").addEventListener("input", updateBatchDeleteConfirmation);
        $("#batch-delete-acknowledge").addEventListener("change", updateBatchDeleteConfirmation);

        $("#salesperson-filter-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            readSalespersonFilters();
            state.sales.page = 0;
            await loadSalespersons();
        });
        $("#salesperson-previous-page").addEventListener("click", () => changeSalespersonPage(state.sales.page - 1));
        $("#salesperson-next-page").addEventListener("click", () => changeSalespersonPage(state.sales.page + 1));
        $("#add-salesperson-button").addEventListener("click", (event) => openSalespersonDialog(null, event.currentTarget));
        $("#salesperson-dialog-close").addEventListener("click", closeSalespersonDialog);
        $("#salesperson-dialog-cancel").addEventListener("click", closeSalespersonDialog);
        $("#salesperson-dialog").addEventListener("close", cleanupSalespersonDialog);
        $("#salesperson-dialog").addEventListener("cancel", (event) => {
            if (state.sales.actionBusy) event.preventDefault();
        });
        $("#salesperson-form").addEventListener("submit", saveSalesperson);

        $("#credential-reset-close").addEventListener("click", closeCredentialResetDialog);
        $("#credential-reset-cancel").addEventListener("click", closeCredentialResetDialog);
        $("#credential-reset-dialog").addEventListener("close", cleanupCredentialResetDialog);
        $("#credential-reset-dialog").addEventListener("cancel", (event) => {
            if (state.sales.actionBusy) event.preventDefault();
        });
        $("#credential-reset-form").addEventListener("submit", resetSalespersonCredential);
        $("#credential-copy-button").addEventListener("click", copyTemporaryCredential);
        $("#city-create-form").addEventListener("submit", createCity);
        $("#city-password-copy-button").addEventListener("click", copyCityTemporaryPassword);
        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape" && $("#image-preview-dialog").hasAttribute("open")) {
                closeImagePreview();
            }
        });
    }

    function showLoginDialog(message) {
        state.attendance.controller?.abort();
        state.attendance.controller = null;
        clearAttendanceView();
        $("#admin-main").hidden = true;
        $("#logout-button").hidden = true;
        closeAuthDialog("#change-password-dialog");
        const error = $("#login-error");
        error.textContent = cleanText(message);
        error.hidden = !error.textContent;
        $("#login-password").value = "";
        openAuthDialog("#login-dialog", "#login-username");
    }

    function showChangePasswordDialog(identity) {
        applyAdminIdentity(identity);
        $("#admin-main").hidden = true;
        $("#logout-button").hidden = true;
        closeAuthDialog("#login-dialog");
        $("#change-password-form").reset();
        $("#change-password-error").hidden = true;
        $("#change-password-error").textContent = "";
        openAuthDialog("#change-password-dialog", "#change-current-password");
    }

    function openAuthDialog(selector, focusSelector) {
        const dialog = $(selector);
        if (!dialog.hasAttribute("open")) {
            if (typeof dialog.showModal === "function") dialog.showModal();
            else dialog.setAttribute("open", "");
        }
        syncDialogState();
        window.setTimeout(() => $(focusSelector).focus(), 0);
    }

    function closeAuthDialog(selector) {
        const dialog = $(selector);
        if (!dialog || !dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else dialog.removeAttribute("open");
        syncDialogState();
    }

    async function loginAdmin(event) {
        event.preventDefault();
        const username = cleanText($("#login-username").value);
        const password = $("#login-password").value;
        if (!username || !password) return;
        const submit = $("#login-submit");
        const error = $("#login-error");
        submit.disabled = true;
        submit.textContent = "正在登录…";
        error.hidden = true;
        error.textContent = "";
        try {
            const payload = await requestAuth(`${API_BASE}/auth/login`, { username, password });
            const identity = unwrap(payload).account || unwrap(payload);
            $("#login-password").value = "";
            if (identity.mustChangePassword === true) {
                showChangePasswordDialog(identity);
                return;
            }
            await enterAdmin(identity);
        } catch (requestError) {
            error.textContent = errorMessage(requestError, "登录失败，请检查用户名和密码。");
            error.hidden = false;
            $("#login-password").focus();
            $("#login-password").select();
        } finally {
            submit.disabled = false;
            submit.textContent = "登录";
        }
    }

    async function changeAdminPassword(event) {
        event.preventDefault();
        const currentPassword = $("#change-current-password").value;
        const newPassword = $("#change-new-password").value;
        const confirmation = $("#change-confirm-password").value;
        const submit = $("#change-password-submit");
        const error = $("#change-password-error");
        error.hidden = true;
        error.textContent = "";
        if (newPassword !== confirmation) {
            error.textContent = "两次输入的新密码不一致。";
            error.hidden = false;
            $("#change-confirm-password").focus();
            return;
        }
        submit.disabled = true;
        submit.textContent = "正在保存…";
        try {
            const payload = await requestAction(`${API_BASE}/auth/change-password`, {
                method: "POST", body: { currentPassword, newPassword }
            });
            const identity = payload.account || payload;
            $("#change-password-form").reset();
            await enterAdmin(identity);
            showSuccess("密码已修改，后续可使用新密码登录。");
        } catch (requestError) {
            if (requestError.status === 401) {
                showLoginDialog("会话已失效，请重新登录。");
                return;
            }
            error.textContent = errorMessage(requestError, "密码修改失败，请检查当前密码。");
            error.hidden = false;
        } finally {
            submit.disabled = false;
            submit.textContent = "保存新密码并进入后台";
        }
    }

    async function logoutAdmin() {
        stopSharedAudio();
        $("#logout-button").disabled = true;
        try {
            await requestAction(`${API_BASE}/auth/logout`, { method: "POST" });
            state.scope.csrfToken = "";
            state.itemsById.clear();
            state.selectedIds.clear();
            showLoginDialog("已退出，可使用其他管理账号登录。");
        } catch (error) {
            if (error.status === 401) {
                state.scope.csrfToken = "";
                showLoginDialog("会话已结束，请重新登录。");
            } else {
                showError(errorMessage(error, "退出失败，请稍后重试。"));
            }
        } finally {
            $("#logout-button").disabled = false;
        }
    }

    function applyOptions(payload) {
        const scope = payload && typeof payload.scope === "object" ? payload.scope : {};
        state.scope = {
            username: cleanText(scope.username || payload.username || "未知账号"),
            allCities: scope.allCities === true,
            city: cleanText(scope.city || ""),
            canDeleteSubmissions: scope.canDeleteSubmissions === true
                || payload.canDeleteSubmissions === true || scope.allCities === true,
            canManageSalespersons: scope.canManageSalespersons === true
                || payload.canManageSalespersons === true || state.scope.canManageSalespersons,
            canManageCities: scope.canManageCities === true || payload.canManageCities === true
                || state.scope.canManageCities,
            csrfToken: state.scope.csrfToken
        };
        state.cities = uniqueStrings(Array.isArray(payload.cities) ? payload.cities : []);
        state.salespersons = Array.isArray(payload.salespersons) ? payload.salespersons : [];
        state.mediaStats = payload.mediaStats && typeof payload.mediaStats === "object"
            ? payload.mediaStats : null;
        state.audioIntelligenceEnabled = payload.audioIntelligenceEnabled === true;

        if (!state.scope.allCities && state.scope.city) {
            state.filters.city = state.scope.city;
        }
        renderScope();
        renderMediaStats();
        renderCityOptions();
        renderSalespersonOptions(state.filters.city, state.filters.salespersonId);
        renderDirectoryPermissions();
        renderSalespersonDirectoryCities();
        writeFiltersToForm();
        updateExportLink();
    }

    function renderScope() {
        $("#scope-username").textContent = state.scope.username || "未知账号";
        $("#scope-range").textContent = state.scope.allCities
            ? "全部城市"
            : (state.scope.city ? `${state.scope.city}（仅本城市）` : "未配置城市范围");
    }

    function applyAdminIdentity(identity) {
        if (!identity || typeof identity !== "object") return;
        state.scope = {
            username: cleanText(identity.username || state.scope.username),
            allCities: identity.allCities === true,
            city: cleanText(identity.city || state.scope.city),
            canDeleteSubmissions: Boolean(identity.accountId || identity.username)
                && identity.canDeleteSubmissions !== false,
            canManageSalespersons: identity.canManageSalespersons === true,
            canManageCities: identity.canManageCities === true,
            csrfToken: cleanText(identity.csrfToken || state.scope.csrfToken)
        };
        if (!state.scope.allCities && state.scope.city) state.filters.city = state.scope.city;
        renderScope();
        renderCityOptions();
        renderSalespersonOptions(state.filters.city, state.filters.salespersonId);
        renderDirectoryPermissions();
        renderSalespersonDirectoryCities();
        writeFiltersToForm();
        updateExportLink();
    }

    function renderMediaStats() {
        const card = $("#media-storage-card");
        if (!state.mediaStats) {
            card.hidden = true;
            return;
        }
        card.hidden = false;
        $("#storage-active-files").textContent = formatCount(numberValue(state.mediaStats.activeFiles));
        $("#storage-total-bytes").textContent = formatBytes(state.mediaStats.totalBytes);
        $("#storage-image-bytes").textContent = formatBytes(state.mediaStats.imageBytes);
        $("#storage-audio-bytes").textContent = formatBytes(state.mediaStats.audioBytes);
        $("#storage-oldest-created-at").textContent = state.mediaStats.oldestCreatedAt
            ? formatFullDateTime(state.mediaStats.oldestCreatedAt) : "暂无文件";
        $("#storage-scope-note").textContent = state.scope.allCities
            ? "统计全部城市的有效媒体，不执行自动清理。"
            : `仅统计${state.scope.city || "当前范围"}可见媒体，不执行自动清理。`;
    }

    function renderCityOptions() {
        const select = $("#filter-city");
        const current = state.scope.allCities ? state.filters.city : state.scope.city;
        select.replaceChildren();
        if (state.scope.allCities) {
            select.appendChild(option("", "全部城市"));
            state.cities.forEach((city) => select.appendChild(option(city, city)));
            select.disabled = false;
            $("#city-scope-hint").hidden = true;
        } else {
            select.appendChild(option(state.scope.city, state.scope.city || "未配置城市"));
            select.disabled = true;
            $("#city-scope-hint").hidden = false;
        }
        select.value = current;
    }

    function renderSalespersonOptions(city, selectedId, updateAppliedFilter = true) {
        const select = $("#filter-salesperson");
        const people = state.salespersons
            .filter((person) => !city || cleanText(person.city) === city)
            .slice()
            .sort((left, right) => cleanText(left.name).localeCompare(cleanText(right.name), "zh-CN"));
        select.replaceChildren(option("", "全部销售"));
        people.forEach((person) => {
            const id = cleanText(person.id || person.salespersonId);
            if (id) select.appendChild(option(id, cleanText(person.name || person.salespersonName || id)));
        });
        if (selectedId && Array.from(select.options).some((item) => item.value === selectedId)) {
            select.value = selectedId;
        } else if (selectedId && updateAppliedFilter) {
            // A historical salesperson may be absent from the current directory; keep the exact filter on reload.
            select.appendChild(option(selectedId, "已选销售（未在当前目录）"));
            select.value = selectedId;
        } else {
            select.value = "";
        }
    }

    function clearAttendanceView() {
        for (const id of ["attendance-total", "attendance-salespeople", "attendance-pending-review"]) $("#" + id).textContent = "--";
        $("#attendance-rows").replaceChildren();
        $("#attendance-table-wrap").hidden = true;
        $("#attendance-pagination").hidden = true;
    }

    async function loadAttendanceSummary(page = 0) {
        state.attendance.controller?.abort();
        const controller = new AbortController();
        state.attendance.controller = controller;
        state.attendance.page = page;
        state.attendance.loading = true;
        const params = buildFilterParams();
        params.set("summaryPage", String(page));
        params.set("summarySize", "50");
        clearAttendanceView();
        $("#attendance-retry").hidden = true;
        $("#attendance-state").textContent = "正在读取当前筛选的全量统计…";
        try {
            const payload = unwrap(await requestJson(`${API_BASE}/submissions/attendance-summary?${params}`, controller.signal));
            if (state.attendance.controller !== controller) return;
            const count = value => Number.isInteger(value) && value >= 0;
            if (!payload || ![payload.totalVisits, payload.checkedInSalespeople, payload.pendingReviewTotal,
                payload.totalElements, payload.totalPages].every(count) || payload.page !== page || !Array.isArray(payload.items)
                || (payload.totalElements > 0 && payload.totalPages < 1)
                || payload.items.some(item => !safeDate(item.date) || !cleanText(item.salespersonId)
                    || ![item.visitCount, item.storeCount, item.pendingReviewCount].every(count))) {
                throw new Error("统计响应尚未完整返回");
            }
            if (payload.totalElements > 0 && page >= payload.totalPages) return loadAttendanceSummary(payload.totalPages - 1);
            state.attendance.totalPages = payload.totalPages;
            $("#attendance-total").textContent = formatCount(payload.totalVisits);
            $("#attendance-salespeople").textContent = formatCount(payload.checkedInSalespeople);
            $("#attendance-pending-review").textContent = formatCount(payload.pendingReviewTotal);
            $("#attendance-state").textContent = payload.items.length
                ? `每日按城市、销售汇总，共 ${formatCount(payload.totalElements)} 组；指标覆盖当前筛选全部记录。`
                : state.filters.status === "DRAFT" ? "当前筛选为草稿，没有已提交的每日打卡汇总。"
                    : "当前筛选没有已提交的每日打卡记录。";
            renderAttendanceRows(payload.items);
            $("#attendance-table-wrap").hidden = payload.items.length === 0;
            $("#attendance-pagination").hidden = payload.totalElements === 0;
            $("#attendance-page-indicator").textContent = `第 ${page + 1} / ${payload.totalPages} 页 · 共 ${formatCount(payload.totalElements)} 组`;
            $("#attendance-previous").disabled = page <= 0;
            $("#attendance-next").disabled = page + 1 >= payload.totalPages;
        } catch (error) {
            if (error.name === "AbortError" || state.attendance.controller !== controller) return;
            $("#attendance-state").textContent = errorMessage(error, "统计读取失败，请重试；下方明细仍可独立查看。");
            $("#attendance-retry").hidden = false;
        } finally {
            if (state.attendance.controller === controller) state.attendance.loading = false;
        }
    }

    function renderAttendanceRows(items) {
        const root = $("#attendance-rows");
        root.replaceChildren();
        for (const item of items) {
            const row = document.createElement("tr");
            const time = value => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat("zh-CN", {
                timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
            }).format(new Date(value)) : "--";
            for (const value of [item.date, item.city || "未记录", item.salespersonName || "未记录销售",
                formatCount(item.visitCount), formatCount(item.storeCount), time(item.firstCheckinAt), time(item.lastCheckinAt), formatCount(item.pendingReviewCount)]) {
                const cell = document.createElement("td"); cell.textContent = value; row.append(cell);
            }
            const action = document.createElement("td"); const button = document.createElement("button");
            button.type = "button"; button.className = "text-button"; button.textContent = "查看明细";
            button.setAttribute("aria-label", `${item.date} ${item.city || ""} ${item.salespersonName || "销售"} 查看明细`);
            button.addEventListener("click", () => openAttendanceDetails(item));
            action.append(button); row.append(action); root.append(row);
        }
    }

    function changeAttendancePage(page) {
        if (state.attendance.loading || page < 0 || page >= state.attendance.totalPages) return;
        void loadAttendanceSummary(page);
    }

    async function openAttendanceDetails(item) {
        state.filters = { ...state.filters, from: item.date, to: item.date,
            city: state.scope.allCities ? cleanText(item.city) : state.scope.city,
            salespersonId: cleanText(item.salespersonId), status: "SUBMITTED" };
        state.page = 0;
        renderCityOptions();
        renderSalespersonOptions(state.filters.city, state.filters.salespersonId, false);
        const salesperson = $("#filter-salesperson");
        if (!Array.from(salesperson.options).some(option => option.value === state.filters.salespersonId)) {
            salesperson.append(option(state.filters.salespersonId, cleanText(item.salespersonName) || "历史销售"));
        }
        writeFiltersToForm(); updateBrowserUrl(); updateExportLink();
        await loadSubmissions();
        $("#data-heading").scrollIntoView({ behavior: "smooth", block: "start" });
    }

    async function applyAttendanceRange(range) {
        const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" })
            .formatToParts(new Date());
        const date = Object.fromEntries(parts.map(part => [part.type, part.value]));
        const today = `${date.year}-${date.month}-${date.day}`;
        const from = range === "month" ? `${date.year}-${date.month}-01` : range === "week"
            ? new Date(Date.parse(`${today}T00:00:00Z`) - 6 * 86400000).toISOString().slice(0, 10) : today;
        $("#filter-from").value = from; $("#filter-to").value = today;
        if (!readFiltersFromForm()) return;
        state.page = 0; updateBrowserUrl(); updateExportLink();
        await loadSubmissions();
    }

    async function loadSubmissions() {
        if (state.controller) state.controller.abort();
        const controller = new AbortController();
        state.controller = controller;
        clearSelection();
        hideError();
        renderLoading(true);
        void loadAttendanceSummary(0);
        try {
            const params = buildFilterParams();
            params.set("page", String(state.page));
            params.set("size", String(PAGE_SIZE));
            const response = await requestJson(`${API_BASE}/submissions?${params.toString()}`, controller.signal);
            if (state.controller !== controller) return;
            const payload = unwrap(response);
            applyResponseScope(payload.scope);
            const items = Array.isArray(payload.items) ? payload.items : [];
            state.itemsById = new Map(items.map((item) => [submissionId(item), item]).filter(([id]) => id));
            state.currentItemIds = [...state.itemsById.keys()];
            state.total = numberValue(payload.totalElements, payload.total, items.length);
            state.firstVisitTotal = numberValue(payload.firstVisitTotal, 0);
            state.revisitTotal = numberValue(payload.revisitTotal, 0);
            state.totalPages = Math.max(1, numberValue(payload.totalPages, Math.ceil(state.total / PAGE_SIZE)));
            state.page = Math.max(0, numberValue(payload.page, state.page));
            if (!items.length && state.total > 0 && state.page >= state.totalPages) {
                state.page = state.totalPages - 1;
                updateBrowserUrl();
                return loadSubmissions();
            }
            renderRows(items);
            if (state.detailId) {
                const current = state.itemsById.get(state.detailId);
                if (current) renderSubmissionDetail(current);
                else closeSubmissionDetail();
            }
            renderResultSummary(items.length);
            renderPagination();
            $("#result-total").textContent = formatCount(state.total);
            $("#result-first-visit-total").textContent = formatCount(state.firstVisitTotal);
            $("#result-revisit-total").textContent = formatCount(state.revisitTotal);
            $("#result-location-attention").textContent = optionalCount(payload.locationAttentionTotal);
            $("#result-review-pending").textContent = optionalCount(payload.reviewPendingTotal);
            $("#result-missing-audio").textContent = optionalCount(payload.missingAudioTotal);
            renderSort();
            renderLoading(false, items.length === 0);
        } catch (error) {
            if (error.name === "AbortError" || state.controller !== controller) return;
            renderLoading(false);
            $("#table-wrap").hidden = true;
            $("#pagination").hidden = true;
            $("#result-summary").textContent = "读取失败，当前条件的结果尚未确认";
            showError(errorMessage(error, "读取拜访记录失败，请稍后重试。"));
        }
    }

    function applyResponseScope(scope) {
        if (!scope || typeof scope !== "object") return;
        state.scope = {
            username: cleanText(scope.username || state.scope.username),
            allCities: scope.allCities === true,
            city: cleanText(scope.city || state.scope.city),
            canDeleteSubmissions: scope.canDeleteSubmissions === true
                || state.scope.canDeleteSubmissions || scope.allCities === true,
            canManageSalespersons: scope.canManageSalespersons === true
                || state.scope.canManageSalespersons,
            canManageCities: scope.canManageCities === true || state.scope.canManageCities,
            csrfToken: state.scope.csrfToken
        };
        renderScope();
        renderMediaStats();
        renderDirectoryPermissions();
    }

    function renderRows(items) {
        const root = $("#submission-rows");
        const template = $("#submission-row-template");
        root.replaceChildren();
        items.forEach((item) => {
            const row = template.content.firstElementChild.cloneNode(true);
            const field = (name) => row.querySelector(`[data-field="${name}"]`);
            const id = submissionId(item);
            const submittedAt = item.completedAt || item.submittedAt;
            field("time").textContent = submittedAt ? formatDateTime(submittedAt) : "尚未提交";

            const status = cleanText(item.status).toUpperCase();
            field("status").textContent = status === "SUBMITTED" ? "已提交" : (status === "DRAFT" ? "草稿" : status || "未知状态");
            field("status").classList.toggle("is-draft", status !== "SUBMITTED");
            renderVisitFrequency(row, item);

            field("city").textContent = cleanText(item.cityName || item.city) || "未记录城市";
            field("salesperson").textContent = cleanText(item.salespersonName) || "未记录销售";
            field("store").textContent = cleanText(item.storeName) || "未记录门店";
            field("customer").textContent = `客户：${cleanText(item.customerName) || "未记录"}`;
            renderPhone(field("phone"), item.customerPhone);
            field("result").textContent = cleanText(item.visitResult) || "未填写拜访结果";
            field("result").title = cleanText(item.visitResult);
            const readableAddress = locationAddress(item);
            const unverifiedLocation = isUnverifiedLocation(item);
            field("address").textContent = locationQualityLabel(item);
            field("address").title = readableAddress;
            field("address").classList.toggle("is-missing", unverifiedLocation || !readableAddress);
            renderRiskChips(field("risks"), item, { compact: true });
            renderEvidenceChips(field("evidence"), item);
            field("location-facts").textContent = locationFacts(item, { compact: true });
            renderRowPhoto(field("photo"), item);
            renderRowAudio(field("audio"), item);
            field("review").textContent = reviewStatusLabel(item.reviewStatus);
            field("review").classList.toggle("is-reviewed", item.reviewStatus === "APPROVED");

            const checkbox = field("select");
            checkbox.value = id;
            checkbox.disabled = !state.scope.canDeleteSubmissions || !id;
            checkbox.checked = state.selectedIds.has(id);
            checkbox.setAttribute("aria-label", `选择${cleanText(item.storeName) || "该条"}拜访记录`);
            checkbox.addEventListener("change", () => setSubmissionSelected(id, checkbox.checked));

            const detail = field("detail");
            detail.addEventListener("click", () => openSubmissionDetail(id, detail));
            root.appendChild(row);
        });
        updateSelectionUI();
    }

    function optionalCount(value) {
        return value === null || value === undefined ? "--" : formatCount(numberValue(value));
    }

    async function changeSort(sortBy) {
        if (state.loading || !Object.hasOwn(SORT_LABELS, sortBy)) return;
        if (!readFiltersFromForm()) return;
        state.filters.sortDirection = state.filters.sortBy === sortBy && state.filters.sortDirection === "asc"
            ? "desc" : "asc";
        state.filters.sortBy = sortBy;
        state.page = 0;
        renderSort();
        updateBrowserUrl();
        updateExportLink();
        await loadSubmissions();
    }

    function renderSort() {
        document.querySelectorAll("[data-sort-by]").forEach((button) => {
            const active = button.dataset.sortBy === state.filters.sortBy;
            const ascending = state.filters.sortDirection === "asc";
            button.closest("th").setAttribute("aria-sort", active ? (ascending ? "ascending" : "descending") : "none");
            $("[data-sort-indicator]", button).textContent = active ? (ascending ? "↑" : "↓") : "↕";
            button.setAttribute("aria-label", `${SORT_LABELS[button.dataset.sortBy]}，点击按${active && ascending ? "降序" : "升序"}排列全部结果`);
        });
        $("#sort-summary").textContent = `按${SORT_LABELS[state.filters.sortBy]}${state.filters.sortDirection === "asc" ? "升序" : "降序"}排列；排序作用于全部筛选结果，Excel 明细使用相同顺序。`;
    }

    function locationQualityLabel(item) {
        return LOCATION_LABELS[cleanText(item.locationQuality).toUpperCase()]
            || (isUnverifiedLocation(item) ? "定位未核验" : "历史位置");
    }

    function locationFacts(item, options = {}) {
        const parts = options.compact ? [] : [locationQualityLabel(item)];
        const accuracy = optionalNonNegativeNumber(item.accuracyMeters);
        if (accuracy !== null) parts.push(`精度 ±${Math.round(accuracy)} 米`);
        const quality = cleanText(item.locationQuality).toUpperCase();
        const distance = optionalNonNegativeNumber(item.distanceMeters);
        if (!options.compact && (quality === "STALE" || quality === "TIME_UNKNOWN")) parts.push("不作为当前到店距离");
        else if (distance !== null && (!options.compact || quality === "OUT_OF_RANGE")) parts.push(`距店约 ${Math.round(distance)} 米`);
        const captured = Date.parse(item.locationCapturedAt || "");
        const completed = Date.parse(item.completedAt || item.submittedAt || item.createdAt || "");
        if (quality !== "TIME_UNKNOWN" && Number.isFinite(captured) && Number.isFinite(completed)) {
            const seconds = Math.round((completed - captured) / 1000);
            parts.push(seconds >= 0 ? `采样距提交 ${seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分钟`}` : "采样时间晚于提交，待核对");
        }
        return parts.join(" · ");
    }

    function reviewStatusLabel(status) {
        return REVIEW_LABELS[cleanText(status).toUpperCase()] || "复核状态未记录";
    }

    function renderRowPhoto(root, item) {
        root.replaceChildren();
        const id = submissionId(item);
        const actualPhotos = Array.isArray(item.photos) ? item.photos.filter(photo => isUuid(photo.photoId)) : [];
        if (!isUuid(id) || (!actualPhotos.length && !item.storefrontPhotoAvailable)) {
            root.textContent = item.storefrontPhotoDeletedAt ? "照片已删除" : "暂无照片";
            return;
        }
        const photos = actualPhotos.length ? actualPhotos : [{photoId: null}];
        const grid = document.createElement("div");
        grid.className = "row-photo-gallery";
        grid.setAttribute("role", "group");
        grid.setAttribute("aria-label", `现场照片，共 ${photos.length} 张`);
        photos.forEach((photo, index) => {
            const kind = photo.photoId ? `photos/${photo.photoId}` : "storefront-photo";
            const button = document.createElement("button");
            button.type = "button";
            button.className = "row-thumbnail-button";
            button.setAttribute("aria-label", `查看${cleanText(item.storeName) || "门店"}第 ${index + 1} 张现场照片`);
            const image = document.createElement("img");
            image.src = mediaUrl(id, kind, { thumbnail: true });
            image.alt = `第 ${index + 1} 张现场照片缩略图`;
            image.width = 72;
            image.height = 72;
            image.loading = "lazy";
            image.decoding = "async";
            bindThumbnailFallback(image, () => {
                image.hidden = true;
                const notice = document.createElement("span");
                notice.textContent = "预览不可用\n点击看原图";
                button.replaceChildren(notice);
            });
            button.appendChild(image);
            button.addEventListener("click", () => openImagePreview(mediaUrl(id, kind),
                mediaUrl(id, kind, { download: true }), `现场照片 ${index + 1}`, button, { id, kind }));
            grid.appendChild(button);
        });
        root.appendChild(grid);
        if (photos.length > 1) {
            const count = document.createElement("span");
            count.className = "row-photo-count";
            count.textContent = `${photos.length} 张照片 · 可横向浏览`;
            root.appendChild(count);
        }
    }

    function bindThumbnailFallback(image, onUnavailable) {
        const initialUrl = image.getAttribute("src");
        let failures = 0;
        image.addEventListener("error", () => {
            if (failures > 2) return;
            failures += 1;
            if (failures <= 2) {
                window.setTimeout(() => {
                    if (image.isConnected && !image.hidden && failures <= 2) {
                        image.src = `${initialUrl}${initialUrl.includes("?") ? "&" : "?"}retry=${failures}`;
                    }
                }, failures === 1 ? 1500 : 4000);
            } else onUnavailable();
        });
    }

    function audioDurationLabel(segments) {
        if (!segments.length) return "未附录音";
        if (segments.some((segment) => segment.parsedDurationMs === null)) return "时长待解析";
        return formatAudioDurationMs(segments.reduce((sum, segment) => sum + segment.parsedDurationMs, 0));
    }

    function renderRowAudio(root, item) {
        root.replaceChildren();
        const segments = normalizeAudioSegments(item).filter((segment) => segment.available);
        if (!segments.length) { root.textContent = "未附录音"; return; }
        const button = document.createElement("button");
        button.type = "button";
        button.className = "row-audio-play";
        button.textContent = `播放 · ${audioDurationLabel(segments)}`;
        button.setAttribute("aria-label", `播放${cleanText(item.storeName)}录音，${audioDurationLabel(segments)}，共 ${segments.length} 段`);
        button.addEventListener("click", () => playAudioSegment(item, segments[0].segmentId));
        const caption = document.createElement("span");
        caption.textContent = `${segments.length} 段${segments.some((segment) => segment.playbackStatus === "PENDING") ? " · 播放副本处理中" : ""}`;
        root.append(button, caption);
    }

    function safePlaybackUrl(value) {
        if (!value) return null;
        try {
            const url = new URL(value, window.location.origin);
            return url.origin === window.location.origin && url.pathname.startsWith(`${MEDIA_PATH}/`)
                ? `${url.pathname}${url.search}` : null;
        } catch (_) { return null; }
    }

    function moveAudioDock(toDetail) {
        $(toDetail ? "#detail-audio-dock" : "#record-audio-dock").appendChild($("#shared-audio-panel"));
    }

    function playAudioSegment(item, segmentId) {
        const tracks = normalizeAudioSegments(item).filter((segment) => segment.available);
        const segment = tracks.find((track) => track.segmentId === segmentId);
        if (!segment) return;
        const id = submissionId(item);
        const audio = $("#shared-audio");
        audio.pause();
        state.activeAudio = { item, id, segmentId, tracks };
        moveAudioDock($("#submission-detail-dialog").hasAttribute("open"));
        const select = $("#shared-audio-segment");
        select.replaceChildren();
        tracks.forEach((track, index) => select.appendChild(option(track.segmentId,
            `第 ${index + 1} 段 · ${audioDurationLabel([track])}`)));
        select.value = segmentId;
        $("#shared-audio-title").textContent = `${cleanText(item.storeName) || "门店"} · 拜访录音`;
        $("#shared-audio-download").href = mediaUrl(id, "audio", { segmentId, download: true });
        $("#shared-audio-panel").hidden = false;
        const playback = segment.playbackStatus === "READY" ? safePlaybackUrl(segment.playbackUrl) : null;
        $("#shared-audio-status").textContent = playback ? "正在加载播放副本…"
            : "正在尝试播放原录音；格式不兼容时可下载原文件。";
        audio.preload = "none";
        audio.src = playback || mediaUrl(id, "audio", { segmentId });
        const request = state.activeAudio;
        audio.play().catch(() => {
            if (state.activeAudio !== request) return;
            $("#shared-audio-status").textContent = "播放尚未开始，请点击播放器播放键；文件不兼容或会话失效时可重新登录后重试。";
        });
    }

    function updatePlayingDuration() {
        const audio = $("#shared-audio");
        if (!state.activeAudio) return;
        const parsed = Number.isFinite(audio.duration) && audio.duration >= 0
            ? formatAudioDurationMs(audio.duration * 1000) : "暂时无法解析";
        $("#shared-audio-status").textContent = `当前播放器解析时长：${parsed}。录制时间以证据说明为准。`;
        document.querySelectorAll("[data-audio-media-duration]").forEach((field) => {
            if (field.dataset.segmentId === state.activeAudio.segmentId && field.dataset.durationSource !== "SERVER") field.textContent = parsed;
        });
    }

    function stopSharedAudio() {
        const audio = $("#shared-audio");
        audio.pause();
        audio.removeAttribute("src");
        audio.load();
        state.activeAudio = null;
        $("#shared-audio-panel").hidden = true;
    }

    function renderVisitFrequency(row, item) {
        const badge = row.querySelector(".visit-frequency");
        const label = visitFrequencyLabel(item);
        const rawType = cleanText(item.visitType).toUpperCase();
        const isFirst = positiveInteger(item.visitOrdinal) === 1
            || ["FIRST", "FIRST_VISIT", "INITIAL", "初访"].includes(rawType);
        badge.hidden = !label;
        badge.textContent = label;
        badge.classList.toggle("is-first", isFirst);
    }

    function visitFrequencyLabel(item) {
        const ordinal = positiveInteger(item.visitOrdinal);
        const explicitRevisitNumber = positiveInteger(item.revisitNumber);
        const rawType = cleanText(item.visitType).toUpperCase();
        const isFirst = ordinal === 1 || ["FIRST", "FIRST_VISIT", "INITIAL", "初访"].includes(rawType);
        const revisitNumber = explicitRevisitNumber || (ordinal > 1 ? ordinal - 1 : 0);

        let label = "";
        if (isFirst) {
            label = "初访";
        } else if (revisitNumber > 0) {
            label = `第${revisitNumber}次复访`;
        } else if (["REVISIT", "RETURN_VISIT", "复访"].includes(rawType)) {
            label = "复访";
        }

        return label;
    }

    function renderPhone(link, rawPhone) {
        const phone = cleanText(rawPhone);
        if (!phone) {
            link.hidden = true;
            return;
        }
        link.hidden = false;
        link.textContent = phone;
        link.href = `tel:${phone.replace(/[^+\d]/g, "")}`;
    }

    function submissionId(item) {
        return cleanText(item && (item.id || item.submissionId));
    }

    function normalizeAudioSegments(item) {
        if (Array.isArray(item?.audioSegments)) {
            return item.audioSegments
                .filter((segment) => isUuid(segment?.segmentId))
                .map((segment) => ({
                    segmentId: cleanText(segment.segmentId),
                    originalFilename: cleanText(segment.originalFilename) || "拜访录音",
                    sizeBytes: numberValue(segment.sizeBytes, 0),
                    contentType: cleanText(segment.contentType),
                    uploadedAt: segment.uploadedAt || null,
                    captureSource: normalizeAudioCaptureSource(segment.captureSource),
                    clientStartedAt: segment.clientStartedAt || null,
                    clientDurationMs: optionalNonNegativeNumber(segment.clientDurationMs),
                    parsedDurationMs: optionalNonNegativeNumber(segment.parsedDurationMs),
                    playbackStatus: cleanText(segment.playbackStatus).toUpperCase(),
                    playbackUrl: cleanText(segment.playbackUrl),
                    fileLastModifiedAt: segment.fileLastModifiedAt || null,
                    timingStatus: normalizeAudioTimingStatus(segment.timingStatus),
                    available: segment.available === true,
                    deletedAt: segment.deletedAt || null
                }));
        }
        const legacyId = submissionId(item);
        if (!isUuid(legacyId) || (item?.audioAvailable !== true && !item?.audioDeletedAt)) return [];
        return [{
            segmentId: legacyId,
            originalFilename: "历史拜访录音",
            sizeBytes: 0,
            contentType: "",
            uploadedAt: null,
            captureSource: "UNKNOWN",
            clientStartedAt: null,
            clientDurationMs: null,
            parsedDurationMs: null,
            playbackStatus: "",
            playbackUrl: "",
            fileLastModifiedAt: null,
            timingStatus: "MISSING",
            available: item.audioAvailable === true,
            deletedAt: item.audioDeletedAt || null
        }];
    }

    function normalizeAudioCaptureSource(value) {
        const source = cleanText(value).toUpperCase();
        return ["BROWSER_RECORDER", "FILE_UPLOAD"].includes(source) ? source : "UNKNOWN";
    }

    function normalizeAudioTimingStatus(value) {
        const status = cleanText(value).toUpperCase();
        return ["ALIGNED", "MISMATCH", "UNVERIFIED_FILE", "MISSING"].includes(status)
            ? status : "MISSING";
    }

    function optionalNonNegativeNumber(value) {
        if (value === null || value === undefined || value === "") return null;
        const number = Number(value);
        return Number.isFinite(number) && number >= 0 ? number : null;
    }

    function locationAddress(item) {
        return cleanText(item.locationAddress || item.formattedAddress || item.readableAddress
            || (item.location && item.location.address));
    }

    function isUnverifiedLocation(item) {
        return cleanText(item?.locationVerificationStatus).toUpperCase() === "UNVERIFIED";
    }

    function locationFailureLabel(rawReason) {
        const labels = {
            PERMISSION_DENIED: "用户拒绝定位权限",
            POSITION_UNAVAILABLE: "设备暂时无法提供位置",
            TIMEOUT: "定位等待超时",
            UNSUPPORTED: "浏览器不支持定位",
            INSECURE_CONTEXT: "当前浏览器环境无法安全定位",
            INVALID_POSITION: "设备返回的坐标无效",
            TIMESTAMP_UNUSABLE: "定位采集时间不可用",
            ACCURACY_INSUFFICIENT: "定位精度不足",
            RESOLVE_FAILED: "定位解析服务不可用",
            USER_CONTINUED_AFTER_WAIT: "销售等待后选择先继续录入"
        };
        const reason = cleanText(rawReason).toUpperCase();
        return labels[reason] || reason || "未记录失败原因";
    }

    function renderRiskChips(root, item, options = {}) {
        root.replaceChildren();
        const status = cleanText(item.status).toUpperCase();
        const riskLevel = cleanText(item.riskLevel).toUpperCase();
        const riskLabels = {
            DEVICE_MULTIPLE_SALES: ["同设备切换销售", "danger"],
            SALESPERSON_MULTIPLE_DEVICES: ["销售多设备", "warning"],
            SALESPERSON_IP_CHURN: ["IP频繁切换", "warning"],
            SHARED_IP_MULTIPLE_SALES: ["多人共享IP", "muted"],
            LOCATION_UNVERIFIED: ["定位未核验", "warning"]
        };
        const flags = Array.isArray(item.riskFlags) ? item.riskFlags : [];
        if (options.compact) {
            flags.forEach((rawFlag) => {
                const flag = cleanText(rawFlag).toUpperCase();
                const mapped = riskLabels[flag];
                if (mapped && flag !== "LOCATION_UNVERIFIED") appendChip(root, mapped[0], mapped[1]);
            });
            if (riskLevel === "HIGH" && !root.hasChildNodes()) appendChip(root, "高风险·需复核", "danger");
            return;
        }
        if (isUnverifiedLocation(item)) appendChip(root, "定位未核验·需结合照片复核", "warning");
        if (item.locationQuality && !["GOOD", "LEGACY"].includes(item.locationQuality)) {
            appendChip(root, locationQualityLabel(item), "warning");
        }
        if (riskLevel === "HIGH") appendChip(root, "高风险·需复核", "danger");
        else if (riskLevel === "MEDIUM") appendChip(root, "中风险·需复核", "warning");
        else if (riskLevel === "LOW") appendChip(root, "低风险提示", "muted");
        flags.forEach((rawFlag) => {
            const flag = cleanText(rawFlag).toUpperCase();
            if (flag === "LOCATION_UNVERIFIED" && isUnverifiedLocation(item)) return;
            const mapped = riskLabels[flag];
            if (mapped) appendChip(root, mapped[0], mapped[1]);
        });
        if (status === "DRAFT") appendChip(root, "草稿未完成", "warning");
        if (!isUnverifiedLocation(item) && !locationAddress(item)) appendChip(root, "地址未解析", "warning");
        if (item.accuracyMeters === null || item.accuracyMeters === undefined || item.accuracyMeters === "") {
            appendChip(root, "精度未记录", "muted");
        }
        if (status === "SUBMITTED" && item.storefrontPhotoAvailable !== true) {
            appendChip(root, item.storefrontPhotoDeletedAt ? "打卡照已删除" : "打卡照缺失", "danger");
        }
        if (isFailedTranscription(item.transcriptionStatus) || isFailedSummary(item.summaryStatus)) {
            appendChip(root, "录音处理异常", "danger");
        }
    }

    function renderEvidenceChips(root, item) {
        root.replaceChildren();
        renderEvidenceState(root, "打卡照", item.storefrontPhotoAvailable, item.storefrontPhotoDeletedAt, true);
        renderEvidenceState(root, "企微截图", item.wechatScreenshotAvailable, item.wechatScreenshotDeletedAt, false);
        const audioSegments = normalizeAudioSegments(item);
        const activeAudioCount = audioSegments.filter((segment) => segment.available).length;
        const deletedAudioCount = audioSegments.filter((segment) => segment.deletedAt).length;
        if (activeAudioCount) appendChip(root, `录音${activeAudioCount}段`, "success");
        if (deletedAudioCount) appendChip(root, `录音${deletedAudioCount}段已删`, "muted");
        if (hasAudioIntelligence(item)) {
            const status = cleanText(item.transcriptionStatus).toUpperCase();
            const tone = isFailedTranscription(status) ? "danger"
                : (["COMPLETED", "SUCCEEDED", "READY"].includes(status) ? "success"
                    : (["PENDING", "QUEUED", "SUBMITTING", "PROCESSING", "SUBMITTED", "RUNNING"].includes(status)
                        ? "processing" : "muted"));
            appendChip(root, transcriptionStatusText(status), tone);
        }
    }

    function renderEvidenceState(root, label, available, deletedAt, showMissing) {
        if (available === true) appendChip(root, label, "success");
        else if (deletedAt) appendChip(root, `${label}已删`, "muted");
        else if (showMissing) appendChip(root, `无${label}`, "danger");
    }

    function appendChip(root, label, tone) {
        const chip = document.createElement("span");
        chip.className = `compact-chip compact-chip--${tone}`;
        chip.textContent = label;
        root.appendChild(chip);
    }

    function setSubmissionSelected(id, selected) {
        if (!id || !state.scope.canDeleteSubmissions) return;
        if (selected) state.selectedIds.add(id);
        else state.selectedIds.delete(id);
        updateSelectionUI();
    }

    function toggleCurrentPageSelection(event) {
        if (!state.scope.canDeleteSubmissions) return;
        const selected = event.currentTarget.checked;
        state.currentItemIds.forEach((id) => {
            if (selected) state.selectedIds.add(id);
            else state.selectedIds.delete(id);
        });
        $("#submission-rows").querySelectorAll('[data-field="select"]').forEach((checkbox) => {
            checkbox.checked = selected;
        });
        updateSelectionUI();
    }

    function clearSelection() {
        state.selectedIds.clear();
        $("#submission-rows").querySelectorAll('[data-field="select"]').forEach((checkbox) => {
            checkbox.checked = false;
        });
        updateSelectionUI();
    }

    function updateSelectionUI() {
        const canDelete = state.scope.canDeleteSubmissions === true;
        document.body.classList.toggle("can-delete-submissions", canDelete);
        const selectedOnPage = state.currentItemIds.filter((id) => state.selectedIds.has(id)).length;
        const allSelected = state.currentItemIds.length > 0 && selectedOnPage === state.currentItemIds.length;
        const selectPage = $("#select-page");
        selectPage.disabled = !canDelete || state.currentItemIds.length === 0;
        selectPage.checked = canDelete && allSelected;
        selectPage.indeterminate = canDelete && selectedOnPage > 0 && !allSelected;
        $("#selected-count").textContent = formatCount(state.selectedIds.size);
        $("#bulk-toolbar").hidden = !canDelete || state.selectedIds.size === 0;
    }

    function openSubmissionDetail(id, trigger) {
        const item = state.itemsById.get(id);
        if (!item) {
            showError("该记录已不在当前页面，请刷新后重试。");
            return;
        }
        state.detailId = id;
        state.detailTrigger = trigger;
        state.detailScroll = { x: window.scrollX, y: window.scrollY, table: $("#table-wrap").scrollLeft };
        renderSubmissionDetail(item);
        const dialog = $("#submission-detail-dialog");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        syncDialogState();
        if (state.activeAudio) moveAudioDock(true);
        $("#detail-close").focus({ preventScroll: true });
        $("#detail-body").scrollTop = 0;
        loadReviewHistory(id);
    }

    function renderSubmissionDetail(item) {
        const title = cleanText(item.storeName) || "拜访记录";
        $("#detail-title").textContent = title;
        const headingBadges = $("#detail-heading-badges");
        headingBadges.replaceChildren();
        const status = cleanText(item.status).toUpperCase();
        appendChip(headingBadges, status === "SUBMITTED" ? "已提交" : (status === "DRAFT" ? "草稿" : status || "未知"),
            status === "SUBMITTED" ? "success" : "warning");
        const visitLabel = visitFrequencyLabel(item);
        if (visitLabel) appendChip(headingBadges, visitLabel, "muted");

        $("#detail-submitted-at").textContent = formatFullDateTime(item.submittedAt || item.createdAt);
        $("#detail-captured-at").textContent = item.locationQuality === "TIME_UNKNOWN"
            ? "采样时间未核验，请查看原始值"
            : (item.locationCapturedAt ? formatFullDateTime(item.locationCapturedAt) : "未记录");
        $("#detail-person").textContent = `${cleanText(item.city) || "城市未记录"} / ${cleanText(item.salespersonName) || "销售未记录"}`;
        const customer = $("#detail-customer");
        customer.replaceChildren(document.createTextNode(cleanText(item.customerName) || "客户未记录"));
        const phone = cleanText(item.customerPhone);
        if (phone) {
            customer.appendChild(document.createTextNode(" / "));
            const link = document.createElement("a");
            link.className = "inline-link";
            link.href = `tel:${phone.replace(/[^+\d]/g, "")}`;
            link.textContent = phone;
            customer.appendChild(link);
        }

        const address = locationAddress(item);
        const unverifiedLocation = isUnverifiedLocation(item);
        $("#detail-address").textContent = address || "设备位置地址未取得";
        $("#detail-address").classList.toggle("is-missing", unverifiedLocation || !address);
        const locationNote = cleanText(item.locationNote);
        $("#detail-location-note").textContent = locationNote ? `位置备注：${locationNote}` : "";
        $("#detail-location-verification").textContent = `${locationFacts(item)}。${unverifiedLocation
            ? `客户端报告：${locationFailureLabel(item.locationFailureReason)}。` : ""}设备报告点与门店登记点分别保存；位置偏差不能单独认定虚假拜访。`;
        const longitude = decimalText(item.longitude);
        const latitude = decimalText(item.latitude);
        $("#detail-coordinates").textContent = longitude && latitude
            ? `${longitude}, ${latitude}` : "经纬度未记录";
        const accuracy = decimalText(item.accuracyMeters);
        $("#detail-accuracy").textContent = accuracy ? `定位精度：±${accuracy} 米` : "定位精度未记录";
        $("#detail-device-point").textContent = longitude && latitude ? `${longitude}, ${latitude}` : "未取得，不用门店坐标代填";
        const storeLongitude = decimalText(item.storeLongitude);
        const storeLatitude = decimalText(item.storeLatitude);
        $("#detail-store-point").textContent = storeLongitude && storeLatitude ? `${storeLongitude}, ${storeLatitude}` : "门店未登记坐标";
        $("#detail-raw-timestamp").textContent = rawLocationTime(item.locationRawTimestamp, item.locationQuality);
        $("#detail-location-received-at").textContent = item.locationReceivedAt ? formatFullDateTime(item.locationReceivedAt) : "未记录";
        const distance = optionalNonNegativeNumber(item.distanceMeters);
        $("#detail-distance").textContent = ["STALE", "TIME_UNKNOWN"].includes(item.locationQuality)
            ? "采样不能代表当前到店位置，距离不作为当前判断"
            : (distance === null ? "无法计算" : `约 ${Math.round(distance)} 米，需结合精度判断`);
        $("#detail-location-source").textContent = `${locationSourceLabel(item.locationSource)} / ${locationQualityLabel(item)}`;
        renderLocationComparison(item);
        renderRiskChips($("#detail-risk-chips"), item);
        $("#detail-identity-method").textContent = identityMethodLabel(item.identityMethod);
        $("#detail-ip-masked").textContent = cleanText(item.submittedIpMasked) || "未记录";
        $("#detail-user-agent").textContent = cleanText(item.userAgentSummary) || "未记录";
        $("#detail-result").textContent = cleanText(item.visitResult) || "未填写拜访结果";
        renderMedia($("#detail-media"), item);
        renderReviewForm(item);
    }

    function rawLocationTime(value, quality) {
        if (value === null || value === undefined || value === "") return "未记录";
        return `${cleanText(String(value))}（设备原始值${quality === "TIME_UNKNOWN" ? "，时间未核验" : ""}）`;
    }

    // Pure geometry: use the device-centred spherical azimuthal equidistant projection.
    function locationComparisonGeometry(item) {
        const number = value => !["number", "string"].includes(typeof value) || String(value).trim() === ""
            ? null : (Number.isFinite(Number(value)) ? Number(value) : null);
        const point = (longitude, latitude) => {
            const lon = number(longitude), lat = number(latitude);
            return lon !== null && lat !== null && Math.abs(lon) <= 180 && Math.abs(lat) <= 90 ? { lon, lat } : null;
        };
        const device = point(item.longitude, item.latitude);
        const store = point(item.storeLongitude, item.storeLatitude);
        if (!device || !store) return { reason: !device ? "MISSING_DEVICE" : "MISSING_STORE" };
        if (!["BROWSER", "BROWSER_GEOLOCATION"].includes(item.locationSource)) return { reason: "UNCONFIRMED_CRS" };
        const radians = Math.PI / 180;
        const first = device.lat * radians, second = store.lat * radians;
        const deltaLatitude = second - first;
        const deltaLongitude = ((store.lon - device.lon + 540) % 360 - 180) * radians;
        const haversine = Math.min(1, Math.max(0, Math.sin(deltaLatitude / 2) ** 2
            + Math.cos(first) * Math.cos(second) * Math.sin(deltaLongitude / 2) ** 2));
        const angle = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        if (angle > Math.PI * 179 / 180) return { reason: "AMBIGUOUS_DIRECTION" };
        const bearing = Math.atan2(Math.sin(deltaLongitude) * Math.cos(second),
            Math.cos(first) * Math.sin(second) - Math.sin(first) * Math.cos(second) * Math.cos(deltaLongitude));
        const computedMeters = 6371000 * angle;
        const x = computedMeters * Math.sin(bearing), y = -computedMeters * Math.cos(bearing);
        const candidateAccuracy = number(item.accuracyMeters);
        const accuracy = candidateAccuracy !== null && candidateAccuracy >= 0 && candidateAccuracy <= 10000000
            ? candidateAccuracy : null;
        const radius = accuracy || 0;
        const minX = Math.min(-radius, x), maxX = Math.max(radius, x);
        const minY = Math.min(-radius, y), maxY = Math.max(radius, y);
        const scale = Math.min(330 / Math.max(20, maxX - minX), 150 / Math.max(20, maxY - minY));
        const centreX = (minX + maxX) / 2, centreY = (minY + maxY) / 2;
        return { deviceX: 200 - centreX * scale, deviceY: 110 - centreY * scale,
            storeX: 200 + (x - centreX) * scale, storeY: 110 + (y - centreY) * scale,
            radius: radius * scale, accuracy, computedMeters, reason: null };
    }

    function renderLocationComparison(item) {
        const container = $("#location-comparison-chart");
        container.replaceChildren();
        const geometry = locationComparisonGeometry(item);
        const note = $("#location-comparison-note");
        if (geometry.reason) {
            const reasons = { MISSING_DEVICE: "未取得设备点，保留门店坐标文字，不补造设备点或距离。",
                MISSING_STORE: "门店未登记坐标，保留设备坐标文字，暂不作双点对照。",
                UNCONFIRMED_CRS: "该来源的坐标系尚未确认，保留原始坐标文字，暂不绘制相对位置。",
                AMBIGUOUS_DIRECTION: "两点跨度过大，相对方向不稳定，请依据原始坐标复核。" };
            note.textContent = reasons[geometry.reason];
            return;
        }
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        const append = (name, attributes, text) => {
            const node = document.createElementNS("http://www.w3.org/2000/svg", name);
            Object.entries(attributes || {}).forEach(([key, value]) => node.setAttribute(key, String(value)));
            if (text) node.textContent = text;
            svg.appendChild(node);
            return node;
        };
        svg.setAttribute("viewBox", "0 0 400 220");
        svg.setAttribute("role", "img");
        svg.setAttribute("aria-label", "设备报告点、门店登记点与设备报告精度半径的相对位置；北向上");
        append("title", {}, "设备与门店相对位置，北向上，视窗随两点与设备精度缩放");
        if (geometry.radius > 0) append("circle", { cx: geometry.deviceX, cy: geometry.deviceY, r: geometry.radius, class: "comparison-accuracy" });
        append("line", { x1: geometry.deviceX, y1: geometry.deviceY, x2: geometry.storeX, y2: geometry.storeY, class: "comparison-connection" });
        append("circle", { cx: geometry.deviceX, cy: geometry.deviceY, r: 6, class: "comparison-device" });
        append("rect", { x: geometry.storeX - 5, y: geometry.storeY - 5, width: 10, height: 10, class: "comparison-store" });
        append("text", { x: 374, y: 21, "text-anchor": "middle", class: "comparison-north" }, "北 ↑");
        container.appendChild(svg);
        const legend = document.createElement("p");
        legend.className = "comparison-legend";
        legend.textContent = "● 设备报告点　■ 门店登记点　浅色圆：设备精度半径";
        container.appendChild(legend);
        const distance = optionalNonNegativeNumber(item.distanceMeters);
        const precision = geometry.accuracy === null ? "设备精度未记录，未绘制精度圈"
            : `设备报告精度 ±${Math.round(geometry.accuracy).toLocaleString("zh-CN")} 米`;
        const historical = ["STALE", "TIME_UNKNOWN", "LEGACY"].includes(item.locationQuality);
        note.textContent = `${precision}；${distance === null ? "后台未记录距离" : `后台记录两点间距约 ${Math.round(distance).toLocaleString("zh-CN")} 米`}。${historical
            ? "这是历史或未核验采样，不代表当前到店距离。" : "请结合照片与位置采样时间复核。"}精度圈是设备报告范围，门店点精度尚未记录。`;
    }

    function locationSourceLabel(source) {
        const labels = { BROWSER: "浏览器定位", BROWSER_GEOLOCATION: "浏览器定位", WECHAT: "微信定位", WECOM: "企业微信定位", MANUAL: "人工报告", UNKNOWN: "来源未记录" };
        return labels[source] || cleanText(source) || "来源未记录";
    }

    function renderReviewForm(item) {
        $("#detail-review-status").textContent = `${reviewStatusLabel(item.reviewStatus)}${item.reviewedBy ? ` · ${cleanText(item.reviewedBy)}` : ""}${item.reviewedAt ? ` · ${formatFullDateTime(item.reviewedAt)}` : ""}`;
        $("#review-form").hidden = item.status !== "SUBMITTED";
        if (state.reviewBusy) return;
        $("#review-status").value = ["APPROVED", "FOLLOW_UP", "FLAGGED"].includes(item.reviewStatus) ? item.reviewStatus : "APPROVED";
        $("#review-note").value = "";
        $("#review-error").hidden = true;
        state.reviewRequest = null;
    }

    async function loadReviewHistory(id) {
        if (state.reviewController) state.reviewController.abort();
        const controller = new AbortController();
        state.reviewController = controller;
        const root = $("#review-history");
        root.textContent = "正在读取复核记录…";
        try {
            const payload = unwrap(await requestJson(`${API_BASE}/submissions/${encodeURIComponent(id)}/reviews`, controller.signal));
            if (state.detailId !== id) return;
            const events = Array.isArray(payload) ? payload : (Array.isArray(payload.items) ? payload.items : []);
            root.replaceChildren();
            if (!events.length) { root.textContent = "暂无人工复核记录"; return; }
            events.forEach((event) => {
                const entry = document.createElement("article");
                const heading = document.createElement("strong");
                heading.textContent = `${reviewStatusLabel(event.status)} · ${cleanText(event.reviewedBy) || "处理人未记录"}`;
                const time = document.createElement("time");
                time.textContent = event.reviewedAt ? formatFullDateTime(event.reviewedAt) : "时间未记录";
                const note = document.createElement("p");
                note.textContent = cleanText(event.note);
                entry.append(heading, time, note);
                root.appendChild(entry);
            });
        } catch (error) {
            if (error.name === "AbortError" || state.detailId !== id) return;
            root.textContent = errorMessage(error, "复核记录读取失败。");
            const retry = document.createElement("button");
            retry.type = "button";
            retry.className = "text-button";
            retry.textContent = "重试读取复核记录";
            retry.addEventListener("click", () => loadReviewHistory(id));
            root.appendChild(retry);
        }
    }

    async function saveReview(event) {
        event.preventDefault();
        if (state.reviewBusy || !state.detailId) return;
        const id = state.detailId;
        const note = cleanText($("#review-note").value);
        const status = $("#review-status").value;
        const errorPanel = $("#review-error");
        errorPanel.hidden = true;
        if (note.length < 2 || note.length > 1000 || !["APPROVED", "FOLLOW_UP", "FLAGGED"].includes(status)) {
            errorPanel.textContent = "请选择复核结果并填写 2–1000 字的原因。";
            errorPanel.hidden = false;
            return;
        }
        if (!state.reviewRequest || state.reviewRequest.id !== id || state.reviewRequest.note !== note || state.reviewRequest.status !== status) {
            state.reviewRequest = { id, note, status, clientEventId: createRequestId() };
        }
        const request = state.reviewRequest;
        state.reviewBusy = true;
        $("#review-submit").disabled = true;
        $("#review-submit").textContent = "正在保存…";
        try {
            await requestAction(`${API_BASE}/submissions/${encodeURIComponent(id)}/review`, {
                body: { clientEventId: request.clientEventId, status, note }
            });
            state.reviewRequest = null;
            $("#review-note").value = "";
            showSuccess("复核记录已保存，处理人与原因已留痕。");
            await loadSubmissions();
            if (state.detailId === id) await loadReviewHistory(id);
        } catch (error) {
            if (state.detailId === id) {
                errorPanel.textContent = errorMessage(error, "复核保存结果未确认，请重试，重复请求不会重复登记。");
                errorPanel.hidden = false;
            }
        } finally {
            state.reviewBusy = false;
            $("#review-submit").disabled = false;
            $("#review-submit").textContent = "保存复核记录";
        }
    }

    function identityMethodLabel(rawMethod) {
        const method = cleanText(rawMethod).toUpperCase();
        if (method === "PERSONAL_CODE") return "销售个人码 + 已绑定设备";
        if (method === "LEGACY_ANONYMOUS") return "历史匿名记录";
        return method || "未记录";
    }

    function closeSubmissionDetail() {
        const dialog = $("#submission-detail-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else dialog.removeAttribute("open");
        cleanupSubmissionDetail();
    }

    function cleanupSubmissionDetail() {
        if (!state.detailId && !state.detailScroll) return;
        $("#detail-media").replaceChildren();
        const trigger = state.detailTrigger;
        const scroll = state.detailScroll;
        if (state.reviewController) state.reviewController.abort();
        moveAudioDock(false);
        state.detailId = null;
        state.detailTrigger = null;
        state.detailScroll = null;
        syncDialogState();
        if (trigger && document.contains(trigger)) trigger.focus({ preventScroll: true });
        if (scroll) { $("#table-wrap").scrollLeft = scroll.table; window.scrollTo(scroll.x, scroll.y); }
    }

    function openBatchDeleteDialog() {
        if (!state.scope.canDeleteSubmissions || state.selectedIds.size === 0 || state.batchDeleteBusy) return;
        if (state.selectedIds.size > PAGE_SIZE) {
            showError(`单次最多删除 ${PAGE_SIZE} 条，请减少选择后重试。`);
            return;
        }
        $("#batch-delete-description").textContent = `即将永久删除 ${formatCount(state.selectedIds.size)} 条完整拜访记录及其 COS 照片、录音。`;
        $("#batch-delete-form").reset();
        $("#batch-delete-error").hidden = true;
        $("#batch-delete-error").textContent = "";
        updateBatchDeleteConfirmation();
        const dialog = $("#batch-delete-dialog");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        syncDialogState();
        $("#batch-delete-reason").focus();
    }

    function closeBatchDeleteDialog() {
        if (state.batchDeleteBusy) return;
        const dialog = $("#batch-delete-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else {
            dialog.removeAttribute("open");
            cleanupBatchDeleteDialog();
        }
    }

    function cleanupBatchDeleteDialog() {
        state.batchDeleteBusy = false;
        $("#batch-delete-form").reset();
        $("#batch-delete-error").hidden = true;
        $("#batch-delete-error").textContent = "";
        $("#batch-delete-confirm").disabled = true;
        $("#batch-delete-confirm").textContent = "确认删除";
        syncDialogState();
        if ($("#bulk-delete-button").offsetParent !== null) $("#bulk-delete-button").focus();
    }

    function updateBatchDeleteConfirmation() {
        const reason = cleanText($("#batch-delete-reason").value);
        const acknowledged = $("#batch-delete-acknowledge").checked;
        $("#batch-delete-confirm").disabled = state.batchDeleteBusy || reason.length < 2 || !acknowledged;
    }

    async function deleteSelectedSubmissions(event) {
        event.preventDefault();
        const ids = [...state.selectedIds];
        const reason = cleanText($("#batch-delete-reason").value);
        if (!state.scope.canDeleteSubmissions || !ids.length || ids.length > PAGE_SIZE || reason.length < 2
                || !$("#batch-delete-acknowledge").checked || state.batchDeleteBusy) {
            updateBatchDeleteConfirmation();
            return;
        }
        state.batchDeleteBusy = true;
        if (state.activeAudio && ids.includes(state.activeAudio.id)) stopSharedAudio();
        const confirm = $("#batch-delete-confirm");
        confirm.disabled = true;
        confirm.textContent = "正在删除…";
        $("#batch-delete-error").hidden = true;
        try {
            const result = await requestAction(`${API_BASE}/submission-deletions`, {
                method: "POST",
                body: {
                    ids,
                    reason,
                    requestId: createRequestId(),
                    confirmation: "DELETE_SELECTED_SUBMISSIONS"
                }
            });
            const deletedCount = numberValue(result.deletedCount);
            const failures = Array.isArray(result.failures) ? result.failures : [];
            const failedCount = numberValue(result.failedCount, failures.length);
            state.batchDeleteBusy = false;
            closeBatchDeleteDialog();
            clearSelection();
            try {
                await refreshAdminData();
            } catch (refreshError) {
                showError(`删除请求已完成（成功 ${formatCount(deletedCount)} 条、失败 ${formatCount(failedCount)} 条），但列表刷新失败，请点击重试。`);
                return;
            }
            if (failedCount > 0) {
                const firstFailure = failures.length ? cleanText(failures[0].message || failures[0].reason) : "";
                showError(`已删除 ${formatCount(deletedCount)} 条，${formatCount(failedCount)} 条失败${firstFailure ? `：${firstFailure}` : ""}。`);
            } else {
                showSuccess(`已永久删除 ${formatCount(deletedCount)} 条拜访记录。`);
            }
        } catch (error) {
            state.batchDeleteBusy = false;
            confirm.textContent = "确认删除";
            updateBatchDeleteConfirmation();
            $("#batch-delete-error").textContent = errorMessage(error, "批量删除失败，请稍后重试。");
            $("#batch-delete-error").hidden = false;
        }
    }

    function createRequestId() {
        if (window.crypto && typeof window.crypto.randomUUID === "function") return window.crypto.randomUUID();
        const bytes = new Uint8Array(16);
        window.crypto.getRandomValues(bytes);
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function renderMedia(root, item) {
        root.replaceChildren();
        const id = cleanText(item.id || item.submissionId);
        if (!isUuid(id)) return renderEmptyMedia(root);
        if (Array.isArray(item.photos) && item.photos.some(photo => isUuid(photo.photoId))) {
            item.photos.filter(photo => isUuid(photo.photoId)).forEach((photo, index) =>
                root.appendChild(createImageMedia(id, `photos/${photo.photoId}`, `门店打卡照 ${index + 1}`)));
        } else if (item.storefrontPhotoAvailable === true) {
            root.appendChild(createImageMedia(id, "storefront-photo", "门店打卡照"));
        } else if (item.storefrontPhotoDeletedAt) {
            root.appendChild(createDeletedMediaCard("storefront-photo", "门店打卡照", item.storefrontPhotoDeletedAt));
        }
        if (item.wechatScreenshotAvailable === true) {
            root.appendChild(createImageMedia(id, "wechat-screenshot", "企微截图"));
        } else if (item.wechatScreenshotDeletedAt) {
            root.appendChild(createDeletedMediaCard("wechat-screenshot", "企微截图", item.wechatScreenshotDeletedAt));
        }
        const audioSegments = normalizeAudioSegments(item);
        if (audioSegments.length || hasAudioIntelligence(item)) {
            root.appendChild(createAudioMedia(id, item, audioSegments));
        }
        if (!root.hasChildNodes()) {
            renderEmptyMedia(root);
        }
    }

    function createImageMedia(id, kind, label) {
        const card = document.createElement("section");
        card.className = "media-card media-card--image";
        card.dataset.mediaId = id;
        card.dataset.mediaKind = kind;
        const title = document.createElement("strong");
        title.className = "media-title";
        title.textContent = label;

        const previewUrl = mediaUrl(id, kind);
        const downloadUrl = mediaUrl(id, kind, { download: true });
        const button = document.createElement("button");
        button.className = "media-thumbnail-button";
        button.type = "button";
        button.setAttribute("aria-label", `放大预览${label}`);

        const image = document.createElement("img");
        image.className = "media-thumbnail";
        image.src = mediaUrl(id, kind, { thumbnail: true });
        image.alt = `${label}缩略图`;
        image.loading = "lazy";
        image.decoding = "async";
        bindThumbnailFallback(image, () => {
            button.classList.add("is-unavailable");
            image.hidden = true;
            const fallback = document.createElement("span");
            fallback.className = "media-thumbnail-fallback";
            fallback.textContent = "预览暂不可用，点击查看原图";
            button.appendChild(fallback);
        });
        button.addEventListener("click", () => openImagePreview(
            previewUrl, downloadUrl, label, button, { id, kind }));
        button.appendChild(image);

        const hint = document.createElement("span");
        hint.className = "media-preview-hint";
        hint.textContent = "点击放大";
        button.appendChild(hint);
        const actions = createMediaActions(id, kind, label, downloadUrl);
        card.append(title, button, actions);
        return card;
    }

    function createAudioMedia(id, item, audioSegments) {
        const card = document.createElement("section");
        card.className = "media-card media-card--audio";
        card.dataset.mediaId = id;
        card.dataset.mediaKind = "audio";
        const title = document.createElement("strong");
        title.className = "media-title";
        const activeCount = audioSegments.filter((segment) => segment.available).length;
        title.textContent = audioSegments.length ? `拜访录音（${activeCount}段可用）` : "拜访录音";

        card.appendChild(title);
        if (audioSegments.length) {
            const list = document.createElement("div");
            list.className = "media-audio-list";
            audioSegments.forEach((segment, index) => {
                list.appendChild(createAudioSegment(id, segment, index, item));
            });
            card.appendChild(list);
        }

        card.appendChild(createAudioIntelligence(id, item));
        return card;
    }

    function createAudioSegment(submissionId, segment, index, submission) {
        const section = document.createElement("section");
        section.className = "media-audio-segment";
        section.dataset.mediaId = submissionId;
        section.dataset.mediaKind = "audio";
        section.dataset.segmentId = segment.segmentId;
        const heading = document.createElement("div");
        heading.className = "media-audio-segment__heading";
        const name = document.createElement("strong");
        name.textContent = `第${index + 1}段 · ${segment.originalFilename}`;
        const detail = document.createElement("span");
        const metadata = [];
        if (segment.sizeBytes > 0) metadata.push(formatBytes(segment.sizeBytes));
        if (segment.uploadedAt) metadata.push(`上传 ${formatFullDateTime(segment.uploadedAt)}`);
        detail.textContent = metadata.join(" · ") || "已记录";
        heading.append(name, detail);
        const evidence = createAudioTimingEvidence(segment, submission);
        section.append(heading, evidence);

        const label = `第${index + 1}段拜访录音`;
        if (segment.available) {
            const play = document.createElement("button");
            play.type = "button";
            play.className = "row-audio-play";
            play.textContent = `播放${label} · ${audioDurationLabel([segment])}`;
            play.addEventListener("click", () => playAudioSegment(submission, segment.segmentId));
            const hint = document.createElement("span");
            hint.className = "media-audio-hint";
            hint.textContent = segment.playbackStatus === "PENDING" ? "播放副本处理中，点击可尝试播放原录音。"
                : (segment.playbackStatus === "FAILED" ? "播放副本生成失败，原录音仍保留，可尝试播放或下载。" : "点击后加载音频，可切换片段和拖动进度。");
            section.append(play, hint, createMediaActions(
                submissionId, "audio", label,
                mediaUrl(submissionId, "audio", { segmentId: segment.segmentId, download: true }),
                segment.segmentId));
        } else if (segment.deletedAt) {
            section.appendChild(createDeletedMediaNotice(label, segment.deletedAt));
        }
        return section;
    }

    function createAudioTimingEvidence(segment, submission) {
        const panel = document.createElement("details");
        panel.className = "audio-timing-evidence";
        const heading = document.createElement("summary");
        heading.className = "audio-timing-evidence__title";
        heading.textContent = "录音时间证据";
        const grid = document.createElement("dl");
        grid.className = "audio-timing-evidence__grid";

        appendAudioTimingField(grid, "客户端上报来源", audioCaptureSourceText(segment.captureSource));
        appendAudioTimingField(grid, "服务端上传时间",
            segment.uploadedAt ? formatFullDateTime(segment.uploadedAt) : "未记录");
        if (segment.captureSource === "BROWSER_RECORDER") {
            appendAudioTimingField(grid, "页面录制开始（客户端报告）",
                segment.clientStartedAt ? formatFullDateTime(segment.clientStartedAt) : "未采集");
            const endedAt = audioClientEndedAt(segment);
            appendAudioTimingField(grid, "页面录制结束（客户端报告）",
                endedAt ? formatFullDateTime(endedAt) : "未采集");
        } else if (segment.captureSource === "FILE_UPLOAD") {
            appendAudioTimingField(grid, "文件修改时间（客户端报告，不可核验）",
                segment.fileLastModifiedAt ? formatFullDateTime(segment.fileLastModifiedAt) : "未采集");
        } else {
            appendAudioTimingField(grid, "录制时间", "录制时间未采集");
        }
        appendAudioTimingField(grid, "客户端报告时长",
            segment.clientDurationMs === null ? "未采集" : formatAudioDurationMs(segment.clientDurationMs));
        const mediaDuration = document.createElement("span");
        mediaDuration.dataset.audioMediaDuration = "true";
        mediaDuration.dataset.segmentId = segment.segmentId;
        mediaDuration.dataset.durationSource = segment.parsedDurationMs === null ? "UNKNOWN" : "SERVER";
        mediaDuration.textContent = !segment.available ? "文件已删除，无法解析"
            : (segment.parsedDurationMs === null ? "待解析，播放时可读取媒体时长" : formatAudioDurationMs(segment.parsedDurationMs));
        appendAudioTimingField(grid, "媒体解析时长", mediaDuration);

        const relation = audioTimingRelationship(segment, submission);
        const status = document.createElement("p");
        status.className = `audio-timing-evidence__status is-${relation.tone}`;
        const statusTitle = document.createElement("strong");
        statusTitle.textContent = relation.title;
        const statusDetail = document.createElement("span");
        statusDetail.textContent = relation.detail;
        status.append(statusTitle, statusDetail);
        panel.append(heading, grid, status);
        return panel;
    }

    function appendAudioTimingField(root, label, value) {
        const item = document.createElement("div");
        item.className = "audio-timing-evidence__field";
        const term = document.createElement("dt");
        term.textContent = label;
        const description = document.createElement("dd");
        if (value instanceof Node) description.appendChild(value);
        else description.textContent = value;
        item.append(term, description);
        root.appendChild(item);
    }

    function audioCaptureSourceText(source) {
        if (source === "BROWSER_RECORDER") return "客户端声明为页面录制（不可独立证明）";
        if (source === "FILE_UPLOAD") return "已有文件上传（录制时间不可核验）";
        return "来源未采集";
    }

    function audioClientEndedAt(segment) {
        const startedAt = timestampValue(segment.clientStartedAt);
        if (startedAt === null || segment.clientDurationMs === null) return null;
        const endedAt = startedAt + segment.clientDurationMs;
        if (!Number.isFinite(endedAt)) return null;
        const endedDate = new Date(endedAt);
        return Number.isNaN(endedDate.getTime()) ? null : endedDate;
    }

    function audioTimingRelationship(segment, submission) {
        if (segment.timingStatus === "UNVERIFIED_FILE" || segment.captureSource === "FILE_UPLOAD") {
            return {
                tone: "warning",
                title: "文件录制时间不可核验",
                detail: "文件修改时间只是客户端报告，不能证明实际录制时间，也无法核验与本次定位、提交的先后关系。"
            };
        }
        if (segment.timingStatus === "MISSING" || segment.captureSource !== "BROWSER_RECORDER") {
            return {
                tone: "muted",
                title: "录制时间未采集",
                detail: "无法核验录制与本次定位、提交的先后关系。"
            };
        }

        const startedAt = timestampValue(segment.clientStartedAt);
        const endedAt = audioClientEndedAt(segment)?.getTime() ?? null;
        const locatedAt = timestampValue(submission?.locationCapturedAt);
        const submittedAt = timestampValue(submission?.submittedAt);
        const relations = [];
        if (startedAt !== null && locatedAt !== null) {
            relations.push(startedAt >= locatedAt ? "录制开始在定位之后" : "录制开始早于定位");
        } else {
            relations.push("无法计算与定位的关系");
        }
        if (submittedAt === null) {
            relations.push("当前仍为草稿，尚无提交时间");
        } else if (endedAt !== null) {
            relations.push(endedAt <= submittedAt ? "录制结束在提交之前" : "录制结束晚于提交");
        } else {
            relations.push("无法计算与提交的关系");
        }
        return segment.timingStatus === "ALIGNED" ? {
            tone: "warning",
            title: "客户端报告时间顺序一致（仅供参考）",
            detail: `客户端报告的录制区间与定位、服务端上传顺序一致，但不能证明实际录制时间；${relations.join("；")}。`
        } : {
            tone: "danger",
            title: "客户端报告时间顺序异常",
            detail: `客户端报告的录制区间超出2分钟设备时钟容差，与定位或服务端上传顺序不一致；${relations.join("；")}。`
        };
    }

    function timestampValue(value) {
        if (!value) return null;
        const timestamp = new Date(value).getTime();
        return Number.isFinite(timestamp) ? timestamp : null;
    }

    function formatAudioDurationMs(value) {
        const milliseconds = Number(value);
        if (!Number.isFinite(milliseconds) || milliseconds < 0) return "未采集";
        const totalSeconds = Math.round(milliseconds / 1000);
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        return hours > 0
            ? `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
            : `${minutes}:${String(seconds).padStart(2, "0")}`;
    }

    function createDeletedMediaCard(kind, label, deletedAt) {
        const card = document.createElement("section");
        card.className = "media-card media-card--image is-deleted";
        card.dataset.mediaKind = kind;
        const title = document.createElement("strong");
        title.className = "media-title";
        title.textContent = label;
        card.append(title, createDeletedMediaNotice(label, deletedAt));
        return card;
    }

    function createDeletedMediaNotice(label, deletedAt) {
        const notice = document.createElement("div");
        notice.className = "media-deleted";
        const status = document.createElement("strong");
        status.textContent = "已删除";
        const detail = document.createElement("span");
        detail.textContent = `${label}已于 ${formatFullDateTime(deletedAt)} 永久删除`;
        notice.append(status, detail);
        return notice;
    }

    function createMediaActions(id, kind, label, downloadUrl, segmentId) {
        const actions = document.createElement("div");
        actions.className = "media-actions";
        actions.appendChild(createDownloadLink(downloadUrl, `下载${label}`));
        if (state.scope.allCities && !kind.startsWith("photos/")) {
            const remove = document.createElement("button");
            remove.className = "media-delete";
            remove.type = "button";
            remove.textContent = "删除";
            remove.addEventListener("click", () => openDeleteDialog(id, kind, label, remove, segmentId));
            actions.appendChild(remove);
        }
        return actions;
    }

    function createAudioIntelligence(id, item) {
        const panel = document.createElement("section");
        panel.className = "audio-intelligence";

        const heading = document.createElement("div");
        heading.className = "audio-intelligence__heading";
        const title = document.createElement("strong");
        title.textContent = state.audioIntelligenceEnabled ? "录音转写与摘要" : "录音转写与摘要（已暂停）";
        heading.appendChild(title);
        if (state.audioIntelligenceEnabled) {
            heading.appendChild(statusBadge("transcription", item.transcriptionStatus));
        }
        panel.appendChild(heading);

        const transcript = cleanText(item.transcript);
        const summary = cleanText(item.summary);
        if (!state.audioIntelligenceEnabled) {
            if (transcript) panel.appendChild(createAiTextBlock("已有转写全文", transcript, "transcript"));
            if (summary) panel.appendChild(createAiTextBlock("已有摘要", summary, "summary"));
            if (!transcript && !summary) {
                panel.appendChild(createAiEmptyText("当前未开通腾讯语音识别权限，自动转文字与摘要已暂停；录音仍可正常播放和下载。"));
            }
            return panel;
        }
        if (transcript) {
            panel.appendChild(createAiTextBlock("转写全文", transcript, "transcript"));
        } else {
            panel.appendChild(createAiEmptyText(transcriptionStatusText(item.transcriptionStatus)));
        }

        const summaryHeader = document.createElement("div");
        summaryHeader.className = "audio-intelligence__subheading";
        const summaryTitle = document.createElement("strong");
        summaryTitle.textContent = "AI 摘要";
        summaryHeader.append(summaryTitle, statusBadge("summary", item.summaryStatus));
        panel.appendChild(summaryHeader);

        panel.appendChild(summary
            ? createAiTextBlock("", summary, "summary")
            : createAiEmptyText(summaryStatusText(item.summaryStatus)));

        const transcriptionFailed = isFailedTranscription(item.transcriptionStatus);
        const summaryFailed = isFailedSummary(item.summaryStatus);
        if ((transcriptionFailed || summaryFailed) && state.scope.allCities) {
            const failure = document.createElement("div");
            failure.className = "transcription-failure";
            const code = cleanText(transcriptionFailed
                ? item.transcriptionErrorCode
                : item.summaryErrorCode);
            if (code) {
                const technical = document.createElement("details");
                technical.className = "technical-error";
                const summary = document.createElement("summary");
                summary.textContent = "查看技术错误";
                const codeText = document.createElement("code");
                codeText.textContent = code;
                technical.append(summary, codeText);
                failure.appendChild(technical);
            }
            if (normalizeAudioSegments(item).some((segment) => segment.available)) {
                const retry = document.createElement("button");
                retry.className = "retry-transcription";
                retry.type = "button";
                retry.textContent = transcriptionFailed ? "重新转写" : "重新生成摘要";
                retry.addEventListener("click", () => retryTranscription(id, retry));
                failure.appendChild(retry);
            }
            panel.appendChild(failure);
        }
        return panel;
    }

    function createAiTextBlock(label, value, kind) {
        const block = document.createElement("div");
        block.className = `ai-text ai-text--${kind}`;
        if (label) {
            const title = document.createElement("strong");
            title.textContent = label;
            block.appendChild(title);
        }
        const copy = document.createElement("p");
        copy.textContent = value;
        block.appendChild(copy);
        return block;
    }

    function createAiEmptyText(message) {
        const empty = document.createElement("p");
        empty.className = "ai-empty";
        empty.textContent = message;
        return empty;
    }

    function statusBadge(kind, rawStatus) {
        const status = cleanText(rawStatus).toUpperCase();
        const badge = document.createElement("span");
        badge.className = `ai-status ${statusClass(status)}`;
        badge.textContent = kind === "summary" ? summaryStatusText(status) : transcriptionStatusText(status);
        return badge;
    }

    function statusClass(status) {
        if (["COMPLETED", "SUCCEEDED", "READY"].includes(status)) return "is-complete";
        if (["FAILED", "ERROR", "UNSUPPORTED"].includes(status)) return "is-failed";
        if (["PENDING", "QUEUED", "SUBMITTING", "PROCESSING", "SUBMITTED", "RUNNING"].includes(status)) return "is-processing";
        return "is-idle";
    }

    function transcriptionStatusText(rawStatus) {
        const status = cleanText(rawStatus).toUpperCase();
        const labels = {
            COMPLETED: "转写完成", SUCCEEDED: "转写完成", READY: "转写完成",
            PENDING: "等待转写", QUEUED: "已排队", SUBMITTING: "正在提交", SUBMITTED: "已提交",
            PROCESSING: "转写中", RUNNING: "转写中", FAILED: "转写失败",
            ERROR: "转写失败", NOT_REQUESTED: "未转写", NONE: "未转写",
            UNSUPPORTED: "格式暂不支持",
            DELETED: "已随录音删除"
        };
        return labels[status] || (status ? status : "未转写");
    }

    function summaryStatusText(rawStatus) {
        const status = cleanText(rawStatus).toUpperCase();
        const labels = {
            COMPLETED: "摘要完成", SUCCEEDED: "摘要完成", READY: "摘要完成",
            PENDING: "等待摘要", QUEUED: "已排队", SUBMITTED: "已提交",
            PROCESSING: "生成中", RUNNING: "生成中", FAILED: "摘要失败",
            ERROR: "摘要失败", NOT_REQUESTED: "暂无摘要", NONE: "暂无摘要",
            DELETED: "已随录音删除"
        };
        return labels[status] || (status ? status : "暂无摘要");
    }

    function isFailedTranscription(status) {
        return ["FAILED", "ERROR", "UNSUPPORTED"].includes(cleanText(status).toUpperCase());
    }

    function isFailedSummary(status) {
        return ["FAILED", "ERROR"].includes(cleanText(status).toUpperCase());
    }

    function hasAudioIntelligence(item) {
        return Boolean(cleanText(item.transcriptionStatus) || cleanText(item.transcript)
            || cleanText(item.summaryStatus) || cleanText(item.summary) || cleanText(item.transcriptionErrorCode));
    }

    function createDownloadLink(url, label) {
        const link = document.createElement("a");
        link.className = "media-download";
        link.href = url;
        link.textContent = label;
        link.setAttribute("download", "");
        return link;
    }

    function renderEmptyMedia(root) {
        const empty = document.createElement("span");
        empty.className = "media-empty";
        empty.textContent = "暂无现场材料";
        root.appendChild(empty);
    }

    function mediaUrl(id, kind, options = {}) {
        const segmentPath = kind === "audio" && options.segmentId
            ? `/${encodeURIComponent(options.segmentId)}` : "";
        const mediaRoot = kind.startsWith("photos/") ? `${API_BASE}/submissions` : MEDIA_PATH;
        const base = `${mediaRoot}/${encodeURIComponent(id)}/media/${kind}${segmentPath}`;
        if (options.thumbnail) return kind.startsWith("photos/") ? `${base}?thumbnail=true` : `${base}/thumbnail`;
        return options.download ? `${base}?download=true` : base;
    }

    function openImagePreview(previewUrl, downloadUrl, label, trigger, identity) {
        const dialog = $("#image-preview-dialog");
        const image = $("#image-preview-content");
        state.previewTrigger = trigger;
        state.previewIdentity = identity || null;
        $("#image-preview-title").textContent = label;
        $("#image-preview-download").href = downloadUrl;
        $("#image-preview-error").hidden = true;
        image.hidden = true;
        image.alt = `${label}大图预览`;
        image.src = previewUrl;
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        syncDialogState();
        $("#image-preview-close").focus();
    }

    function closeImagePreview() {
        const dialog = $("#image-preview-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else {
            dialog.removeAttribute("open");
            cleanupImagePreview();
        }
    }

    function cleanupImagePreview() {
        const image = $("#image-preview-content");
        image.removeAttribute("src");
        image.alt = "";
        image.hidden = true;
        $("#image-preview-error").hidden = true;
        $("#image-preview-download").href = "#";
        syncDialogState();
        if (state.previewTrigger && document.contains(state.previewTrigger)) state.previewTrigger.focus({ preventScroll: true });
        state.previewTrigger = null;
        state.previewIdentity = null;
    }

    function openDeleteDialog(id, kind, label, trigger, segmentId) {
        if (!state.scope.allCities || state.actionBusy || !isUuid(id)) return;
        if (kind === "audio" && !isUuid(segmentId)) return;
        state.pendingDelete = { id, kind, label, trigger, segmentId: segmentId || null };
        $("#delete-media-description").textContent = `即将删除“${label}”。请再次核对当前拜访记录。`;
        $("#delete-media-reason").value = "";
        $("#delete-media-acknowledge").checked = false;
        $("#delete-media-error").hidden = true;
        $("#delete-media-error").textContent = "";
        updateDeleteConfirmation();
        const dialog = $("#delete-media-dialog");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        syncDialogState();
        $("#delete-media-reason").focus();
    }

    function closeDeleteDialog() {
        if (state.actionBusy) return;
        const dialog = $("#delete-media-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else {
            dialog.removeAttribute("open");
            cleanupDeleteDialog();
        }
    }

    function cleanupDeleteDialog() {
        const trigger = state.pendingDelete && state.pendingDelete.trigger;
        state.pendingDelete = null;
        state.actionBusy = false;
        $("#delete-media-form").reset();
        $("#delete-media-error").hidden = true;
        $("#delete-media-error").textContent = "";
        $("#delete-media-confirm").disabled = true;
        $("#delete-media-confirm").textContent = "确认永久删除";
        syncDialogState();
        if (trigger && document.contains(trigger)) trigger.focus();
    }

    function updateDeleteConfirmation() {
        const reason = cleanText($("#delete-media-reason").value);
        const acknowledged = $("#delete-media-acknowledge").checked;
        $("#delete-media-confirm").disabled = state.actionBusy || !reason || !acknowledged;
    }

    async function deletePendingMedia(event) {
        event.preventDefault();
        const pending = state.pendingDelete;
        const reason = cleanText($("#delete-media-reason").value);
        if (!pending || !state.scope.allCities || state.actionBusy || !reason
                || !$("#delete-media-acknowledge").checked) {
            updateDeleteConfirmation();
            return;
        }
        state.actionBusy = true;
        const confirm = $("#delete-media-confirm");
        confirm.disabled = true;
        confirm.textContent = "正在删除…";
        $("#delete-media-error").hidden = true;
        let deleted = false;
        try {
            const segmentPath = pending.kind === "audio"
                ? `/${encodeURIComponent(pending.segmentId)}` : "";
            await requestAction(
                `${API_BASE}/submissions/${encodeURIComponent(pending.id)}/media/${pending.kind}${segmentPath}`,
                { method: "DELETE", body: { reason } });
            deleted = true;
            stopDeletedMedia(pending.id, pending.kind, pending.segmentId);
            if (state.previewIdentity && state.previewIdentity.id === pending.id
                    && state.previewIdentity.kind === pending.kind) {
                closeImagePreview();
            }
            const dialog = $("#delete-media-dialog");
            state.actionBusy = false;
            if (typeof dialog.close === "function") dialog.close();
            else {
                dialog.removeAttribute("open");
                cleanupDeleteDialog();
            }
            showSuccess(`${pending.label}已永久删除。`);
            try {
                await refreshAdminData();
            } catch (refreshError) {
                showError("媒体已删除，但列表刷新失败，请点击重试刷新页面。");
            }
        } catch (error) {
            if (deleted) return;
            state.actionBusy = false;
            confirm.textContent = "确认永久删除";
            updateDeleteConfirmation();
            $("#delete-media-error").textContent = errorMessage(error, "删除失败，请稍后重试。");
            $("#delete-media-error").hidden = false;
        }
    }

    function stopDeletedMedia(id, kind, segmentId) {
        if (kind === "audio" && state.activeAudio?.id === id
                && (!segmentId || state.activeAudio.segmentId === segmentId)) stopSharedAudio();
    }

    function syncDialogState() {
        const hasOpenDialog = Array.from(document.querySelectorAll("dialog"))
            .some((dialog) => dialog.hasAttribute("open"));
        document.body.classList.toggle("preview-open", hasOpenDialog);
    }

    function renderDirectoryPermissions() {
        const allowed = state.scope.canManageSalespersons === true;
        const tab = $("#salespersons-tab");
        tab.hidden = !allowed;
        tab.tabIndex = allowed && state.activeView === "salespersons" ? 0 : -1;
        $("#add-salesperson-button").hidden = !allowed;
        if (!allowed && state.activeView === "salespersons") switchAdminView("records");
        updateSelectionUI();
    }

    function handleTabKeyboard(event) {
        if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
        const tabs = Array.from(document.querySelectorAll('[data-admin-view]:not([hidden])'));
        if (tabs.length < 2) return;
        event.preventDefault();
        const index = Math.max(0, tabs.indexOf(event.currentTarget));
        let targetIndex = index;
        if (event.key === "ArrowRight") targetIndex = (index + 1) % tabs.length;
        if (event.key === "ArrowLeft") targetIndex = (index - 1 + tabs.length) % tabs.length;
        if (event.key === "Home") targetIndex = 0;
        if (event.key === "End") targetIndex = tabs.length - 1;
        tabs[targetIndex].focus();
        switchAdminView(tabs[targetIndex].dataset.adminView);
    }

    async function switchAdminView(view) {
        const nextView = view === "salespersons" && state.scope.canManageSalespersons ? "salespersons" : "records";
        state.activeView = nextView;
        document.querySelectorAll("[data-admin-view]").forEach((tab) => {
            const active = tab.dataset.adminView === nextView;
            tab.classList.toggle("is-active", active);
            tab.setAttribute("aria-selected", String(active));
            tab.tabIndex = active ? 0 : -1;
        });
        $("#records-panel").hidden = nextView !== "records";
        $("#salespersons-panel").hidden = nextView !== "salespersons";
        if (nextView === "salespersons") {
            const tasks = [];
            if (!state.sales.loaded) tasks.push(loadSalespersons());
            if (!state.cityDirectory.loaded) tasks.push(loadCityDirectory());
            await Promise.allSettled(tasks);
        }
    }

    function renderSalespersonDirectoryCities() {
        const filter = $("#salesperson-city");
        const form = $("#salesperson-form-city");
        const filterValue = state.scope.allCities ? state.sales.filters.city : state.scope.city;
        const formValue = form.value;
        filter.replaceChildren();
        form.replaceChildren();
        if (state.scope.allCities) {
            filter.appendChild(option("", "全部城市"));
            state.cities.forEach((city) => {
                filter.appendChild(option(city, city));
                form.appendChild(option(city, city));
            });
            filter.disabled = false;
            form.disabled = false;
        } else {
            filter.appendChild(option(state.scope.city, state.scope.city || "未配置城市"));
            form.appendChild(option(state.scope.city, state.scope.city || "未配置城市"));
            filter.disabled = true;
            form.disabled = true;
            state.sales.filters.city = state.scope.city;
        }
        if (Array.from(filter.options).some((item) => item.value === filterValue)) filter.value = filterValue;
        if (Array.from(form.options).some((item) => item.value === formValue)) form.value = formValue;
    }

    function readSalespersonFilters() {
        state.sales.filters = {
            q: cleanText($("#salesperson-query").value),
            city: state.scope.allCities ? cleanText($("#salesperson-city").value) : state.scope.city,
            status: cleanText($("#salesperson-status").value)
        };
    }

    async function loadSalespersons() {
        if (!state.scope.canManageSalespersons) return;
        if (state.sales.controller) state.sales.controller.abort();
        state.sales.controller = new AbortController();
        state.sales.loading = true;
        $("#salesperson-loading").hidden = false;
        $("#salesperson-table-wrap").hidden = true;
        $("#salesperson-empty").hidden = true;
        $("#salesperson-pagination").hidden = true;
        $("#salesperson-search-button").disabled = true;
        const params = new URLSearchParams();
        Object.entries(state.sales.filters).forEach(([name, value]) => {
            if (value) params.set(name, value);
        });
        params.set("page", String(state.sales.page));
        params.set("size", "50");
        try {
            const payload = unwrap(await requestJson(
                `${API_BASE}/salespersons?${params.toString()}`, state.sales.controller.signal));
            const items = Array.isArray(payload.items) ? payload.items : (Array.isArray(payload) ? payload : []);
            state.sales.itemsById = new Map(items.map((item) => [salespersonId(item), item]).filter(([id]) => id));
            state.sales.total = numberValue(payload.totalElements, payload.total, items.length);
            state.sales.totalPages = Math.max(1, numberValue(payload.totalPages, Math.ceil(state.sales.total / 50)));
            state.sales.page = Math.max(0, numberValue(payload.page, state.sales.page));
            state.sales.loaded = true;
            renderSalespersonRows(items);
            renderSalespersonPagination();
            $("#salesperson-loading").hidden = true;
            $("#salesperson-table-wrap").hidden = items.length === 0;
            $("#salesperson-empty").hidden = items.length !== 0;
        } catch (error) {
            if (error.name !== "AbortError") {
                $("#salesperson-loading").hidden = true;
                showError(errorMessage(error, "读取销售目录失败，请稍后重试。"));
            }
        } finally {
            state.sales.loading = false;
            $("#salesperson-search-button").disabled = false;
        }
    }

    function salespersonId(item) {
        return cleanText(item && (item.id || item.salespersonId));
    }

    function renderSalespersonRows(items) {
        const root = $("#salesperson-rows");
        const template = $("#salesperson-row-template");
        root.replaceChildren();
        items.forEach((item) => {
            const row = template.content.firstElementChild.cloneNode(true);
            const field = (name) => row.querySelector(`[data-field="${name}"]`);
            const id = salespersonId(item);
            field("name").textContent = cleanText(item.name || item.salespersonName) || "未命名";
            field("city").textContent = cleanText(item.city) || "未配置";
            field("position").textContent = cleanText(item.position || item.title) || "—";
            field("employment-status").textContent = cleanText(item.employmentStatus) || "—";
            const status = salespersonStatus(item);
            field("status").textContent = status === "ACTIVE" ? "已启用" : "已禁用";
            field("status").classList.toggle("is-inactive", status !== "ACTIVE");
            field("sort-order").textContent = String(numberValue(item.sortOrder));
            field("edit").addEventListener("click", (event) => openSalespersonDialog(id, event.currentTarget));
            field("reset-credential").addEventListener("click", (event) => openCredentialResetDialog(id, event.currentTarget));
            root.appendChild(row);
        });
    }

    function salespersonStatus(item) {
        const raw = cleanText(item.status || item.enabledStatus).toUpperCase();
        if (raw) return ["ACTIVE", "ENABLED", "TRUE", "1"].includes(raw) ? "ACTIVE" : "INACTIVE";
        return item.enabled === false ? "INACTIVE" : "ACTIVE";
    }

    function renderSalespersonPagination() {
        $("#salesperson-pagination").hidden = state.sales.total === 0;
        $("#salesperson-previous-page").disabled = state.sales.page <= 0;
        $("#salesperson-next-page").disabled = state.sales.page + 1 >= state.sales.totalPages;
        $("#salesperson-page-indicator").textContent = `第 ${state.sales.page + 1} / ${state.sales.totalPages} 页`;
    }

    async function changeSalespersonPage(page) {
        if (state.sales.loading || page < 0 || page >= state.sales.totalPages) return;
        state.sales.page = page;
        await loadSalespersons();
        $("#salespersons-panel").scrollIntoView({ behavior: "smooth", block: "start" });
    }

    function openSalespersonDialog(id, trigger) {
        if (!state.scope.canManageSalespersons || state.sales.actionBusy) return;
        const item = id ? state.sales.itemsById.get(id) : null;
        if (id && !item) {
            showError("该销售记录已不在当前列表，请刷新后重试。");
            return;
        }
        state.sales.dialogTrigger = trigger;
        $("#salesperson-form").reset();
        $("#salesperson-id").value = id || "";
        $("#salesperson-dialog-title").textContent = item ? "编辑销售" : "新增销售";
        $("#salesperson-name").value = cleanText(item && (item.name || item.salespersonName));
        $("#salesperson-position").value = cleanText(item && (item.position || item.title));
        $("#salesperson-employment-status").value = cleanText(item && item.employmentStatus) || "在职";
        $("#salesperson-form-status").value = item ? salespersonStatus(item) : "ACTIVE";
        $("#salesperson-sort-order").value = String(item ? numberValue(item.sortOrder) : 0);
        renderSalespersonDirectoryCities();
        const city = cleanText(item && item.city) || (state.scope.allCities ? state.cities[0] : state.scope.city);
        if (Array.from($("#salesperson-form-city").options).some((optionItem) => optionItem.value === city)) {
            $("#salesperson-form-city").value = city;
        }
        $("#salesperson-form-error").hidden = true;
        $("#salesperson-form-error").textContent = "";
        const dialog = $("#salesperson-dialog");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        syncDialogState();
        $("#salesperson-name").focus();
    }

    function closeSalespersonDialog() {
        if (state.sales.actionBusy) return;
        const dialog = $("#salesperson-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else {
            dialog.removeAttribute("open");
            cleanupSalespersonDialog();
        }
    }

    function cleanupSalespersonDialog() {
        const trigger = state.sales.dialogTrigger;
        state.sales.dialogTrigger = null;
        state.sales.actionBusy = false;
        $("#salesperson-form").reset();
        $("#salesperson-save-button").disabled = false;
        $("#salesperson-save-button").textContent = "保存";
        $("#salesperson-form-error").hidden = true;
        $("#salesperson-form-error").textContent = "";
        syncDialogState();
        if (trigger && document.contains(trigger)) trigger.focus();
    }

    async function saveSalesperson(event) {
        event.preventDefault();
        if (!state.scope.canManageSalespersons || state.sales.actionBusy) return;
        const id = cleanText($("#salesperson-id").value);
        const name = cleanText($("#salesperson-name").value);
        const city = state.scope.allCities ? cleanText($("#salesperson-form-city").value) : state.scope.city;
        if (!name || !city) {
            $("#salesperson-form-error").textContent = "请填写销售姓名并选择城市。";
            $("#salesperson-form-error").hidden = false;
            return;
        }
        state.sales.actionBusy = true;
        const save = $("#salesperson-save-button");
        save.disabled = true;
        save.textContent = "正在保存…";
        let result;
        try {
            result = await requestAction(id
                ? `${API_BASE}/salespersons/${encodeURIComponent(id)}`
                : `${API_BASE}/salespersons`, {
                method: id ? "PATCH" : "POST",
                body: {
                    name,
                    city,
                    position: cleanText($("#salesperson-position").value) || null,
                    employmentStatus: $("#salesperson-employment-status").value,
                    status: $("#salesperson-form-status").value,
                    sortOrder: numberValue($("#salesperson-sort-order").value)
                }
            });
        } catch (error) {
            state.sales.actionBusy = false;
            save.disabled = false;
            save.textContent = "保存";
            $("#salesperson-form-error").textContent = errorMessage(error, "保存销售失败，请稍后重试。");
            $("#salesperson-form-error").hidden = false;
            return;
        }
        const trigger = state.sales.dialogTrigger;
        state.sales.actionBusy = false;
        closeSalespersonDialog();
        state.sales.loaded = false;
        await loadSalespersons();
        try {
            const options = unwrap(await requestJson(`${API_BASE}/options`));
            applyOptions(options);
        } catch (refreshError) {
            showError("销售资料已保存，但筛选选项刷新失败，请稍后重试。");
        }
        const temporaryCode = cleanText(result.temporaryCheckinCode);
        if (temporaryCode) showTemporaryCredential(temporaryCode, name, trigger);
        else showSuccess(id ? `已更新销售“${name}”。` : `已新增销售“${name}”。`);
    }

    function openCredentialResetDialog(id, trigger) {
        if (!state.scope.canManageSalespersons || state.sales.actionBusy) return;
        const item = state.sales.itemsById.get(id);
        if (!item) {
            showError("该销售记录已不在当前列表，请刷新后重试。");
            return;
        }
        state.sales.resetTarget = { id, name: cleanText(item.name || item.salespersonName) || "该销售" };
        state.sales.resetTrigger = trigger;
        $("#credential-reset-title").textContent = "重置销售个人码";
        $("#credential-reset-form").reset();
        $("#credential-reset-description").textContent = `即将重置“${state.sales.resetTarget.name}”的个人码并解除已有设备绑定。`;
        $("#credential-reset-confirmation").hidden = false;
        $("#credential-reset-result").hidden = true;
        $("#credential-reset-code").value = "";
        $("#credential-reset-error").hidden = true;
        $("#credential-reset-confirm").hidden = false;
        $("#credential-reset-confirm").disabled = false;
        $("#credential-reset-confirm").textContent = "确认重置";
        $("#credential-reset-cancel").textContent = "取消";
        showCredentialResetDialog();
        $("#credential-reset-reason").focus();
    }

    function showTemporaryCredential(code, name, trigger) {
        state.sales.resetTarget = { id: "", name };
        state.sales.resetTrigger = trigger;
        $("#credential-reset-title").textContent = `“${name}”个人码已生成`;
        $("#credential-reset-confirmation").hidden = true;
        $("#credential-reset-result").hidden = false;
        $("#credential-reset-code").value = code;
        $("#credential-reset-error").hidden = true;
        $("#credential-reset-confirm").hidden = true;
        $("#credential-reset-cancel").textContent = "完成";
        showCredentialResetDialog();
        $("#credential-reset-code").focus();
        $("#credential-reset-code").select();
    }

    function showCredentialResetDialog() {
        const dialog = $("#credential-reset-dialog");
        if (!dialog.hasAttribute("open")) {
            if (typeof dialog.showModal === "function") dialog.showModal();
            else dialog.setAttribute("open", "");
        }
        syncDialogState();
    }

    function closeCredentialResetDialog() {
        if (state.sales.actionBusy) return;
        const dialog = $("#credential-reset-dialog");
        if (!dialog.hasAttribute("open")) return;
        if (typeof dialog.close === "function") dialog.close();
        else {
            dialog.removeAttribute("open");
            cleanupCredentialResetDialog();
        }
    }

    function cleanupCredentialResetDialog() {
        const trigger = state.sales.resetTrigger;
        state.sales.resetTarget = null;
        state.sales.resetTrigger = null;
        state.sales.actionBusy = false;
        $("#credential-reset-form").reset();
        $("#credential-reset-title").textContent = "重置销售个人码";
        $("#credential-reset-code").value = "";
        $("#credential-reset-result").hidden = true;
        $("#credential-reset-confirmation").hidden = false;
        $("#credential-reset-confirm").hidden = false;
        $("#credential-reset-confirm").disabled = false;
        $("#credential-reset-confirm").textContent = "确认重置";
        $("#credential-reset-cancel").textContent = "取消";
        $("#credential-reset-error").hidden = true;
        $("#credential-reset-error").textContent = "";
        syncDialogState();
        if (trigger && document.contains(trigger)) trigger.focus();
    }

    async function resetSalespersonCredential(event) {
        event.preventDefault();
        const target = state.sales.resetTarget;
        if (!target || !target.id || state.sales.actionBusy) return;
        const reason = cleanText($("#credential-reset-reason").value);
        if (!reason) {
            $("#credential-reset-error").textContent = "请输入重置原因。";
            $("#credential-reset-error").hidden = false;
            return;
        }
        state.sales.actionBusy = true;
        const confirm = $("#credential-reset-confirm");
        confirm.disabled = true;
        confirm.textContent = "正在重置…";
        try {
            const result = await requestAction(
                `${API_BASE}/salespersons/${encodeURIComponent(target.id)}/credential-reset`, {
                    method: "POST",
                    body: { reason }
                });
            const code = cleanText(result.temporaryCheckinCode);
            state.sales.actionBusy = false;
            if (!code) {
                $("#credential-reset-error").textContent = "重置请求已成功，但接口未返回新个人码。请勿重复操作，并联系管理员核查。";
                $("#credential-reset-error").hidden = false;
                confirm.hidden = true;
                $("#credential-reset-cancel").textContent = "关闭";
                return;
            }
            $("#credential-reset-confirmation").hidden = true;
            $("#credential-reset-result").hidden = false;
            $("#credential-reset-title").textContent = `“${target.name}”个人码已重置`;
            $("#credential-reset-code").value = code;
            confirm.hidden = true;
            $("#credential-reset-cancel").textContent = "完成";
            $("#credential-reset-code").focus();
            $("#credential-reset-code").select();
        } catch (error) {
            state.sales.actionBusy = false;
            confirm.disabled = false;
            confirm.textContent = "确认重置";
            $("#credential-reset-error").textContent = errorMessage(error, "重置个人码失败，请稍后重试。");
            $("#credential-reset-error").hidden = false;
        }
    }

    async function copyTemporaryCredential() {
        await copySecretInput($("#credential-reset-code"), $("#credential-copy-button"), "个人码");
    }

    async function loadCityDirectory() {
        if (!state.scope.canManageSalespersons || state.cityDirectory.loading) return;
        if (!state.scope.canManageCities) {
            const items = state.scope.city
                ? [{ name: state.scope.city, status: "ACTIVE" }]
                : [];
            state.cityDirectory.items = items;
            state.cityDirectory.loaded = true;
            renderCityDirectory(items);
            return;
        }
        state.cityDirectory.loading = true;
        $("#city-directory-loading").hidden = false;
        try {
            const [cityPayload, accountPayload] = await Promise.all([
                requestJson(`${API_BASE}/cities`),
                requestJson(`${API_BASE}/admin-accounts`)
            ]);
            const payload = unwrap(cityPayload);
            const accountsPayload = unwrap(accountPayload);
            const items = Array.isArray(payload.items) ? payload.items : (Array.isArray(payload) ? payload : []);
            state.cityDirectory.accounts = Array.isArray(accountsPayload.items)
                ? accountsPayload.items : (Array.isArray(accountsPayload) ? accountsPayload : []);
            state.cityDirectory.items = items;
            state.cityDirectory.loaded = true;
            renderCityDirectory(items);
        } catch (error) {
            showError(errorMessage(error, "读取城市目录失败，请稍后重试。"));
        } finally {
            state.cityDirectory.loading = false;
            $("#city-directory-loading").hidden = true;
        }
    }

    function renderCityDirectory(items) {
        const root = $("#city-directory-list");
        root.replaceChildren();
        const visible = state.scope.allCities ? items : items.filter((item) => cleanText(item.city || item.name) === state.scope.city);
        visible.forEach((item) => {
            const card = document.createElement("article");
            card.className = "city-directory-item";
            const cityName = cleanText(item.city || item.name);
            const name = document.createElement("strong");
            name.textContent = cityName || "未命名城市";
            const status = cleanText(item.status).toUpperCase();
            const statusLabel = status === "ACTIVE" ? "已启用" : (status === "INACTIVE" ? "已停用" : status);
            const statusText = document.createElement("span");
            statusText.textContent = statusLabel ? `城市状态：${statusLabel}` : "城市已配置";
            card.append(name, statusText);
            const accounts = state.cityDirectory.accounts.filter((account) =>
                cleanText(account.role).toUpperCase() === "CITY_ADMIN"
                && cleanText(account.city) === cityName);
            accounts.forEach((account) => card.appendChild(createCityAdminRow(account)));
            if (state.scope.canManageCities && accounts.length === 0) {
                const emptyAdmin = document.createElement("span");
                emptyAdmin.textContent = "暂无城市管理员";
                card.appendChild(emptyAdmin);
            }
            root.appendChild(card);
        });
        if (!visible.length) {
            const empty = document.createElement("p");
            empty.className = "detail-muted";
            empty.textContent = "当前范围暂无城市目录数据。";
            root.appendChild(empty);
        }
        $("#city-directory-scope").textContent = state.scope.canManageCities
            ? "总管理员可新增城市和对应后台管理员。" : `当前账号仅可查看${state.scope.city || "本城市"}。`;
        $("#city-create-form").hidden = !state.scope.canManageCities;
    }

    function createCityAdminRow(account) {
        const row = document.createElement("div");
        row.className = "city-admin-row";
        const copy = document.createElement("span");
        const username = cleanText(account.username) || "未命名账号";
        copy.textContent = `管理员：${username}${account.mustChangePassword === true ? "（待改密）" : ""}`;
        row.appendChild(copy);
        const accountId = cleanText(account.accountId || account.id);
        if (state.scope.canManageCities && accountId) {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "city-password-reset-button";
            button.textContent = "重置密码";
            button.addEventListener("click", () => resetCityAdminPassword(account, button));
            row.appendChild(button);
        }
        return row;
    }

    async function resetCityAdminPassword(account, button) {
        const accountId = cleanText(account.accountId || account.id);
        const username = cleanText(account.username) || "该管理员";
        if (!state.scope.canManageCities || !accountId || state.cityDirectory.actionBusy) return;
        if (!window.confirm(`确认重置 ${username} 的密码？已登录会话将立即失效。`)) return;
        state.cityDirectory.actionBusy = true;
        button.disabled = true;
        button.textContent = "重置中…";
        hideError();
        try {
            const result = await requestAction(
                `${API_BASE}/admin-accounts/${encodeURIComponent(accountId)}/password-reset`,
                { method: "POST" });
            const password = cleanText(result.temporaryPassword);
            if (!password) throw new Error("重置成功，但接口未返回临时密码，请勿重复操作并联系管理员。");
            $("#city-credential-title").textContent = `${username} 的临时密码（仅显示本次）`;
            $("#city-temporary-password").value = password;
            $("#city-credential-result").hidden = false;
            $("#city-temporary-password").focus();
            $("#city-temporary-password").select();
            state.cityDirectory.loaded = false;
            await loadCityDirectory();
        } catch (error) {
            showError(errorMessage(error, "重置城市管理员密码失败。"));
        } finally {
            state.cityDirectory.actionBusy = false;
            if (document.contains(button)) {
                button.disabled = false;
                button.textContent = "重置密码";
            }
        }
    }

    async function createCity(event) {
        event.preventDefault();
        if (!state.scope.canManageCities || state.cityDirectory.actionBusy) return;
        const city = cleanText($("#city-create-name").value);
        const adminUsername = cleanText($("#city-create-username").value);
        if (!city || !adminUsername) return;
        state.cityDirectory.actionBusy = true;
        $("#city-create-button").disabled = true;
        $("#city-create-button").textContent = "正在创建…";
        $("#city-create-error").hidden = true;
        $("#city-credential-result").hidden = true;
        $("#city-temporary-password").value = "";
        let result;
        try {
            result = await requestAction(`${API_BASE}/cities`, {
                method: "POST",
                body: { name: city, adminUsername }
            });
        } catch (error) {
            $("#city-create-error").textContent = errorMessage(error, "新增城市失败，请稍后重试。");
            $("#city-create-error").hidden = false;
            return;
        } finally {
            state.cityDirectory.actionBusy = false;
            $("#city-create-button").disabled = false;
            $("#city-create-button").textContent = "新增城市";
        }
        const password = cleanText(result.temporaryPassword
            || (result.administrator && result.administrator.temporaryPassword));
        $("#city-create-form").reset();
        state.cityDirectory.loaded = false;
        await loadCityDirectory();
        if (password) {
            $("#city-credential-title").textContent = `${adminUsername} 的临时密码（仅显示本次）`;
            $("#city-temporary-password").value = password;
            $("#city-credential-result").hidden = false;
            $("#city-temporary-password").focus();
            $("#city-temporary-password").select();
        } else {
            $("#city-create-error").textContent = "城市已创建，但接口未返回临时密码。请勿重复创建，并联系管理员核查。";
            $("#city-create-error").hidden = false;
        }
        try {
            const options = unwrap(await requestJson(`${API_BASE}/options`));
            applyOptions(options);
        } catch (refreshError) {
            showError("城市已创建，但筛选选项刷新失败，请稍后重试。");
        }
    }

    async function copyCityTemporaryPassword() {
        await copySecretInput($("#city-temporary-password"), $("#city-password-copy-button"), "临时密码");
    }

    async function copySecretInput(input, button, label) {
        const secret = input.value;
        if (!secret) return;
        const original = button.textContent;
        try {
            await navigator.clipboard.writeText(secret);
            button.textContent = "已复制";
            window.setTimeout(() => { button.textContent = original; }, 1800);
        } catch (error) {
            input.focus();
            input.select();
            showError(`${label}未能自动复制，已为你选中文本，请手动复制。`);
        }
    }

    async function retryTranscription(id, button) {
        if (!state.scope.allCities || state.actionBusy || !isUuid(id)) return;
        state.actionBusy = true;
        button.disabled = true;
        const original = button.textContent;
        button.textContent = "正在提交…";
        hideError();
        try {
            await requestAction(`${API_BASE}/submissions/${encodeURIComponent(id)}/transcription`, {
                method: "POST"
            });
            showSuccess("已重新提交处理，结果将在列表中更新。");
            await loadSubmissions();
        } catch (error) {
            button.disabled = false;
            button.textContent = original;
            showError(errorMessage(error, "重新转写提交失败，请稍后重试。"));
        } finally {
            state.actionBusy = false;
        }
    }

    async function refreshAdminData() {
        const options = unwrap(await requestJson(`${API_BASE}/options`));
        applyOptions(options);
        await loadSubmissions();
    }

    function isUuid(value) {
        return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
    }

    function renderResultSummary(visibleCount) {
        const from = state.total === 0 ? 0 : state.page * PAGE_SIZE + 1;
        const to = state.total === 0 ? 0 : from + visibleCount - 1;
        $("#result-summary").textContent = state.total === 0
            ? "当前条件无数据"
            : `显示 ${formatCount(from)}–${formatCount(to)} 条，共 ${formatCount(state.total)} 条`;
    }

    function renderPagination() {
        const pagination = $("#pagination");
        pagination.hidden = state.total === 0;
        $("#previous-page").disabled = state.page <= 0;
        $("#next-page").disabled = state.page + 1 >= state.totalPages;
        $("#page-indicator").textContent =
            `第 ${state.page + 1} / ${state.totalPages} 页 · 共 ${formatCount(state.total)} 条`;
    }

    function renderLoading(loading, empty = false) {
        state.loading = loading;
        $("#loading-state").hidden = !loading;
        $("#table-wrap").hidden = loading || empty;
        $("#empty-state").hidden = loading || !empty;
        $("#search-button").disabled = loading;
        if (loading) $("#pagination").hidden = true;
    }

    async function changePage(nextPage) {
        if (state.loading || nextPage < 0 || nextPage >= state.totalPages) return;
        state.page = nextPage;
        updateBrowserUrl();
        await loadSubmissions();
        $("#data-heading").scrollIntoView({ behavior: "smooth", block: "start" });
    }

    async function resetFilters() {
        state.filters = {
            q: "",
            from: "",
            to: "",
            city: state.scope.allCities ? "" : state.scope.city,
            salespersonId: "",
            status: "",
            visitType: "",
            locationStatus: "",
            reviewStatus: "",
            mediaStatus: "",
            sortBy: "completedAt",
            sortDirection: "desc"
        };
        state.page = 0;
        renderCityOptions();
        renderSalespersonOptions(state.filters.city, "");
        writeFiltersToForm();
        updateBrowserUrl();
        updateExportLink();
        await loadSubmissions();
    }

    function readFiltersFromForm() {
        const from = $("#filter-from").value;
        const to = $("#filter-to").value;
        if (from && to && from > to) {
            showError("开始日期不能晚于结束日期。");
            $("#filter-from").focus();
            return false;
        }
        state.filters = {
            q: cleanText($("#filter-query").value),
            from,
            to,
            city: state.scope.allCities ? $("#filter-city").value : state.scope.city,
            salespersonId: $("#filter-salesperson").value,
            status: $("#filter-status").value,
            visitType: $("#filter-visit-type").value,
            locationStatus: $("#filter-location-status").value,
            reviewStatus: $("#filter-review-status").value,
            mediaStatus: $("#filter-media-status").value,
            sortBy: state.filters.sortBy,
            sortDirection: state.filters.sortDirection
        };
        return true;
    }

    function readFiltersFromUrl() {
        const params = new URLSearchParams(window.location.search);
        state.filters = {
            q: cleanText(params.get("q")),
            from: safeDate(params.get("from")),
            to: safeDate(params.get("to")),
            city: cleanText(params.get("city")),
            salespersonId: cleanText(params.get("salespersonId")),
            status: ["DRAFT", "SUBMITTED"].includes(params.get("status")) ? params.get("status") : "",
            visitType: ["FIRST_VISIT", "REVISIT"].includes(params.get("visitType"))
                ? params.get("visitType") : "",
            locationStatus: Object.hasOwn(LOCATION_LABELS, params.get("locationStatus")) ? params.get("locationStatus") : "",
            reviewStatus: Object.hasOwn(REVIEW_LABELS, params.get("reviewStatus")) ? params.get("reviewStatus") : "",
            mediaStatus: ["HAS_AUDIO", "MISSING_AUDIO", "MISSING_PHOTO"].includes(params.get("mediaStatus")) ? params.get("mediaStatus") : "",
            sortBy: Object.hasOwn(SORT_LABELS, params.get("sortBy")) ? params.get("sortBy") : "completedAt",
            sortDirection: params.get("sortDirection") === "asc" ? "asc" : "desc"
        };
        const page = Number.parseInt(params.get("page"), 10);
        state.page = Number.isInteger(page) && page > 0 ? page - 1 : 0;
    }

    function writeFiltersToForm() {
        $("#filter-query").value = state.filters.q;
        $("#filter-from").value = state.filters.from;
        $("#filter-to").value = state.filters.to;
        $("#filter-status").value = state.filters.status;
        $("#filter-visit-type").value = state.filters.visitType;
        $("#filter-location-status").value = state.filters.locationStatus;
        $("#filter-review-status").value = state.filters.reviewStatus;
        $("#filter-media-status").value = state.filters.mediaStatus;
        renderSort();
        if (Array.from($("#filter-city").options).some((item) => item.value === state.filters.city)) {
            $("#filter-city").value = state.filters.city;
        }
        if (Array.from($("#filter-salesperson").options).some((item) => item.value === state.filters.salespersonId)) {
            $("#filter-salesperson").value = state.filters.salespersonId;
        }
    }

    function updateExportLink() {
        const params = buildFilterParams();
        $("#export-link").href = params.toString() ? `${EXPORT_PATH}?${params.toString()}` : EXPORT_PATH;
    }

    function updateBrowserUrl() {
        const params = buildFilterParams();
        if (state.page > 0) params.set("page", String(state.page + 1));
        const query = params.toString();
        window.history.replaceState(null, "", `${window.location.pathname}${query ? `?${query}` : ""}`);
    }

    function buildFilterParams() {
        const params = new URLSearchParams();
        Object.entries(state.filters).forEach(([name, value]) => {
            if (value) params.set(name, value);
        });
        return params;
    }

    async function requestJson(url, signal) {
        const response = await fetch(url, {
            method: "GET",
            credentials: "same-origin",
            cache: "no-store",
            headers: { Accept: "application/json" },
            signal
        });
        let payload = null;
        const contentType = response.headers.get("content-type") || "";
        if (contentType.includes("application/json")) {
            payload = await response.json();
        }
        if (!response.ok) {
            const error = new Error(response.status === 401
                ? "管理会话已失效，请重新登录。"
                : (response.status === 403 ? "当前账号无权查看该城市数据。"
                    : (cleanText(payload && payload.message) || `请求失败（HTTP ${response.status}）`)));
            error.status = response.status;
            error.code = cleanText(payload && payload.code);
            throw error;
        }
        return payload || {};
    }

    async function requestAuth(url, body) {
        const response = await fetch(url, {
            method: "POST",
            credentials: "same-origin",
            cache: "no-store",
            headers: { Accept: "application/json", "Content-Type": "application/json" },
            body: JSON.stringify(body)
        });
        const contentType = response.headers.get("content-type") || "";
        const payload = contentType.includes("application/json") ? await response.json() : null;
        if (!response.ok) {
            const error = new Error(cleanText(payload && payload.message)
                || (response.status === 401 ? "用户名或密码错误。" : `请求失败（HTTP ${response.status}）`));
            error.status = response.status;
            error.code = cleanText(payload && payload.code);
            throw error;
        }
        return payload || {};
    }

    async function requestAction(url, options = {}) {
        const headers = { Accept: "application/json" };
        if (state.scope.csrfToken) headers["X-CSRF-Token"] = state.scope.csrfToken;
        const request = {
            method: options.method || "POST",
            credentials: "same-origin",
            cache: "no-store",
            headers
        };
        if (options.body !== undefined) {
            headers["Content-Type"] = "application/json";
            request.body = JSON.stringify(options.body);
        }
        const response = await fetch(url, request);
        const contentType = response.headers.get("content-type") || "";
        let payload = null;
        if (contentType.includes("application/json")) payload = await response.json();
        if (!response.ok) {
            let message = cleanText(payload && payload.message);
            if (!message && response.status === 401) message = "管理会话已失效，请重新登录。";
            if (!message && response.status === 403) message = "当前账号无权执行此操作，或所选数据超出城市管理范围。";
            if (!message && response.status === 404) message = "请求的记录或管理接口不存在，可能尚未上线或已被处理。";
            if (!message && response.status === 409) message = "当前状态不允许此操作，请刷新后重试。";
            const error = new Error(message || `请求失败（HTTP ${response.status}）`);
            error.status = response.status;
            error.code = cleanText(payload && payload.code);
            throw error;
        }
        return unwrap(payload || {});
    }

    function unwrap(payload) {
        return payload && typeof payload.data === "object" ? payload.data : (payload || {});
    }

    function showError(message) {
        $("#page-error-message").textContent = message;
        $("#page-error").hidden = false;
    }

    function showSuccess(message) {
        if (state.successTimer) window.clearTimeout(state.successTimer);
        $("#page-success-message").textContent = message;
        $("#page-success").hidden = false;
        state.successTimer = window.setTimeout(hideSuccess, 8000);
    }

    function hideSuccess() {
        if (state.successTimer) window.clearTimeout(state.successTimer);
        state.successTimer = null;
        $("#page-success").hidden = true;
        $("#page-success-message").textContent = "";
    }

    function hideError() {
        $("#page-error").hidden = true;
        $("#page-error-message").textContent = "";
    }

    function errorMessage(error, fallback) {
        return error && typeof error.message === "string" && error.message.trim() ? error.message : fallback;
    }

    function option(value, label) {
        const item = document.createElement("option");
        item.value = value;
        item.textContent = label;
        return item;
    }

    function uniqueStrings(values) {
        return [...new Set(values.map(cleanText).filter(Boolean))];
    }

    function cleanText(value) {
        return value == null ? "" : String(value).trim();
    }

    function safeDate(value) {
        const text = cleanText(value);
        return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : "";
    }

    function decimalText(value) {
        if (value === null || value === undefined || value === "") return "";
        const number = Number(value);
        return Number.isFinite(number) ? String(Math.round(number * 1e6) / 1e6) : cleanText(value);
    }

    function numberValue(...values) {
        for (const value of values) {
            const number = Number(value);
            if (Number.isFinite(number) && number >= 0) return Math.trunc(number);
        }
        return 0;
    }

    function positiveInteger(value) {
        const number = Number(value);
        return Number.isInteger(number) && number > 0 ? number : 0;
    }

    function formatCount(value) {
        return new Intl.NumberFormat("zh-CN").format(value || 0);
    }

    function formatBytes(value) {
        const bytes = Number(value);
        if (!Number.isFinite(bytes) || bytes < 0) return "--";
        if (bytes < 1024) return `${Math.trunc(bytes)} B`;
        const units = ["KB", "MB", "GB", "TB"];
        let amount = bytes / 1024;
        let index = 0;
        while (amount >= 1024 && index < units.length - 1) {
            amount /= 1024;
            index += 1;
        }
        return `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: amount >= 100 ? 0 : 1 })
            .format(amount)} ${units[index]}`;
    }

    function formatDateTime(value) {
        if (!value) return "时间未记录";
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return cleanText(value);
        return new Intl.DateTimeFormat("zh-CN", {
            timeZone: "Asia/Shanghai",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            hour12: false
        }).format(date).replace("/", "-");
    }

    function formatFullDateTime(value) {
        if (!value) return "时间未记录";
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return cleanText(value);
        return new Intl.DateTimeFormat("zh-CN", {
            timeZone: "Asia/Shanghai",
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            hour12: false
        }).format(date).replaceAll("/", "-");
    }
}());
