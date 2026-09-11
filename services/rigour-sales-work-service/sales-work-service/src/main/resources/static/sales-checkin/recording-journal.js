(function (root) {
    "use strict";

    // 与照片/草稿共用有容量限制、有事务超时的 IndexedDB 仓库。每个媒体块只写一次；
    // 不把 timeslice 块作为独立音频，只有 stop 事件后的完整块集合才标记完成。
    const PREFIX = "recording:";
    const MAX_CHUNKS = 30000;
    const DEFAULT_MAX_BYTES = 256 * 1024 * 1024;
    const key = (id) => `${PREFIX}${id}:`;
    const validId = (value) => typeof value === "string" && /^[a-zA-Z0-9-]{1,80}$/.test(value);
    function storage() {
        const value = root.SalesCheckinDraftStore;
        if (!value?.saveMedia || !value?.mediaFor || !value?.removeMedia) throw new Error("本机录音分块保存不可用");
        return value;
    }
    function create(owner, draftId, sessionId, metadata = {}) {
        if (!owner || !draftId || !validId(sessionId)) throw new Error("录音保存身份无效");
        const store = storage();
        const maximum = Number.isFinite(metadata.maxBytes) && metadata.maxBytes > 0
            ? Math.min(metadata.maxBytes, DEFAULT_MAX_BYTES) : DEFAULT_MAX_BYTES;
        let sequence = 0;
        let bytes = 0;
        let closed = false;
        let tail = Promise.resolve();
        const manifest = {version: 1, sessionId, draftId, owner, status: "OPEN", chunkCount: 0,
            sizeBytes: 0, mimeType: metadata.mimeType || "", clientStartedAt: metadata.clientStartedAt || null,
            filename: metadata.filename || "现场录音", interrupted: false, backgrounded: false,
            activeDurationMs: 0, createdAt: Date.now()};
        function queue(operation) {
            tail = tail.then(operation);
            // Callers observe errors, but a failed earlier write must also prevent later/final manifest writes.
            void tail.catch(() => {});
            return tail;
        }
        function writeManifest() {
            return store.saveMedia(owner, draftId, `${key(sessionId)}manifest`,
                new Blob([JSON.stringify({...manifest, updatedAt: Date.now()})], {type: "application/json"}));
        }
        function update(details) {
            manifest.interrupted ||= details?.interrupted === true;
            manifest.backgrounded ||= details?.backgrounded === true;
            if (Number.isFinite(details?.activeDurationMs)) manifest.activeDurationMs = Math.max(0, details.activeDurationMs);
            manifest.chunkCount = sequence;
            manifest.sizeBytes = bytes;
        }
        const ready = queue(writeManifest);
        return Object.freeze({sessionId, ready,
            append(blob) {
                if (closed || !(blob instanceof Blob)) return Promise.reject(new Error("录音会话已结束"));
                if (!blob.size) return tail;
                if (sequence >= MAX_CHUNKS || bytes + blob.size > maximum) {
                    closed = true;
                    return Promise.reject(Object.assign(new Error("录音达到本机单文件保存上限"), {code: "RECORDING_SIZE_LIMIT"}));
                }
                const chunkId = `${key(sessionId)}chunk:${String(sequence++).padStart(6, "0")}`;
                bytes += blob.size;
                return queue(() => store.saveMedia(owner, draftId, chunkId, blob));
            },
            checkpoint(details) {
                if (closed) return tail;
                return queue(() => { update(details); return writeManifest(); });
            },
            finish(details = {}) {
                closed = true;
                return queue(() => {
                    update(details);
                    manifest.status = details.finalized === true ? "FINALIZED" : "INTERRUPTED";
                    return writeManifest();
                });
            }
        });
    }

    async function list(owner, draftId) {
        if (!owner || !draftId) return [];
        const rows = await storage().mediaFor(owner, draftId);
        const entries = [];
        for (const row of rows) {
            if (row.owner !== owner || !row.mediaId?.startsWith(PREFIX) || !row.mediaId.endsWith(":manifest")
                    || !(row.file instanceof Blob) || row.file.size > 16384) continue;
            let manifest;
            try { manifest = JSON.parse(await row.file.text()); } catch (_) { continue; }
            if (manifest.version !== 1 || manifest.owner !== owner || manifest.draftId !== draftId
                    || !validId(manifest.sessionId) || row.mediaId !== `${key(manifest.sessionId)}manifest`) continue;
            const chunkPrefix = `${key(manifest.sessionId)}chunk:`;
            const chunks = rows.filter(item => item.owner === owner && item.mediaId?.startsWith(chunkPrefix))
                .sort((a, b) => a.mediaId.localeCompare(b.mediaId));
            let contiguous = true;
            let sizeBytes = 0;
            chunks.forEach((item, index) => {
                contiguous &&= item.mediaId === `${chunkPrefix}${String(index).padStart(6, "0")}` && item.file instanceof Blob;
                sizeBytes += item.file?.size || 0;
            });
            if (!chunks.length) continue;
            const complete = contiguous && manifest.status === "FINALIZED"
                && chunks.length === manifest.chunkCount && sizeBytes === manifest.sizeBytes;
            entries.push({...manifest, complete, contiguous, sizeBytes,
                blob: new Blob(chunks.map(item => item.file), {type: manifest.mimeType || chunks[0].file.type || "audio/webm"})});
        }
        return entries.sort((left, right) => left.createdAt - right.createdAt);
    }

    async function remove(owner, draftId, sessionId) {
        if (!owner || !draftId || !validId(sessionId)) throw new Error("录音保存身份无效");
        const store = storage();
        const rows = await store.mediaFor(owner, draftId);
        const matches = rows.filter(row => row.owner === owner && row.mediaId?.startsWith(key(sessionId)));
        // Remove the manifest last: interrupted cleanup remains discoverable and never looks complete.
        matches.sort((a, b) => Number(a.mediaId.endsWith(":manifest")) - Number(b.mediaId.endsWith(":manifest")));
        for (const row of matches) await store.removeMedia(owner, draftId, row.mediaId);
    }
    root.SalesCheckinRecordingJournal = Object.freeze({create, list, remove});
})(typeof window === "undefined" ? globalThis : window);
