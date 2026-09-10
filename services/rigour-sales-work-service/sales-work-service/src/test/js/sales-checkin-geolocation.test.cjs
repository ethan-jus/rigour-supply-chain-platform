const assert = require("node:assert/strict");
const { webcrypto } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const sourcePath = path.resolve(__dirname, "../../main/resources/static/sales-checkin/app.js");
const source = fs.readFileSync(sourcePath, "utf8").replace(/\n\}\)\(\);\s*$/, `
    window.__test = { locationEvidenceFromPosition, shouldReplaceLocationSample,
        normalizeUnverifiedLocationEvidence, visitSelectedStoreReady, isVisitStepReady,
        buildSubmissionPayload, state };
})();`);
const window = {};
vm.runInNewContext(source, {
    window, document: { addEventListener() {}, querySelector() { return {value: ""}; } },
    navigator: {}, crypto: webcrypto, Blob, Headers, URL,
    btoa: (value) => Buffer.from(value, "binary").toString("base64")
}, { filename: sourcePath });
const api = window.__test;
const now = Date.UTC(2026, 8, 7, 8);
const position = (time, accuracy = 30) => ({ coords: { longitude: 120.123456789,
    latitude: 30.123456789, accuracy }, timestamp: time });
let checks = 0;
const check = (label, fn) => { fn(); checks += 1; console.log(`ok - ${label}`); };
check("raw epoch milliseconds and full coordinate are preserved", () => {
    const sample = api.locationEvidenceFromPosition(position(now - 1000), now);
    assert.equal(sample.capturedAt, new Date(now - 1000).toISOString());
    assert.equal(sample.receivedAt, new Date(now).toISOString());
    assert.equal(sample.rawTimestamp, String(now - 1000));
    assert.equal(sample.longitude, 120.123456789);
    assert.equal(sample.timeStatus, "KNOWN");
});
check("stale location remains evidence without becoming fresh", () => {
    const sample = api.locationEvidenceFromPosition(position(now - 600000), now);
    assert.equal(sample.capturedAt, new Date(now - 600000).toISOString());
    assert.equal(sample.timeStatus, "STALE");
    assert.notEqual(sample.capturedAt, sample.receivedAt);
});
check("unknown clocks and epoch seconds never become receipt time", () => {
    for (const value of [0, 5000, now / 1000, "not-a-time", NaN, undefined, now + 86400000]) {
        const sample = api.locationEvidenceFromPosition(position(value), now);
        assert.equal(sample.capturedAt, null);
        assert.equal(sample.timeStatus, "UNKNOWN");
        assert.equal(sample.longitude, 120.123456789);
    }
});
check("low or missing accuracy is retained rather than blocking", () => {
    assert.equal(api.locationEvidenceFromPosition(position(now, 25000), now).accuracyMeters, 25000);
    assert.equal(api.locationEvidenceFromPosition(position(now, null), now).accuracyMeters, null);
    assert.equal(api.normalizeUnverifiedLocationEvidence({longitude: 181, latitude: 30}), null);
});
check("later better sample wins, stale or older callback cannot replace fresh", () => {
    const good = api.locationEvidenceFromPosition(position(now - 2000, 70), now);
    const better = api.locationEvidenceFromPosition(position(now - 1000, 20), now);
    const stale = api.locationEvidenceFromPosition(position(now - 600000, 1), now);
    assert.equal(api.shouldReplaceLocationSample(good, better), true);
    assert.equal(api.shouldReplaceLocationSample(better, good), false);
    assert.equal(api.shouldReplaceLocationSample(good, stale), false);
    assert.equal(api.shouldReplaceLocationSample(null, stale), true);
});
check("selected authorized store progresses without GPS or nearby membership", () => {
    Object.assign(api.state.visit, {city: "杭州", salespersonId: "sales", selectedStore: {id: "far-store"},
        location: null, locationContext: null, nearbyStores: []});
    assert.equal(api.visitSelectedStoreReady(), true);
    assert.equal(api.isVisitStepReady(1), true);
    const payload = api.buildSubmissionPayload();
    assert.equal(payload.storeId, "far-store");
    assert.equal(payload.location ?? null, null);
});
console.log(`${checks} geolocation behavior checks passed`);
