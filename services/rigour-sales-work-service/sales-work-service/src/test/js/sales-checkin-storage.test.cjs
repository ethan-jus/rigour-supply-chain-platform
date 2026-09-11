const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/sales-checkin/storage.js'), 'utf8');
const tick = () => new Promise((resolve) => setImmediate(resolve));
const owner = 'tenant-one:sales-one';
const snapshot = (id = 'visit-one') => ({visit: {salespersonId: 'sales-one'}, submission: {clientSubmissionId: id, status: 'DRAFT', audioSegments: []}});

// A small event-controlled IndexedDB boundary. Requests and transaction commit are separate events;
// staged writes only become durable on commit and are discarded on abort. Production scheduling is not copied.
function harness() {
    let sequence = 0; let now = 1000000;
    const timers = new Map(); const openRequests = []; const connections = []; const committed = new Map();
    let openFailure = null;
    function advance(milliseconds) {
        const until = now + milliseconds;
        while (true) {
            const next = [...timers.entries()].filter(([, timer]) => timer.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
            if (!next) break;
            now = next[1].at; timers.delete(next[0]); next[1].fn();
        }
        now = until;
    }
    function database() {
        const db = {closed: false, closeCount: 0, transactions: [], schemaWrites: 0,
            close() { this.closed = true; this.closeCount += 1; },
            createObjectStore() { this.schemaWrites += 1; return {createIndex() {}}; },
            transaction(stores, mode) {
                if (this.closed) throw new Error('connection is closed');
                const tx = {stores, mode, requests: [], puts: [], deletes: [], staged: [], abortCount: 0, aborted: false, completed: false,
                    abort() {
                        this.abortCount += 1;
                        if (this.completed) throw new Error('already completed');
                        this.aborted = true; this.staged = []; this.onabort?.();
                    },
                    commit() {
                        if (this.aborted) return;
                        this.completed = true;
                        this.staged.forEach((operation) => operation()); this.staged = []; this.oncomplete?.();
                    },
                    objectStore(name) {
                        const request = (kind, value) => { const item = {kind, value, result: undefined}; tx.requests.push(item); return item; };
                        return {
                            index(index) { return {getAll(value) { return request(`${name}.${index}.getAll`, value); }, openCursor(value) { return request(`${name}.${index}.cursor`, value); }}; },
                            openCursor() { return request(`${name}.cursor`); },
                            put(value) { if (tx.aborted) throw new Error('aborted'); tx.puts.push(value); tx.staged.push(() => committed.set(value.key, value)); },
                            delete(key) { if (tx.aborted) throw new Error('aborted'); tx.deletes.push(key); tx.staged.push(() => committed.delete(key)); }
                        };
                    }
                };
                this.transactions.push(tx); return tx;
            }};
        connections.push(db); return db;
    }
    const indexedDB = {open() {
        if (openFailure) { const error = openFailure; openFailure = null; throw error; }
        const request = {}; openRequests.push(request); return request;
    }};
    const window = {indexedDB, setTimeout(fn, milliseconds) { timers.set(++sequence, {fn, at: now + milliseconds}); return sequence; },
        clearTimeout(id) { timers.delete(id); }};
    vm.runInNewContext(source, {window, Blob, Date: {now: () => now}, Set, Map});
    const succeed = (request, result) => { request.result = result; request.onsuccess?.(); };
    const openLatest = () => { const db = database(); succeed(openRequests.at(-1), db); return db; };
    return {api: window.SalesCheckinDraftStore, timers, openRequests, connections, committed, advance, succeed, openLatest, database,
        throwOnOpen(error) { openFailure = error; }, removeIndexedDB() { window.indexedDB = null; }};
}
function observed(promise) {
    const state = {settled: false, value: undefined, error: undefined};
    state.done = promise.then((value) => { state.settled = true; state.value = value; }, (error) => { state.settled = true; state.error = error; });
    return state;
}
async function beginSave(h, id) {
    const outcome = observed(h.api.save(owner, snapshot(id)));
    const db = h.openLatest(); await tick();
    return {outcome, db, tx: db.transactions.at(-1)};
}

test('an open request with no events stops after 10s, and a late successful connection is closed', async () => {
    const h = harness(); const outcome = observed(h.api.save(owner, snapshot()));
    h.advance(9999); await tick(); assert.equal(outcome.settled, false);
    h.advance(1); await outcome.done; assert.equal(outcome.error.code, 'LOCAL_STORAGE_TIMEOUT');
    const late = h.openLatest(); assert.equal(late.closed, true); assert.equal(late.transactions.length, 0);
    assert.equal(h.timers.size, 0);
    const next = observed(h.api.save(owner, snapshot('next'))); assert.equal(h.openRequests.length, 2);
    const db = h.openLatest(); await tick(); const tx = db.transactions[0]; h.succeed(tx.requests[0], []); tx.commit(); await next.done;
    assert.equal(next.error, undefined); assert.equal(h.committed.has(owner + '|next'), true); assert.equal(h.timers.size, 0);
});

test('a late upgrade after an open timeout cannot create stores', async () => {
    const h = harness(); const outcome = observed(h.api.list(owner));
    h.advance(10000); await outcome.done;
    const request = h.openRequests[0]; const db = h.database(); let aborted = 0;
    request.result = db; request.transaction = {abort() { aborted += 1; }}; request.onupgradeneeded();
    assert.equal(db.schemaWrites, 0); assert.equal(aborted, 1); assert.equal(db.closed, true);
});

test('blocked and synchronous failed opens are retryable and do not leave timers or cached failed attempts', async () => {
    const h = harness(); h.throwOnOpen(new Error('browser denied'));
    const first = observed(h.api.list(owner)); await first.done; assert.match(first.error.message, /browser denied/); assert.equal(h.timers.size, 0);
    const second = observed(h.api.list(owner)); h.openRequests[0].onblocked(); await second.done; assert.equal(h.timers.size, 0);
    const third = observed(h.api.list(owner)); assert.equal(h.openRequests.length, 2);
    const db = h.openLatest(); await tick(); const tx = db.transactions[0]; h.succeed(tx.requests[0], []); tx.commit(); await third.done;
    assert.equal(third.error, undefined); assert.deepEqual(Array.from(third.value), []); assert.equal(h.timers.size, 0);
});

test('metadata save reports success only after transaction commit and clears its deadline', async () => {
    const h = harness(); const {outcome, tx} = await beginSave(h);
    h.succeed(tx.requests[0], []); await tick();
    assert.equal(tx.puts.length, 1); assert.equal(h.committed.size, 0); assert.equal(outcome.settled, false);
    tx.commit(); await outcome.done;
    assert.equal(outcome.error, undefined); assert.equal(h.committed.size, 1); assert.equal(h.timers.size, 0);
    h.advance(100000); assert.equal(tx.abortCount, 0);
});

test('metadata timeout aborts at 15s and ignores a late request callback that would otherwise enqueue put', async () => {
    const h = harness(); const {outcome, tx, db} = await beginSave(h);
    h.advance(14999); await tick(); assert.equal(outcome.settled, false);
    h.advance(1); await outcome.done;
    assert.equal(outcome.error.code, 'LOCAL_STORAGE_TIMEOUT'); assert.equal(tx.abortCount, 1); assert.equal(db.closed, true);
    h.succeed(tx.requests[0], []); tx.oncomplete?.(); await tick();
    assert.equal(tx.puts.length, 0); assert.equal(h.committed.size, 0); assert.equal(outcome.error.code, 'LOCAL_STORAGE_TIMEOUT'); assert.equal(h.timers.size, 0);
});

test('a queued write without commit is rolled back on timeout, and the next save opens a fresh connection', async () => {
    const h = harness(); const {outcome, tx} = await beginSave(h);
    h.succeed(tx.requests[0], []); assert.equal(tx.puts.length, 1);
    h.advance(15000); await outcome.done; tx.commit(); assert.equal(h.committed.size, 0);
    const next = observed(h.api.save(owner, snapshot('second'))); assert.equal(h.openRequests.length, 2);
    const db = h.openLatest(); await tick(); const second = db.transactions[0]; h.succeed(second.requests[0], []); second.commit(); await next.done;
    assert.equal(next.error, undefined); assert.equal(h.committed.has(owner + '|second'), true); assert.equal(h.committed.has(owner + '|visit-one'), false);
});

test('read transactions have a 10s deadline even if request success arrives without transaction completion', async () => {
    const h = harness(); const outcome = observed(h.api.list(owner)); const db = h.openLatest(); await tick(); const tx = db.transactions[0];
    h.succeed(tx.requests[0], [{owner, key: owner + '|visit-one', updatedAt: 1000000, snapshot: snapshot()}]);
    await tick(); assert.equal(outcome.settled, false);
    h.advance(10000); await outcome.done;
    assert.equal(outcome.error.code, 'LOCAL_STORAGE_TIMEOUT'); assert.equal(db.closed, true); assert.equal(tx.abortCount, 1); assert.equal(h.timers.size, 0);
});

test('a normal read retains owner scope and clears timers on transaction completion', async () => {
    const h = harness(); const outcome = observed(h.api.mediaFor(owner, 'visit-one')); const db = h.openLatest(); await tick(); const tx = db.transactions[0];
    assert.equal(tx.mode, 'readonly'); assert.equal(tx.requests[0].value, owner + '|visit-one');
    h.succeed(tx.requests[0], [{mediaId: 'audio:one'}]); tx.commit(); await outcome.done;
    assert.equal(outcome.value[0].mediaId, 'audio:one'); assert.equal(h.timers.size, 0); assert.equal(db.closed, false);
});

test('Blob writes receive 60s, then abort and suppress late cursor put', async () => {
    const h = harness(); const outcome = observed(h.api.saveMedia(owner, 'visit-one', 'photo', new Blob(['image'])));
    const db = h.openLatest(); await tick(); const tx = db.transactions[0];
    h.advance(15000); await tick(); assert.equal(outcome.settled, false);
    h.advance(44999); await tick(); assert.equal(outcome.settled, false);
    h.advance(1); await outcome.done; assert.equal(outcome.error.code, 'LOCAL_STORAGE_TIMEOUT'); assert.equal(tx.abortCount, 1);
    h.succeed(tx.requests[0], null); assert.equal(tx.puts.length, 0); assert.equal(db.closed, true); assert.equal(h.timers.size, 0);
});

test('completed Blob transactions clear their long deadline and preserve owner/draft keys', async () => {
    const h = harness(); const outcome = observed(h.api.saveMedia(owner, 'visit-one', 'audio:one', new Blob(['sample'])));
    const db = h.openLatest(); await tick(); const tx = db.transactions[0]; h.succeed(tx.requests[0], null); tx.commit(); await outcome.done;
    const entry = h.committed.get(owner + '|visit-one|audio:one');
    assert.equal(entry.owner, owner); assert.equal(entry.draft, owner + '|visit-one'); assert.equal(entry.size, 6); assert.equal(h.timers.size, 0);
});

test('late cursor cleanup cannot delete media or advance cursors after timeout', async () => {
    const h = harness(); const outcome = observed(h.api.remove(owner, 'visit-one'));
    const db = h.openLatest(); await tick(); const tx = db.transactions[0];
    h.advance(15000); await outcome.done;
    let deleted = 0; let continued = 0;
    h.succeed(tx.requests[0], {delete() { deleted += 1; }, continue() { continued += 1; }});
    assert.equal(deleted, 0); assert.equal(continued, 0); assert.equal(tx.abortCount, 1); assert.equal(h.timers.size, 0);
});

test('transaction failure is not saved success and does not leak its deadline', async () => {
    const h = harness(); const {outcome, tx} = await beginSave(h);
    tx.error = new Error('quota exceeded'); tx.onerror(); await outcome.done;
    h.succeed(tx.requests[0], []); tx.oncomplete?.();
    assert.match(outcome.error.message, /quota exceeded/); assert.equal(tx.puts.length, 0); assert.equal(h.timers.size, 0);
});

test('version change closes the cached connection and the next operation reopens', async () => {
    const h = harness(); const {outcome, tx, db} = await beginSave(h); h.succeed(tx.requests[0], []); tx.commit(); await outcome.done;
    db.onversionchange(); assert.equal(db.closed, true);
    const read = observed(h.api.list(owner)); assert.equal(h.openRequests.length, 2);
    const fresh = h.openLatest(); await tick(); const next = fresh.transactions[0]; h.succeed(next.requests[0], []); next.commit(); await read.done;
    assert.equal(read.error, undefined); assert.equal(h.timers.size, 0);
});

test('unavailable IndexedDB rejects immediately without starting a deadline', async () => {
    const h = harness(); h.removeIndexedDB(); const outcome = observed(h.api.list(owner)); await outcome.done;
    assert.match(outcome.error.message, /不支持/); assert.equal(h.timers.size, 0); assert.equal(h.openRequests.length, 0);
});
