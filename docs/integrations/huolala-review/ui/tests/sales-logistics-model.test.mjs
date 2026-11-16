import test from "node:test";
import assert from "node:assert/strict";
import {
    orders,
    cents,
    allocate,
    initialState,
    platformExamples,
    ingestCompleted,
    saveManual,
    saveManualForOrder,
    linkTransport,
    selectableSalesTransports,
    transition,
    syncCompleted,
} from "../src/sales-logistics/model.mjs";
import {
    dictionaryOptions,
    carrierEntryMode,
    reviewDictionaries,
    carrierCode,
} from "../src/sales-logistics/dictionary-examples.mjs";
const manual = {
    transportType: "销售订单配送",
    mode: "第三方配送",
    company: "顺丰",
    tracking: "SF002",
    warehouse: "w1",
    date: "2026-11-12",
    to: "北辰台球俱乐部",
    contact: "周先生",
    phone: "13800000001",
    address: "示例地址",
    fees: [],
    amount: 3500,
    payment: "未付款",
    owner: "林明",
    remark: "",
    vouchers: [{ name: "运费凭证.pdf", size: 1024 }],
};
const total = (s) =>
    s.records
        .filter((r) => r.status === "已确认")
        .reduce((n, r) => n + r.amount, 0);
test("销售侧手工新增一笔运输并关联当前单，费用仅一份", () => {
    const s = initialState(),
        a = saveManualForOrder(s, manual, "s1");
    assert.equal(a.state.records.length, s.records.length + 1);
    assert.deepEqual(a.record.links, ["s1"]);
    assert.deepEqual(a.record.allocations, { s1: 3500 });
    assert.equal(a.record.status, "待确认");
    assert.equal(a.record.payment, "未付款");
    assert.equal(a.record.transportType, "销售订单配送");
    assert.equal(a.record.vouchers.length, 1);
    assert.equal(s.records.length, 3);
});
test("销售侧手工录入缺凭证或订单无效不产生孤立运输", () => {
    const s = initialState();
    assert.throws(
        () => saveManualForOrder(s, { ...manual, vouchers: [] }, "s1"),
        /凭证/,
    );
    assert.throws(() => saveManualForOrder(s, manual, "s4"), /有效/);
    assert.equal(s.records.length, 3);
});
test("销售侧自营可无快递号，但仍需凭证且自动关联", () => {
    const a = saveManualForOrder(
        initialState(),
        {
            ...manual,
            mode: "自营配送",
            company: "",
            tracking: "",
            amount: 0,
            remark: "无额外支出",
        },
        "s1",
    );
    assert.equal(a.record.company, "自营");
    assert.equal(a.record.amount, 0);
    assert.deepEqual(a.record.links, ["s1"]);
});
test("销售侧不能手工伪造货拉拉同步账单", () =>
    assert.throws(
        () =>
            saveManualForOrder(
                initialState(),
                { ...manual, company: "货拉拉" },
                "s1",
            ),
        /已同步/,
    ));
test("已完成货拉拉直接进入运输记录，未完成不入库", () => {
    const s = initialState();
    assert.equal(s.records.filter((r) => r.sourceId).length, 2);
    assert.equal(
        s.records.some((r) => r.sourceId === "h3"),
        false,
    );
    assert.deepEqual(s.records.find((r) => r.sourceId === "h1").links, []);
});
test("重复同步不重复运输或费用", () => {
    const s = syncCompleted(initialState()),
        a = syncCompleted(s);
    assert.equal(s.records.length, 4);
    assert.deepEqual(a, s);
});
test("销售单关联已有运输不新增费用", () => {
    const s = initialState(),
        a = linkTransport(s, "rh-h1", "s1");
    assert.equal(a.state.records.length, s.records.length);
    assert.deepEqual(a.record.links, ["s1"]);
    assert.equal(a.record.allocations.s1, 13764);
});
test("重复关联幂等，只有一个关系", () => {
    const a = linkTransport(initialState(), "rh-h1", "s1");
    const b = linkTransport(a.state, "rh-h1", "s1");
    assert.equal(b.repeated, true);
    assert.deepEqual(b.state, a.state);
});
test("一车两单按分分摊，总额只确认一次", () => {
    let s = linkTransport(initialState(), "rh-h1", "s1").state;
    const a = linkTransport(s, "rh-h1", "s2");
    assert.deepEqual(a.record.allocations, { s1: 8258, s2: 5506 });
    assert.equal(total(transition(a.state, "rh-h1", "确认")), 13764);
});
test("确认后不能改变关系，撤销后再关联", () => {
    let s = transition(initialState(), "rh-h1", "确认");
    assert.equal(total(s), 13764);
    assert.throws(() => linkTransport(s, "rh-h1", "s1"), /撤销/);
    s = transition(s, "rh-h1", "撤销", "补归属");
    assert.equal(linkTransport(s, "rh-h1", "s1").record.links.length, 1);
});
test("取消销售单不能关联", () =>
    assert.throws(() => linkTransport(initialState(), "rh-h1", "s4"), /有效/));
test("未确认来源变价更新，关联不丢", () => {
    let s = linkTransport(initialState(), "rh-h1", "s1").state;
    s = ingestCompleted(s, [
        { ...platformExamples[0], amount: 14264, version: 2 },
    ]);
    const r = s.records.find((r) => r.sourceId === "h1");
    assert.equal(r.amount, 14264);
    assert.deepEqual(r.links, ["s1"]);
    assert.equal(r.allocations.s1, 14264);
});
test("已确认成本不被平台新账单覆盖", () => {
    let s = linkTransport(initialState(), "rh-h1", "s1").state;
    s = transition(s, "rh-h1", "确认");
    s = ingestCompleted(s, [
        { ...platformExamples[0], amount: 14264, version: 2 },
    ]);
    const r = s.records.find((r) => r.sourceId === "h1");
    assert.equal(r.amount, 13764);
    assert.equal(r.platformAmount, 14264);
    assert.equal(r.billChanged, true);
    assert.equal(r.allocations.s1, 13764);
    assert.equal(total(s), 13764);
});
test("手工物流无需先关联销售单，必须带凭证", () => {
    assert.throws(
        () => saveManual(initialState(), { ...manual, vouchers: [] }),
        /凭证/,
    );
    const a = saveManual(initialState(), manual);
    assert.equal(a.record.amount, 3500);
    assert.deepEqual(a.record.links, []);
    assert.equal(a.record.payment, "未付款");
});
test("手工费用分项含优惠不重计", () => {
    const a = saveManual(initialState(), {
        ...manual,
        fees: [
            { name: "运费", amount: 3500 },
            { name: "保价", amount: 200 },
            { name: "优惠", amount: -200 },
        ],
    });
    assert.equal(a.record.amount, 3500);
});
test("编辑不能删除全部凭证", () => {
    const a = saveManual(initialState(), manual);
    assert.throws(
        () =>
            saveManual(a.state, {
                ...manual,
                editId: a.record.id,
                vouchers: [],
            }),
        /凭证/,
    );
});
test("订货宝缺运费不能确认，补金额也必须附费用凭证", () => {
    assert.throws(() => transition(initialState(), "rd1", "确认"), /补齐/);
    assert.throws(
        () =>
            saveManual(initialState(), {
                ...manual,
                editId: "rd1",
                tracking: "SF202611120001",
                vouchers: [],
            }),
        /凭证/,
    );
});
test("只有已作废可删除", () => {
    let s = initialState();
    assert.throws(() => transition(s, "rd1", "删除", "误录"), /不支持/);
    s = transition(s, "rd1", "作废", "误录");
    assert.equal(transition(s, "rd1", "删除", "清理").records.length, 2);
});
test("金额精度、未知和零分开", () => {
    assert.equal(cents(""), null);
    assert.equal(cents("0"), 0);
    assert.equal(cents("-0.01"), -1);
    assert.throws(() => cents("1.111"));
    assert.deepEqual(allocate(10001, orders.slice(0, 3)), {
        s1: 4688,
        s2: 3125,
        s3: 2188,
    });
    assert.deepEqual(allocate(100, []), {});
});

test("同公司相同单号去除空格仍拦截，其他公司不同票可登记", () => {
    const a = saveManualForOrder(initialState(), manual, "s1");
    assert.throws(
        () =>
            saveManualForOrder(
                a.state,
                { ...manual, tracking: "  SF002  " },
                "s2",
            ),
        /已有运输/,
    );
    const b = saveManualForOrder(a.state, { ...manual, company: "极兔" }, "s2");
    assert.equal(b.state.records.length, a.state.records.length + 1);
});
test("自营无快递号的不同运输可分别登记，不被空单号误判重复", () => {
    const d = { ...manual, mode: "自营配送", company: "", tracking: "" };
    const a = saveManualForOrder(initialState(), d, "s1");
    const b = saveManualForOrder(a.state, d, "s2");
    assert.equal(b.state.records.length, 5);
    assert.equal(b.record.tracking, "");
});
test("当前销售候选只提供货拉拉完成单，其他公司不提供同步选择", () => {
    const s = initialState();
    assert.equal(
        selectableSalesTransports(s, { mode: "第三方配送", company: "货拉拉" })
            .length,
        2,
    );
    assert.equal(
        selectableSalesTransports(s, { mode: "第三方配送", company: "顺丰" })
            .length,
        0,
    );
    assert.equal(
        selectableSalesTransports(s, { mode: "自营配送", company: "货拉拉" })
            .length,
        0,
    );
    assert.equal(
        selectableSalesTransports(s, {
            mode: "第三方配送",
            company: "货拉拉",
            employee: "林明",
        })[0].sourceId,
        "h1",
    );
});
test("字典新增公司默认手工，停用项不进入新建选项，名称更改不改变接入能力", () => {
    const dictionaries = {
        ...reviewDictionaries,
        COST_CARRIER: [
            ...reviewDictionaries.COST_CARRIER,
            { code: "NEW_COURIER", label: "新快递公司", enabled: true },
            { code: "OLD", label: "已停用公司", enabled: false },
        ].map((item) =>
            item.code === "HLL" ? { ...item, label: "货拉拉企业运输" } : item,
        ),
    };
    assert.ok(
        dictionaryOptions("COST_CARRIER", dictionaries).some(
            (item) => item.code === "NEW_COURIER",
        ),
    );
    assert.ok(
        !dictionaryOptions("COST_CARRIER", dictionaries).some(
            (item) => item.code === "OLD",
        ),
    );
    assert.equal(carrierEntryMode("NEW_COURIER"), "MANUAL");
    assert.equal(
        carrierEntryMode(carrierCode("货拉拉企业运输", dictionaries)),
        "SELECT_SYNCED",
    );
});
test("订货宝物流已自动关联原销售单，重复关联不生成记录或费用", () => {
    const s = initialState();
    const row = s.records.find((r) => r.id === "rd1");
    assert.deepEqual(row.links, ["s3"]);
    assert.equal(row.transportType, "销售订单配送");
    assert.equal(row.amount, null);
    assert.deepEqual(linkTransport(s, "rd1", "s3").state, s);
});
test("销售关联自动设为销售订单配送，不误改调拨用途", () => {
    const s = initialState();
    assert.equal(
        linkTransport(s, "rh-h1", "s1").record.transportType,
        "销售订单配送",
    );
    const other = {
        ...s,
        records: s.records.map((r) =>
            r.id === "rh-h1" ? { ...r, transportType: "仓间调拨" } : r,
        ),
    };
    assert.throws(() => linkTransport(other, "rh-h1", "s1"), /核对运输类型/);
});

test("无仓库直发可保存真实发货方，选择仓库后手工更正也原样保留", () => {
    const a = saveManualForOrder(
        initialState(),
        {
            ...manual,
            warehouse: "",
            shipper: "厂家仓",
            shippingContact: "王先生",
            shippingPhone: "13800000009",
            shippingAddress: "实际厂家发货地址",
        },
        "s1",
    );
    assert.equal(a.record.from, "厂家仓");
    assert.equal(a.record.shippingAddress, "实际厂家发货地址");
    const b = saveManualForOrder(
        initialState(),
        {
            ...manual,
            shipper: "本次装货点",
            shippingAddress: "本次实际装货地址",
        },
        "s1",
    );
    assert.equal(b.record.warehouse, "w1");
    assert.equal(b.record.from, "本次装货点");
    assert.equal(b.record.shippingAddress, "本次实际装货地址");
});
test("实际发货方或地址被清空时不能用仓库档案悄悄补回", () => {
    assert.throws(
        () =>
            saveManualForOrder(
                initialState(),
                { ...manual, shippingAddress: "" },
                "s1",
            ),
        /发货地址/,
    );
    assert.throws(
        () =>
            saveManualForOrder(
                initialState(),
                { ...manual, shipper: "" },
                "s1",
            ),
        /发货方/,
    );
});

test("订货宝重复同步不重建且不覆盖已补费用与凭证", async () => {
    const { syncDhbExample } = await import("../src/sales-logistics/model.mjs");
    const saved = saveManual(initialState(), {
        ...manual,
        editId: "rd1",
        tracking: "SF202611120001",
    });
    const result = syncDhbExample(saved.state);
    assert.equal(result, saved.state);
    assert.equal(result.records.find((r) => r.id === "rd1").amount, 3500);
    assert.equal(
        result.records.find((r) => r.id === "rd1").sourceShipment,
        "FH.20261112.0001",
    );
});
