(function () {
    "use strict";

    const API_BASE = "/sales-checkin/admin/api/v1";
    const EXPORT_PATH = "/sales-checkin/admin/export.csv";
    const MEDIA_PATH = "/sales-checkin/admin/submissions";
    const PAGE_SIZE = 20;

    const state = {
        scope: { username: "", allCities: false, city: "" },
        cities: [],
        salespersons: [],
        mediaStats: null,
        filters: { from: "", to: "", city: "", salespersonId: "", status: "" },
        page: 0,
        total: 0,
        totalPages: 1,
        loading: false,
        controller: null,
        previewTrigger: null,
        previewIdentity: null,
        pendingDelete: null,
        actionBusy: false,
        successTimer: null
    };

    const $ = (selector, root = document) => root.querySelector(selector);

    document.addEventListener("DOMContentLoaded", init);

    async function init() {
        bindEvents();
        readFiltersFromUrl();
        writeFiltersToForm();
        try {
            const options = await requestJson(`${API_BASE}/options`);
            applyOptions(unwrap(options));
            await loadSubmissions();
        } catch (error) {
            showError(errorMessage(error, "后台数据加载失败，请确认管理账号权限后重试。"));
            renderLoading(false);
        }
    }

    function bindEvents() {
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
            renderSalespersonOptions($("#filter-city").value, currentSalesperson);
        });
        $("#reset-button").addEventListener("click", resetFilters);
        $("#retry-button").addEventListener("click", loadSubmissions);
        $("#success-close-button").addEventListener("click", hideSuccess);
        $("#previous-page").addEventListener("click", () => changePage(state.page - 1));
        $("#next-page").addEventListener("click", () => changePage(state.page + 1));
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
        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape" && $("#image-preview-dialog").hasAttribute("open")) {
                closeImagePreview();
            }
        });
    }

    function applyOptions(payload) {
        const scope = payload && typeof payload.scope === "object" ? payload.scope : {};
        state.scope = {
            username: cleanText(scope.username || payload.username || "未知账号"),
            allCities: scope.allCities === true,
            city: cleanText(scope.city || "")
        };
        state.cities = uniqueStrings(Array.isArray(payload.cities) ? payload.cities : []);
        state.salespersons = Array.isArray(payload.salespersons) ? payload.salespersons : [];
        state.mediaStats = payload.mediaStats && typeof payload.mediaStats === "object"
            ? payload.mediaStats : null;

        if (!state.scope.allCities && state.scope.city) {
            state.filters.city = state.scope.city;
        }
        renderScope();
        renderMediaStats();
        renderCityOptions();
        renderSalespersonOptions(state.filters.city, state.filters.salespersonId);
        writeFiltersToForm();
        updateExportLink();
    }

    function renderScope() {
        $("#scope-username").textContent = state.scope.username || "未知账号";
        $("#scope-range").textContent = state.scope.allCities
            ? "全部城市"
            : (state.scope.city ? `${state.scope.city}（仅本城市）` : "未配置城市范围");
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

    function renderSalespersonOptions(city, selectedId) {
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
        } else {
            select.value = "";
            if (selectedId) state.filters.salespersonId = "";
        }
    }

    async function loadSubmissions() {
        if (state.controller) state.controller.abort();
        state.controller = new AbortController();
        hideError();
        renderLoading(true);
        try {
            const params = buildFilterParams();
            params.set("page", String(state.page));
            params.set("size", String(PAGE_SIZE));
            const response = await requestJson(`${API_BASE}/submissions?${params.toString()}`, state.controller.signal);
            const payload = unwrap(response);
            applyResponseScope(payload.scope);
            const items = Array.isArray(payload.items) ? payload.items : [];
            state.total = numberValue(payload.totalElements, payload.total, items.length);
            state.totalPages = Math.max(1, numberValue(payload.totalPages, Math.ceil(state.total / PAGE_SIZE)));
            state.page = Math.max(0, numberValue(payload.page, state.page));
            renderRows(items);
            renderResultSummary(items.length);
            renderPagination();
            $("#result-total").textContent = formatCount(state.total);
            renderLoading(false, items.length === 0);
        } catch (error) {
            if (error.name === "AbortError") return;
            renderLoading(false);
            showError(errorMessage(error, "读取拜访记录失败，请稍后重试。"));
        }
    }

    function applyResponseScope(scope) {
        if (!scope || typeof scope !== "object") return;
        state.scope = {
            username: cleanText(scope.username || state.scope.username),
            allCities: scope.allCities === true,
            city: cleanText(scope.city || state.scope.city)
        };
        renderScope();
        renderMediaStats();
    }

    function renderRows(items) {
        const root = $("#submission-rows");
        const template = $("#submission-row-template");
        root.querySelectorAll("audio.media-audio").forEach((audio) => {
            audio.pause();
            audio.removeAttribute("src");
            audio.load();
        });
        root.replaceChildren();
        items.forEach((item) => {
            const row = template.content.firstElementChild.cloneNode(true);
            const field = (name) => row.querySelector(`[data-field="${name}"]`);
            const submittedAt = item.submittedAt || item.createdAt;
            field("time").textContent = formatDateTime(submittedAt);
            field("captured-time").textContent = item.locationCapturedAt
                ? `定位采集：${formatDateTime(item.locationCapturedAt)}` : "定位采集时间未记录";

            const status = cleanText(item.status).toUpperCase();
            field("status").textContent = status === "SUBMITTED" ? "已提交" : (status === "DRAFT" ? "草稿" : status || "未知状态");
            field("status").classList.toggle("is-draft", status !== "SUBMITTED");
            renderVisitFrequency(row, item);

            field("city").textContent = cleanText(item.city) || "未记录城市";
            field("salesperson").textContent = cleanText(item.salespersonName) || "未记录销售";
            field("store").textContent = cleanText(item.storeName) || "未记录门店";
            field("customer").textContent = `客户：${cleanText(item.customerName) || "未记录"}`;
            renderPhone(field("phone"), item.customerPhone);
            field("result").textContent = cleanText(item.visitResult) || "未填写拜访结果";
            field("result").title = cleanText(item.visitResult);
            renderLocation(row, item);
            renderMedia(field("media"), item);
            root.appendChild(row);
        });
    }

    function renderVisitFrequency(row, item) {
        const badge = row.querySelector(".visit-frequency");
        const note = row.querySelector(".visit-frequency-note");
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

        badge.hidden = !label;
        note.hidden = !label;
        badge.textContent = label;
        badge.classList.toggle("is-first", isFirst);
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

    function renderLocation(row, item) {
        const address = row.querySelector('[data-field="address"]');
        const readableAddress = cleanText(item.locationAddress || item.formattedAddress || item.readableAddress
            || (item.location && item.location.address));
        address.textContent = readableAddress || "地址暂未解析";
        address.classList.toggle("is-missing", !readableAddress);

        const note = cleanText(item.locationNote);
        row.querySelector('[data-field="location-note"]').textContent = note ? `备注：${note}` : "";

        const longitude = decimalText(item.longitude);
        const latitude = decimalText(item.latitude);
        row.querySelector('[data-field="coordinates"]').textContent = longitude && latitude
            ? `${longitude}, ${latitude}` : "经纬度未记录";
        const accuracy = decimalText(item.accuracyMeters);
        row.querySelector('[data-field="accuracy"]').textContent = accuracy
            ? `精度：±${accuracy} 米` : "精度未记录";
    }

    function renderMedia(root, item) {
        root.replaceChildren();
        const id = cleanText(item.id || item.submissionId);
        if (!isUuid(id)) return renderEmptyMedia(root);
        if (item.storefrontPhotoAvailable === true) {
            root.appendChild(createImageMedia(id, "storefront-photo", "门店打卡照"));
        } else if (item.storefrontPhotoDeletedAt) {
            root.appendChild(createDeletedMediaCard("storefront-photo", "门店打卡照", item.storefrontPhotoDeletedAt));
        }
        if (item.wechatScreenshotAvailable === true) {
            root.appendChild(createImageMedia(id, "wechat-screenshot", "企微截图"));
        } else if (item.wechatScreenshotDeletedAt) {
            root.appendChild(createDeletedMediaCard("wechat-screenshot", "企微截图", item.wechatScreenshotDeletedAt));
        }
        if (item.audioAvailable === true || item.audioDeletedAt || hasAudioIntelligence(item)) {
            root.appendChild(createAudioMedia(id, item));
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
        image.addEventListener("error", () => {
            if (image.dataset.fallback !== "original") {
                image.dataset.fallback = "original";
                image.src = previewUrl;
                return;
            }
            button.classList.add("is-unavailable");
            image.hidden = true;
            const fallback = document.createElement("span");
            fallback.className = "media-thumbnail-fallback";
            fallback.textContent = "点击查看原图";
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

    function createAudioMedia(id, item) {
        const card = document.createElement("section");
        card.className = "media-card media-card--audio";
        card.dataset.mediaId = id;
        card.dataset.mediaKind = "audio";
        const title = document.createElement("strong");
        title.className = "media-title";
        title.textContent = "拜访录音";

        card.appendChild(title);
        if (item.audioAvailable === true) {
            const audio = document.createElement("audio");
            audio.className = "media-audio";
            audio.controls = true;
            audio.preload = "none";
            audio.src = mediaUrl(id, "audio");
            audio.setAttribute("controlslist", "nodownload");
            audio.setAttribute("aria-label", "播放拜访录音");
            audio.addEventListener("play", () => {
                document.querySelectorAll("audio.media-audio").forEach((other) => {
                    if (other !== audio) other.pause();
                });
            });

            const hint = document.createElement("span");
            hint.className = "media-audio-hint";
            hint.textContent = "点击播放，可拖动进度";
            card.append(audio, hint, createMediaActions(
                id, "audio", "拜访录音", mediaUrl(id, "audio", { download: true })));
        } else if (item.audioDeletedAt) {
            card.appendChild(createDeletedMediaNotice("拜访录音", item.audioDeletedAt));
        }

        card.appendChild(createAudioIntelligence(id, item));
        return card;
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

    function createMediaActions(id, kind, label, downloadUrl) {
        const actions = document.createElement("div");
        actions.className = "media-actions";
        actions.appendChild(createDownloadLink(downloadUrl, `下载${label}`));
        if (state.scope.allCities) {
            const remove = document.createElement("button");
            remove.className = "media-delete";
            remove.type = "button";
            remove.textContent = "删除";
            remove.addEventListener("click", () => openDeleteDialog(id, kind, label, remove));
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
        title.textContent = "录音转写与摘要";
        const transcription = statusBadge("transcription", item.transcriptionStatus);
        heading.append(title, transcription);
        panel.appendChild(heading);

        const transcript = cleanText(item.transcript);
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

        const summary = cleanText(item.summary);
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
                const codeText = document.createElement("code");
                codeText.textContent = `失败代码：${code}`;
                failure.appendChild(codeText);
            }
            if (item.audioAvailable === true) {
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
        const base = `${MEDIA_PATH}/${encodeURIComponent(id)}/media/${kind}`;
        if (options.thumbnail) return `${base}/thumbnail`;
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
        document.body.classList.add("preview-open");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
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
        document.body.classList.remove("preview-open");
        if (state.previewTrigger && document.contains(state.previewTrigger)) state.previewTrigger.focus();
        state.previewTrigger = null;
        state.previewIdentity = null;
    }

    function openDeleteDialog(id, kind, label, trigger) {
        if (!state.scope.allCities || state.actionBusy || !isUuid(id)) return;
        state.pendingDelete = { id, kind, label, trigger };
        $("#delete-media-description").textContent = `即将删除“${label}”。请再次核对当前拜访记录。`;
        $("#delete-media-reason").value = "";
        $("#delete-media-acknowledge").checked = false;
        $("#delete-media-error").hidden = true;
        $("#delete-media-error").textContent = "";
        updateDeleteConfirmation();
        const dialog = $("#delete-media-dialog");
        if (typeof dialog.showModal === "function") dialog.showModal();
        else dialog.setAttribute("open", "");
        document.body.classList.add("preview-open");
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
        document.body.classList.remove("preview-open");
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
            await requestAction(
                `${API_BASE}/submissions/${encodeURIComponent(pending.id)}/media/${pending.kind}`,
                { method: "DELETE", body: { reason } });
            deleted = true;
            stopDeletedMedia(pending.id, pending.kind);
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

    function stopDeletedMedia(id, kind) {
        document.querySelectorAll(`[data-media-id="${id}"][data-media-kind="${kind}"] audio`).forEach((audio) => {
            audio.pause();
            audio.removeAttribute("src");
            audio.load();
        });
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
        $("#page-indicator").textContent = `第 ${state.page + 1} / ${state.totalPages} 页`;
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
            from: "",
            to: "",
            city: state.scope.allCities ? "" : state.scope.city,
            salespersonId: "",
            status: ""
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
            from,
            to,
            city: state.scope.allCities ? $("#filter-city").value : state.scope.city,
            salespersonId: $("#filter-salesperson").value,
            status: $("#filter-status").value
        };
        return true;
    }

    function readFiltersFromUrl() {
        const params = new URLSearchParams(window.location.search);
        state.filters = {
            from: safeDate(params.get("from")),
            to: safeDate(params.get("to")),
            city: cleanText(params.get("city")),
            salespersonId: cleanText(params.get("salespersonId")),
            status: ["DRAFT", "SUBMITTED"].includes(params.get("status")) ? params.get("status") : ""
        };
        const page = Number.parseInt(params.get("page"), 10);
        state.page = Number.isInteger(page) && page > 0 ? page - 1 : 0;
    }

    function writeFiltersToForm() {
        $("#filter-from").value = state.filters.from;
        $("#filter-to").value = state.filters.to;
        $("#filter-status").value = state.filters.status;
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
            if (response.status === 401) throw new Error("管理账号未授权或凭据已失效，请重新打开管理页登录。");
            if (response.status === 403) throw new Error("当前账号无权查看该城市数据。");
            throw new Error(cleanText(payload && payload.message) || `请求失败（HTTP ${response.status}）`);
        }
        return payload || {};
    }

    async function requestAction(url, options = {}) {
        const headers = { Accept: "application/json" };
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
            if (response.status === 401) throw new Error("管理账号未授权或凭据已失效，请重新打开管理页登录。");
            if (response.status === 403) throw new Error("仅总管理员可执行此操作。");
            if (response.status === 404) throw new Error("记录或媒体不存在，可能已被处理。");
            if (response.status === 409) throw new Error(cleanText(payload && payload.message)
                || "当前状态不允许此操作，请刷新后重试。");
            throw new Error(cleanText(payload && payload.message) || `请求失败（HTTP ${response.status}）`);
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
