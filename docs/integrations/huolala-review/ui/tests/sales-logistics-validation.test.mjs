import test from "node:test";
import assert from "node:assert/strict";
import {
    manualFieldErrors,
    validateVoucher,
} from "../src/sales-logistics/validation.mjs";
import {
    saveManual,
    initialState,
    transition,
} from "../src/sales-logistics/model.mjs";
const draft = {
    transportType: "销售订单配送",
    mode: "第三方配送",
    company: "顺丰",
    tracking: "SF001",
    shipper: "实际发货方",
    shippingAddress: "发货地址",
    date: "2026-11-12",
    to: "收货单位",
    contact: "王先生",
    phone: "027-12345678",
    address: "收货地址",
    payment: "未知",
    amount: null,
    fees: [],
    remark: "",
    vouchers: [{ name: "凭证.pdf", size: 100 }],
};
test("空白提交逐一列出全部保存必填，不只返回第一项", () => {
    const e = manualFieldErrors({});
    for (const name of [
        "运输类型",
        "配送方式",
        "发货方",
        "发货地址",
        "发货日期",
        "收货单位",
        "收货联系人",
        "收货联系电话",
        "收货地址",
        "付款状态",
        "业务凭证",
    ])
        assert.ok(e[name], name);
});
test("第三方必填公司和单号，自营不强制外部单号", () => {
    assert.ok(
        manualFieldErrors({ ...draft, company: "", tracking: "" })["配送公司"],
    );
    assert.ok(
        manualFieldErrors({ ...draft, company: "", tracking: "" })["快递单号"],
    );
    assert.deepEqual(
        manualFieldErrors({
            ...draft,
            mode: "自营配送",
            company: "",
            tracking: "",
        }),
        {},
    );
});
test("费用未知允许保存，运输记录不再需要确认", () => {
    const result = saveManual(initialState(), draft);
    assert.equal(result.record.amount, null);
    assert.throws(
        () => transition(result.state, result.record.id, "确认"),
        /无需确认/,
    );
});
test("零费用必须说明，完整说明及凭证后保存生效", () => {
    assert.ok(manualFieldErrors({ ...draft, amount: 0 })["备注"]);
    const result = saveManual(initialState(), {
        ...draft,
        amount: 0,
        remark: "客户承担，无我方额外支出",
    });
    assert.equal(
        result.record.status,
        "有效",
    );
});
test("空费用行和负合计拦截，优惠负数可参与合法合计", () => {
    assert.ok(
        manualFieldErrors({ ...draft, fees: [{ name: "", amount: 100 }] })[
            "账单明细"
        ],
    );
    assert.ok(
        manualFieldErrors({ ...draft, fees: [{ name: "优惠", amount: -100 }] })[
            "运输费用（元）"
        ],
    );
    assert.deepEqual(
        manualFieldErrors({
            ...draft,
            fees: [
                { name: "运费", amount: 3500 },
                { name: "优惠", amount: -200 },
            ],
        }),
        {},
    );
});
test("日期、长度、字典非法值校验且允许固话国际号码", () => {
    assert.ok(manualFieldErrors({ ...draft, date: "2026-02-30" })["发货日期"]);
    assert.ok(
        manualFieldErrors({ ...draft, tracking: "A".repeat(81) })["快递单号"],
    );
    assert.ok(
        manualFieldErrors({ ...draft, payment: "凭空新增状态" })["付款状态"],
    );
    assert.ok(
        manualFieldErrors({ ...draft, phone: "联系电话" })["收货联系电话"],
    );
    assert.deepEqual(
        manualFieldErrors({
            ...draft,
            phone: "+86 (027) 12345678",
            shippingPhone: "13800000000",
        }),
        {},
    );
});
test("凭证拒绝空文件、超限和非允许扩展名，10MB边界可用", () => {
    for (const f of [
        { name: "a.pdf", size: 0 },
        { name: "a.png", size: 10 * 1024 * 1024 + 1 },
        { name: "a.exe", size: 100 },
    ])
        assert.ok(validateVoucher(f));
    assert.equal(
        validateVoucher({ name: "a.JPG", size: 10 * 1024 * 1024 }),
        "",
    );
    assert.equal(validateVoucher({ name: "a.xlsx", size: 100 }), "");
});
test("编辑最后一份凭证移除必须拒绝且原始状态不变", () => {
    const result = saveManual(initialState(), draft);
    assert.throws(
        () =>
            saveManual(result.state, {
                ...draft,
                editId: result.record.id,
                vouchers: [],
            }),
        /凭证/,
    );
    assert.equal(result.state.records[0].vouchers.length, 1);
});
