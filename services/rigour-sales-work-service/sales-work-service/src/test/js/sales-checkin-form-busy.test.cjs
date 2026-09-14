const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {test} = require("node:test");

const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/sales-checkin/app.js"), "utf8");
function section(from, to) {
    const start = source.indexOf(from);
    const end = source.indexOf(to, start);
    assert.ok(start >= 0 && end > start, `production section ${from}`);
    return source.slice(start, end);
}
const production = [
    section("    const LOCKED_BUSINESS_SELECTORS", "    let storePickerOpen"),
    section("    function lockIdentitySelectors()", "    async function switchIdentity()"),
    section("    function renderBusinessLock()", "    function startNewSubmission("),
    section("    function persistFromForm()", "    function syncStateFromForm()")
].join("\n");

function harness(native = false) {
    const elements = new Map();
    const make = (id, disabled = false) => {
        const attributes = new Map();
        const listeners = new Map();
        const element = {id, disabled, dataset: {}, hidden: false, controls: [],
            classList: {toggle() {}},
            setAttribute(name, value) {attributes.set(name, value);},
            getAttribute(name) {return attributes.get(name);},
            removeAttribute(name) {attributes.delete(name);},
            querySelectorAll() {return this.controls;},
            addEventListener(type, listener, capture) {assert.equal(capture, true); listeners.set(type, listener);},
            removeEventListener(type, listener, capture) {
                assert.equal(capture, true); assert.equal(listeners.get(type), listener); listeners.delete(type);
            },
            dispatch(type, cancelable = true) {
                const event = {cancelable, prevented: false, stopped: false,
                    preventDefault() {this.prevented = true;}, stopImmediatePropagation() {this.stopped = true;}};
                listeners.get(type)?.(event); return event;
            }, listeners};
        elements.set(id, element); return element;
    };
    const $ = id => elements.get(id) || make(id);
    const visit = $("#visit-form"), store = $("#store-form");
    if (native) {visit.inert = false; store.inert = false;}
    const customer = $("#customer-name");
    const fixed = make("#state-disabled", true);
    const photo = $("#photo-control");
    const visitSubmit = $("#submit-visit-button"), storeSubmit = $("#submit-store-button");
    visit.controls = [customer, fixed, photo, $("#visit-city"), $("#visit-salesperson"), visitSubmit];
    store.controls = [$("#store-name"), $("#store-city"), $("#store-salesperson"), storeSubmit];
    $("#visit-salesperson").disabled = $("#store-salesperson").disabled = true;
    const calls = {sync: 0, persist: 0, render: 0, photoBlocked: false};
    const state = {submitting: false, preparingSubmission: false, completed: false,
        identity: {authenticated: true}, ui: {}, submission: {businessLocked: false, uploadedMedia: []}};
    const context = {state, $, MEDIA: {wechat: "wechat-screenshot"},
        setStableText(element, value) {element.textContent = value;},
        cancelLocationCapture() {}, abortPoiSearch() {}, hideStoreResults() {}, hidePoiResults() {},
        renderPhotos() {photo.disabled = calls.photoBlocked;},
        renderFlowSteps() {visitSubmit.disabled = state.submitting;},
        renderFlowActions() {calls.render++;}, syncStateFromForm() {calls.sync++;}, persistDraft() {calls.persist++;}};
    vm.runInNewContext(`${production}\nglobalThis.api = {setFormsDisabled, setLegacyFormBusy, persistFromForm, renderBusinessLock};`, context);
    return {api: context.api, state, calls, $, make, visit, store, customer, fixed, photo, visitSubmit, storeSubmit};
}

test("old Safari disables both forms and restores each original disabled state", () => {
    const h = harness(); h.state.submitting = true; h.api.setFormsDisabled(true);
    for (const form of [h.visit, h.store]) {
        assert.equal(form.getAttribute("aria-busy"), "true");
        assert.ok(form.controls.every(control => control.disabled));
    }
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, false);
    assert.equal(h.$("#store-name").disabled, false);
    assert.equal(h.fixed.disabled, true);
    assert.equal(h.$("#visit-salesperson").disabled, true);
    assert.equal(h.$("#store-salesperson").disabled, true);
    assert.equal(h.visitSubmit.disabled, false); assert.equal(h.storeSubmit.disabled, false);
    assert.equal(h.visit.getAttribute("aria-busy"), "false");
    assert.equal(h.visit.getAttribute("inert"), undefined);
});

test("GPS preparation followed by submit does not overwrite the pre-busy snapshot", () => {
    const h = harness(); h.state.submitting = true;
    h.api.setFormsDisabled(true); h.api.setFormsDisabled(true);
    const added = h.make("#later-control", true); h.visit.controls.push(added);
    h.api.setFormsDisabled(true);
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, false); assert.equal(added.disabled, true);
    assert.equal(h.visit.listeners.size, 0);
});

test("an existing business lock survives a failed submit and fallback restoration", () => {
    const h = harness(); h.state.submission.businessLocked = true; h.api.renderBusinessLock();
    assert.equal(h.customer.dataset.businessLocked, "true");
    h.state.submitting = true; h.api.setFormsDisabled(true);
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, true); assert.equal(h.customer.dataset.businessLocked, "true");
});

test("a new server business lock gains its marker after temporary restore and can later be released", () => {
    const h = harness(); h.state.submitting = true; h.api.setFormsDisabled(true);
    h.state.submission.serverId = "new-server-record"; h.api.renderBusinessLock();
    assert.equal(h.customer.dataset.businessLocked, undefined);
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, true); assert.equal(h.customer.dataset.businessLocked, "true");
    h.state.submission = {businessLocked: false, uploadedMedia: []}; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, false); assert.equal(h.customer.dataset.businessLocked, undefined);
});

test("media restrictions calculated during submit are reapplied after restoring temporary disabled values", () => {
    const h = harness(); h.state.submitting = true; h.api.setFormsDisabled(true);
    h.calls.photoBlocked = true; h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.photo.disabled, true); assert.equal(h.fixed.disabled, true);
});

test("fallback capture blocks controls reenabling during asynchronous rendering and removes handlers on release", () => {
    const h = harness(); h.state.submitting = true; h.api.setFormsDisabled(true);
    h.customer.disabled = false;
    for (const type of ["click", "keydown", "beforeinput", "input", "change", "submit"]) {
        const event = h.visit.dispatch(type);
        assert.equal(event.prevented, true); assert.equal(event.stopped, true);
    }
    const noncancelable = h.visit.dispatch("input", false);
    assert.equal(noncancelable.prevented, false); assert.equal(noncancelable.stopped, true);
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.visit.dispatch("click").stopped, false);
    assert.equal(h.store.listeners.size, 0);
});

test("native inert browsers retain their original control states and install no fallback guards", () => {
    const h = harness(true); h.state.submitting = true; h.api.setFormsDisabled(true);
    assert.equal(h.customer.disabled, false); assert.equal(h.fixed.disabled, true);
    assert.equal(h.visitSubmit.disabled, true); assert.equal(h.storeSubmit.disabled, true);
    assert.equal(h.visit.listeners.size, 0); assert.equal(h.store.listeners.size, 0);
    h.state.submitting = false; h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, false); assert.equal(h.fixed.disabled, true);
});

test("no fallback snapshot is required when initializing or reopening the form", () => {
    const h = harness(); h.api.setFormsDisabled(false); h.api.setFormsDisabled(false);
    assert.equal(h.customer.disabled, false); assert.equal(h.fixed.disabled, true);
    assert.equal(h.visit.listeners.size, 0);
});

test("persistFromForm ignores busy input events but resumes normal edit and save after failure", () => {
    const h = harness(); h.state.submitting = true; h.api.persistFromForm();
    assert.equal(h.calls.sync, 0); assert.equal(h.calls.persist, 0); assert.equal(h.calls.render, 0);
    h.state.submitting = false; h.api.persistFromForm();
    assert.equal(h.calls.sync, 1); assert.equal(h.calls.persist, 1); assert.equal(h.calls.render, 1);
});
