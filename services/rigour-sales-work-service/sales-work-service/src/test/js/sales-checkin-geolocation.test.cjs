const assert = require("node:assert/strict");
const { webcrypto } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const scriptPath = path.resolve(
    __dirname, "../../main/resources/static/sales-checkin/app.js");
let source = fs.readFileSync(scriptPath, "utf8");
source = source.replace(/\n\}\)\(\);\s*$/, `
    window.__geolocationTestApi = {
        resolveGeolocationCapturedAtMs,
        resolveAdvancingGeolocationClockCapturedAtMs
    };
})();`);

const window = {};
vm.runInNewContext(source, {
    window,
    document: { addEventListener() {} },
    navigator: {},
    crypto: webcrypto,
    btoa: (value) => Buffer.from(value, "binary").toString("base64"),
    Blob,
    Headers,
    URL,
    Map,
    Set,
    Uint8Array,
    Date,
    Math,
    Number,
    String,
    Array,
    Object,
    RegExp,
    Promise,
    console
}, { filename: scriptPath });

const {
    resolveGeolocationCapturedAtMs,
    resolveAdvancingGeolocationClockCapturedAtMs
} = window.__geolocationTestApi;
const now = Date.UTC(2026, 8, 1, 8, 0, 0);
const appleEpochOffsetMs = 978307200000;
let checks = 0;

function expectCapturedAt(value, expected, label) {
    const actual = resolveGeolocationCapturedAtMs(value, now, 60_000);
    assert.ok(actual !== null && Math.abs(actual - expected) < 2,
        `${label}: expected ${expected}, got ${actual}`);
    checks += 1;
}

function expectNullCapturedAt(value, label) {
    assert.equal(resolveGeolocationCapturedAtMs(value, now, 60_000), null, label);
    checks += 1;
}

function expectAdvancing(previous, current, expected, label) {
    assert.equal(resolveAdvancingGeolocationClockCapturedAtMs(previous, current), expected, label);
    checks += 1;
}

expectCapturedAt(now - 1_000, now - 1_000, "epoch milliseconds");
expectCapturedAt((now - 2_000) / 1_000, now - 2_000, "epoch seconds");
expectCapturedAt((now - 3_000) * 1_000, now - 3_000, "epoch microseconds");
expectCapturedAt((now - 4_000) * 1_000_000, now - 4_000, "epoch nanoseconds");
expectCapturedAt(now - 5_000 - appleEpochOffsetMs, now - 5_000, "Apple milliseconds");
expectCapturedAt(
    (now - 6_000 - appleEpochOffsetMs) / 1_000, now - 6_000, "Apple seconds");
expectCapturedAt(String((now - 7_000) / 1_000), now - 7_000, "numeric string seconds");

expectNullCapturedAt(44_000, "single monotonic millisecond sample is not trusted");
expectNullCapturedAt(44_000_000, "single monotonic microsecond sample is not trusted");
expectNullCapturedAt(44_000_000_000, "single monotonic nanosecond sample is not trusted");
expectNullCapturedAt(now - 61_000, "stale epoch milliseconds");
expectNullCapturedAt(0, "zero timestamp");
expectNullCapturedAt(Number.NaN, "NaN timestamp");
expectNullCapturedAt(
    (now - 7 * 24 * 60 * 60 * 1000) / 1000, "seven-day-old Unix seconds");
expectNullCapturedAt(Date.UTC(2001, 8, 1) / 1000, "2001 Unix seconds");
expectNullCapturedAt(
    (now + 7 * 24 * 60 * 60 * 1000) / 1000, "future Unix seconds");

expectAdvancing(
    { value: 5_000_000_000_000, receivedAtMs: now - 1_000 },
    { value: 5_001_000_000_000, receivedAtMs: now }, now,
    "advancing boot-relative nanoseconds");
expectAdvancing(
    { value: 5_000_000_000_000, receivedAtMs: now - 1_000 },
    { value: 5_000_000_000_000, receivedAtMs: now }, null,
    "static boot-relative timestamp");
expectAdvancing(
    { value: now - 7 * 24 * 60 * 60 * 1000, receivedAtMs: now - 1_000 },
    { value: now - 7 * 24 * 60 * 60 * 1000 + 1, receivedAtMs: now }, null,
    "stale epoch milliseconds cannot masquerade as microseconds");
expectAdvancing(
    { value: (now - 7 * 24 * 60 * 60 * 1000) / 1000, receivedAtMs: now - 1_000 },
    { value: (now - 7 * 24 * 60 * 60 * 1000) / 1000 + 1, receivedAtMs: now }, null,
    "stale epoch seconds cannot masquerade as milliseconds");
expectAdvancing(
    { value: (now + 7 * 24 * 60 * 60 * 1000) / 1000, receivedAtMs: now - 1_000 },
    { value: (now + 7 * 24 * 60 * 60 * 1000) / 1000 + 1, receivedAtMs: now }, null,
    "future epoch seconds cannot masquerade as milliseconds");
expectAdvancing(
    { value: 44_000, receivedAtMs: now - 1_000 },
    { value: 45_000, receivedAtMs: now }, now,
    "advancing page-relative milliseconds");
expectAdvancing(
    { value: 44_000_000, receivedAtMs: now - 1_000 },
    { value: 45_000_000, receivedAtMs: now }, now,
    "advancing page-relative microseconds");
expectAdvancing(
    { value: 44_000_000_000, receivedAtMs: now - 1_000 },
    { value: 45_000_000_000, receivedAtMs: now }, now,
    "advancing page-relative nanoseconds");
expectAdvancing(
    { value: 400 * 24 * 60 * 60 * 1_000_000_000, receivedAtMs: now - 1_000 },
    { value: 400 * 24 * 60 * 60 * 1_000_000_000 + 1_000_000_000,
        receivedAtMs: now }, null,
    "implausibly long boot-relative nanoseconds");

console.log(`sales-checkin geolocation timestamp tests: ${checks}/${checks} passed`);
