const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const {test} = require("node:test");
const {randomUUID} = require("node:crypto");

// Use the current application and the existing DOM/transport boundary fixture.
const fixturePath = path.join(__dirname, "sales-checkin-recovery.test.cjs");
const source = fs.readFileSync(fixturePath, "utf8");
const fixture = new Module(fixturePath);
fixture.filename = fixturePath;
fixture.paths = Module._nodeModulePaths(__dirname);
fixture._compile(source.slice(0, source.indexOf("let checks = 0;")) + "\nmodule.exports = {harness};", fixturePath);
const owner = "t1:sales1";
const recording = content => Object.assign(new Blob([content], {type: "audio/wav"}),
    {name: "same-recording.wav", lastModified: 100});

function prepared() {
    const h = fixture.exports.harness();
    h.id = randomUUID();
    h.old = recording("old-content");
    h.replacement = recording("new-content");
    h.api.state.completed = true;
    Object.assign(h.api.state.submission, {serverId: "record-one", status: "SUBMITTED"});
    h.api.state.submission.audioSegments = [{segmentId: h.id, originalFilename: h.old.name,
        sizeBytes: h.old.size, uploadState: "ERROR", uploadErrorStatus: 400, uploadErrorReason: "NO_AUDIO_TRACK"}];
    h.api.state.files.audio = [{segmentId: h.id, file: h.old}];
    return h;
}
async function restore(h, media, snapshot = h.api.snapshotDraft()) {
    const fresh = fixture.exports.harness();
    fresh.window.SalesCheckinDraftStore.mediaFor = async () => media;
    assert.equal(await fresh.api.openSavedDraft({owner, snapshot}, false), true);
    fresh.api.setRequest(async () => ({id: "record-one", status: "SUBMITTED", uploadedMedia: ["storefront-photo"],
        photoIds: snapshot.submission.photos.map(item => item.photoId), audioSegmentIds: []}));
    return fresh;
}
const mediaRow = (h, file, generation) => ({mediaId: `audio:${h.id}`, file, filename: file.name,
    lastModified: file.lastModified, ...(generation ? {generation} : {})});

test("a same-name same-size replacement whose Blob save fails cannot restore or upload the old Blob", async () => {
    const h = prepared();
    assert.equal(h.old.name, h.replacement.name); assert.equal(h.old.size, h.replacement.size);
    h.window.SalesCheckinDraftStore.saveMedia = async () => {throw Object.assign(new Error("quota"), {name: "QuotaExceededError"});};
    h.api.attachAudioFile(h.id, h.replacement);
    await Promise.all([...h.api.state.pendingMedia]); await h.api.persistDraft();
    const snapshot = h.api.snapshotDraft();
    assert.match(snapshot.submission.audioSegments[0].mediaGeneration, /^[0-9a-f-]{36}$/);
    const fresh = await restore(h, [mediaRow(h, h.old)], snapshot);
    assert.equal(fresh.api.state.files.audio.length, 0);
    assert.equal(fresh.api.state.submission.audioSegments[0].uploadState, "NEEDS_FILE");
    assert.match(fresh.api.state.submission.audioSegments[0].errorMessage, /版本不一致/);
    await fresh.api.supplementCurrentEvidence();
    assert.equal(fresh.uploaded.filter(args => args[0].startsWith("audio/")).length, 0);
});

test("a failed Blob checkpoint keeps the new in-memory file uploadable for the same visit", async () => {
    const h = prepared();
    h.window.SalesCheckinDraftStore.saveMedia = async () => {throw new Error("synthetic local failure");};
    h.api.attachAudioFile(h.id, h.replacement);
    await Promise.all([...h.api.state.pendingMedia]);
    const snapshot = h.api.snapshotDraft();
    h.window.SalesCheckinDraftStore.mediaFor = async () => [mediaRow(h, h.old)];
    assert.equal(await h.api.openSavedDraft({owner, snapshot}, false), true);
    assert.equal(h.api.state.files.audio[0].file, h.replacement);
    h.api.setRequest(async () => ({id: "record-one", status: "SUBMITTED", uploadedMedia: ["storefront-photo"],
        photoIds: [h.photoId], audioSegmentIds: []}));
    await h.api.supplementCurrentEvidence();
    const uploads = h.uploaded.filter(args => args[0].startsWith("audio/"));
    assert.equal(uploads.length, 1);
    assert.equal(await uploads[0][1].text(), "new-content");
    assert.equal(h.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
});

test("matching generation survives refresh and is passed once with the selected Blob", async () => {
    const h = prepared(); let stored;
    h.window.SalesCheckinDraftStore.saveMedia = async (savedOwner, draft, mediaId, file, metadata) => {
        stored = {mediaId, file, generation: metadata.generation, filename: file.name, lastModified: file.lastModified};
        assert.equal(savedOwner, owner); assert.equal(draft, h.api.state.submission.clientSubmissionId);
    };
    h.api.attachAudioFile(h.id, h.replacement);
    const generation = h.api.state.submission.audioSegments[0].mediaGeneration;
    await Promise.all([...h.api.state.pendingMedia]); await h.api.persistDraft();
    assert.equal(stored.generation, generation);
    const fresh = await restore(h, [stored]);
    assert.equal(fresh.api.state.submission.audioSegments[0].mediaGeneration, generation);
    assert.equal(fresh.api.state.files.audio[0].generation, generation);
    assert.equal(await fresh.api.state.files.audio[0].file.text(), "new-content");
    await fresh.api.supplementCurrentEvidence();
    assert.equal(fresh.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
});

test("only two legacy records without generation remain compatible; one-sided or different versions require a file", async () => {
    for (const [metadataGeneration, blobGeneration, expected] of [
        [null, null, true], [null, randomUUID(), false], [randomUUID(), null, false], [randomUUID(), randomUUID(), false]
    ]) {
        const h = prepared(); h.api.state.submission.audioSegments[0].uploadState = "LOCAL";
        h.api.state.submission.audioSegments[0].mediaGeneration = metadataGeneration;
        const fresh = await restore(h, [mediaRow(h, h.old, blobGeneration)]);
        assert.equal(fresh.api.state.files.audio.length, expected ? 1 : 0);
        assert.equal(fresh.api.state.submission.audioSegments[0].uploadState, expected ? "LOCAL" : "NEEDS_FILE");
    }
});

test("an obsolete local Blob cannot downgrade an already confirmed server upload", async () => {
    const h = prepared();
    Object.assign(h.api.state.submission.audioSegments[0], {uploadState: "UPLOADED", mediaGeneration: randomUUID()});
    const fresh = await restore(h, [mediaRow(h, h.old)]);
    assert.equal(fresh.api.state.files.audio.length, 0);
    assert.equal(fresh.api.state.submission.audioSegments[0].uploadState, "UPLOADED");
});

test("an older completed recorder journal cannot bypass replacement generation checks", async () => {
    const h = prepared(), fresh = fixture.exports.harness();
    const generation = randomUUID();
    Object.assign(h.api.state.submission.audioSegments[0], {uploadState: "NEEDS_FILE", mediaGeneration: generation});
    const snapshot = h.api.snapshotDraft();
    fresh.context.File = class extends Blob {
        constructor(parts, name, options) {super(parts, options); this.name = name; this.lastModified = 100;}
    };
    let saved = 0, removed = 0;
    fresh.window.SalesCheckinDraftStore.mediaFor = async () => [mediaRow(h, h.old)];
    fresh.window.SalesCheckinDraftStore.saveMedia = async () => {saved++;};
    fresh.window.SalesCheckinRecordingJournal = {
        list: async () => [{owner, draftId: snapshot.submission.clientSubmissionId, sessionId: h.id,
            complete: true, blob: h.old, mimeType: "audio/wav"}],
        remove: async () => {removed++;}
    };
    assert.equal(await fresh.api.openSavedDraft({owner, snapshot}, false), true);
    assert.equal(fresh.api.state.files.audio.length, 0);
    assert.equal(fresh.api.state.submission.audioSegments[0].uploadState, "NEEDS_FILE");
    assert.equal(fresh.api.state.submission.audioSegments[0].mediaGeneration, generation);
    assert.equal(saved, 0); assert.equal(removed, 0);
});
