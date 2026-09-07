#!/usr/bin/env node
"use strict";

// Local fixture only. PW_MODULE_PATH may point to an installed official Playwright package.
// This exercises desktop WebKit with mobile viewport/touch, not a physical iPhone or its camera/GPS.
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { webkit } = require(process.env.PW_MODULE_PATH || "playwright");

const origin = "http://127.0.0.1:8774";
const api = "/sales-checkin/api/v1";
const moduleRoot = path.resolve(__dirname, "..");
const repoRoot = path.resolve(moduleRoot, "../../..");
const ephemeral = process.argv.includes("--ephemeral");
const output = path.join(repoRoot, "docs/qa-20260907/webkit", ...(ephemeral ? ["ephemeral"] : []));
const photoPath = path.join(__dirname, "fixtures/demo-storefront.jpg");
const report = {
    startedAt: new Date().toISOString(), engine: "Playwright WebKit", contextMode: ephemeral ? "ephemeral" : "persistent temporary profile",
    scope: "127.0.0.1:8774 fixture only; fictional records and supplied demo photo",
    limits: ["Desktop WebKit mobile emulation is not physical iOS Safari", "No real camera, GPS, iOS permission dialog or mobile keyboard tested", "Fixture API does not validate production authorization or storage durability"],
    cases: []
};
fs.mkdirSync(output, {recursive: true});

async function createContext(browser, options = {}) {
    if (ephemeral) {
        const context = await browser.newContext(options);
        return {context, closeContext: () => context.close()};
    }
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "sales-checkin-webkit-"));
    let context;
    try {context = await webkit.launchPersistentContext(profile, {headless: true, ...options});}
    catch (error) {fs.rmSync(profile, {recursive: true, force: true}); throw error;}
    return {context, closeContext: async () => {try {await context.close();} finally {fs.rmSync(profile, {recursive: true, force: true});}}};
}

async function nativeStorageProbe(browser) {
    const {context, closeContext} = await createContext(browser);
    const page = await context.newPage();
    try {
        await context.route(origin + "/__webkit-native-probe", route => route.fulfill({contentType: "text/html", body: '<!doctype html><title>Native IndexedDB probe</title><input id="probe-file" type="file">'}));
        await page.goto(origin + "/__webkit-native-probe");
        await page.locator("#probe-file").setInputFiles(photoPath);
        return await page.evaluate(async () => {
            const results = [];
            for (const kind of ["string", "blob", "file", "input-file"]) {
                const value = kind === "string" ? "demo" : kind === "blob" ? new Blob([new Uint8Array([1, 2, 3, 4])])
                    : kind === "input-file" ? document.querySelector("#probe-file").files[0] : new File([new Uint8Array([1, 2, 3, 4])], "demo.bin");
                try {
                    const request = indexedDB.open("native-probe-" + kind, 1);
                    request.onupgradeneeded = () => request.result.createObjectStore("files");
                    const db = await new Promise((resolve, reject) => {request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);});
                    try {
                        await new Promise((resolve, reject) => {
                            const transaction = db.transaction("files", "readwrite");
                            const write = transaction.objectStore("files").put(value, "example");
                            write.onerror = () => reject(write.error);
                            transaction.oncomplete = resolve; transaction.onabort = () => reject(transaction.error);
                        });
                    } finally {db.close();}
                    results.push({kind, saved: true});
                } catch (error) {results.push({kind, saved: false, error: String(error)});}
            }
            return results;
        });
    } finally {await closeContext();}
}

async function runCase(browser, width) {
    const name = `webkit-${width}`;
    const resultText = `WebKit ${width} ${crypto.randomUUID()} 示例：确认补货需求，后续跟进。`;
    const result = {name, width, mobile: true, touch: true, checks: [], blockedChecks: [], consoleErrors: [], pageErrors: [], httpErrors: [], listNetwork: [], screenshots: []};
    report.cases.push(result);
    const {context, closeContext} = await createContext(browser, {viewport: {width, height: 844}, isMobile: true, hasTouch: true,
        deviceScaleFactor: 1, locale: "zh-CN", timezoneId: "Asia/Shanghai",
        ...(width === 390 ? {geolocation: {latitude: 30.2858, longitude: 120.1383, accuracy: 35}, permissions: ["geolocation"]} : {})});
    const page = await context.newPage();
    await page.addInitScript(() => {
        window.__webkitTouchEvidence = [];
        window.__webkitStorageErrors = [];
        const layout = () => {
            const rect = document.querySelector("#visit-step-2-next")?.getBoundingClientRect();
            return {time: performance.now(), bodyClass: document.body.className, active: document.activeElement?.id,
                nextRect: rect ? {x: rect.x, y: rect.y, width: rect.width, height: rect.height} : null};
        };
        for (const type of ["pointerdown", "pointerup", "pointercancel", "touchstart", "touchend", "touchcancel", "mouseover", "mousemove", "mousedown", "mouseup", "click", "focusout"]) document.addEventListener(type, event => window.__webkitTouchEvidence.push({type: event.type,
            target: event.target.closest("button, input, textarea")?.id || event.target.tagName, trusted: event.isTrusted, ...layout()}), {passive: true, capture: true});
        document.addEventListener("DOMContentLoaded", () => new MutationObserver(() => window.__webkitTouchEvidence.push({type: "body-class", ...layout()}))
            .observe(document.body, {attributes: true, attributeFilter: ["class"]}));
        const put = IDBObjectStore.prototype.put;
        IDBObjectStore.prototype.put = function (...args) {
            try {
                const request = put.apply(this, args);
                request.addEventListener("error", () => window.__webkitStorageErrors.push({store: this.name, name: request.error?.name, message: request.error?.message}));
                return request;
            } catch (error) {window.__webkitStorageErrors.push({store: this.name, name: error.name, message: error.message}); throw error;}
        };
        window.__webkitIdbReads = [];
        for (const prototype of [IDBObjectStore.prototype, IDBIndex.prototype]) {
            const getAll = prototype.getAll;
            prototype.getAll = function (...args) {
                const store = this.objectStore?.name || this.name;
                const request = getAll.apply(this, args);
                request.addEventListener("error", () => window.__webkitStorageErrors.push({operation: "getAll", store, name: request.error?.name, message: request.error?.message}));
                request.addEventListener("success", () => window.__webkitIdbReads.push({store, count: request.result.length,
                    media: store === "media" ? request.result.map(item => ({mediaId: item.mediaId, size: item.size, blob: item.file instanceof Blob, fileType: item.file?.type})) : undefined}));
                return request;
            };
        }
    });
    page.setDefaultTimeout(15000);
    const responses = [];
    const pendingResponses = [];
    await context.route("**/*", async route => {
        const url = new URL(route.request().url());
        if (url.origin !== origin) {
            result.checks.push({name: "unexpected external request blocked", passed: false, url: url.origin + url.pathname});
            return route.abort("blockedbyclient");
        }
        return route.continue();
    });
    page.on("pageerror", error => result.pageErrors.push(error.message));
    page.on("console", message => { if (message.type() === "error") result.consoleErrors.push(message.text()); });
    page.on("request", request => {if (new URL(request.url()).pathname === api + "/submissions/mine") result.listNetwork.push({event: "request", at: new Date().toISOString()});});
    page.on("requestfailed", request => {if (new URL(request.url()).pathname === api + "/submissions/mine") result.listNetwork.push({event: "failed", at: new Date().toISOString(), error: request.failure()?.errorText});});
    page.on("response", response => {
        const url = new URL(response.url());
        if (url.pathname === api + "/submissions/mine") result.listNetwork.push({event: "response", at: new Date().toISOString(), status: response.status()});
        if (response.status() >= 400) result.httpErrors.push({path: url.pathname, status: response.status()});
        if (url.pathname === "/sales-checkin/app.js") pendingResponses.push(response.body().then(body => {result.appSha256 = crypto.createHash("sha256").update(body).digest("hex");}));
        if (!url.pathname.startsWith(api + "/submissions")) return;
        const pending = (async () => {
            let body;
            try { body = await response.json(); } catch (_) { return; }
            responses.push({path: url.pathname, method: response.request().method(), status: response.status(), body});
        })();
        pendingResponses.push(pending);
    });
    const check = (name, actual, details) => {
        result.checks.push({name, passed: Boolean(actual), ...(details === undefined ? {} : {details})});
        assert.ok(actual, name + (details === undefined ? "" : ": " + JSON.stringify(details)));
    };
    const shot = async stage => {
        const filename = `${name}-${stage}.png`;
        await page.screenshot({path: path.join(output, filename), fullPage: true});
        result.screenshots.push(filename);
        const geometry = await page.evaluate(() => ({viewport: window.innerWidth, client: document.documentElement.clientWidth,
            scroll: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth), touch: navigator.maxTouchPoints,
            overflow: [...document.querySelectorAll("main, input, textarea, select, .step-actions, #app-bottom-nav")]
                .filter(element => element.getClientRects().length && getComputedStyle(element).position !== "absolute")
                .map(element => ({id: element.id || element.tagName, left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right}))
                .filter(rect => rect.left < -1 || rect.right > window.innerWidth + 1)}));
        check(stage + " viewport width and no page overflow", geometry.viewport === width && geometry.scroll <= width && geometry.overflow.length === 0, geometry);
    };
    try {
        const initial = await page.goto(origin + "/sales-checkin/", {waitUntil: "networkidle"});
        check("fixture marker", initial.headers()["x-preview-fixture"] === "local-example-only");
        await page.locator("#store-search-results button").first().waitFor({state: "visible"});
        result.capabilities = await page.evaluate(() => ({maxTouchPoints: navigator.maxTouchPoints,
            touchStartAvailable: "ontouchstart" in window, coarsePointer: matchMedia("(pointer: coarse)").matches}));
        await shot("01-home");
        await page.locator("#store-search-results button").first().tap();
        check("real trusted touch event delivered", await page.evaluate(() => window.__webkitTouchEvidence.some(event => event.trusted)));
        await page.waitForFunction(() => !document.querySelector("#visit-step-1-next").disabled);
        const selectedStore = await page.locator("#selected-store-name").textContent();
        check("store tap selects a real row", selectedStore.includes("示例"), selectedStore);
        if (width === 320) {
            await page.locator("#visit-location-continue").tap();
            result.locationScenario = "user-reported inaccurate location: actual visible button";
        } else result.locationScenario = "browser emulated geolocation, not real GPS";
        await page.locator("#visit-step-1-next").tap();
        await page.locator("#customer-name").waitFor({state: "visible"});
        await page.locator("#customer-name").fill(`WebKit ${width} 示例店长`);
        await page.locator("#visit-result").fill(resultText);
        check("browser recording is visible in main visit step", await page.locator("#record-audio-button").isVisible());
        await page.locator("#visit-step-2-next").tap();
        await page.locator("#visit-step-3").waitFor({state: "visible"});
        check("first tap after text input enters photo step", true);
        await page.locator("#storefront-photo").waitFor({state: "attached"});
        await page.locator("#photo-album-input").setInputFiles(photoPath);
        await page.locator("#photo-preview-card").waitFor({state: "visible"});
        const readLocalEvidence = async () => page.evaluate(async text => {
            const request = indexedDB.open("rigour.sales-checkin.v2", 1);
            const db = await new Promise((resolve, reject) => {request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);});
            try {
                const tx = db.transaction(["drafts", "media"], "readonly");
                const read = name => new Promise((resolve, reject) => {const request = tx.objectStore(name).getAll(); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);});
                const [drafts, media] = await Promise.all([read("drafts"), read("media")]);
                const record = drafts.find(row => row.snapshot.visit.visitResult === text);
                const photo = media.find(row => row.draft === record?.key && row.mediaId === "photo");
                try {return {form: Boolean(record), photo: Boolean(photo), photoBytes: photo ? (await photo.file.arrayBuffer()).byteLength : 0};}
                catch (error) {return {form: Boolean(record), photo: Boolean(photo), photoBytes: 0, photoReadError: String(error)};}
            } finally {db.close();}
        }, resultText);
        await page.waitForFunction(() => !document.querySelector("#draft-save-status").textContent.includes("正在保存"));
        result.localBeforeReload = await readLocalEvidence().catch(error => ({error: error.message}));
        check("form reached real IndexedDB", result.localBeforeReload.form);
        const storageLimitation = report.nativeStorageProbe.some(entry => entry.kind !== "string" && !entry.saved);
        if (!result.localBeforeReload.photoBytes) {
            if (!storageLimitation) throw new Error("Photo Blob unavailable despite native storage probe passing");
            result.blockedChecks.push("Photo durability: native WebKit Blob/File → IndexedDB fails before App code is involved");
            check("local media storage failure is disclosed", (await page.locator("#draft-save-status").textContent()).includes("附件未在本机保存"));
        } else check("actual photo bytes readable from IndexedDB", result.localBeforeReload.photoBytes > 0);
        await shot("02-photo-before-reload");
        await page.reload({waitUntil: "networkidle"});
        await page.waitForFunction(text => document.querySelector("#visit-result").value === text, resultText);
        check("reload restores customer", await page.locator("#customer-name").inputValue() === `WebKit ${width} 示例店长`);
        check("reload restores selected store", await page.locator("#selected-store-name").textContent() === selectedStore);
        result.localAfterReload = await readLocalEvidence().catch(error => ({error: error.message}));
        if (await page.locator("#photo-preview-card").isVisible()) {
            check("reload restores actual photo preview state", (await page.locator("#photo-file-name").textContent()).includes("demo-storefront.jpg"));
        } else {
            if (!storageLimitation) throw new Error("Photo not restored despite native storage probe passing");
            if (!result.blockedChecks.length) result.blockedChecks.push("Photo reload restoration blocked by native WebKit Blob/File storage limitation");
            // A visible, actual file-input action restores the user's retained original for the separate current-page upload check.
            await page.locator("#photo-album-input").setInputFiles(photoPath);
            await page.locator("#photo-preview-card").waitFor({state: "visible"});
            result.reselectedRetainedOriginalAfterReload = true;
        }
        await shot("03-restored");
        await page.locator("#privacy-accepted").check();
        await page.locator("#submit-visit-button").tap();
        await page.locator("#success-panel").waitFor({state: "visible", timeout: 30000});
        await Promise.all(pendingResponses);
        const create = responses.find(entry => entry.method === "POST" && [api + "/submissions", api + "/submissions/unverified-location"].includes(entry.path));
        check("created expected submission", create && create.status === 200 && create.body.id && create.body.clientSubmissionId);
        const complete = responses.find(entry => entry.path === `${api}/submissions/${create.body.id}/complete`);
        check("complete confirms same record and submitted status", complete && complete.status === 200 && complete.body.id === create.body.id
            && complete.body.clientSubmissionId === create.body.clientSubmissionId && complete.body.status === "SUBMITTED");
        check("photo uploaded before successful completion", complete.body.uploadedMedia.includes("storefront-photo"));
        check("success screen has no unselected WeChat attachment row", !(await page.locator("#success-wechat-row").isVisible()));
        result.record = {id: complete.body.id, clientSubmissionId: complete.body.clientSubmissionId, submittedAt: complete.body.submittedAt};
        await shot("04-success");
        await page.locator("#success-view-record-button").tap();
        await page.locator("#history-detail-page").waitFor({state: "visible"});
        await page.locator("#history-detail-content").getByText(resultText, {exact: true}).waitFor({state: "visible"});
        check("own exact submitted record detail", true);
        await page.locator(".history-detail-photo img").waitFor({state: "visible"});
        await page.waitForFunction(() => [...document.querySelectorAll(".history-detail-photo img")].some(image => image.complete && image.naturalWidth > 0));
        check("same-origin owner-media route loads photo thumbnail", await page.locator(".history-detail-photo img").first().getAttribute("src").then(src => src.includes(`/submissions/${complete.body.id}/mine/media/storefront-photo`)));
        await shot("06-detail");
        await page.locator(".history-detail-photo").first().tap();
        await page.locator("#history-photo-dialog").waitFor({state: "visible"});
        await shot("07-photo-viewer");
        await page.locator('[data-history-action="close-photo"]').tap();
        await page.locator("#app-back-button").tap();
        await page.locator("#personal-history-page").waitFor({state: "visible"});
        const card = page.locator(".history-record-card").filter({hasText: resultText});
        await card.waitFor({state: "visible", timeout: 30000});
        await shot("08-records");
        await card.tap();
        await page.locator("#history-detail-content").getByText(resultText, {exact: true}).waitFor({state: "visible"});
        check("record list reopens the same submitted visit", true);
        check("no uncaught page exceptions", result.pageErrors.length === 0, result.pageErrors);
        result.passed = result.checks.every(check => check.passed);
    } catch (error) {
        result.passed = false;
        result.error = error.stack;
        result.failureState = await page.evaluate(() => ({events: window.__webkitTouchEvidence, active: document.activeElement.id,
            nextDisabled: document.querySelector("#visit-step-2-next").disabled,
            step3Hidden: document.querySelector("#visit-step-3").hidden,
            formInert: document.querySelector("#visit-form").inert,
            errors: [...document.querySelectorAll(".field__error")].map(element => element.textContent).filter(Boolean),
            draftStatus: document.querySelector("#draft-save-status").textContent,
            storageErrors: window.__webkitStorageErrors, idbReads: window.__webkitIdbReads}));
        try {await page.screenshot({path: path.join(output, `${name}-FAILED.png`), fullPage: true}); result.screenshots.push(`${name}-FAILED.png`);} catch (_) {}
        console.error(`${name}: ${error.message}`);
    } finally {
        result.completedAt = new Date().toISOString();
        await closeContext();
        fs.writeFileSync(path.join(output, "results.json"), JSON.stringify(report, null, 2) + "\n");
    }
}

(async () => {
    const browser = await webkit.launch({headless: true});
    report.browserVersion = browser.version();
    try {report.nativeStorageProbe = await nativeStorageProbe(browser); for (const width of [390, 320]) await runCase(browser, width);}
    finally {await browser.close();}
    report.completedAt = new Date().toISOString();
    report.uiPassed = report.cases.every(test => test.passed && test.checks.every(check => check.passed));
    report.blockedChecks = report.cases.flatMap(test => test.blockedChecks.map(reason => ({case: test.name, reason})));
    report.passed = report.uiPassed && report.blockedChecks.length === 0;
    report.status = report.passed ? "PASS" : report.uiPassed ? "BLOCKED_BY_RUNTIME" : "FAIL";
    fs.writeFileSync(path.join(output, "results.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(JSON.stringify({status: report.status, passed: report.passed, uiPassed: report.uiPassed, browserVersion: report.browserVersion,
        cases: report.cases.map(test => ({name: test.name, passed: test.passed, checks: test.checks.length, blockedChecks: test.blockedChecks, error: test.error})), output}, null, 2));
    if (!report.passed) process.exitCode = report.uiPassed ? 2 : 1;
})().catch(error => {console.error(error); process.exitCode = 1;});
