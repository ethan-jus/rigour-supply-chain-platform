/** 列表按实际业务时间：货拉拉下单，订货宝／手工发货。 */
export const transportTime = (row) => {
    const value = row.source === "货拉拉同步" ? row.time || "" : row.sourceShippedAt || row.shippingTime || row.date || "";
    return value.length === 10 ? value + " 00:00:00" : value.replace("T", " ").slice(0,19);
};
export const transportStatus = (row) => {
    if(row.status === "已作废") return "已作废";
    const linked = orders.filter(o => row.links?.includes(o.id));
    const complete = row.amount != null && linked.length > 0 && linked.every(o => o.sales && o.department)
        && (row.source !== "手工录入" || row.vouchers?.length > 0);
    return complete ? "已完善" : "待完善";
};

import {
    carrierCode,
    carrierEntryMode,
    reviewDictionaries,
} from "./dictionary-examples.mjs";
import { assertManualFields } from "./validation.mjs";
// 独立评审示例。所有金额为整数分，不调用正式服务。
export const money = (n) =>
    n == null
        ? "-"
        : (n / 100).toLocaleString("zh-CN", {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
          });
export function cents(value) {
    if (value === "" || value == null) return null;
    if (!/^-?\d+(\.\d{1,2})?$/.test(String(value)))
        throw Error("金额最多保留两位小数");
    const [a, b = ""] = String(value).replace("-", "").split(".");
    const result =
        (Number(a) * 100 + Number(b.padEnd(2, "0"))) *
        (String(value).startsWith("-") ? -1 : 1);
    if (!Number.isSafeInteger(result)) throw Error("金额超出支持范围");
    return result;
}
export function allocate(total, selected) {
    if (!selected.length) return {};
    if (total == null)
        return Object.fromEntries(selected.map((o) => [o.id, null]));
    const weight = selected.reduce((s, o) => s + o.amount, 0);
    if (!weight) throw Error("订单金额不足以生成分摊建议");
    let used = 0;
    return Object.fromEntries(
        selected.map((o, i) => {
            const n =
                i === selected.length - 1
                    ? total - used
                    : Math.floor((total * o.amount) / weight + 0.5);
            used += n;
            return [o.id, n];
        }),
    );
}
export const orders = [
    {
        id: "s1",
        no: "XS202611120001",
        customer: "北辰台球俱乐部",
        sales: "林明",
        department: "武汉销售部",
        amount: 600000,
        contact: "周先生",
        phone: "13800000001",
        address: "武汉市江岸区示例路18号",
        status: "有效",
    },
    {
        id: "s2",
        no: "XS202611120002",
        customer: "星河台球馆",
        sales: "陈静",
        department: "武汉销售部",
        amount: 400000,
        contact: "杨女士",
        phone: "13800000002",
        address: "武汉市江汉区示例街26号",
        status: "有效",
    },
    {
        id: "s3",
        no: "XS202611120003",
        customer: "远景运动中心",
        sales: "赵宁",
        department: "杭州销售部",
        amount: 280000,
        contact: "许先生",
        phone: "13800000003",
        address: "杭州市拱墅区示例路9号",
        status: "有效",
    },
    {
        id: "s4",
        no: "XS202611120004",
        customer: "青禾桌球馆",
        sales: "林明",
        department: "武汉销售部",
        amount: 200000,
        contact: "刘先生",
        phone: "13800000004",
        address: "武汉市洪山区示例路6号",
        status: "已取消",
    },
];
export const warehouses = [
    {
        id: "w1",
        name: "武汉城市仓",
        contact: "张先生",
        phone: "13800000005",
        address: "武汉市东西湖区示例仓储园1号",
    },
    {
        id: "w2",
        name: "杭州城市仓",
        contact: "李女士",
        phone: "13800000006",
        address: "杭州市拱墅区示例仓储园2号",
    },
];
const feeExample = [
    { name: "基础运费", amount: 10885 },
    { name: "高速路桥费", amount: 2000 },
    { name: "搬运费", amount: 1100 },
    { name: "税费", amount: 279 },
    { name: "优惠", amount: -500 },
];
export const platformExamples = [
    {
        id: "h1",
        no: "HL202611120001",
        time: "2026-11-12 10:20:00",
        date: "2026-11-12",
        employee: "林明",
        vehicle: "小面",
        status: "已完成",
        warehouse: "w1",
        from: "武汉城市仓",
        to: "北辰台球俱乐部",
        stops: 2,
        amount: 13764,
        paid: 13764,
        fees: feeExample,
        version: 1,
        useTime: "2026-11-12 10:25:00",
        completeTime: "2026-11-12 11:20:00",
        distanceKm: "32.50",
        service: "同城快车",
        reason: "销售订单配送",
        remark: "原型示例，非真实运输",
        unpaid: 0,
        refund: 0,
        bonus: 0,
        payType: "企业账户余额",
        billUpdatedAt: "2026-11-12 11:22:00",
        invoiceAmount: 13764,
        invoiceStatus: "可开票",
        insuranceStatus: "平台未提供",
        orderContact: "林明",
        orderPhone: "13800000001",
        driver: { name: "司机示例", phone: "平台未提供", plate: "示例车牌" },
        progress: [
            { time: "2026-11-12 10:20:00", label: "订单已提交" },
            { time: "2026-11-12 10:30:00", label: "货物已装车" },
            { time: "2026-11-12 11:20:00", label: "订单已完成" },
        ],
        addresses: [
            {
                name: "武汉城市仓",
                contact: "张先生",
                phone: "13800000005",
                address: "武汉市东西湖区示例仓储园1号",
            },
            {
                name: "中途示例收货点",
                contact: "平台未提供",
                phone: "平台未提供",
                address: "武汉市江岸区示例中途点",
            },
            {
                name: "北辰台球俱乐部",
                contact: "周先生",
                phone: "13800000001",
                address: "武汉市江岸区示例路18号",
            },
        ],
        billVouchers: [
            {
                id: "bill-1",
                name: "费用凭证示意.svg",
                state: "READY",
                url: "/review-assets/bill-voucher.svg",
                fetchedAt: "2026-11-12 10:30:00",
            },
        ],
        receiptInfo: {
            回单类型: "拍照回单",
            回单状态: "已确认",
            送回方式: "-",
            寄回快递单号: "-",
            回单备注: "原型示例，非真实运输",
        },
        receipts: [
            {
                id: "receipt-1",
                name: "运输回单示意.svg",
                state: "READY",
                url: "/review-assets/receipt.svg",
                fetchedAt: "2026-11-12 10:30:00",
            },
        ],
        stopPhotos: [
            {
                index: 0,
                name: "武汉城市仓",
                phase: "装货",
                files: [
                    {
                        id: "loading-1",
                        name: "装货照片示意.svg",
                        state: "READY",
                        url: "/review-assets/loading.svg",
                        fetchedAt: "2026-11-12 10:30:00",
                    },
                ],
            },
            {
                index: 2,
                name: "北辰台球俱乐部",
                phase: "卸货",
                files: [
                    {
                        id: "unloading-1",
                        name: "卸货照片示意.svg",
                        state: "FAILED",
                        reason: "文件获取超时",
                        retryUrl: "/review-assets/unloading.svg",
                    },
                ],
            },
        ],
    },
    {
        id: "h2",
        no: "HL202611120002",
        time: "2026-11-12 09:10:00",
        date: "2026-11-12",
        employee: "陈静",
        vehicle: "微面",
        status: "已完成",
        warehouse: "w1",
        from: "武汉城市仓",
        to: "星河台球馆",
        stops: 1,
        amount: 6540,
        paid: 0,
        unpaid: 6540,
        refund: 0,
        fees: [{ name: "运费", amount: 6540 }],
        version: 1,
    },
    {
        id: "h3",
        no: "HL202611120003",
        time: "2026-11-12 11:10:00",
        date: "2026-11-12",
        employee: "林明",
        vehicle: "小面",
        status: "运输中",
        warehouse: "w1",
        from: "武汉城市仓",
        to: "北辰台球俱乐部",
        stops: 1,
        amount: 5000,
        paid: 0,
        fees: [],
        version: 1,
    },
];
const payment = (p) =>
    p.paid === p.amount ? "已付款" : p.paid ? "部分付款" : "未付款";
export function ingestCompleted(state, snapshots) {
    let records = [...state.records],
        platform = [...state.platform];
    for (const p of snapshots) {
        if (p.status !== "已完成") continue;
        const previous = platform.find((x) => x.id === p.id);
        platform = previous
            ? platform.map((x) => (x.id === p.id ? p : x))
            : [...platform, p];
        const r = records.find((x) => x.sourceId === p.id);
        if (r) {
            if (JSON.stringify(previous) === JSON.stringify(p)) continue;
            records = records.map((x) =>
                x.id === r.id
                    ? {
                          ...x,
                          platformAmount: p.amount,
                          billChanged:
                              (x.manuallyEdited || x.status === "已作废") && x.amount !== p.amount,
                          amount: x.manuallyEdited || x.status === "已作废" ? x.amount : p.amount,
                          allocations:
                              x.manuallyEdited || x.status === "已作废"
                                  ? x.allocations
                                  : allocate(
                                        p.amount,
                                        orders.filter((o) =>
                                            x.links.includes(o.id),
                                        ),
                                    ),
                          payment: payment(p),
                          date: p.date,
                          time: p.time,
                          from: p.from,
                          to: p.to,
                          vehicle: p.vehicle,
                          logs: [
                              ...x.logs,
                              "同步平台最新资料，保留业务关联和人工维护成本",
                          ],
                      }
                    : x,
            );
        } else {
            records.unshift({
                id: "rh-" + p.id,
                no: "LC20261112" + p.id.slice(1).padStart(4, "0"),
                sourceId: p.id,
                company: "货拉拉",
                tracking: p.no,
                mode: "第三方配送",
                transportType: "",
                date: p.date,
                time: p.time,
                from: p.from,
                to: p.to,
                stops: p.stops,
                vehicle: p.vehicle,
                amount: p.amount,
                platformAmount: p.amount,
                status: "有效",
                source: "货拉拉同步",
                links: [],
                allocations: {},
                warehouse: p.warehouse,
                payment: payment(p),
                owner: "",
                remark: "",
                fees: [],
                vouchers: [],
                logs: ["货拉拉已完成订单自动生成运输记录，待关联销售订单"],
            });
        }
    }
    return { ...state, records, platform };
}
export function initialState() {
    return ingestCompleted(
        {
            platform: [],
            records: [
                {
                    id: "rd1",
                    no: "LC202611120003",
                    company: "顺丰",
                    tracking: "SF202611120001",
                    mode: "第三方配送",
                    transportType: "销售订单配送",
                    date: "2026-11-12",
                    time: "2026-11-12 08:30:00",
                    from: "杭州城市仓",
                    to: "远景运动中心",
                    stops: 1,
                    vehicle: "-",
                    amount: null,
                    status: "有效",
                    source: "订货宝同步",
                    shipper: warehouses.find((w) => w.id === "w2").name,
                    shippingContact: warehouses.find((w) => w.id === "w2")
                        .contact,
                    shippingPhone: warehouses.find((w) => w.id === "w2").phone,
                    shippingAddress: warehouses.find((w) => w.id === "w2")
                        .address,
                    contact: orders.find((o) => o.id === "s3").contact,
                    phone: orders.find((o) => o.id === "s3").phone,
                    address: orders.find((o) => o.id === "s3").address,
                    sourceShipment: "FH.20261112.0001",
                    sourceShipmentStatus: "已发货",
                    sourceShippedAt: "2026-11-12 08:40:00",
                    sourceUpdatedAt: "2026-11-12 08:45:00",
                    sourceItems: [
                        { name: "配送商品示例", quantity: 2, unit: "箱" },
                    ],
                    sourceOrder: "DH.20261112.0003",
                    links: ["s3"],
                    allocations: { s3: null },
                    warehouse: "w2",
                    payment: "未知",
                    owner: "赵宁",
                    remark: "",
                    fees: [],
                    vouchers: [],
                    logs: ["订货宝发货信息按原销售单自动关联，运费未提供"],
                },
            ],
        },
        platformExamples,
    );
}
export function saveManual(state, draft) {
    const existing = state.records.find((r) => r.id === draft.editId);
    if (existing?.status === "已作废") throw Error("作废记录不可编辑");
    if (existing?.source === "货拉拉同步") {
        const amount = draft.amount;
        if (amount != null && (!Number.isSafeInteger(amount) || amount < 0)) throw Error("请填写有效运输费用");
        if (amount === 0 && !draft.remark?.trim()) throw Error("零费用请说明原因");
        const row = {...existing, amount, owner: draft.owner, remark: draft.remark,
            status: "有效", manuallyEdited: true,
            allocations: allocate(amount, orders.filter(o => existing.links.includes(o.id))),
            logs: [...existing.logs, "修改本系统费用，平台账单保持只读"]};
        return {state: {...state, records: state.records.map(r => r.id === row.id ? row : r)}, record: row};
    }
    const warehouse = warehouses.find((w) => w.id === draft.warehouse);
    const shipping = {
        shipper: draft.shipper ?? warehouse?.name ?? "",
        shippingContact: draft.shippingContact ?? warehouse?.contact ?? "",
        shippingPhone: draft.shippingPhone ?? warehouse?.phone ?? "",
        shippingAddress: draft.shippingAddress ?? warehouse?.address ?? "",
    };
    assertManualFields({ ...draft, ...shipping });
    if (
        draft.mode === "第三方配送" &&
        state.records.some(
            (r) =>
                r.id !== existing?.id &&
                r.company === draft.company &&
                r.tracking?.trim() === draft.tracking.trim() &&
                r.status !== "已作废",
        )
    )
        throw Error("该快递单号已有运输记录，请复用已有记录");
    const amount = draft.fees?.length
        ? draft.fees.reduce((s, f) => s + f.amount, 0)
        : draft.amount;
    if (amount != null && amount < 0) throw Error("运输费用不能为负数");
    if (amount === 0 && !draft.remark.trim()) throw Error("零费用请说明原因");
    const links = existing?.links || [];
    const row = {
        ...existing,
        ...draft,
        id: existing?.id || "rm-" + (state.records.length + 1),
        no:
            existing?.no ||
            "LC20261112M" + String(state.records.length + 1).padStart(3, "0"),
        source: existing?.source || "手工录入",
        ...shipping,
        from: shipping.shipper,
        stops: existing?.stops || 1,
        company: draft.mode === "自营配送" ? "自营" : draft.company,
        tracking: draft.mode === "自营配送" ? "" : draft.tracking.trim(),
        status: "有效",
        manuallyEdited: true,
        time: draft.date,
        links,
        allocations: allocate(
            amount,
            orders.filter((o) => links.includes(o.id)),
        ),
        logs: [
            ...(existing?.logs || []),
            existing ? "补充费用与凭证" : "手工新增运输记录，待关联销售订单",
        ],
    };
    return {
        state: {
            ...state,
            records: existing
                ? state.records.map((r) => (r.id === row.id ? row : r))
                : [row, ...state.records],
        },
        record: row,
    };
}
// 销售侧当前仅货拉拉具备可选择的同步数据源，字典扩展不等于接口已接入。
export function selectableSalesTransports(
    state,
    {
        mode,
        company,
        q = "",
        employee = "",
        destination = "",
        transportDate = "",
        linkState = "",
    },
    dictionaries = reviewDictionaries,
) {
    if (
        mode !== "第三方配送" ||
        carrierEntryMode(carrierCode(company, dictionaries)) !== "SELECT_SYNCED"
    )
        return [];
    return state.records
        .filter(
            (r) =>
                r.status !== "已作废" &&
                r.mode === mode &&
                r.company === company &&
                r.source === "货拉拉同步" &&
                state.platform.some(
                    (p) => p.id === r.sourceId && p.status === "已完成",
                ) &&
                (r.tracking + r.no).includes(q) &&
                (!destination || (r.to + r.address).includes(destination)) &&
                (!transportDate || r.date === transportDate) &&
                (!linkState ||
                    (linkState === "yes" ? r.links.length : !r.links.length)) &&
                (!employee ||
                    (
                        state.platform.find((p) => p.id === r.sourceId)
                            ?.employee || ""
                    ).includes(employee)),
        )
        .sort((a, b) => b.time.localeCompare(a.time));
}
export function linkTransport(state, recordId, orderId) {
    const r = state.records.find((r) => r.id === recordId),
        o = orders.find((o) => o.id === orderId);
    if (!o || o.status !== "有效") throw Error("请选择有效销售订单");
    if (!r || r.status === "已作废") throw Error("请选择有效运输记录");
    if (r.links.includes(orderId)) return { state, record: r, repeated: true };

    if (r.transportType && r.transportType !== "销售订单配送")
        throw Error("该记录不是销售配送，请核对运输类型");
    const links = [...r.links, orderId],
        row = {
            ...r,
            transportType: "销售订单配送",
            links,
            allocations: allocate(
                r.amount,
                orders.filter((o) => links.includes(o.id)),
            ),
            logs: [
                ...r.logs,
                "从销售订单关联 " +
                    o.no +
                    "，自动带入 " +
                    o.sales +
                    "／" +
                    o.department,
            ],
        };
    return {
        state: {
            ...state,
            records: state.records.map((x) => (x.id === r.id ? row : x)),
        },
        record: row,
        repeated: false,
    };
}
// 销售侧快捷录入共用运输台账校验；两步均成功后才提交原型状态。
export function saveManualForOrder(state, draft, orderId) {
    const order = orders.find((o) => o.id === orderId);
    if (!order || order.status !== "有效") throw Error("请选择有效销售订单");
    if (draft.transportType !== "销售订单配送")
        throw Error("销售订单物流须为销售订单配送");
    if (
        draft.mode === "第三方配送" &&
        carrierEntryMode(carrierCode(draft.company)) === "SELECT_SYNCED"
    )
        throw Error("货拉拉请关联已同步的已完成运输记录");
    const saved = saveManual(state, draft);
    return linkTransport(saved.state, saved.record.id, orderId);
}
export function transition(state, id, action, reason = "") {
    const r = state.records.find((x) => x.id === id);
    if (!r) throw Error("运输记录不存在");
    const allowed = {有效: ["作废"], 已作废: ["删除"]};
    if (!allowed[r.status]?.includes(action)) throw Error("当前状态不支持该操作；运输保存即生效，无需确认或撤销");
    if (!reason.trim() || reason.length > 500) throw Error("请填写不超过500字的原因");
    if (action === "删除") return {...state, records: state.records.filter(x => x.id !== id)};
    return {...state, records: state.records.map(x => x.id === id
        ? {...x, status: "已作废", logs: [...x.logs, action + "：" + reason]} : x)};
}

export function syncCompleted(state) {
    return ingestCompleted(state, [
        ...platformExamples,
        {
            ...platformExamples[1],
            id: "h4",
            no: "HL202611120004",
            time: "2026-11-12 11:30:00",
            employee: "赵宁",
            warehouse: "w2",
            from: "杭州城市仓",
            to: "远景运动中心",
            amount: 4000,
            unpaid: 4000,
            fees: [{ name: "运费", amount: 4000 }],
            paid: 0,
        },
    ]);
}

// 评审示例：同一订货宝发货身份复用记录，人工费用与确认状态均保留。
export function syncDhbExample(state) {
    const source = initialState().records.find(
        (r) => r.sourceShipment === "FH.20261112.0001",
    );
    const existing = state.records.find(
        (r) => r.sourceShipment === source.sourceShipment,
    );
    if (existing) return state;
    return { ...state, records: [source, ...state.records] };
}
