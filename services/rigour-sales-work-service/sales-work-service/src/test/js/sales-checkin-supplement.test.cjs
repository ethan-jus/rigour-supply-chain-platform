const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const {test} = require("node:test");
const {randomUUID} = require("node:crypto");

// Reuse the existing DOM/transport fixture; it evaluates the current production app.js.
const fixturePath = path.join(__dirname, "sales-checkin-recovery.test.cjs");
const fixtureSource = fs.readFileSync(fixturePath, "utf8");
const fixture = new Module(fixturePath);
fixture.filename = fixturePath;
fixture.paths = Module._nodeModulePaths(__dirname);
fixture._compile(fixtureSource.slice(0, fixtureSource.indexOf("let checks = 0;")) + "\nmodule.exports = {harness};", fixturePath);

function ready() {
    const h = fixture.exports.harness();
    const segmentId = randomUUID();
    h.api.state.completed = true;
    Object.assign(h.api.state.submission, {serverId: "original-record", status: "SUBMITTED", submissionKey: "original-key"});
    h.api.state.submission.audioSegments = [{segmentId, uploadState: "LOCAL", captureSource: "BROWSER_RECORDER"}];
    h.api.state.files.audio = [{segmentId, file: new Blob(["retained recording"])}];
    h.reads = 0;
    h.receipt = () => ({id: "original-record", status: "SUBMITTED", uploadedMedia: ["storefront-photo"], photoIds: [h.photoId], audioSegmentIds: []});
    h.api.setRequest(async () => {h.reads++; return h.receipt();});
    h.segmentId = segmentId;
    return h;
}
function audioUploads(h) {return h.uploaded.filter(args => args[0].startsWith("audio/"));}

test("metadata storage failure cannot prevent uploading the in-memory recording", async () => {
    const h = ready();
    h.window.SalesCheckinDraftStore.save = async () => {throw Object.assign(new Error("synthetic local save failure"), {name: "QuotaExceededError"});};
    await h.api.supplementCurrentEvidence();
    assert.equal(audioUploads(h).length, 1);
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
    assert.equal(h.api.state.files.audio[0].file.size, 18);
    assert.doesNotMatch(h.element("#draft-save-status").textContent, /本机已保存/);
});

test("local cleanup failure cannot undo a valid server receipt or require another upload", async () => {
    const h = ready();
    const key = `t1:sales1/${h.api.state.submission.clientSubmissionId}/audio:${h.segmentId}`;
    h.api.state.unsavedMedia.add(key);
    h.window.SalesCheckinDraftStore.removeMedia = async () => {throw new Error("synthetic cleanup failure");};
    h.api.setRequest(async () => {if (h.reads++) throw new Error("later network unavailable"); return h.receipt();});
    await h.api.supplementCurrentEvidence();
    assert.equal(audioUploads(h).length, 1);
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
    assert.equal(h.reads, 1);
    assert.equal(h.api.state.unsavedMedia.has(key), true);
    assert.equal(h.api.state.files.audio.length, 1);
});

test("missing original is actionable on the success page", async () => {
    const h = ready(); h.api.state.files.audio = [];
    await h.api.supplementCurrentEvidence();
    assert.equal(audioUploads(h).length, 0);
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "NEEDS_FILE");
    assert.match(h.element("#success-media-note").textContent, /原文件.*重新选择|重新选择.*原文件/);
});

test("HTTP 415 is shown as its real rejection and never as a confirmed upload", async () => {
    const h = ready();
    h.api.setUpload(async () => {throw Object.assign(new Error("录音格式不支持，请重新选择兼容的录音文件"), {status: 415});});
    await h.api.supplementCurrentEvidence();
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "ERROR");
    assert.equal(h.api.state.submission.audioSegments[0].uploadErrorStatus, 415);
    assert.match(h.element("#success-media-note").textContent, /格式不支持/);
});

test("an expired supplement performs only receipt lookup and displays the deadline failure", async () => {
    const h = ready(); h.receipt = () => ({id: "original-record", status: "SUBMITTED", uploadedMedia: ["storefront-photo"], photoIds: [h.photoId], audioSegmentIds: [], supplementUntil: "2000-01-01T00:00:00Z"});
    await h.api.supplementCurrentEvidence();
    assert.equal(audioUploads(h).length, 0);
    assert.match(h.element("#success-media-note").textContent, /补传时间已结束/);
});

test("authentication failure remains visible and retains the original recording", async () => {
    const h = ready(); h.api.setRequest(async () => {throw Object.assign(new Error("身份已失效，请重新验证后重试"), {status: 401});});
    await h.api.supplementCurrentEvidence();
    assert.equal(audioUploads(h).length, 0);
    assert.equal(h.api.state.files.audio.length, 1);
    assert.match(h.element("#success-media-note").textContent, /身份已失效/);
});

test("single-segment retry cannot use a different visit after waiting for metadata", async () => {
    const h = ready(); h.api.state.completed = false; h.api.state.submission.status = "DRAFT";
    h.api.state.submission.audioSegments[0].uploadState = "ERROR";
    let release; h.api.state.files.audio[0].metadataPromise = new Promise(resolve => {release = resolve;});
    const pending = h.api.retryAudioSegment(h.segmentId);
    h.api.state.submission = {...h.api.state.submission, serverId: "next-record", submissionKey: "next-key", clientSubmissionId: randomUUID(), audioSegments: []};
    h.api.state.files.audio = [];
    release(); await pending;
    assert.equal(audioUploads(h).length, 0);
});

test("manual retry and success retry share one per-visit upload queue", async () => {
    const h = ready(); h.api.state.submission.audioSegments[0].uploadState = "ERROR";
    let release; h.api.setRequest(() => new Promise(resolve => {release = resolve;}));
    const automatic = h.api.supplementCurrentEvidence(); await Promise.resolve(); await Promise.resolve();
    await h.api.retryAudioSegment(h.segmentId);
    assert.equal(audioUploads(h).length, 0);
    release(h.receipt()); await automatic;
    assert.equal(audioUploads(h).length, 1);
});

test("local save failure plus network failure retains the Blob, unsaved marker, and actual error", async () => {
    const h = ready();
    h.window.SalesCheckinDraftStore.save = async () => {throw new Error("synthetic storage unavailable");};
    let writes = 0;
    h.api.setUpload(async () => {writes++; throw Object.assign(new Error("录音格式不支持"), {status: 415});});
    await h.api.supplementCurrentEvidence();
    assert.equal(writes, 1); assert.equal(h.api.state.files.audio[0].file.size, 18);
    const key = `t1:sales1/${h.api.state.submission.clientSubmissionId}/audio:${h.segmentId}`;
    assert.equal(h.api.state.unsavedMedia.has(key), true);
    assert.match(h.element("#success-media-note").textContent, /本机保存未完成/);
    assert.match(h.element("#success-media-note").textContent, /格式不支持/);
    assert.equal(h.api.state.evidenceSyncIds.size, 0);
});

test("next visit during receipt lookup still sends only the original visit with captured credentials", async () => {
    const h = ready(); const originalId = h.api.state.submission.clientSubmissionId;
    let release; h.api.setRequest(() => new Promise(resolve => {release = resolve;}));
    const pending = h.api.supplementCurrentEvidence(); await Promise.resolve(); await Promise.resolve();
    h.api.state.submission = {...h.api.state.submission, serverId: "next-record", submissionKey: "next-key", clientSubmissionId: randomUUID(), audioSegments: []};
    h.api.state.files.audio = [];
    release(h.receipt()); await pending;
    assert.equal(audioUploads(h).length, 1);
    assert.equal(audioUploads(h)[0][4].submissionId, "original-record");
    assert.equal(audioUploads(h)[0][4].submissionKey, "original-key");
    assert.equal(h.api.state.submission.audioSegments.length, 0);
    assert.ok(h.saved.some(row => row.owner === "t1:sales1" && row.snapshot.submission.clientSubmissionId === originalId));
});

test("identity changes during receipt lookup prevent further uploads", async () => {
    const h = ready(); let release;
    h.api.setRequest(() => new Promise(resolve => {release = resolve;}));
    const pending = h.api.supplementCurrentEvidence(); await Promise.resolve(); await Promise.resolve();
    h.api.state.identity = {authenticated: true, tenantId: "t1", salespersonId: "other-sales"};
    release(h.receipt()); await pending;
    assert.equal(audioUploads(h).length, 0); assert.equal(h.saved.length, 0);
    assert.equal(h.api.state.evidenceSyncIds.size, 0);
});

test("manual draft retry freezes explicit credentials and does not update a later visit on response", async () => {
    const h = ready(); h.api.state.completed = false; h.api.state.submission.status = "DRAFT";
    h.api.state.submission.audioSegments[0].uploadState = "ERROR";
    const originalId = h.api.state.submission.clientSubmissionId;
    let release, options;
    h.api.setUpload((...args) => {options = args[4]; return new Promise(resolve => {release = resolve;});});
    const pending = h.api.retryAudioSegment(h.segmentId);
    assert.equal(options.submissionId, "original-record"); assert.equal(options.submissionKey, "original-key");
    assert.equal(h.api.state.evidenceSyncIds.has(originalId), true);
    h.api.state.submission = {...h.api.state.submission, serverId: "next-record", submissionKey: "next-key", clientSubmissionId: randomUUID(), audioSegments: []};
    release({id: "original-record", kind: "audio", segmentId: h.segmentId}); await pending;
    assert.equal(h.api.state.submission.audioSegments.length, 0);
    assert.ok(h.saved.some(row => row.snapshot.submission.clientSubmissionId === originalId && row.snapshot.submission.audioSegments[0].uploadState === "UPLOADED"));
    assert.equal(h.api.state.evidenceSyncIds.size, 0);
});

test("an explicitly retried skipped recording is uploaded through the shared completion queue", async () => {
    const h = ready(); h.api.state.submission.audioSegments[0].uploadState = "SKIPPED";
    await h.api.retryAudioSegment(h.segmentId);
    assert.equal(audioUploads(h).length, 1);
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
});

test("identity changes while the local checkpoint is pending prevent the following upload", async () => {
    const h = ready(); let release, saves = 0;
    h.window.SalesCheckinDraftStore.save = () => ++saves === 2 ? new Promise(resolve => {release = resolve;}) : Promise.resolve();
    const pending = h.api.supplementCurrentEvidence();
    for (let i = 0; i < 20 && !release; i++) await Promise.resolve();
    assert.equal(typeof release, "function");
    h.api.state.identity = {authenticated: true, tenantId: "t1", salespersonId: "other-sales"};
    h.window.SalesCheckinDraftStore.save = async () => {};
    release(); await pending;
    assert.equal(audioUploads(h).length, 0);
});

test("first submit completes and automatically uploads retained audio despite metadata storage failure", async () => {
    const h = fixture.exports.harness(); const segmentId = randomUUID();
    h.api.state.submission.audioSegments = [{segmentId, uploadState: "LOCAL"}];
    h.api.state.files.audio = [{segmentId, file: new Blob(["initial recording"])}];
    h.window.SalesCheckinDraftStore.save = async () => {throw new Error("synthetic quota");};
    h.api.setRequest(async url => {
        if (url === "/submissions") return {id: "server1", status: "DRAFT", uploadedMedia: [], audioSegmentIds: []};
        return {id: "server1", status: "SUBMITTED", uploadedMedia: ["storefront-photo"], photoIds: [h.photoId], audioSegmentIds: []};
    });
    await h.api.submitVisit({preventDefault() {}});
    for (let i = 0; i < 50 && h.api.state.evidenceSyncIds.size; i++) await Promise.resolve();
    assert.equal(h.api.state.completed, true);
    assert.equal(audioUploads(h).length, 1);
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
});
