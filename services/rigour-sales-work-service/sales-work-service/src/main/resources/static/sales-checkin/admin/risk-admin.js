/* Authenticated browser/file associations. Original visit verdicts remain independent. */
(() => {
    "use strict";
    const text = value => value == null ? "" : String(value).trim();
    const labels = { PENDING: "待复核", EXPLAINED: "共用已说明", FLAGGED: "确认异常", INCONCLUSIVE: "无法确认", NOT_REQUIRED: "无需复核" };
    const assignments = { PERSONAL: "管理员指定个人使用", SHARED: "管理员指定共用", UNCONFIRMED: "归属未确认" };
    const node = (tag, className, value) => { const n = document.createElement(tag); if (className) n.className = className; if (value != null) n.textContent = value; return n; };
    const button = (label, action, className = "text-button") => { const n = node("button", className, label); n.type = "button"; n.addEventListener("click", action); return n; };
    const duration = value => { if (!(Number(value) > 0)) return "时长待确认"; const s = Math.round(Number(value) / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
    const durationText = group => `${duration(group.durationMs)}${group.durationMs > 0 && group.durationSource === "CLIENT_ESTIMATE" ? " · 客户端读取" : ""}`;
    const status = group => node("span", `risk-status risk-status--${text(group.reviewStatus).toLowerCase()}`, `${labels[group.reviewStatus] || "状态未知"}${group.newEvidence ? " · 有新关联" : ""}`);
    const safeCount = value => Number.isFinite(Number(value)) && value != null ? String(value) : "—";
    const uuid = () => crypto.randomUUID();
    function create(adapter) {
        const base = `${adapter.apiBase}/risk`;
        const dialog = document.querySelector("#risk-detail-dialog"), body = document.querySelector("#risk-detail-body");
        const state = { rows: new Map(), batch: null, list: null, drawer: null, drawerGeneration: 0, detail: null, kind: null, id: null, context: null,
            trigger: null, timelineScope: "HISTORY", timelinePage: 0, salespersonChangesOnly: false, identity: { open: false, page: 0, changesOnly: false, data: null, controller: null }, view: null, queryKey: null, listPage: 0, sortBy: "lastSubmittedAt", sortDirection: "desc", busy: false, eventKeys: new Map(), drafts: {} };
        const read = async (url, signal) => {
            try { return adapter.unwrap(await adapter.requestJson(url, signal)); }
            catch (error) { if (error.status === 401) adapter.onUnauthorized?.(error.message); throw error; }
        };
        const date = value => {
            if (!value) return "未记录";
            const time = new Date(value); if (!Number.isFinite(time.getTime())) return "时间未确认";
            const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" }).formatToParts(time).map(p => [p.type, p.value]));
            return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
        };
        const filtered = () => { const source = adapter.getFilters(), params = new URLSearchParams(); for (const name of ["from", "to", "city", "salespersonId"]) if (source.get(name)) params.set(name, source.get(name)); return params; };
        const groupParams = () => { const p = filtered(), f = adapter.getFilters(); if (f.get("riskQuery")) p.set("q", f.get("riskQuery")); if (f.get("riskReviewStatus")) p.set("reviewStatus", f.get("riskReviewStatus"));
            if (state.view === "devices" && f.get("deviceRisk") === "SHARED") p.set("multiSalespersonOnly", "true");
            if (state.view === "audio-groups" && f.get("audioRisk")) p.set(f.get("audioRisk") === "CROSS_SALES" ? "crossSalespersonOnly" : "duplicateOnly", "true"); return p; };
        const detailParams = () => { const p = filtered(); p.set("page", state.timelinePage); p.set("size", "20"); p.set("timelineScope", state.timelineScope); if (state.kind === "DEVICE" && state.salespersonChangesOnly) p.set("salespersonChangesOnly", "true"); if (state.context) p.set("submissionId", state.context); return p; };
        const endpoint = kind => kind === "DEVICE" ? "devices" : "audios";
        const message = (container, value, retry) => { container.replaceChildren(node("p", "risk-state", value)); if (retry) container.append(button("重新读取", retry, "secondary-button")); };
        function renderRow(row, item) {
            const id = text(item.id || item.submissionId); state.rows.set(id, row);
            row.querySelector('[data-field="current-risk"]')?.replaceChildren();
            for (const field of ["device-risk", "device-people", "audio-risk"]) { const target = row.querySelector(`[data-field="${field}"]`); if (target) target.replaceChildren(node("span", "risk-muted", "读取关联…")); }
        }
        function groupLink(group, context) {
            const b = button(group.code || "查看关联", () => open(group.kind, group.id, context, b), "risk-code-button");
            b.dataset.riskGroupId = text(group.id); b.dataset.riskGroupKind = group.kind; b.dataset.riskSubmissionId = text(context);
            b.title = group.kind === "DEVICE" ? "查看浏览器标识档案" : "查看相同录音关联"; return b;
        }
        function paintRow(row, summary, context) {
            const device = row.querySelector('[data-field="device-risk"]'), people = row.querySelector('[data-field="device-people"]'), audio = row.querySelector('[data-field="audio-risk"]');
            device.replaceChildren(); people.replaceChildren(); audio.replaceChildren();
            const currentRisk = row.querySelector('[data-field="current-risk"]');
            if (currentRisk) {
                currentRisk.replaceChildren();
                const level = { HIGH: "高风险", MEDIUM: "中风险", LOW: "低风险" }[summary.riskLevel];
                if (level) currentRisk.append(node("span", `risk-current-level risk-current-level--${summary.riskLevel.toLowerCase()}`, `当前：${level}`));
                if (summary.reviewPending) currentRisk.append(node("span", "risk-muted", "含待复核线索"));
            }
            if (summary.device) { const g = summary.device; device.append(groupLink(g, context), node("span", "risk-muted", `可见历史 ${safeCount(g.historyCount)} 次`)); people.append(node("strong", "risk-people-count", `${safeCount(g.salespersonCount)} 人`)); if (g.salespersonCount > 1) people.append(node("span", "risk-inline-warning", "同标识多销售")); }
            else { device.append(node("span", "risk-muted", "未保留浏览器标识")); people.textContent = "—"; }
            const audios = Array.isArray(summary.audios) ? summary.audios : [];
            if (!audios.length) { audio.append(node("span", "risk-muted", "无录音关联")); return; }
            audios.forEach((g, index) => {
                const line = node("div", "risk-audio-association"); line.append(groupLink(g, context), node("span", "risk-muted", `相同文件 · ${safeCount(g.historyCount)} 次拜访 · ${safeCount(g.salespersonCount)} 人`));
                if (index < 2) audio.append(line); else { let more = audio.querySelector("details"); if (!more) { more = node("details", "risk-more-audio"); more.append(node("summary", "", `其余 ${audios.length - 2} 组录音`)); audio.append(more); } more.append(line); }
            });
        }
        async function loadRowSummaries(items, parentSignal) {
            state.batch?.abort(); const controller = new AbortController(); state.batch = controller;
            const abort = () => controller.abort(); parentSignal?.addEventListener("abort", abort, { once: true });
            const ids = items.map(item => text(item.id || item.submissionId)).filter(Boolean).slice(0, 100);
            state.rows.forEach((row, id) => { if (!row.isConnected || !ids.includes(id)) state.rows.delete(id); });
            if (!ids.length || parentSignal?.aborted) { controller.abort(); parentSignal?.removeEventListener("abort", abort); return; }
            const params = filtered(); params.set("submissionIds", ids.join(","));
            try { const result = await read(`${base}/summaries?${params}`, controller.signal); if (controller.signal.aborted || state.batch !== controller) return;
                const map = new Map((result.items || []).map(item => [text(item.submissionId), item]));
                ids.forEach(id => { const row = state.rows.get(id); if (!row?.isConnected) return; if (map.has(id)) paintRow(row, map.get(id), id); else row.querySelectorAll('[data-field="device-risk"],[data-field="device-people"],[data-field="audio-risk"]').forEach(n => n.replaceChildren(node("span", "risk-muted", "关联暂不可用"))); });
            } catch (error) { if (error.name === "AbortError") return; ids.forEach(id => { const row = state.rows.get(id); if (!row?.isConnected) return; row.querySelector('[data-field="device-risk"]').replaceChildren(button("重试关联", () => loadRowSummaries(items, parentSignal))); row.querySelector('[data-field="device-people"]').textContent = "—"; row.querySelector('[data-field="audio-risk"]').textContent = "关联读取失败"; }); }
            finally { parentSignal?.removeEventListener("abort", abort); }
        }
        async function loadView(view, keepPage = false) {
            state.view = view; const container = document.querySelector(view === "devices" ? "#risk-devices-content" : "#risk-audios-content");
            const params = groupParams(), key = `${view}:${params}`; if (!keepPage || state.queryKey !== key) state.listPage = 0; state.queryKey = key;
            state.list?.abort(); const controller = new AbortController(); state.list = controller;
            params.set("page", state.listPage); params.set("size", "20"); params.set("sortBy", state.sortBy); params.set("sortDirection", state.sortDirection);
            message(container, "正在读取关联档案…");
            try { const result = await read(`${base}/${view === "devices" ? "devices" : "audios"}?${params}`, controller.signal); if (controller.signal.aborted || state.list !== controller) return; renderList(container, result); }
            catch (error) { if (error.name !== "AbortError") message(container, error.message || "关联档案读取失败。", () => loadView(view, true)); }
        }
        function renderList(container, result) {
            const isDevice = state.view === "devices"; container.replaceChildren();
            const heading = node("div", "risk-list-heading"); const title = node("div"); title.append(node("h2", "", isDevice ? "设备档案" : "录音查重"), node("p", "risk-muted", `共 ${safeCount(result.totalElements)} 个${isDevice ? "浏览器标识" : "文件组"} · 仅已提交拜访 · 当前账号可见范围`));
            const sort = node("select"); sort.setAttribute("aria-label", "关联档案排序"); [["lastSubmittedAt", "最近关联时间"], ["historyCount", "可见历史关联数"], ["salespersonCount", "关联销售人数"]].forEach(([value, label]) => { const option = node("option", "", label); option.value = value; sort.append(option); }); sort.value = state.sortBy;
            sort.addEventListener("change", () => { state.sortBy = sort.value; void loadView(state.view); });
            const direction = button(state.sortDirection === "desc" ? "降序 ↓" : "升序 ↑", () => { state.sortDirection = state.sortDirection === "desc" ? "asc" : "desc"; void loadView(state.view); }, "secondary-button");
            const controls = node("div", "risk-list-sort"); controls.append(sort, direction); heading.append(title, controls); container.append(heading);
            container.append(node("p", "risk-scope-note", isDevice ? "按日期、城市、销售、设备关联、线索复核和编号查询。浏览器标识不是手机序列号，不能直接证明实际操作者。" : "按日期、城市、销售、录音关联、线索复核和编号查询。相同文件表示原文件字节一致，不证明录制者或违规。"));
            const wrap = node("div", "risk-group-table-wrap"); wrap.tabIndex = 0; wrap.setAttribute("role", "region"); wrap.setAttribute("aria-label", isDevice ? "设备档案列表，可横向滚动" : "录音查重列表，可横向滚动");
            const table = node("table", "risk-group-table"), head = node("thead"), tr = node("tr");
            [isDevice ? "浏览器标识" : "文件编号 / 已存时长", "日期/城市/销售筛选内", "可见历史", "关联销售", "最早关联打卡", "最近关联打卡", "线索复核"].forEach(label => { const th = node("th", "", label); th.scope = "col"; tr.append(th); }); head.append(tr); table.append(head); const tbody = node("tbody");
            (result.items || []).forEach(g => { const row = node("tr"), first = node("td"); first.append(groupLink(g, null)); if (!isDevice) first.append(node("small", "risk-muted", durationText(g))); row.append(first);
                [`${safeCount(g.filterCount)} 次拜访`, `${safeCount(g.historyCount)} 次拜访`, `${safeCount(g.salespersonCount)} 人`, date(g.firstSubmittedAt), date(g.lastSubmittedAt)].forEach(value => row.append(node("td", "", value)));
                const review = node("td"); review.append(status(g)); row.append(review); tbody.append(row); });
            table.append(tbody); wrap.append(table); container.append(wrap);
            if (!(result.items || []).length) container.append(node("p", "risk-state", "当前条件下没有关联档案。"));
            container.append(pager(result, page => { state.listPage = page; void loadView(state.view, true); }));
        }
        function pager(page, change) {
            const bar = node("div", "risk-pagination"), total = Number(page.totalPages) || 0, current = Number(page.page) || 0;
            bar.append(node("span", "", `第 ${total ? current + 1 : 0} / ${total} 页 · 共 ${safeCount(page.totalElements)} 条`));
            const controls = node("div"), previous = button("上一页", () => change(current - 1), "secondary-button"), next = button("下一页", () => change(current + 1), "secondary-button"); previous.disabled = current <= 0; next.disabled = current + 1 >= total; controls.append(previous, next); bar.append(controls); return bar;
        }
        function close() { state.identity.controller?.abort(); state.drawer?.abort(); state.drawerGeneration++; if (dialog.open) dialog.close(); adapter.onDialogChange(); const target = state.trigger?.isConnected ? state.trigger : [...document.querySelectorAll(".risk-code-button")].find(button =>
                button.dataset.riskGroupId === text(state.id) && button.dataset.riskGroupKind === state.kind && button.dataset.riskSubmissionId === text(state.context) && button.getClientRects().length);
            if (target) target.focus({ preventScroll: true }); }
        dialog.addEventListener("cancel", event => { event.preventDefault(); close(); });
        document.querySelector("#risk-detail-close").addEventListener("click", close);
        dialog.addEventListener("click", event => { if (event.target === dialog) close(); });
        dialog.addEventListener("close", () => adapter.onDialogChange());
        async function open(kind, id, context, trigger) {
            if (!id || !["DEVICE", "AUDIO"].includes(kind)) return;
            state.kind = kind; state.id = id; state.context = context || null; state.trigger = trigger; state.timelineScope = "HISTORY"; state.timelinePage = 0; state.salespersonChangesOnly = false; state.identity.controller?.abort(); state.identity = { open: false, page: 0, changesOnly: false, data: null, controller: null }; state.detail = null; state.drafts = {}; state.eventKeys.clear(); body.replaceChildren();
            document.querySelector("#risk-detail-kicker").textContent = kind === "DEVICE" ? "设备档案 · 浏览器标识" : "录音查重 · 原文件关联";
            document.querySelector("#risk-detail-title").textContent = "正在读取…";
            if (!dialog.open) dialog.showModal(); adapter.onDialogChange(); await loadDetail();
        }
        function captureForms() { body.querySelectorAll("form[data-risk-form]").forEach(form => { state.drafts[form.dataset.riskForm] = Object.fromEntries(new FormData(form)); if (form.dataset.riskForm === "assignment") state.drafts.assignment._open = form.closest("details")?.open; }); }
        async function loadDetail(notice = "") {
            captureForms(); state.identity.controller?.abort(); state.drawer?.abort(); const controller = new AbortController(); state.drawer = controller; const generation = ++state.drawerGeneration;
            message(body, "正在读取授权范围内的关联记录…");
            try { const result = await read(`${base}/${endpoint(state.kind)}/${encodeURIComponent(state.id)}?${detailParams()}`, controller.signal); if (controller.signal.aborted || generation !== state.drawerGeneration || !dialog.open) return;
                state.detail = result; renderDetail(result, notice);
            } catch (error) { if (error.name !== "AbortError") message(body, error.message || "关联详情读取失败。", () => loadDetail()); }
        }
        function section(title) { const s = node("section", "risk-section"); s.append(node("h3", "", title)); return s; }
        function fact(label, value) { const n = node("div", label === "已保存时长" ? "risk-duration-fact" : ""); n.append(node("dt", "", label), node("dd", "", value)); return n; }
        function renderDetail(detail, notice) {
            const g = detail.summary || {}, isDevice = state.kind === "DEVICE"; body.replaceChildren(); document.querySelector("#risk-detail-title").textContent = g.code || "关联详情";
            const top = node("div", "risk-detail-intro"); top.append(status(g), node("p", "", isDevice ? "编号由浏览器标识生成，不是手机序列号。关联销售表示记录归属，不能证明实际操作者。" : "SHA-256 一致表示原文件内容一致。最早关联打卡不代表最早上传，也不证明录制者或声音属于谁。")); body.append(top);
            if (notice) body.append(node("p", "risk-operation-notice", notice));
            const metrics = node("dl", "risk-detail-metrics"); metrics.append(fact("日期/城市/销售筛选内", `${safeCount(g.filterCount)} 次拜访`), fact("权限内可见历史", `${safeCount(g.historyCount)} 次拜访`), fact("历史关联销售", `${safeCount(g.salespersonCount)} 人`), fact("历史关联门店 / 日期", `${safeCount(g.storeCount)} 家 / ${safeCount(g.dateCount)} 天`)); body.append(metrics);
            if (!isDevice && state.context) { const context = node("p", "risk-context-note", `比较拜访：${state.context}。其他 ${safeCount(g.otherCount)} 次拜访；更早关联打卡 ${safeCount(g.earlierCount)} 次。总数包含本次关联。`); body.append(context); }
            const evidence = section("可见证据"); const facts = node("dl", "risk-evidence-facts"); facts.append(fact("最早保留的关联打卡", `${date(g.firstSubmittedAt)} · ${g.firstSalespersonName || "未记录销售"}`));
            if (!isDevice) { facts.append(fact("已保存时长", durationText(g)), fact("原文件大小", detail.sizeBytes != null ? `${Number(detail.sizeBytes).toLocaleString("zh-CN")} 字节` : "未记录"), fact("SHA-256", detail.sha256 || "未返回完整摘要")); if (detail.firstReceivedAt) facts.append(fact("服务器首次收到（新事件）", date(detail.firstReceivedAt))); }
            else if (detail.browserSummaries?.length) facts.append(fact("浏览器自报信息", detail.browserSummaries.join("；")));
            evidence.append(facts); body.append(evidence);
            if (isDevice) body.append(identityDisclosure());
            const people = section("关联销售"); (detail.salespeople || []).forEach(p => { const row = node("div", "risk-person-row"); row.append(node("strong", "", p.salespersonName || "未记录姓名"), node("span", "", `${safeCount(p.count)} 次 · ${date(p.firstSubmittedAt)} — ${date(p.lastSubmittedAt)}`)); people.append(row); }); body.append(people);
            if (isDevice) {
                const ownership = node("details", "risk-ownership-disclosure");
                const latest = detail.assignments?.[0];
                ownership.append(node("summary", "", `管理员指定归属 · ${latest ? assignments[latest.assignmentType] || "已有说明" : "归属未确认"}`));
                ownership.open = state.drafts.assignment?._open === true; ownership.append(assignmentForm(detail)); body.append(ownership);
            }
            const timeline = section("打卡流水"); const switches = node("div", "risk-timeline-switch"); [["HISTORY", "可见历史"], ["FILTERED", "日期/城市/销售筛选内"]].forEach(([scope, label]) => { const b = button(label, () => { state.timelineScope = scope; state.timelinePage = 0; void loadDetail(); }, "secondary-button"); b.setAttribute("aria-pressed", String(scope === state.timelineScope)); switches.append(b); }); timeline.append(switches);
            if (isDevice) {
                const mode = node("div", "risk-account-timeline-mode");
                mode.setAttribute("role", "group"); mode.setAttribute("aria-label", "打卡账号变化筛选");
                [[false, "全部拜访"], [true, "仅看账号变化"]].forEach(([changesOnly, label]) => {
                    const control = button(label, () => { state.salespersonChangesOnly = changesOnly; state.timelinePage = 0; void loadDetail(); }, "secondary-button");
                    control.setAttribute("aria-pressed", String(state.salespersonChangesOnly === changesOnly)); mode.append(control);
                });
                timeline.append(mode);
                if (state.salespersonChangesOnly) timeline.append(node("p", "risk-muted", "按服务端保留的拜访顺序比较账号变化；不代表手机或真实操作者变化。可切回全部拜访查看前后记录。"));
            }
            const visits = detail.visits || { items: [], page: 0, totalPages: 0, totalElements: 0 }; const flow = node("ol", "risk-timeline");
            (visits.items || []).forEach(visit => { const li = node("li"); li.append(node("p", "risk-visit-time", `${date(visit.submittedAt)}${visit.matchesFilter ? " · 筛选内" : ""}`), node("strong", "", `${visit.salespersonName || "未记录销售"} · ${visit.city || "未记录城市"} · ${visit.storeName || "未记录门店"}`), node("p", "risk-muted", visit.locationAddress ? `设备报告地址：${visit.locationAddress}` : "未保存设备报告地址，可到打卡详情查看坐标"));
                if (visit.deviceCode) li.append(node("p", "risk-muted", `浏览器标识 ${visit.deviceCode}`));
                (visit.audios || []).forEach(a => li.append(node("p", "risk-muted", `${a.code || "录音"} · ${a.originalFilename || "未记录文件名"} · ${durationText(a)}`)));
                const b = button("查看对应打卡", async () => { b.disabled = true; try { await adapter.openSubmission(text(visit.submissionId), b); } catch (error) { let errorNode = li.querySelector(".risk-error"); if (!errorNode) { errorNode = node("p", "risk-error"); li.append(errorNode); } errorNode.textContent = error.message || "打卡详情读取失败。"; } finally { b.disabled = false; } }); li.append(b); flow.append(li); });
            timeline.append(flow); if (!(visits.items || []).length) timeline.append(node("p", "risk-muted", state.salespersonChangesOnly ? "此范围暂无账号变化记录；不代表未发生过切换。" : "此范围暂无打卡记录。")); timeline.append(pager(visits, page => { state.timelinePage = page; void loadDetail(); })); body.append(timeline);
            if (isDevice) { body.append(eventHistory("归属操作历史", detail.assignments || [], detail.assignmentsTotal, "assignments")); }
            body.append(reviewForm(detail)); body.append(eventHistory("线索复核历史", detail.reviews || [], detail.reviewsTotal, "reviews"));
        }
        function identityDisclosure() {
            const disclosure = node("details", "risk-ownership-disclosure risk-identity-disclosure");
            disclosure.append(node("summary", "", "新版本身份验证记录"));
            const content = node("div", "risk-identity-content");
            disclosure.append(content);
            const kind = state.kind, groupId = state.id, generation = state.drawerGeneration;
            const current = () => state.kind === kind && state.id === groupId && state.drawerGeneration === generation && dialog.open && disclosure.isConnected;
            function render(data) {
                content.replaceChildren(node("p", "risk-muted", "仅表示服务器成功验证过该浏览器中的销售账号，不代表手机归属或真实操作者。按管理权限范围读取，不套用拜访日期筛选。"));
                const modes = node("div", "risk-account-timeline-mode");
                modes.setAttribute("role", "group"); modes.setAttribute("aria-label", "身份验证事件筛选");
                [[false, "全部验证记录"], [true, "仅看账号变化"]].forEach(([changesOnly, label]) => {
                    const control = button(label, () => { state.identity.changesOnly = changesOnly; state.identity.data = null; void load(0); }, "secondary-button");
                    control.setAttribute("aria-pressed", String(state.identity.changesOnly === changesOnly)); modes.append(control);
                });
                modes.append(button("刷新验证记录", () => { state.identity.data = null; void load(0); }));
                content.append(modes);
                if (!data) { content.append(node("p", "risk-muted", "展开后读取新版本已采集事件。")); return; }
                content.append(node("p", "risk-identity-coverage", `权限内已采集 ${safeCount(data.availableEventCount)} 条成功验证记录${data.firstAvailableEventAt ? ` · 最早保留事件 ${date(data.firstAvailableEventAt)}` : ""}。记录可能不完整；不是功能启用或手机首次使用时间。`));
                const events = node("ol", "risk-event-list risk-identity-events");
                data.items.forEach(event => {
                    const entry = node("li");
                    entry.append(node("strong", "", `${event.salespersonName || "未记录销售"}${event.city ? ` · ${event.city}` : ""}`), node("small", "risk-muted", `${date(event.occurredAt)} · ${event.eventType === "VISIBLE_ACCOUNT_CHANGED" ? "与上一条可见验证账号不同" : "身份验证成功"}`));
                    if (event.eventType === "VISIBLE_ACCOUNT_CHANGED" && event.previousSalespersonId) entry.append(node("p", "risk-muted", `上一条可见验证：${event.previousSalespersonName || "未记录销售"}${event.previousOccurredAt ? ` · ${date(event.previousOccurredAt)}` : ""}。不表示两条事件之间没有其他操作。`));
                    events.append(entry);
                });
                content.append(events);
                if (!data.items.length) content.append(node("p", "risk-identity-empty", Number(data.availableEventCount) === 0
                    ? "暂无已采集的成功验证记录；旧版未采集，异步记录也可能缺失，不能推断未切换。"
                    : "当前范围未找到账号变化事件；已有记录可能不完整，不能推断未切换。"));
                content.append(pager(data, page => { void load(page); }));
            }
            async function load(page) {
                state.identity.controller?.abort(); const controller = new AbortController(); state.identity.controller = controller;
                message(content, "正在读取已采集的身份验证记录…");
                const params = new URLSearchParams({ page: String(page), size: "20", salespersonChangesOnly: String(state.identity.changesOnly) });
                try {
                    const data = await read(`${base}/devices/${encodeURIComponent(groupId)}/identity-events?${params}`, controller.signal);
                    if (!current() || !disclosure.open || controller.signal.aborted || state.identity.controller !== controller) return;
                    if (!Array.isArray(data.items) || !Number.isFinite(Number(data.totalElements)) || data.totalElements == null) throw new Error("身份验证事件响应不完整，请重新读取。");
                    state.identity.data = data; state.identity.page = page; render(data);
                } catch (error) {
                    if (error.name !== "AbortError" && current() && disclosure.open) message(content, error.message || "身份验证事件读取失败。", () => load(page));
                }
            }
            disclosure.open = state.identity.open;
            render(state.identity.data);
            disclosure.addEventListener("toggle", () => {
                if (!current()) return;
                state.identity.open = disclosure.open;
                if (!disclosure.open) { state.identity.controller?.abort(); return; }
                if (!state.identity.data) void load(state.identity.page);
            });
            return disclosure;
        }
        function selectField(label, name, options, value) { const wrapper = node("label", "field"); wrapper.append(node("span", "", label)); const select = node("select"); select.name = name; options.forEach(([key, text]) => { const option = node("option", "", text); option.value = key; select.append(option); }); if (value != null) select.value = value; wrapper.append(select); return { wrapper, input: select }; }
        function noteField(form, draft) { const label = node("label", "field risk-form-wide"); label.append(node("span", "", "备注（必填）")); const input = node("textarea"); input.name = "note"; input.required = true; input.maxLength = 1000; input.rows = 3; input.value = draft.note || ""; label.append(input); form.append(label); }
        function assignmentForm(detail) {
            const sectionNode = section("管理员指定归属"), form = node("form", "risk-action-form"); form.dataset.riskForm = "assignment"; const draft = state.drafts.assignment || {};
            sectionNode.append(node("p", "risk-muted", "归属是管理员的说明，不改变历史记录，也不把最早关联账号认定为机主。"));
            const latestAssignment = detail.assignments?.[0];
            sectionNode.append(node("p", "risk-assignment-current", latestAssignment
                ? `最近一次说明：${assignments[latestAssignment.assignmentType] || latestAssignment.assignmentType}${latestAssignment.salespersonName ? ` · ${latestAssignment.salespersonName}` : ""} · ${date(latestAssignment.assignedAt)}${latestAssignment.validFrom || latestAssignment.validTo ? `（适用日期 ${latestAssignment.validFrom || "未限定"} 至 ${latestAssignment.validTo || "未限定"}）` : ""}`
                : "归属未确认 · 尚无管理员归属说明"));
            const type = selectField("归属类型", "assignmentType", Object.entries(assignments), draft.assignmentType || "UNCONFIRMED");
            const persons = new Map(); (adapter.getSalespersons() || []).forEach(p => persons.set(text(p.id || p.salespersonId), p.name || p.salespersonName));
            const person = selectField("指定销售", "salespersonId", [["", "请选择销售"], ...[...persons].filter(([id]) => id)], draft.salespersonId || "");
            const update = () => { person.input.disabled = type.input.value !== "PERSONAL"; person.input.required = type.input.value === "PERSONAL"; }; type.input.addEventListener("change", update); update(); form.append(type.wrapper, person.wrapper);
            for (const [name, label] of [["validFrom", "适用开始日期（选填）"], ["validTo", "适用结束日期（选填）"]]) { const wrapper = node("label", "field"); wrapper.append(node("span", "", label)); const input = node("input"); input.type = "date"; input.name = name; input.value = draft[name] || ""; wrapper.append(input); form.append(wrapper); }
            noteField(form, draft); attachWrite(form, "assignment", `${base}/devices/${encodeURIComponent(state.id)}/assignments`, data => ({ assignmentType: data.assignmentType, salespersonId: data.assignmentType === "PERSONAL" ? data.salespersonId : null, validFrom: data.validFrom || null, validTo: data.validTo || null, note: data.note }), "保存归属说明"); sectionNode.append(form); return sectionNode;
        }
        function reviewForm(detail) {
            const sectionNode = section("关联线索复核"), form = node("form", "risk-action-form"); form.dataset.riskForm = "review"; const draft = state.drafts.review || {};
            sectionNode.append(node("p", "risk-muted", "仅处理此关联线索，保留原拜访复核结论。新增关联会重新进入待复核，旧说明仍可追溯。"));
            const field = selectField("本次线索结论", "status", [["", "请选择结论"], ...["EXPLAINED", "FLAGGED", "INCONCLUSIVE"].map(key => [key, labels[key]])], draft.status || ""); field.input.required = true; form.append(field.wrapper); noteField(form, draft);
            attachWrite(form, "review", `${base}/groups/${state.kind}/${encodeURIComponent(state.id)}/reviews`, data => ({ evidenceVersion: detail.summary.evidenceVersion, status: data.status, note: data.note }), "保存线索复核"); sectionNode.append(form); return sectionNode;
        }
        function attachWrite(form, key, url, payload, label) {
            const submit = node("button", "primary-button", label); submit.type = "submit"; const feedback = node("p", "risk-form-feedback"); feedback.setAttribute("role", "status"); form.append(submit, feedback);
            form.addEventListener("submit", async event => { event.preventDefault(); if (state.busy || !form.reportValidity()) return; const data = Object.fromEntries(new FormData(form)); data.note = text(data.note); if (!data.note) { feedback.textContent = "请填写本次操作的具体说明。"; return; }
                if (data.validFrom && data.validTo && data.validFrom > data.validTo) { feedback.textContent = "结束日期不能早于开始日期。"; return; }
                const bodyPayload = payload(data), fingerprint = `${url}:${JSON.stringify(bodyPayload)}`; let clientEventId = state.eventKeys.get(fingerprint); if (!clientEventId) { clientEventId = uuid(); state.eventKeys.set(fingerprint, clientEventId); }
                const groupId = state.id, kind = state.kind; state.busy = true; form.inert = true; submit.disabled = true; feedback.textContent = "正在保存…";
                try { await adapter.requestAction(url, { method: "POST", body: { ...bodyPayload, clientEventId } }); if (state.id !== groupId || state.kind !== kind || !dialog.open) return; form.reset(); state.drafts[key] = {}; await loadDetail("已保存，操作时间与管理员身份已留痕。"); void adapter.onMutation(); }
                catch (error) { if (error.status === 401) adapter.onUnauthorized?.(error.message); if (state.id !== groupId || state.kind !== kind || !dialog.open) return; if (error.status === 409) await loadDetail("关联证据已变化，请查看更新后的流水再确认。已保留本次备注，尚未写入新结论。"); else feedback.textContent = `${error.message || "保存结果暂未确认。"} 请手动重试；相同内容会使用同一操作编号核对。`; }
                finally { state.busy = false; form.inert = false; submit.disabled = false; }
            });
        }
        function eventHistory(title, initial, total, type) {
            const sectionNode = section(`${title} · ${safeCount(total)} 条`), list = node("ol", "risk-event-list"); const kind = state.kind, id = state.id, generation = state.drawerGeneration;
            function append(events) { events.forEach(e => { const li = node("li"); li.append(node("strong", "", type === "assignments" ? `${assignments[e.assignmentType] || e.assignmentType}${e.salespersonName ? ` · ${e.salespersonName}` : ""}` : labels[e.status] || e.status), node("p", "", e.note || "未记录备注"), node("small", "risk-muted", `${date(e.assignedAt || e.reviewedAt)} · ${e.actor || "未记录管理员"}${e.scopeCity ? ` · ${e.scopeCity}` : ""}`));
                if (e.validFrom || e.validTo) li.append(node("small", "risk-muted", `适用日期：${e.validFrom || "未限定"} 至 ${e.validTo || "未限定"}`)); list.append(li); }); }
            append(initial); sectionNode.append(list); if (!initial.length) sectionNode.append(node("p", "risk-muted", "暂无操作记录。"));
            if (Number(total) > initial.length) { let page = 0; const more = button("读取完整操作历史", async () => { more.disabled = true; try { const path = type === "assignments" ? `devices/${encodeURIComponent(id)}/assignments` : `groups/${kind}/${encodeURIComponent(id)}/reviews`; const result = await read(`${base}/${path}?page=${page}&size=50`); if (generation !== state.drawerGeneration || !dialog.open) return; if (page === 0) list.replaceChildren(); append(result.items || []); page++; more.hidden = page >= result.totalPages; more.textContent = "读取更多历史"; } catch (error) { more.textContent = "读取失败，点击重试"; } finally { more.disabled = false; } }, "secondary-button"); sectionNode.append(more); } return sectionNode;
        }
        function reset() { state.batch?.abort(); state.list?.abort(); state.drawer?.abort(); close(); state.rows.clear(); state.detail = null; state.identity = { open: false, page: 0, changesOnly: false, data: null, controller: null }; state.id = null; state.context = null; state.drafts = {}; state.eventKeys.clear(); body.replaceChildren(); document.querySelector("#risk-devices-content").replaceChildren(); document.querySelector("#risk-audios-content").replaceChildren(); }
        return { renderRow, loadRowSummaries, loadView, reset };
    }
    window.CheckinRiskAdmin = Object.freeze({ create });
})();
