(function (root) {
    "use strict";

    const DAY_MS = 86400000;
    const PAGE_SIZE = 20;
    const MEDIA_BASE = "/sales-checkin/api/v1/submissions/";
    const TERMINAL_MEDIA = new Set(["UPLOADED", "SKIPPED", "DISCARDED"]);
    const pad = (value) => String(value).padStart(2, "0");
    const unwrap = (value) => value && Object.prototype.hasOwnProperty.call(value, "data") ? value.data : value;

    // All calendar boundaries are business days in Shanghai, independent of the phone's timezone.
    function shanghaiParts(value) {
        if (value === undefined || value === null || value === "") return null;
        const date = new Date(value);
        if (!Number.isFinite(date.getTime())) return null;
        try {
            const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric",
                month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
                hourCycle: "h23" }).formatToParts(date);
            return Object.fromEntries(parts.map((part) => [part.type, part.value]));
        } catch (_) {
            const adjusted = new Date(date.getTime() + 8 * 3600000);
            return { year: String(adjusted.getUTCFullYear()), month: pad(adjusted.getUTCMonth() + 1),
                day: pad(adjusted.getUTCDate()), hour: pad(adjusted.getUTCHours()),
                minute: pad(adjusted.getUTCMinutes()), second: pad(adjusted.getUTCSeconds()) };
        }
    }
    function dayKey(value) {
        const parts = shanghaiParts(value);
        return parts ? `${parts.year}-${parts.month}-${parts.day}` : "";
    }
    function timestamp(value) {
        const parts = shanghaiParts(value);
        return parts ? `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}` : "时间未提供";
    }
    function validDay(value) {
        if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
        const time = Date.parse(`${value}T00:00:00Z`);
        return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value;
    }
    function shiftDay(value, days) {
        if (!validDay(value)) return "";
        return new Date(Date.parse(`${value}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
    }
    function validRange(from, to, today) {
        return validDay(from) && validDay(to) && from <= to && to <= today;
    }
    function duration(value) {
        if (value === null || value === undefined || !Number.isFinite(Number(value)) || Number(value) < 0) return "时长待解析";
        const seconds = Math.floor(Number(value) / 1000);
        const minutes = Math.floor(seconds / 60);
        return `${minutes}:${pad(seconds % 60)}`;
    }
    function positiveDuration(value) {
        return value !== null && value !== undefined && Number.isFinite(Number(value)) && Number(value) > 0
            ? Number(value) : null;
    }
    function audioDuration(item, aggregate = false) {
        const parsed = positiveDuration(aggregate ? item.audioDurationMs : item.parsedDurationMs);
        if (parsed !== null) return duration(parsed);
        const displayed = positiveDuration(aggregate ? item.audioDisplayDurationMs : item.durationMs);
        const source = aggregate ? item.audioDurationSource : item.durationSource;
        if (displayed !== null && source === "SERVER_PARSED") return duration(displayed);
        const estimated = displayed !== null && ["CLIENT_ESTIMATE", "MIXED"].includes(source)
            ? displayed : !aggregate ? positiveDuration(item.clientDurationMs) : null;
        if (estimated !== null) return `${duration(estimated)} · ${source === "MIXED" ? "含本机计时" : "本机记录"}`;
        // Interrupted session elapsed time is not the encoded recording duration.
        return "时长待确认";
    }
    function firstPhoto(item) {
        const photo = item.photos?.find((value) => value && (value.mediaId || value.photoId));
        if (photo) return { ...photo, mediaId: photo.mediaId || `photo-${photo.photoId}` };
        if (item.photoIds?.length) return { mediaId: `photo-${item.photoIds[0]}` };
        return item.uploadedMedia?.includes("storefront-photo") ? { mediaId: "storefront-photo" } : null;
    }
    function ownerKey(identity) {
        return identity?.authenticated && identity.salespersonId
            ? `${identity.tenantId || "same-origin"}:${identity.salespersonId}` : "";
    }
    function pendingEvidence(snapshot) {
        const submission = snapshot?.submission || {};
        return (submission.photos || []).some(photo => photo.uploadState !== "UPLOADED")
            || submission.pendingWechat === true || (submission.audioSegments || [])
            .some((segment) => !TERMINAL_MEDIA.has(segment.uploadState));
    }
    function ownLocalRecords(records, identity) {
        const owner = ownerKey(identity);
        if (!owner || !Array.isArray(records)) return [];
        return records.filter((record) => record.owner === owner
            && String(record.snapshot?.visit?.salespersonId || "") === String(identity.salespersonId)
            && record.snapshot?.submission?.clientSubmissionId
            && (record.snapshot.visit.selectedStore || record.snapshot.store?.name || record.snapshot.submission.serverId))
            .sort((left, right) => (right.updatedAt || 0) - (left.updatedAt || 0));
    }
    function mediaUrl(id, mediaId, variant) {
        return `${MEDIA_BASE}${encodeURIComponent(id)}/mine/media/${encodeURIComponent(mediaId)}?variant=${variant}`;
    }
    function safeMediaUrl(value, id, mediaId, variant, origin) {
        if (!value) return "";
        try {
            const base = origin || root.location?.origin || "http://localhost";
            const url = new URL(value, base);
            const expected = new URL(mediaUrl(id, mediaId, variant), base);
            return url.origin === expected.origin && url.pathname === expected.pathname
                && url.searchParams.get("variant") === variant && [...url.searchParams.keys()].length === 1
                && !url.hash && !url.username && !url.password ? `${url.pathname}${url.search}` : "";
        } catch (_) { return ""; }
    }

    /** Own-history read model; transport and all mutation/recovery credentials remain in app.js. */
    function init(adapters) {
        if (typeof adapters?.requestJson !== "function" || typeof adapters?.getIdentity !== "function") {
            throw new Error("打卡记录缺少身份或请求适配器");
        }
        const document = root.document;
        const byId = (id) => document.getElementById(id);
        const refs = { page: byId("personal-history-page"), content: byId("history-content"),
            detailPage: byId("history-detail-page"), detailContent: byId("history-detail-content"),
            calendar: byId("history-calendar-dialog"), calendarContent: byId("history-calendar-content"),
            photo: byId("history-photo-dialog"), photoContent: byId("history-photo-content") };
        if (Object.values(refs).some((value) => !value)) throw new Error("打卡记录页面尚未就绪");
        const now = () => typeof adapters.now === "function" ? adapters.now() : Date.now();
        const today = () => dayKey(now());
        const initialFilters = () => ({ from: today(), to: today(), sort: "desc" });
        const state = { owner: "", open: false, view: "list", tab: "submitted", filters: initialFilters(),
            items: [], total: null, pages: 0, page: -1, loaded: false, loading: false, error: "", nextError: "",
            locals: [], localLoading: false, localError: "", actionError: "", actionBusy: false, actionRecordId: "",
            detailId: "", detail: null, detailLoading: false, detailError: "",
            listEpoch: 0, localEpoch: 0, detailEpoch: 0, listAbort: null, detailAbort: null,
            scrollY: 0, calendar: null, activeAudio: null, dialogFocus: null };

        function element(tag, className, text) {
            const node = document.createElement(tag);
            if (className) node.className = className;
            if (text !== undefined && text !== null) node.textContent = String(text);
            return node;
        }
        function icon(name) {
            const node = element("span", `app-icon icon-${name}`);
            node.setAttribute("aria-hidden", "true");
            return node;
        }
        function button(text, className, action, iconName) {
            const node = element("button", className);
            node.type = "button";
            if (iconName) node.append(icon(iconName));
            if (text) node.append(element("span", "", text));
            node.addEventListener("click", action);
            return node;
        }
        function notice(text, className = "history-empty") {
            const node = element("div", className, text);
            node.setAttribute("role", className.includes("error") ? "alert" : "status");
            return node;
        }
        function retryNotice(text, retry) {
            const node = notice(text, "history-error");
            node.append(button("重试", "history-retry", retry));
            return node;
        }
        function stopAudio() {
            for (const audio of refs.detailContent.querySelectorAll("audio")) {
                audio.pause(); audio.removeAttribute("src"); audio.load();
            }
            state.activeAudio = null;
        }
        function closeDialog(dialog) {
            if (dialog.open && typeof dialog.close === "function") dialog.close();
            dialog.removeAttribute("open"); dialog.hidden = true;
            state.dialogFocus?.focus?.();
            state.dialogFocus = null;
        }
        function openDialog(dialog) {
            state.dialogFocus = document.activeElement;
            dialog.hidden = false;
            if (typeof dialog.showModal === "function") { if (!dialog.open) dialog.showModal(); }
            else { dialog.setAttribute("open", ""); dialog.setAttribute("role", "dialog"); dialog.setAttribute("aria-modal", "true"); }
            dialog.querySelector("button, input, select")?.focus();
        }
        function invalidate() {
            state.listEpoch += 1; state.detailEpoch += 1; state.localEpoch += 1;
            state.listAbort?.abort(); state.detailAbort?.abort();
            state.loading = false; state.detailLoading = false; state.localLoading = false;
        }
        function resetIdentity() {
            invalidate(); stopAudio(); closeDialog(refs.calendar); closeDialog(refs.photo);
            Object.assign(state, { owner: "", open: false, view: "list", tab: "submitted", filters: initialFilters(),
                items: [], total: null, pages: 0, page: -1, loaded: false, error: "", nextError: "", locals: [],
                localError: "", actionError: "", actionBusy: false, actionRecordId: "", detailId: "", detail: null, detailError: "",
                scrollY: 0 });
            refs.content.replaceChildren(); refs.detailContent.replaceChildren(); refs.photoContent.replaceChildren();
            refs.calendarContent.replaceChildren(); refs.page.hidden = true; refs.detailPage.hidden = true;
        }
        function ensureOwner() {
            const current = ownerKey(adapters.getIdentity());
            if (current !== state.owner) { resetIdentity(); state.owner = current; }
            if (!current) { adapters.onAuthRequired?.(); return false; }
            return true;
        }
        function validRequest(owner, epoch, kind) {
            return state.open && state.owner === owner && ownerKey(adapters.getIdentity()) === owner
                && state[`${kind}Epoch`] === epoch;
        }
        function errorMessage(error, fallback) {
            if (error?.status === 401 || error?.status === 403) return "身份已失效或无权访问，请重新验证身份。";
            if (error?.status === 404) return "该记录不存在或已删除。";
            return fallback;
        }
        function announceView(view) {
            state.view = view;
            refs.page.hidden = view !== "list"; refs.detailPage.hidden = view !== "detail";
            adapters.onViewChange?.(view);
        }
        function restoreScroll() {
            const run = () => { if (state.open && state.view === "list") root.scrollTo?.(0, state.scrollY); };
            if (root.requestAnimationFrame) root.requestAnimationFrame(run); else run();
        }
        function pendingRecords() {
            return state.locals.filter((record) => record.snapshot.submission.status !== "SUBMITTED" || pendingEvidence(record.snapshot));
        }
        async function loadLocals() {
            const owner = state.owner; const epoch = ++state.localEpoch;
            state.localLoading = true; state.localError = "";
            try {
                const records = await (adapters.getLocalRecords?.() || []);
                if (!validRequest(owner, epoch, "local")) return;
                state.locals = ownLocalRecords(records, adapters.getIdentity());
            } catch (_) {
                if (!validRequest(owner, epoch, "local")) return;
                state.localError = "暂时无法读取本机待处理记录；服务端已提交记录仍可查询。";
            } finally {
                if (validRequest(owner, epoch, "local")) {
                    state.localLoading = false;
                    if (state.view === "list") renderList();
                    else if (state.detail && !state.actionBusy) renderDetail();
                }
            }
        }
        async function loadPage(page = 0) {
            if (!ensureOwner() || !state.open) return;
            state.listAbort?.abort();
            state.listAbort = typeof root.AbortController === "function" ? new root.AbortController() : null;
            const owner = state.owner; const epoch = ++state.listEpoch;
            const filters = { ...state.filters };
            const identity = adapters.getIdentity();
            state.loading = true; state.error = ""; state.nextError = "";
            if (page === 0) { state.items = []; state.total = null; state.loaded = false; state.page = -1; }
            renderList();
            const params = new URLSearchParams({ salespersonId: identity.salespersonId, status: "SUBMITTED",
                dateFrom: filters.from, dateTo: filters.to, sortDir: filters.sort, page: String(page), size: String(PAGE_SIZE) });
            try {
                const data = unwrap(await adapters.requestJson(`/submissions/mine?${params}`, { signal: state.listAbort?.signal }));
                if (!validRequest(owner, epoch, "list")) return;
                if (!Array.isArray(data?.items) || !Number.isInteger(data.totalElements) || data.totalElements < 0
                    || !Number.isInteger(data.totalPages) || data.totalPages < 0 || data.page !== page) {
                    throw new Error("历史回执格式不完整");
                }
                // Mixed-status or foreign responses must never be represented as valid empty history.
                if (data.items.some((item) => !item.id || item.status !== "SUBMITTED"
                    || (item.salespersonId && String(item.salespersonId) !== String(identity.salespersonId))
                    || !dayKey(item.submittedAt) || dayKey(item.submittedAt) < filters.from || dayKey(item.submittedAt) > filters.to)) {
                    throw new Error("历史回执不符合本人日期范围");
                }
                const combined = page === 0 ? data.items : [...state.items, ...data.items];
                state.items = [...new Map(combined.map((item) => [String(item.id), item])).values()];
                state.total = data.totalElements; state.pages = data.totalPages; state.page = page; state.loaded = true;
            } catch (error) {
                if (!validRequest(owner, epoch, "list")) return;
                const message = errorMessage(error, "记录加载失败，请检查网络后重试。");
                if (page === 0) state.error = message; else state.nextError = message;
            } finally {
                if (validRequest(owner, epoch, "list")) { state.loading = false; if (state.view === "list") renderList(); }
            }
        }
        function setDateRange(from, to) {
            if (!ensureOwner() || !validRange(from, to, today())) return false;
            state.filters = { ...state.filters, from, to }; state.scrollY = 0; state.tab = "submitted";
            void loadPage(0); return true;
        }
        function setSort(sort) {
            if (!["asc", "desc"].includes(sort) || !ensureOwner()) return;
            state.filters.sort = sort; state.scrollY = 0; void loadPage(0);
        }
        function setTab(tab) {
            if (!["submitted", "pending"].includes(tab) || !ensureOwner()) return;
            state.tab = tab; state.actionError = ""; state.scrollY = 0; renderList();
            if (tab === "submitted" && !state.loaded && !state.loading) void loadPage(0);
            if (tab === "pending") void loadLocals();
        }
        function thumbnailImage(id, item, label, onError, onLoad) {
            const image = element("img"); image.alt = label; image.loading = "lazy"; image.decoding = "async";
            const url = safeMediaUrl(item.thumbnailUrl, id, item.mediaId, "thumbnail") || mediaUrl(id, item.mediaId, "thumbnail");
            const owner = state.owner; const view = state.view;
            let retries = 0;
            image.addEventListener("load", () => { image.hidden = false; onLoad?.(); });
            image.addEventListener("error", () => {
                image.hidden = true; onError?.();
                if (retries >= 2 || typeof root.setTimeout !== "function") return;
                retries += 1;
                root.setTimeout(() => {
                    if (!state.open || state.view !== view || state.owner !== owner
                            || ownerKey(adapters.getIdentity()) !== owner || image.isConnected === false) return;
                    // Only retry the bounded server thumbnail, never auto-decode original phone photos.
                    image.loading = "eager"; image.src = url;
                }, retries * 1500);
            });
            image.src = url;
            return image;
        }
        function recordThumbnail(id, label, photo) {
            const box = element("span", "history-record-image");
            box.append(icon("photo"));
            if (id && photo) box.append(thumbnailImage(id, photo, label,
                () => box.setAttribute("aria-label", "预览暂不可用，打开明细查看照片"),
                () => box.removeAttribute("aria-label")));
            return box;
        }
        function submittedCard(item) {
            const card = button("", "history-record-card", () => { void showDetail(item.id); });
            card.append(recordThumbnail(item.id, "门店现场照片", firstPhoto(item)));
            const body = element("span", "history-record-body");
            body.append(element("span", "history-record-time", timestamp(item.submittedAt)),
                element("strong", "history-record-name", item.storeName || "未命名门店"),
                element("span", "history-record-summary", item.visitResult || "点击查看拜访明细"));
            const count = item.audioSegmentIds?.length || 0;
            const status = element("span", "history-record-status", "已提交");
            status.prepend?.(icon("check-circle"));
            if (count) status.append(element("span", "history-record-audio", ` · ${count} 段录音 · ${audioDuration(item, true)}`));
            body.append(status); card.append(body, icon("chevron-right")); return card;
        }
        function pendingCard(record) {
            const snapshot = record.snapshot; const submission = snapshot.submission;
            const isSubmitted = submission.status === "SUBMITTED";
            const unknown = submission.syncState === "UNKNOWN" || Boolean(submission.attemptedPayload);
            const status = isSubmitted ? "已提交 · 证据待补传" : unknown ? "提交结果待确认" : submission.syncRequested ? "待同步" : "本机草稿";
            const card = button("", "history-record-card", () => { void resumeLocal(record); });
            card.disabled = state.actionBusy;
            card.append(recordThumbnail(submission.serverId, "现场照片", firstPhoto({
                photos: submission.photos?.filter((photo) => photo.uploadState === "UPLOADED") })));
            const body = element("span", "history-record-body");
            body.append(element("span", "history-record-time", `本机保存于 ${timestamp(record.updatedAt || snapshot.savedAt)}`),
                element("strong", "history-record-name", snapshot.visit.selectedStore?.name || snapshot.store?.name || "尚未选择门店"),
                element("span", "history-record-summary", snapshot.visit.visitResult || "继续完成这次拜访"),
                element("span", "history-record-status is-warning", status));
            card.append(body, icon("chevron-right")); return card;
        }
        function renderList() {
            if (!state.open || state.view !== "list") return;
            const identity = adapters.getIdentity();
            const body = element("div", "history-body");
            body.append(element("p", "history-identity", `${identity.salespersonName || "本人"} · 仅本人记录`));
            const tabs = element("div", "history-tabs"); tabs.setAttribute("role", "tablist");
            for (const [tab, label] of [["submitted", "已提交"], ["pending", `待处理${state.localLoading ? "" : `（${pendingRecords().length}）`}`]]) {
                const node = button(label, `history-tab${state.tab === tab ? " is-active" : ""}`, () => setTab(tab));
                node.setAttribute("role", "tab"); node.setAttribute("aria-selected", String(state.tab === tab)); tabs.append(node);
            }
            body.append(tabs);
            if (state.tab === "submitted") {
                const controls = element("div", "history-date-controls");
                const previous = button("", "history-date-arrow", () => setDateRange(shiftDay(state.filters.from, -1), shiftDay(state.filters.to, -1)), "chevron-left");
                previous.setAttribute("aria-label", "日期往前一天");
                const label = state.filters.from === state.filters.to ? state.filters.from : `${state.filters.from} 至 ${state.filters.to}`;
                const trigger = button(label, "history-date-trigger", openCalendar, "calendar"); trigger.append(icon("chevron-down"));
                const next = button("", "history-date-arrow", () => setDateRange(shiftDay(state.filters.from, 1), shiftDay(state.filters.to, 1)), "chevron-right");
                next.disabled = state.filters.to >= today(); next.setAttribute("aria-label", "日期往后一天");
                controls.append(previous, trigger, next); body.append(controls);
                const presets = element("div", "history-presets");
                const isToday = state.filters.from === today() && state.filters.to === today();
                const isWeek = state.filters.from === shiftDay(today(), -6) && state.filters.to === today();
                presets.append(button("今天", `history-preset${isToday ? " is-active" : ""}`, () => setDateRange(today(), today())),
                    button("近 7 天", `history-preset${isWeek ? " is-active" : ""}`, () => setDateRange(shiftDay(today(), -6), today())),
                    button("自定义", `history-preset${!isToday && !isWeek ? " is-active" : ""}`, openCalendar));
                body.append(presets);
                const toolbar = element("div", "history-list-toolbar");
                toolbar.append(element("span", "history-count", state.total === null ? (state.loading ? "正在查询拜访记录…" : "记录数量暂不可用") : `共 ${state.total} 次拜访`));
                const sort = element("select", "history-sort"); sort.setAttribute("aria-label", "记录时间排序");
                for (const [value, label] of [["desc", "最新在前"], ["asc", "最早在前"]]) {
                    const option = element("option", "", label); option.value = value; sort.append(option);
                }
                sort.value = state.filters.sort; sort.addEventListener("change", () => setSort(sort.value)); toolbar.append(sort); body.append(toolbar);
                if (state.error) body.append(retryNotice(state.error, () => { void loadPage(0); }));
                else if (!state.loaded && state.loading) body.append(notice("正在加载本人记录…", "history-loading"));
                else {
                    const list = element("div", "history-record-list"); let previousDay = "";
                    for (const item of state.items) {
                        const currentDay = dayKey(item.submittedAt);
                        if (state.filters.from !== state.filters.to && currentDay !== previousDay) {
                            list.append(element("h2", "history-date-group", currentDay)); previousDay = currentDay;
                        }
                        list.append(submittedCard(item));
                    }
                    body.append(list);
                    if (state.loaded && state.total === 0) body.append(notice("所选日期没有已提交记录。"));
                    if (state.nextError) body.append(retryNotice(state.nextError, () => { void loadPage(state.page + 1); }));
                    else if (state.page + 1 < state.pages) {
                        const more = button(state.loading ? "正在加载…" : "加载更多", "history-more", () => { void loadPage(state.page + 1); });
                        more.disabled = state.loading; body.append(more);
                    } else if (state.loaded && state.total > 0) body.append(element("p", "history-end", state.filters.from === state.filters.to ? "已显示当日全部记录" : "已显示所选日期全部记录"));
                }
            } else {
                body.append(element("p", "history-pending-note", "这里是本机保存的草稿、待同步和证据待补传记录，不计入已提交拜访数。"));
                if (state.localError) body.append(retryNotice(state.localError, () => { void loadLocals(); }));
                else if (state.localLoading && !state.locals.length) body.append(notice("正在读取本机记录…", "history-loading"));
                else if (!pendingRecords().length) body.append(notice("本机暂无待处理记录。"));
                const list = element("div", "history-record-list"); pendingRecords().forEach((record) => list.append(pendingCard(record))); body.append(list);
                if (state.actionError) body.append(retryNotice(state.actionError, () => {
                    const record = state.locals.find((item) => item.snapshot.submission.clientSubmissionId === state.actionRecordId);
                    if (record) void resumeLocal(record); else void loadLocals();
                }));
            }
            if (state.localError && state.tab !== "pending") body.append(notice(state.localError, "history-local-note"));
            refs.content.replaceChildren(body);
        }

        async function open(options = {}) {
            if (!ensureOwner()) return;
            if (state.view === "detail") { state.detailEpoch += 1; state.detailAbort?.abort(); stopAudio(); closeDialog(refs.photo); }
            state.open = true; state.actionError = "";
            if (options.pendingOnly) state.tab = "pending";
            announceView("list"); renderList(); restoreScroll();
            const jobs = [loadLocals()];
            if (options.refresh || (!state.loaded && !state.loading)) jobs.push(loadPage(0));
            await Promise.all(jobs);
        }
        function close() {
            if (state.open && state.view === "list") state.scrollY = root.scrollY || 0;
            state.open = false; invalidate(); stopAudio(); closeDialog(refs.calendar); closeDialog(refs.photo);
            refs.page.hidden = true; refs.detailPage.hidden = true; adapters.onViewChange?.("closed");
        }
        async function refresh() {
            if (!ensureOwner() || !state.open) return;
            if (state.view === "detail") await Promise.all([loadDetail(state.detailId), loadLocals()]);
            else await Promise.all([loadPage(0), loadLocals()]);
        }
        function invalidateList() {
            state.listEpoch += 1; state.listAbort?.abort(); state.loading = false; state.loaded = false;
            // Keep date/sort/scroll while requiring a fresh server page the next time the list opens.
        }
        function backToList() {
            if (!ensureOwner() || !state.open) return;
            state.detailEpoch += 1; state.detailAbort?.abort(); stopAudio(); closeDialog(refs.photo);
            announceView("list"); renderList(); restoreScroll();
            if (!state.loaded && !state.loading) void loadPage(0);
        }
        async function getDetail(id, options) {
            const result = unwrap(await adapters.requestJson(`/submissions/${encodeURIComponent(id)}/mine`, options || {}));
            if (!result?.id || String(result.id) !== String(id) || String(result.salespersonId) !== String(adapters.getIdentity()?.salespersonId)) {
                throw new Error("记录身份不一致");
            }
            return result;
        }
        async function showDetail(id) {
            if (!ensureOwner()) return;
            if (state.view === "list") state.scrollY = root.scrollY || 0;
            state.open = true; stopAudio(); state.detailId = String(id); state.detail = null;
            state.detailError = ""; announceView("detail"); root.scrollTo?.(0, 0);
            await loadDetail(state.detailId);
            if (!state.locals.length && !state.localLoading) await loadLocals();
        }
        async function loadDetail(id) {
            if (!state.open || !id) return;
            state.detailAbort?.abort(); state.detailAbort = typeof root.AbortController === "function" ? new root.AbortController() : null;
            const owner = state.owner; const epoch = ++state.detailEpoch;
            state.detailLoading = true; state.detailError = ""; renderDetail();
            try {
                const result = await getDetail(id, { signal: state.detailAbort?.signal });
                if (!validRequest(owner, epoch, "detail")) return;
                state.detail = result;
            } catch (error) {
                if (!validRequest(owner, epoch, "detail")) return;
                state.detailError = errorMessage(error, "明细加载失败，请检查网络后重试。");
            } finally {
                if (validRequest(owner, epoch, "detail")) { state.detailLoading = false; renderDetail(); }
            }
        }
        function fact(list, label, value) {
            list.append(element("dt", "", label), element("dd", "", value === undefined || value === null || value === "" ? "未提供" : value));
        }
        function section(title, className = "") {
            const node = element("section", `history-detail-section ${className}`.trim()); node.append(element("h3", "", title)); return node;
        }
        function locationLabel(quality) {
            return ({ GOOD: "已记录设备定位", STALE: "已记录 · 定位未刷新", LOW_ACCURACY: "已记录 · 定位精度偏低",
                OUT_OF_RANGE: "已记录 · 距门店较远", USER_REPORTED: "已记录 · 销售反馈定位不准", MISSING: "未获取到设备定位",
                STORE_UNLOCATED: "已记录 · 门店坐标待完善",
                STORE_LOCATION_MISSING: "已记录 · 门店坐标待完善", STORE_ANCHOR_MISSING: "已记录 · 门店坐标待完善",
                FUTURE_TIMESTAMP: "已记录 · 设备时间待复核", TIME_UNKNOWN: "已记录 · 采集时间待复核" })[quality] || "定位证据已留存，后台可复核";
        }
        function renderDetail() {
            if (!state.open || state.view !== "detail") return;
            stopAudio();
            const body = element("div", "history-detail-body");
            if (state.detailError) body.append(retryNotice(state.detailError, () => { void loadDetail(state.detailId); }));
            else if (state.detailLoading) body.append(notice("正在加载打卡明细…", "history-loading"));
            else if (state.detail) {
                const detail = state.detail;
                const heading = element("div", "history-detail-heading");
                heading.append(element("h2", "", detail.storeName || "未命名门店"), element("p", "", detail.city || "城市未提供"));
                heading.append(element("span", `history-detail-status${detail.status === "SUBMITTED" ? "" : " is-warning"}`, detail.status === "SUBMITTED" ? "已提交" : "尚未提交"));
                body.append(heading);
                const info = section("拜访信息"); const facts = element("dl", "history-detail-facts");
                fact(facts, "打卡时间", detail.status === "SUBMITTED" ? timestamp(detail.submittedAt) : "尚未提交");
                fact(facts, "销售", detail.salespersonName); fact(facts, "城市", detail.city); fact(facts, "客户姓名", detail.customerName);
                if (detail.customerPhone) fact(facts, "客户电话", detail.customerPhone);
                info.append(facts); body.append(info);
                const result = section("拜访结果"); result.append(element("p", "history-detail-result", detail.visitResult || "未提供")); body.append(result);
                const location = section("现场定位", "history-detail-location");
                location.append(element("p", "", locationLabel(detail.locationQuality)), element("p", "history-location-note", "设备定位情况已留存"));
                const evidence = element("details"); evidence.append(element("summary", "", "查看定位证据"));
                const fields = element("dl", "history-detail-facts");
                fact(fields, "设备报告地址", detail.locationAddress);
                fact(fields, "设备经纬度", detail.longitude != null && detail.latitude != null ? `${detail.longitude}, ${detail.latitude}` : "未获取");
                fact(fields, "报告精度", detail.accuracyMeters != null ? `${Math.round(detail.accuracyMeters)} 米` : "未提供");
                fact(fields, "距门店", detail.distanceMeters != null ? `约 ${Math.round(detail.distanceMeters)} 米` : "无法计算");
                fact(fields, "采集时间", timestamp(detail.locationCapturedAt)); fact(fields, "服务端接收", timestamp(detail.locationReceivedAt));
                fact(fields, "定位来源", detail.locationSource); if (detail.locationNote) fact(fields, "补充说明", detail.locationNote);
                evidence.append(fields); location.append(evidence); body.append(location);
                const media = Array.isArray(detail.media) ? detail.media : [];
                const storefront = Array.isArray(detail.photos) && detail.photos.length
                    ? detail.photos.map(photo => ({...photo, mediaId: photo.mediaId || `photo-${photo.photoId}`, kind: "storefront-photo"}))
                    : media.filter(item => item.kind === "storefront-photo");
                const photos = [...storefront, ...media.filter(item => item.kind === "wechat-screenshot")];
                const photoSection = section(`现场照片与截图 · ${photos.length} 张`); const gallery = element("div", "history-detail-photos");
                const retryPhotos = button("刷新图片", "history-media-refresh", () => { void loadDetail(detail.id); });
                retryPhotos.hidden = true;
                for (const photo of photos) gallery.append(photoThumbnail(detail, photo, () => { retryPhotos.hidden = false; }));
                if (!photos.length) photoSection.append(element("p", "", "当前没有可查看的图片。"));
                photoSection.append(gallery, retryPhotos); body.append(photoSection);
                const audios = media.filter((item) => item.kind === "audio");
                const audioSection = section(`沟通录音 · ${audios.length} 段`);
                audios.forEach((mediaItem, index) => audioSection.append(audioPlayer(detail, mediaItem, index)));
                if (!audios.length) audioSection.append(element("p", "", "本次未上传录音。")); body.append(audioSection);

            }
            refs.detailContent.replaceChildren(body);
        }
        function photoThumbnail(detail, item, onError) {
            const label = item.kind === "storefront-photo" ? "现场门店照片" : "微信截图";
            const node = button("", "history-detail-photo", () => openPhoto(detail, item)); node.setAttribute("aria-label", `${label}，点击放大`);
            const caption = element("span", "", label);
            const image = thumbnailImage(detail.id, item, label, () => {
                caption.textContent = "预览暂不可用，点开原图"; onError?.();
            }, () => { caption.textContent = label; });
            node.append(icon("photo"), image, caption); return node;
        }
        function openPhoto(detail, item) {
            if (!ensureOwner() || !state.open || state.detailId !== String(detail.id)) return;
            const url = item.originalUrl ? safeMediaUrl(item.originalUrl, detail.id, item.mediaId, "original")
                : item.mediaId ? mediaUrl(detail.id, item.mediaId, "original") : "";
            refs.photoContent.replaceChildren();
            if (!url) refs.photoContent.append(notice("照片地址暂不可用，请刷新明细后重试。", "history-error"));
            else {
                const image = element("img", "history-photo-original"); image.alt = item.kind === "storefront-photo" ? "现场门店照片原图" : "微信截图原图";
                image.src = url;
                image.addEventListener("error", () => {
                    image.hidden = true; refs.photoContent.replaceChildren(retryNotice("原图加载失败，请检查网络后重试。", () => openPhoto(detail, item)));
                });
                refs.photoContent.append(image);
            }
            openDialog(refs.photo);
        }
        function audioPlayer(detail, item, index) {
            const box = element("div", "history-detail-audio");
            const header = element("div", "history-detail-audio-header");
            header.append(icon("mic"), element("strong", "", `录音 ${index + 1}`), element("span", "", audioDuration(item))); box.append(header);
            box.append(element("p", "history-audio-filename", item.originalFilename || "录音文件"));
            const playback = item.playbackStatus === "READY" ? safeMediaUrl(item.playbackUrl, detail.id, item.mediaId, "playback") : "";
            const original = safeMediaUrl(item.originalUrl, detail.id, item.mediaId, "original");
            const audio = element("audio", "history-player"); audio.controls = true; audio.preload = "none";
            audio.setAttribute("aria-label", `录音 ${index + 1} 播放器`);
            const status = element("p", "history-audio-state"); status.setAttribute("role", "status");
            if (playback) audio.src = playback;
            else if (original) {
                audio.src = original;
                status.textContent = item.playbackStatus === "FAILED" ? "兼容音频生成失败，可播放或下载原件。" : "可直接播放原件。";
            } else status.textContent = "录音地址暂不可用，请刷新明细重试。";
            audio.addEventListener("play", () => {
                if (!state.open || state.view !== "detail" || !ensureOwner()) { audio.pause(); return; }
                if (state.activeAudio && state.activeAudio !== audio) state.activeAudio.pause(); state.activeAudio = audio;
            });
            audio.addEventListener("error", () => { status.textContent = "当前浏览器无法播放这段录音，请下载原件或刷新明细后重试。"; });
            box.append(audio, status);
            if (original) { const link = element("a", "history-audio-download", "下载原始录音"); link.href = original; link.download = item.originalFilename || "录音"; box.append(link); }
            if (!playback) box.append(button("刷新解析状态", "history-media-refresh", () => { void loadDetail(detail.id); }));
            return box;
        }
        async function resumeLocal(record) {
            if (!ensureOwner() || !state.open || state.actionBusy || !state.locals.includes(record)) return;
            const owner = state.owner; const submission = record.snapshot.submission;
            if (submission.status === "SUBMITTED" && submission.serverId) { await showDetail(submission.serverId); return; }
            state.actionBusy = true; state.actionError = ""; state.actionRecordId = submission.clientSubmissionId; renderList();
            try {
                let receipt = null;
                if (submission.syncState === "UNKNOWN" || submission.attemptedPayload || submission.serverId) {
                    if (!submission.submissionKey) throw new Error("本机凭据缺失");
                    try { receipt = unwrap(await adapters.requestJson(`/submissions/by-client/${encodeURIComponent(submission.clientSubmissionId)}`,
                        { headers: { "X-Submission-Key": submission.submissionKey } })); }
                    catch (error) { if (error.status !== 404) throw error; }
                    if (ownerKey(adapters.getIdentity()) !== owner || state.owner !== owner || !state.open) return;
                    if (receipt?.status === "SUBMITTED" && receipt.id) { await showDetail(receipt.id); return; }
                }
                if (typeof adapters.onResumeLocal !== "function") throw new Error("恢复入口暂不可用");
                if (await adapters.onResumeLocal(record, { receipt, mode: "resume" }) === false) {
                    state.actionError = "当前还有操作未结束，请稍后重试恢复这条记录。";
                }
            } catch (_) {
                if (ownerKey(adapters.getIdentity()) === owner && state.owner === owner) {
                    state.actionError = "暂时无法确认或恢复这条记录。请保留本机记录并重试，避免重新创建同一次拜访。";
                }
            } finally {
                if (state.owner === owner) { state.actionBusy = false; if (state.view === "list") renderList(); }
            }
        }
        function openCalendar() {
            if (!ensureOwner() || !state.open) return;
            state.calendar = { mode: state.filters.from === state.filters.to ? "single" : "range", from: state.filters.from,
                to: state.filters.to, month: state.filters.from.slice(0, 7), pickingEnd: false, error: "" };
            renderCalendar(); openDialog(refs.calendar);
        }
        function changeMonth(offset) {
            const calendar = state.calendar;
            const base = new Date(`${calendar.month}-01T00:00:00Z`); base.setUTCMonth(base.getUTCMonth() + offset);
            const month = base.toISOString().slice(0, 7);
            if (month <= today().slice(0, 7)) { calendar.month = month; renderCalendar(); }
        }
        function selectDay(day) {
            const calendar = state.calendar;
            if (!validDay(day) || day > today()) return;
            if (calendar.mode === "single") { calendar.from = day; calendar.to = day; }
            else if (!calendar.pickingEnd) { calendar.from = day; calendar.to = ""; calendar.pickingEnd = true; }
            else { calendar.to = day; if (calendar.to < calendar.from) [calendar.from, calendar.to] = [calendar.to, calendar.from]; calendar.pickingEnd = false; }
            calendar.error = ""; renderCalendar();
        }
        function renderCalendar() {
            const calendar = state.calendar; if (!calendar) return;
            const body = element("div", "history-calendar-body"); const modes = element("div", "history-calendar-modes");
            for (const [mode, label] of [["single", "单日"], ["range", "日期范围"]]) {
                const node = button(label, `history-tab${calendar.mode === mode ? " is-active" : ""}`, () => {
                    calendar.mode = mode; calendar.pickingEnd = false;
                    if (mode === "single") calendar.to = calendar.from;
                    renderCalendar();
                }); node.setAttribute("aria-pressed", String(mode === calendar.mode)); modes.append(node);
            }
            body.append(modes);
            const nav = element("div", "history-calendar-month-nav");
            const previous = button("", "history-date-arrow", () => changeMonth(-1), "chevron-left"); previous.setAttribute("aria-label", "上个月");
            const month = element("input", "history-calendar-month-picker"); month.type = "month"; month.value = calendar.month; month.max = today().slice(0, 7);
            month.setAttribute("aria-label", "选择年份和月份"); month.addEventListener("change", () => {
                if (validDay(`${month.value}-01`) && month.value <= today().slice(0, 7)) { calendar.month = month.value; renderCalendar(); }
            });
            const next = button("", "history-date-arrow", () => changeMonth(1), "chevron-right"); next.setAttribute("aria-label", "下个月"); next.disabled = calendar.month >= today().slice(0, 7);
            nav.append(previous, month, next); body.append(nav);
            const weekdays = element("div", "history-calendar-weekdays"); ["一", "二", "三", "四", "五", "六", "日"].forEach((label) => weekdays.append(element("span", "", label))); body.append(weekdays);
            const grid = element("div", "history-calendar-grid");
            const first = `${calendar.month}-01`; const weekday = (new Date(`${first}T00:00:00Z`).getUTCDay() + 6) % 7;
            const monthEnd = new Date(`${first}T00:00:00Z`); monthEnd.setUTCMonth(monthEnd.getUTCMonth() + 1, 0);
            const visibleDays = Math.ceil((weekday + monthEnd.getUTCDate()) / 7) * 7;
            for (let offset = 0; offset < visibleDays; offset += 1) {
                const day = shiftDay(first, offset - weekday); const outside = day.slice(0, 7) !== calendar.month;
                const selected = day === calendar.from || day === calendar.to;
                const inRange = calendar.from && calendar.to && day > calendar.from && day < calendar.to;
                const node = button(String(Number(day.slice(-2))), `history-calendar-day${selected ? " is-selected" : ""}${inRange ? " is-in-range" : ""}${day === today() ? " is-today" : ""}${outside ? " is-outside" : ""}`, () => selectDay(day));
                node.disabled = day > today(); node.setAttribute("aria-label", day); node.setAttribute("aria-pressed", String(selected)); grid.append(node);
            }
            body.append(grid);
            const inputs = element("div", "history-calendar-range");
            for (const [field, label] of calendar.mode === "single" ? [["from", "选择日期"]] : [["from", "开始日期"], ["to", "结束日期"]]) {
                const wrapper = element("label", "", label); const input = element("input"); input.type = "date"; input.max = today(); input.value = calendar[field];
                input.addEventListener("change", () => {
                    calendar[field] = input.value; if (calendar.mode === "single") calendar.to = input.value;
                    if (validDay(input.value)) calendar.month = input.value.slice(0, 7);
                    calendar.error = ""; calendar.pickingEnd = false; renderCalendar();
                }); wrapper.append(input); inputs.append(wrapper);
            }
            body.append(inputs);
            if (calendar.mode === "range" && calendar.pickingEnd) body.append(notice("请选择结束日期（可与开始日期相同）。", "history-calendar-hint"));
            if (calendar.error) body.append(notice(calendar.error, "history-error"));
            const actions = element("div", "history-calendar-actions");
            actions.append(button("取消", "secondary-button", () => closeDialog(refs.calendar)), button("确认日期", "primary-button", () => {
                if (!validRange(calendar.from, calendar.to, today())) { calendar.error = "请选择有效的起止日期，结束日期不能早于开始日期或晚于今天。"; renderCalendar(); return; }
                setDateRange(calendar.from, calendar.to); closeDialog(refs.calendar);
            })); body.append(actions); refs.calendarContent.replaceChildren(body);
        }

        const bindings = [];
        for (const shell of [refs.page, refs.detailPage, refs.calendar, refs.photo]) {
            const handler = (event) => {
                const action = event.target.closest?.("[data-history-action]")?.dataset.historyAction;
                if (action === "back") { close(); adapters.onBack?.(); }
                if (action === "list") backToList();
                if (action === "close-calendar") closeDialog(refs.calendar);
                if (action === "close-photo") closeDialog(refs.photo);
            };
            shell.addEventListener("click", handler); bindings.push([shell, "click", handler]);
        }
        for (const dialog of [refs.calendar, refs.photo]) {
            const cancel = (event) => { event.preventDefault(); closeDialog(dialog); };
            const keys = (event) => {
                if (event.key === "Escape") { event.preventDefault(); closeDialog(dialog); }
                if (event.key !== "Tab") return;
                const nodes = [...dialog.querySelectorAll("button, input, select, a[href]")].filter((node) => !node.disabled && !node.hidden);
                if (!nodes.length) return;
                const index = nodes.indexOf(document.activeElement);
                if (event.shiftKey && index <= 0) { event.preventDefault(); nodes[nodes.length - 1].focus(); }
                else if (!event.shiftKey && (index === nodes.length - 1 || index < 0)) { event.preventDefault(); nodes[0].focus(); }
            };
            dialog.addEventListener("cancel", cancel); dialog.addEventListener("keydown", keys);
            bindings.push([dialog, "cancel", cancel], [dialog, "keydown", keys]);
        }
        return Object.freeze({ open, close, refresh, invalidateList, resetIdentity, showDetail, backToList, setDateRange, setSort, setTab,
            getState: () => ({ owner: state.owner, open: state.open, view: state.view, tab: state.tab, filters: { ...state.filters },
                ids: state.items.map((item) => item.id), totalElements: state.total, page: state.page, totalPages: state.pages,
                loading: state.loading, error: state.error, nextError: state.nextError, pendingCount: pendingRecords().length,
                localError: state.localError, actionError: state.actionError, detailId: state.detailId, detailError: state.detailError }),
            destroy: () => { resetIdentity(); bindings.forEach(([node, name, handler]) => node.removeEventListener(name, handler)); }
        });
    }

    root.SalesCheckinHistory = Object.freeze({ init });
    if (typeof module !== "undefined" && module.exports) module.exports = { init, dayKey, timestamp, validDay, shiftDay,
        validRange, duration, audioDuration, firstPhoto, ownerKey, pendingEvidence, ownLocalRecords, mediaUrl, safeMediaUrl };
})(typeof window !== "undefined" ? window : globalThis);
