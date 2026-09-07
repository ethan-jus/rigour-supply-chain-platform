(function (root) {
    "use strict";

    // 草稿与 Blob 分开写入：只有事务完成才报告已保存。截止时间只负责退出等待，不能把未知结果当成功。
    const DATABASE = "rigour.sales-checkin.v2";
    const MAX_BYTES = 512 * 1024 * 1024;
    const MAX_DRAFTS = 20;
    const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
    const OPEN_TIMEOUT_MS = 10000;
    const READ_TIMEOUT_MS = 10000;
    const WRITE_TIMEOUT_MS = 15000;
    const BLOB_WRITE_TIMEOUT_MS = 60000;
    let opening;
    let connection;
    const key = (owner, id) => `${owner}|${id}`;
    const timeoutError = () => Object.assign(new Error("本机保存或读取超时，结果尚未确认；请保留页面并重试"), { code: "LOCAL_STORAGE_TIMEOUT" });

    function closeConnection(db) {
        if (connection === db) connection = null;
        try { db?.close(); } catch (_) { /* The browser may have already closed the connection. */ }
    }

    function open() {
        if (!root.indexedDB) return Promise.reject(new Error("当前浏览器不支持本机保存"));
        if (connection) return Promise.resolve(connection);
        if (opening) return opening.promise;
        // Store the attempt before invoking IndexedDB: synchronous failures must not cache a rejected promise.
        const attempt = {};
        opening = attempt;
        attempt.promise = new Promise((resolve, reject) => {
            let settled = false;
            let request;
            const finish = (error, db) => {
                if (settled) { if (db) closeConnection(db); return; }
                settled = true;
                root.clearTimeout(timer);
                if (opening === attempt) opening = null;
                if (error) { reject(error); return; }
                connection = db;
                db.onversionchange = () => closeConnection(db);
                db.onclose = () => { if (connection === db) connection = null; };
                resolve(db);
            };
            const timer = root.setTimeout(() => {
                finish(timeoutError());
                try { request?.transaction?.abort(); } catch (_) { /* Open requests have no cancellable transaction outside upgrade. */ }
            }, OPEN_TIMEOUT_MS);
            try {
                request = root.indexedDB.open(DATABASE, 1);
                request.onupgradeneeded = () => {
                    if (settled) {
                        try { request.transaction?.abort(); } catch (_) { /* Do not perform a late schema write. */ }
                        closeConnection(request.result);
                        return;
                    }
                    try {
                        const db = request.result;
                        db.createObjectStore("drafts", { keyPath: "key" }).createIndex("owner", "owner");
                        db.createObjectStore("media", { keyPath: "key" }).createIndex("draft", "draft");
                    } catch (error) {
                        finish(error);
                        try { request.transaction?.abort(); } catch (_) { /* Upgrade may already be aborted. */ }
                    }
                };
                request.onerror = () => finish(request.error || new Error("无法打开本机存储"));
                request.onblocked = () => finish(new Error("请关闭旧打卡页面后重试保存"));
                request.onsuccess = () => finish(null, request.result);
            } catch (error) { finish(error); }
        });
        return attempt.promise;
    }

    async function transact(stores, mode, operation, timeout = WRITE_TIMEOUT_MS) {
        const db = await open();
        return new Promise((resolve, reject) => {
            let tx;
            try { tx = db.transaction(stores, mode); }
            catch (error) { closeConnection(db); reject(error); return; }
            let settled = false;
            let value;
            const finish = (error, abort = false, reset = false) => {
                if (settled) return;
                settled = true;
                root.clearTimeout(timer);
                // Set settled before abort: some browsers synchronously deliver abort/error callbacks.
                if (abort) { try { tx.abort(); } catch (_) { /* Commit may already have started; its outcome remains unconfirmed. */ } }
                if (reset) closeConnection(db);
                if (error) reject(error); else resolve(value);
            };
            const timer = root.setTimeout(() => finish(timeoutError(), true, true), timeout);
            tx.oncomplete = () => finish();
            tx.onabort = tx.onerror = () => finish(tx.error || new Error("本机保存未完成"));
            // Every asynchronous request/cursor callback is gated. A timeout must not later enqueue put/delete work.
            const onSuccess = (request, callback) => {
                request.onsuccess = () => {
                    if (settled) return;
                    try { callback(request.result); }
                    catch (error) { finish(error, true); }
                };
            };
            try { operation(tx, (next) => { if (!settled) value = next; }, onSuccess); }
            catch (error) { finish(error, true); }
        });
    }

    function readAll(store, index, value) {
        return transact([store], "readonly", (tx, result, onSuccess) => {
            onSuccess(tx.objectStore(store).index(index).getAll(value), result);
        }, READ_TIMEOUT_MS);
    }

    async function list(owner) {
        const records = await readAll("drafts", "owner", owner);
        return records.filter((record) => Date.now() - record.updatedAt <= RETENTION_MS)
            .sort((left, right) => right.updatedAt - left.updatedAt);
    }

    async function save(owner, snapshot) {
        if (!owner || !snapshot?.submission?.clientSubmissionId) throw new Error("缺少保存身份");
        return transact(["drafts", "media"], "readwrite", (tx, result, onSuccess) => {
            const drafts = tx.objectStore("drafts");
            const id = key(owner, snapshot.submission.clientSubmissionId);
            onSuccess(drafts.index("owner").getAll(owner), (records) => {
                if (records.length >= MAX_DRAFTS && !records.some((item) => item.key === id)) {
                    const disposable = records.filter((item) =>
                        item.snapshot.submission.status === "SUBMITTED" && !item.snapshot.submission.pendingWechat
                        && !(item.snapshot.submission.audioSegments || []).some((segment) =>
                            !["UPLOADED", "SKIPPED", "DISCARDED"].includes(segment.uploadState)))
                        .sort((left, right) => left.updatedAt - right.updatedAt)[0];
                    if (!disposable) { tx.abort(); return; }
                    drafts.delete(disposable.key);
                    const cursor = tx.objectStore("media").index("draft").openCursor(disposable.key);
                    onSuccess(cursor, (current) => {
                        if (!current) return;
                        current.delete(); current.continue();
                    });
                }
                drafts.put({ key: id, owner, updatedAt: Date.now(), snapshot });
            });
        });
    }

    async function saveMedia(owner, draftId, mediaId, file) {
        if (!owner || !draftId || !(file instanceof Blob)) throw new Error("文件无法在本机保存");
        return transact(["media"], "readwrite", (tx, result, onSuccess) => {
            const media = tx.objectStore("media");
            const draft = key(owner, draftId);
            const id = `${draft}|${mediaId}`;
            // Cursor iteration bounds live Blob memory; getAll would retain every visit's media at once.
            let used = 0;
            const request = media.openCursor();
            onSuccess(request, (cursor) => {
                if (cursor) {
                    if (cursor.key !== id) used += cursor.value.size;
                    cursor.continue();
                    return;
                }
                if (used + file.size > MAX_BYTES) { tx.abort(); return; }
                media.put({ key: id, draft, owner, mediaId, file, size: file.size,
                    filename: file.name || "附件", lastModified: file.lastModified || null,
                    updatedAt: Date.now() });
            });
        }, BLOB_WRITE_TIMEOUT_MS);
    }

    async function mediaFor(owner, draftId) {
        return readAll("media", "draft", key(owner, draftId));
    }

    async function removeMedia(owner, draftId, mediaId) {
        return transact(["media"], "readwrite", (tx) => {
            tx.objectStore("media").delete(`${key(owner, draftId)}|${mediaId}`);
        });
    }

    async function remove(owner, draftId) {
        return transact(["drafts", "media"], "readwrite", (tx, result, onSuccess) => {
            const draft = key(owner, draftId);
            tx.objectStore("drafts").delete(draft);
            const cursor = tx.objectStore("media").index("draft").openCursor(draft);
            onSuccess(cursor, (current) => {
                if (!current) return;
                current.delete(); current.continue();
            });
        });
    }

    async function clearOwner(owner) {
        const records = await readAll("drafts", "owner", owner);
        for (const record of records) await remove(owner, record.snapshot.submission.clientSubmissionId);
    }

    async function prune() {
        return transact(["drafts", "media"], "readwrite", (tx, result, onSuccess) => {
            ["drafts", "media"].forEach((name) => {
                const request = tx.objectStore(name).openCursor();
                onSuccess(request, (cursor) => {
                    if (!cursor) return;
                    if (Date.now() - cursor.value.updatedAt > RETENTION_MS) cursor.delete();
                    cursor.continue();
                });
            });
        });
    }

    root.SalesCheckinDraftStore = Object.freeze({ save, list, saveMedia, mediaFor, removeMedia,
        remove, clearOwner, prune, MAX_BYTES, MAX_DRAFTS, RETENTION_MS });
})(typeof window === "undefined" ? globalThis : window);
