/* Bounded photo metadata and sequential thumbnail processing for the visit workspace. */
(function (root) {
    "use strict";
    const MAX_PHOTOS = 9;
    const MAX_PREVIEW_PIXELS = 16 * 1024 * 1024;
    const PREVIEW_EDGE = 384;
    const uuid = value => typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
    const source = value => ["CAMERA", "FILE_IMPORT"].includes(value) ? value : null;
    function normalize(raw, restoring = false) {
        const seen = new Set();
        return (Array.isArray(raw) ? raw : []).filter(item => item && uuid(item.photoId) && !seen.has(item.photoId) && seen.add(item.photoId))
            .slice(0, MAX_PHOTOS).map(item => {
                let uploadState = String(item.uploadState || "NEEDS_FILE");
                if (restoring && ["UPLOADING", "DELETING"].includes(uploadState)) uploadState = "UNKNOWN";
                if (restoring && ["LOCAL", "ERROR"].includes(uploadState)) uploadState = "NEEDS_FILE";
                if (!["LOCAL", "UPLOADING", "UPLOADED", "ERROR", "UNKNOWN", "NEEDS_FILE", "DELETING"].includes(uploadState)) uploadState = "NEEDS_FILE";
                return { photoId: item.photoId, mediaId: `photo-${item.photoId}`, captureSource: source(item.captureSource),
                    originalFilename: String(item.originalFilename || "拜访照片").slice(0, 512),
                    contentType: String(item.contentType || ""), sizeBytes: item.sizeBytes != null && Number.isFinite(Number(item.sizeBytes)) ? Number(item.sizeBytes) : null,
                    uploadedAt: item.uploadedAt || null, uploadState,
                    uploadErrorStatus: Number.isInteger(item.uploadErrorStatus) ? item.uploadErrorStatus : null,
                    mayExistRemotely: item.mayExistRemotely === true || ["UPLOADED", "UNKNOWN", "UPLOADING"].includes(uploadState),
                    thumbnailUrl: typeof item.thumbnailUrl === "string" ? item.thumbnailUrl : null,
                    originalUrl: typeof item.originalUrl === "string" ? item.originalUrl : null,
                    errorMessage: typeof item.errorMessage === "string" ? item.errorMessage.slice(0, 300) : "" };
            });
    }
    function record(file, photoId, captureSource) {
        return normalize([{photoId, captureSource, originalFilename: file.name,
            contentType: file.type, sizeBytes: file.size, uploadState: "LOCAL", mayExistRemotely: false}])[0];
    }
    function merge(current, receipt) {
        const result = normalize(current);
        const byId = new Map(result.map(item => [item.photoId, item]));
        const received = Array.isArray(receipt?.photos) ? receipt.photos : [];
        const receivedIds = [...new Set([...(receipt?.photoIds || []), ...received.map(item => item.photoId)].filter(uuid))];
        if (Array.isArray(receipt?.photoIds)) {
            for (const item of result) {
                if (!receivedIds.includes(item.photoId) && ["UPLOADED", "UPLOADING", "UNKNOWN"].includes(item.uploadState)) {
                    Object.assign(item, {uploadState: "NEEDS_FILE", mayExistRemotely: false,
                        thumbnailUrl: null, originalUrl: null, uploadedAt: null});
                }
            }
        }
        for (const photoId of receivedIds) {
            const remote = received.find(item => item.photoId === photoId) || {photoId};
            const item = byId.get(photoId) || {photoId};
            Object.assign(item, remote, {photoId, uploadState: "UPLOADED", mayExistRemotely: true, errorMessage: ""});
            if (!byId.has(photoId)) { result.push(item); byId.set(photoId, item); }
        }
        return normalize(result);
    }
    function fitted(width, height) {
        const scale = Math.min(1, PREVIEW_EDGE / Math.max(width, height));
        return {width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale))};
    }
    async function toPreview(file, env) {
        const bytes = await env.readPrefix(file, 256 * 1024);
        const dimensions = env.dimensions(bytes);
        if (!dimensions || dimensions.width * dimensions.height > MAX_PREVIEW_PIXELS) return null;
        let bitmap = null, image = null, originalUrl = null, canvas = null;
        try {
            if (typeof env.createImageBitmap === "function") {
                try {
                    bitmap = await env.createImageBitmap(file, {resizeWidth: PREVIEW_EDGE, resizeQuality: "high", imageOrientation: "from-image"});
                } catch (_) { /* Known, bounded dimensions permit the ordinary decoder fallback. */ }
            }
            if (!bitmap) {
                image = new env.Image();
                originalUrl = env.URL.createObjectURL(file);
                await new Promise((resolve, reject) => {
                    const timer = env.setTimeout(() => reject(new Error("preview timeout")), 15000);
                    image.onload = () => { env.clearTimeout(timer); resolve(); };
                    image.onerror = () => { env.clearTimeout(timer); reject(new Error("preview unavailable")); };
                    image.src = originalUrl;
                });
            }
            const visual = bitmap || image;
            const width = visual.width || visual.naturalWidth, height = visual.height || visual.naturalHeight;
            if (!width || !height || width * height > MAX_PREVIEW_PIXELS) return null;
            const size = fitted(width, height);
            canvas = env.document.createElement("canvas");
            canvas.width = size.width; canvas.height = size.height;
            const context = canvas.getContext("2d", {alpha: false});
            if (!context || typeof canvas.toBlob !== "function") return null;
            context.fillStyle = "#ffffff"; context.fillRect(0, 0, size.width, size.height);
            context.drawImage(visual, 0, 0, size.width, size.height);
            return await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", 0.8));
        } finally {
            if (bitmap && typeof bitmap.close === "function") bitmap.close();
            if (image) { image.onload = null; image.onerror = null; image.removeAttribute("src"); }
            if (originalUrl) env.URL.revokeObjectURL(originalUrl);
            if (canvas) { canvas.width = 1; canvas.height = 1; }
        }
    }
    function createPreviewQueue(env) {
        let tail = Promise.resolve();
        const entries = new Map();
        return {
            get(id) { return entries.get(id)?.url || null; },
            enqueue(id, file) {
                const previous = entries.get(id);
                if (previous?.file === file) return previous.promise;
                this.release(id);
                const entry = {file, url: null, promise: null};
                entries.set(id, entry);
                entry.promise = tail.then(async () => {
                    if (entries.get(id) !== entry) return null;
                    const blob = await toPreview(file, env).catch(() => null);
                    if (!blob || entries.get(id) !== entry) return null;
                    entry.url = env.URL.createObjectURL(blob);
                    return entry.url;
                });
                tail = entry.promise.catch(() => null);
                return entry.promise;
            },
            release(id) {
                const entry = entries.get(id);
                if (entry?.url) env.URL.revokeObjectURL(entry.url);
                entries.delete(id);
            },
            releaseAll() { for (const id of entries.keys()) this.release(id); }
        };
    }
    root.SalesCheckinPhotos = Object.freeze({MAX_PHOTOS, MAX_PREVIEW_PIXELS, PREVIEW_EDGE,
        normalize, record, merge, fitted, createPreviewQueue});
})(typeof window !== "undefined" ? window : globalThis);
