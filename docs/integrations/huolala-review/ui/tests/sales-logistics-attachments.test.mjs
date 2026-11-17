import test from "node:test";
import assert from "node:assert/strict";
import { attachmentGroups } from "../src/sales-logistics/attachment-model.mjs";
import {
    initialState,
    syncCompleted,
    transition,
} from "../src/sales-logistics/model.mjs";
test("货拉拉资料分账单、回单和站点装卸，不混成一个空态", () => {
    const s = initialState(),
        r = s.records.find((r) => r.sourceId === "h1"),
        p = s.platform.find((p) => p.id === "h1");
    const groups = attachmentGroups(r, p);
    assert.deepEqual(
        groups.map((g) => g.title),
        ["账单凭证", "运输回单", "装卸货照片"],
    );
    assert.deepEqual(
        groups.map((g) => g.files.length),
        [1, 1, 2],
    );
    assert.match(groups[2].files[0].group, /第1站.*装货/);
    assert.match(groups[2].files[1].group, /第3站.*卸货/);
    assert.equal(groups[2].files[1].state, "FAILED");
    assert.equal(p.receiptInfo["回单类型"], "拍照回单");
});
test("失败文件补取结果不改确认费用，重复同步附件数量不增加", () => {
    let s = initialState();
    s.records = s.records.map(r => r.id === "rh-h1" ? {...r, manuallyEdited: true} : r);
    const r = s.records.find((r) => r.sourceId === "h1"),
        p = s.platform.find((p) => p.id === "h1");
    const before = JSON.stringify(s);
    const results = {
        [r.id + ":unloading-1"]: {
            state: "READY",
            url: "/review-assets/unloading.svg",
            fetchedAt: "本次获取",
        },
    };
    assert.equal(attachmentGroups(r, p, results)[2].files[1].state, "READY");
    assert.equal(JSON.stringify(s), before);
    s = syncCompleted(syncCompleted(s));
    assert.deepEqual(
        attachmentGroups(
            s.records.find((r) => r.sourceId === "h1"),
            s.platform.find((p) => p.id === "h1"),
            results,
        ).map((g) => g.files.length),
        [1, 1, 2],
    );
    assert.equal(s.records.find((r) => r.sourceId === "h1").amount, 13764);
});
test("平台未提供资料仍显示三个分组，手工凭证保持独立", () => {
    const s = initialState();
    const groups = attachmentGroups(
        s.records.find((r) => r.sourceId === "h2"),
        s.platform.find((p) => p.id === "h2"),
    );
    assert.equal(groups.length, 3);
    assert.ok(
        groups.every((g) => g.files.length === 0 && g.empty.includes("未提供")),
    );
    const manual = attachmentGroups({
        id: "m1",
        vouchers: [{ name: "手工凭证.png", url: "blob:example" }],
    });
    assert.deepEqual(manual.map(g => g.title), ["费用凭证", "运输回单", "装货照片", "卸货照片"]);
    assert.equal(manual[0].files[0].state, "READY");
});
test("同一账单文件被费用明细重复引用时只展示一份", () => {
    const file = { id: "same", name: "费用凭证.svg", state: "READY" };
    const groups = attachmentGroups(
        { id: "r" },
        { fees: [{ name: "路桥费", vouchers: [file] }], billVouchers: [file] },
    );
    assert.equal(groups[0].files.length, 1);
});
