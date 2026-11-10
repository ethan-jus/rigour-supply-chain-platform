import React, { useEffect, useState } from "react";

const money = (n) =>
    n == null
        ? "-"
        : Number(n).toLocaleString("zh-CN", {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
          });
const platformPayment = (row) => {
    if ([row.total, row.paid, row.unpaid].some((v) => v == null || !Number.isFinite(Number(v)) || Number(v) < 0)) return "待核对";
    const cents = (n) => Math.round(Number(n) * 100);
    if (Number(row.refund) > 0 || cents(row.paid) + cents(row.unpaid) !== cents(row.total)) return "待核对";
    if (cents(row.total) === 0) return "无需付款";
    return cents(row.unpaid) === 0 ? "已付款" : cents(row.paid) > 0 ? "部分付款" : "未付款";
};
const now = "2026-11-09 17:20:00";
const billSyncTime = "2026-11-10 10:40:00";
const billKey = (row) =>
    JSON.stringify({
        total: row.total,
        refund: row.refund,
        bonus: row.bonus,
        fees: row.fees.map((fee) => [fee.id, fee.name, fee.type, fee.amount]),
    });
const billSnapshot = (row) => ({
    version: row.billVersion,
    updated: row.billUpdatedAt,
    total: row.total,
    paid: row.paid,
    unpaid: row.unpaid,
    refund: row.refund,
    fees: row.fees.map((fee) => ({
        ...fee,
        vouchers: [...(fee.vouchers || [])],
    })),
});
const billSnapshotKey = (row) =>
    JSON.stringify([billKey(row), row.paid, row.unpaid]);
const savePlatformBill = (row, patch) => {
    const next = { ...row, ...patch, synced: billSyncTime };
    if (billSnapshotKey(next) === billSnapshotKey(row)) return next;
    next.billVersion = (row.billVersion || 0) + 1;
    next.billUpdatedAt = billSyncTime;
    next.billHistory = [billSnapshot(next), ...(row.billHistory || [])];
    return next;
};
const syncBillExample = (row) => {
    if (
        row.no === "3624120071116903105" &&
        !row.fees.some((fee) => fee.id === "parking-added")
    ) {
        return savePlatformBill(row, {
            total: 142.64,
            unpaid: 5,
            fees: [
                ...row.fees,
                {
                    id: "parking-added",
                    type: "22",
                    name: "停车费",
                    amount: 5,
                    vouchers: [],
                },
            ],
        });
    }
    if (row.no === "3624120071116903107" && row.total === 60) {
        return savePlatformBill(row, {
            status: "已完成",
            completed: "2026-11-09 16:10:00",
            total: 65,
            unpaid: 65,
            fees: [
                {
                    id: "base",
                    type: "206",
                    name: "基础运费",
                    amount: 60,
                    vouchers: [],
                },
                {
                    id: "parking",
                    type: "22",
                    name: "停车费",
                    amount: 5,
                    vouchers: [],
                },
            ],
        });
    }
    return { ...row, synced: billSyncTime };
};
const carrierFor = (row) =>
    row?.source === "货拉拉同步" ? "货拉拉" : row?.carrier || "-";
const warehouses = [
    {
        code: "WH001",
        name: "武汉仓",
        contact: "陈女士",
        phone: "13900000005",
        address: "湖北省武汉市江岸区台北一路21号",
    },
    {
        code: "WH002",
        name: "杭州仓",
        contact: "赵先生",
        phone: "13900000004",
        address: "浙江省杭州市拱墅区通仓路18号",
    },
    {
        code: "WH003",
        name: "焦作云仓",
        contact: "王先生",
        phone: "",
        address: "河南省焦作市博爱县云仓园区",
    },
];
const emptyShipping = {
    warehouseCode: "",
    shipper: "",
    senderContact: "",
    senderPhone: "",
    senderAddress: "",
    originSource: "",
};
const shippingFromWarehouse = (warehouse, source = "warehouse") =>
    warehouse
        ? {
              warehouseCode: warehouse.code,
              shipper: warehouse.name,
              senderContact: warehouse.contact,
              senderPhone: warehouse.phone,
              senderAddress: warehouse.address,
              originSource: source,
          }
        : { ...emptyShipping };
const hr = [
    {
        id: "EMP001",
        name: "李明",
        phone: "13800000001",
        dept: "武汉销售部",
        state: "在职",
    },
    {
        id: "EMP002",
        name: "陈静",
        phone: "13800000002",
        dept: "武汉运营部",
        state: "在职",
    },
    {
        id: "EMP003",
        name: "张敏",
        phone: "13800000003",
        dept: "杭州销售部",
        state: "在职",
    },
];
const platformStaff = [
    { id: "HLL001", name: "李明", phone: "13800000001", dept: "武汉分公司" },
    { id: "HLL002", name: "陈静", phone: "13800000002", dept: "武汉分公司" },
    { id: "HLL003", name: "张敏", phone: "13800000003", dept: "杭州分公司" },
];
const businessOrders = [
    {
        no: "XS20261109001",
        kind: "销售单",
        customer: "武汉球友俱乐部",
        sales: "李明",
        dept: "武汉销售部",
        date: "2026-11-09",
        amount: 800,
        contact: "王先生",
        phone: "13900000001",
        address: "武汉市江岸区沿江大道18号",
        count: 1,
        hint: "收货电话、地址一致",
    },
    {
        no: "XS20261109002",
        kind: "销售单",
        customer: "华中桌球俱乐部",
        sales: "张敏",
        dept: "杭州销售部",
        date: "2026-11-09",
        amount: 400,
        contact: "刘先生",
        phone: "13900000002",
        address: "武汉市江汉区新华路26号",
        count: 0,
        hint: "送达地点一致",
    },
    {
        no: "XS20261108003",
        kind: "销售单",
        customer: "楚天体育用品",
        sales: "李明",
        dept: "武汉销售部",
        date: "2026-11-08",
        amount: 600,
        contact: "张女士",
        phone: "13900000003",
        address: "武汉市武昌区中北路66号",
        count: 0,
        hint: "用车日期接近",
    },
    {
        no: "DB20261109001",
        kind: "调拨单",
        originWarehouseCode: "WH001",
        customer: "杭州仓",
        sales: "-",
        dept: "杭州运营部",
        date: "2026-11-09",
        amount: null,
        contact: "赵先生",
        phone: "13900000004",
        address: "杭州市拱墅区通仓路18号",
        count: 0,
        hint: "收货仓库一致",
    },
    {
        no: "QT20261109001",
        kind: "其他订单",
        customer: "华中品鉴活动",
        sales: "李明",
        dept: "武汉销售部",
        date: "2026-11-09",
        amount: null,
        contact: "王先生",
        phone: "13900000001",
        address: "武汉市江岸区沿江大道18号",
        count: 0,
        hint: "送达地点一致",
    },
];
const route = [
    {
        kind: "发",
        name: "武汉仓",
        contact: "陈女士",
        phone: "13900000005",
        address: "湖北省武汉市江岸区台北一路21号",
    },
    {
        kind: "收",
        name: "武汉球友俱乐部",
        contact: "王先生",
        phone: "13900000001",
        address: "湖北省武汉市江岸区沿江大道18号",
    },
    {
        kind: "收",
        name: "华中桌球俱乐部",
        contact: "刘先生",
        phone: "13900000002",
        address: "湖北省武汉市江汉区新华路26号",
    },
];
const initialOrders = [
    {
        no: "3624120071116903105",
        time: "2026-11-09 10:00:00",
        created: "2026-11-09 09:18:22",
        completed: "2026-11-09 11:05:16",
        status: "已完成",
        staffId: "HLL001",
        total: 137.64,
        paid: 137.64,
        unpaid: 0,
        refund: 0,
        bonus: 0,
        vehicle: "小面包车",
        route,
        detail: true,
        fees: [
            {
                id: "exclusive",
                type: "203",
                name: "专享价",
                amount: 108.85,
                vouchers: [],
            },
            {
                id: "highway",
                type: "21",
                name: "高速路桥费",
                amount: 20,
                vouchers: [
                    {
                        id: "highway-proof",
                        name: "高速路桥费凭证（示例）.txt",
                        url: "/vouchers/highway-voucher-demo.txt",
                        state: "已留存",
                        savedAt: now,
                    },
                ],
            },
            {
                id: "moving",
                type: "23",
                name: "搬运费（司机添加）",
                amount: 11,
                vouchers: [],
            },
            {
                id: "tax",
                type: "205",
                name: "税费",
                amount: 2.79,
                vouchers: [],
            },
            {
                id: "coupon",
                type: "3",
                name: "货运券抵扣",
                amount: -5,
                vouchers: [],
            },
        ],
        driver: "周师傅",
        driverPhone: "139****0006",
        plate: "鄂A**234",
        remark: "按收货点顺序配送",
        reason: "门店补货",
        synced: now,
    },
    {
        no: "3624120071116903106",
        time: "2026-11-09 13:00:00",
        created: "2026-11-09 12:10:09",
        completed: "2026-11-09 14:12:16",
        status: "已完成",
        staffId: "HLL002",
        total: 85,
        paid: 0,
        unpaid: 85,
        refund: 0,
        bonus: 0,
        vehicle: "小面包车",
        route: [
            route[0],
            {
                kind: "收",
                name: "楚天体育用品",
                contact: "张女士",
                phone: "13900000003",
                address: "湖北省武汉市武昌区中北路66号",
            },
        ],
        detail: true,
        fees: [
            {
                id: "base",
                type: "206",
                name: "基础运费",
                amount: 80,
                vouchers: [],
            },
            {
                id: "parking",
                type: "22",
                name: "停车费",
                amount: 5,
                vouchers: [
                    {
                        id: "parking-pending",
                        name: "停车费凭证",
                        state: "待获取",
                    },
                ],
            },
        ],
        driver: "刘师傅",
        driverPhone: "139****0007",
        plate: "鄂A**345",
        remark: "",
        reason: "客户配送",
        synced: now,
    },
    {
        no: "3624120071116903107",
        time: "2026-11-09 15:00:00",
        created: "2026-11-09 14:26:30",
        completed: "",
        status: "进行中",
        staffId: "HLL001",
        total: 60,
        paid: 0,
        unpaid: 60,
        refund: 0,
        bonus: 0,
        vehicle: "中面包车",
        route: [route[0], route[1]],
        detail: true,
        fees: [
            {
                id: "base",
                type: "206",
                name: "基础运费",
                amount: 60,
                vouchers: [],
            },
        ],
        driver: "赵师傅",
        driverPhone: "139****0008",
        plate: "鄂A**456",
        remark: "",
        reason: "",
        synced: now,
    },
    {
        no: "3624120071116903108",
        time: "2026-11-08 16:30:00",
        created: "2026-11-08 15:55:30",
        completed: "",
        status: "已取消",
        staffId: "HLL003",
        total: 0,
        paid: 0,
        unpaid: 0,
        refund: 0,
        bonus: 0,
        vehicle: "小面包车",
        route: [
            {
                kind: "发",
                name: "杭州仓",
                contact: "赵先生",
                phone: "13900000004",
                address: "浙江省杭州市拱墅区通仓路18号",
            },
            {
                kind: "收",
                name: "杭州球友俱乐部",
                contact: "孙先生",
                phone: "13900000009",
                address: "浙江省杭州市拱墅区新明路28号",
            },
        ],
        detail: true,
        fees: [
            {
                id: "cancellation",
                type: "24",
                name: "取消费用",
                amount: 0,
                vouchers: [],
            },
        ],
        driver: "",
        driverPhone: "",
        plate: "",
        remark: "客户调整配送时间",
        reason: "",
        synced: now,
    },
    {
        no: "3624120071116903109",
        time: "2026-11-08 09:00:00",
        created: "2026-11-08 08:35:00",
        completed: "",
        status: "已完成",
        staffId: null,
        total: null,
        vehicle: "小面包车",
        route: [],
        detail: false,
        fees: [],
        synced: now,
    },
];
initialOrders.forEach((row, index) =>
    Object.assign(row, {
        source: "货拉拉同步",
        recordNo: `LC20261109${String(index + 1).padStart(4, "0")}`,
        docState: "待确认",
        creator: "系统同步",
        carrier: "货拉拉",
        billVersion: row.detail ? 1 : 0,
        billUpdatedAt: row.detail ? now : "",
    }),
);
initialOrders.forEach((row) => {
    row.billHistory = row.detail ? [billSnapshot(row)] : [];
});
initialOrders.push({
    no: "MANUAL001",
    recordNo: "LC202611090006",
    source: "手动录入",
    docState: "已确认",
    creator: "管理员",
    carrier: "顺丰",
    waybill: "SF202611090001",
    time: "2026-11-09 09:30:00",
    created: "2026-11-09 09:10:00",
    status: "-",
    staffId: null,
    total: 35,
    payment: "未付款",
    manualPlatformNo: "SF-ORDER-202611090001",
    manualBillItems: [{name:"运费",amount:35},{name:"保价费",amount:2},{name:"优惠",amount:-2}],
    paid: null,
    unpaid: 0,
    refund: 0,
    bonus: 0,
    vehicle: "-",
    route: [route[0], route[1]],
    detail: true,
    fees: [{ id: "freight", name: "运费", amount: 35, vouchers: [] }],
    confirmedCost: 35,
    remark: "门店补货",
    owner: "李明",
    synced: "-",
});

const kindFor = {
    销售订单配送: "销售单",
    仓间调拨: "调拨单",
    其他费用: "其他订单",
};
const initialLinks = {
    MANUAL001: { type: "销售订单配送", nos: ["XS20261109001"] },
    "3624120071116903105": {
        type: "销售订单配送",
        nos: ["XS20261109001", "XS20261109002"],
    },
};
const emptyFilters = {
    record: "",
    source: "",
    type: "",
    doc: "",
    waybill: "",
    carrier: "",
    no: "",
    start: "",
    end: "",
    status: "",
    order: "",
    staff: "",
    name: "",
    biz: "",
    dept: "",
    sales: "",
    destination: "",
    phone: "",
    employee: "",
};
const allColumns = [
    ["recordNo", "费用单号"],
    ["carrier", "物流公司"],
    ["type", "运输类型"],
    ["date", "运输日期"],
    ["destination", "收货方"],
    ["no", "第三方订单号"],
    ["waybill", "运单号"],
    ["total", "运输费用（元）"],
    ["doc", "单据状态"],
    ["creator", "创建人"],
    ["source", "数据来源"],
    ["payment", "付款状态"],
    ["status", "货拉拉订单状态"],
    ["staff", "货拉拉下单员工"],
    ["employee", "本系统关联员工"],
    ["staffState", "员工关联状态"],
    ["business", "已关联业务单号"],
    ["orderState", "订单关联状态"],
    ["sales", "业务员"],
    ["dept", "部门"],
    ["time", "用车时间"],
    ["created", "下单时间"],
    ["completed", "完成时间"],
    ["origin", "发货地点"],
    ["vehicle", "车型"],
    ["paid", "已付金额（元）"],
    ["unpaid", "未付金额（元）"],
    ["refund", "退款金额（元）"],
    ["phone", "平台员工手机号"],
    ["synced", "最近同步时间"],
];
const defaultColumns = allColumns.slice(0, 10).map(([key]) => key);

function Tag({ children, tone = "" }) {
    return <span className={`tag ${tone}`}>{children}</span>;
}
function Field({ label, children, required = false }) {
    return (
        <label className="field">
            <span>
                {required && <i>* </i>}
                {label}
            </span>
            {children}
        </label>
    );
}
function Select({ value, onChange, options, placeholder = "全部", ...rest }) {
    return (
        <select
            value={value}
            onChange={(e) => onChange(e.target.value)}
            {...rest}
        >
            <option value="">{placeholder}</option>
            {options.map((x) => (
                <option key={x} value={x}>
                    {x}
                </option>
            ))}
        </select>
    );
}
function Modal({
    title,
    children,
    close,
    footer,
    wide = false,
    record = false,
    inactive = false,
}) {
    return (
        <div
            className="overlay"
            inert={inactive}
            onMouseDown={(e) => e.target === e.currentTarget && close()}
        >
            <section
                className={`modal ${wide ? "wide" : ""} ${record ? "record-form" : ""}`}
                role="dialog"
                aria-modal="true"
                aria-label={title}
            >
                <div className="modal-head">
                    <h2>{title}</h2>
                    <button
                        className="icon-button"
                        aria-label="关闭弹窗"
                        onClick={close}
                    >
                        ×
                    </button>
                </div>
                <div className="modal-body">{children}</div>
                {footer && <div className="modal-footer">{footer}</div>}
            </section>
        </div>
    );
}
function Section({ title, action, children }) {
    return (
        <section className="section">
            <div className="section-head">
                <h3>{title}</h3>
                {action}
            </div>
            {children}
        </section>
    );
}
function Info({ label, children }) {
    return (
        <div className="info">
            <dt>{label}</dt>
            <dd>{children ?? "-"}</dd>
        </div>
    );
}

function ReadonlyField({ label, value }) {
    return (
        <Field label={label}>
            <input readOnly className="readonly" value={value || "-"} />
        </Field>
    );
}
function BusinessOrderTable({ rows }) {
    return rows.length ? (
        <div className="table-scroll">
            <table>
                <thead>
                    <tr>
                        {[
                            "业务单号",
                            "客户／收货仓库",
                            "业务员",
                            "部门",
                            "订单日期",
                            "业务订单金额（元）",
                        ].map((label) => (
                            <th key={label}>{label}</th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {rows.map((row) => (
                        <tr key={row.no}>
                            <td className="blue-text">{row.no}</td>
                            <td>{row.customer}</td>
                            <td>{row.sales}</td>
                            <td>{row.dept}</td>
                            <td>{row.date}</td>
                            <td className="numeric">{money(row.amount)}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    ) : (
        <div className="empty compact">未关联业务订单</div>
    );
}
function DeliveryStops({ points }) {
    return points.length ? (
        <div className="auto-stops">
            {points.map((point, index) => (
                <div className="manual-stop" key={index}>
                    <strong>{point.name || point.address || "-"}</strong>
                    <dl className="info-grid">
                        <Info label="收货联系人">{point.contact || "-"}</Info>
                        <Info label="收货联系电话">{point.phone || "-"}</Info>
                        <Info label="收货地址">{point.address || "-"}</Info>
                    </dl>
                </div>
            ))}
        </div>
    ) : (
        <div className="empty compact">暂无收货信息</div>
    );
}
function ShippingStops({ points, warehouseName }) {
    return points.length ? (
        <div className="shipping-stops">
            {points.map((point, index) => (
                <dl className="info-grid shipping-info" key={index}>
                    {warehouseName && (
                        <Info label="发货仓库">{warehouseName}</Info>
                    )}
                    <Info label="发货方">{point.name || "-"}</Info>
                    <Info label="发货联系人">{point.contact || "-"}</Info>
                    <Info label="发货联系电话">{point.phone || "-"}</Info>
                    <Info label="发货地址">{point.address || "-"}</Info>
                </dl>
            ))}
        </div>
    ) : (
        <div className="empty compact">暂无发货信息</div>
    );
}

function PlatformBill({ row, onVoucher, onRevoke }) {
    const vouchers = row.fees.flatMap((fee) =>
        (fee.vouchers || []).map((file) => ({
            ...file,
            feeName: fee.name,
            amount: fee.amount,
        })),
    );
    const changed =
        row.docState === "已确认" &&
        row.confirmedBillKey &&
        row.confirmedBillKey !== billKey(row);
    return (
        <div className="platform-bill">
            <div className="bill-heading">
                <h4>平台账单</h4>
                <Tag>只读</Tag>
            </div>
            {changed && (
                <div className="notice warning bill-change-alert">
                    <span>
                        平台账单已更新，已确认成本仍为 ¥
                        {money(row.confirmedCost)}，请核对后处理。
                    </span>
                    <button className="link" onClick={onRevoke}>
                        撤销确认
                    </button>
                </div>
            )}
            <h4>账单汇总</h4>
            <div className="bill-summary-grid">
                {[
                    ["账单总计（元）", row.total],
                    ["已支付（元）", row.paid],
                    ["未支付（元）", row.unpaid],
                    ["退款（元）", row.refund],
                ].map(([label, amount], index) => (
                    <div
                        className={`bill-summary-item ${index === 0 ? "primary-amount" : ""}`}
                        key={label}
                    >
                        <span>{label}</span>
                        <strong>{money(amount)}</strong>
                    </div>
                ))}
            </div>
            <dl className="info-grid bill-meta">
                <Info label="付款状态">{platformPayment(row)}</Info>
                <Info label="赠金抵扣（元）">{money(row.bonus)}</Info>
                <Info label="账单更新时间">{row.billUpdatedAt || "-"}</Info>
                <Info label="最近同步时间">{row.synced || "-"}</Info>
            </dl>
            <h4>费用明细</h4>
            <div className="table-scroll">
                <table className="bill-fee-table">
                    <thead>
                        <tr>
                            <th>费用项目</th>
                            <th className="numeric">金额（元）</th>
                            <th>凭证</th>
                        </tr>
                    </thead>
                    <tbody>
                        {row.fees.map((fee) => (
                            <tr key={fee.id}>
                                <td>{fee.name}</td>
                                <td
                                    className={`numeric ${fee.amount < 0 ? "discount-amount" : ""}`}
                                >
                                    {money(fee.amount)}
                                </td>
                                <td>
                                    {fee.vouchers?.some(
                                        (file) => file.state === "已留存",
                                    ) ? (
                                        <button
                                            className="link"
                                            onClick={() =>
                                                onVoucher({
                                                    ...fee.vouchers.find(
                                                        (file) =>
                                                            file.state ===
                                                            "已留存",
                                                    ),
                                                    feeName: fee.name,
                                                    amount: fee.amount,
                                                })
                                            }
                                        >
                                            查看凭证
                                        </button>
                                    ) : fee.vouchers?.length ? (
                                        "待获取"
                                    ) : (
                                        "-"
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            {!row.fees.length && (
                <div className="empty compact">暂无平台费用明细</div>
            )}
            <h4>凭证</h4>
            {vouchers.length ? (
                <div className="table-scroll">
                    <table>
                        <thead>
                            <tr>
                                <th>所属费用</th>
                                <th>文件</th>
                                <th>凭证状态</th>
                                <th>取得时间</th>
                                <th>操作</th>
                            </tr>
                        </thead>
                        <tbody>
                            {vouchers.map((file) => (
                                <tr key={file.id}>
                                    <td>{file.feeName}</td>
                                    <td>{file.name}</td>
                                    <td>
                                        <Tag
                                            tone={
                                                file.state === "已留存"
                                                    ? "green"
                                                    : "amber"
                                            }
                                        >
                                            {file.state}
                                        </Tag>
                                    </td>
                                    <td>{file.savedAt || "-"}</td>
                                    <td>
                                        {file.state === "已留存" ? (
                                            <button
                                                className="link"
                                                onClick={() => onVoucher(file)}
                                            >
                                                查看
                                            </button>
                                        ) : (
                                            "-"
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            ) : (
                <div className="empty compact">暂无平台凭证</div>
            )}
            {row.billHistory?.length > 1 && (
                <details className="bill-history">
                    <summary>账单变更记录</summary>
                    <div className="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    <th>账单更新时间</th>
                                    <th className="numeric">账单总计（元）</th>
                                    <th className="numeric">已支付（元）</th>
                                    <th className="numeric">未支付（元）</th>
                                    <th className="numeric">退款（元）</th>
                                </tr>
                            </thead>
                            <tbody>
                                {row.billHistory.map((item) => (
                                    <tr key={item.version}>
                                        <td>{item.updated}</td>
                                        <td className="numeric">
                                            {money(item.total)}
                                        </td>
                                        <td className="numeric">
                                            {money(item.paid)}
                                        </td>
                                        <td className="numeric">
                                            {money(item.unpaid)}
                                        </td>
                                        <td className="numeric">
                                            {money(item.refund)}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </details>
            )}
        </div>
    );
}

export function App() {
    const [page, setPage] = useState("orders");
    const [manual, setManual] = useState({
        ...emptyShipping,
        type: "",
        nos: [],
        date: "2026-11-09",
        carrier: "",
        waybill: "",
        platformNo: "",
        payment: "未知",
        billItems: [],
        amount: "",
        owner: "",
        remark: "",
    });
    const [editNo, setEditNo] = useState(null);
    const [rowMenu, setRowMenu] = useState(null);
    const [orders, setOrders] = useState(initialOrders);
    const editingRow = orders.find((row) => row.no === editNo);
    const editingPlatform = editingRow?.source === "货拉拉同步";
    const updateBillItems = (items) => setManual((current) => ({...current, billItems: items,
        amount: items.length ? (items.some((item) => item.amount === "") ? "" : String(items.reduce((sum, item) => sum + Math.round(Number(item.amount) * 100), 0) / 100)) : current.amount }));
    const [links, setLinks] = useState(initialLinks);
    const [staffLinks, setStaffLinks] = useState({ HLL001: "EMP001" });
    const [audits, setAudits] = useState([
        {
            no: initialOrders[0].no,
            time: "2026-11-09 14:20:00",
            action: "关联业务订单",
            content: "XS20261109001、XS20261109002",
            by: "管理员",
        },
    ]);
    const [filters, setFilters] = useState(emptyFilters);
    const [applied, setApplied] = useState(emptyFilters);
    const [more, setMore] = useState(false);
    const [selected, setSelected] = useState([]);
    const [columns, setColumns] = useState(defaultColumns);
    const [modal, setModal] = useState(null);
    const [detailNo, setDetailNo] = useState(null);
    const [targetNo, setTargetNo] = useState(null);
    const [linkType, setLinkType] = useState("");
    const [picked, setPicked] = useState([]);
    const [reason, setReason] = useState("");
    const [bizFilter, setBizFilter] = useState({
        no: "",
        customer: "",
        sales: "",
        dept: "",
        date: "",
    });
    const [staffTarget, setStaffTarget] = useState(null);
    const [employee, setEmployee] = useState("");
    const [employeeSearch, setEmployeeSearch] = useState("");
    const [staffFilter, setStaffFilter] = useState({
        name: "",
        phone: "",
        state: "",
    });
    const [error, setError] = useState("");
    const [toast, setToast] = useState("");
    const [exportFields, setExportFields] = useState(
        allColumns.map(([key]) => key),
    );
    const [preview, setPreview] = useState(false);
    const [voucher, setVoucher] = useState(null);
    const [syncRange, setSyncRange] = useState({
        start: "2026-11-01",
        end: "2026-11-09",
    });
    const [runs, setRuns] = useState([
        {
            time: now,
            state: "部分成功",
            list: 5,
            detail: 4,
            pending: 1,
            failed: 0,
        },
    ]);
    const [connected, setConnected] = useState(false);
    const [autoSync, setAutoSync] = useState(true);
    const [frequency, setFrequency] = useState("15分钟");
    const notify = (message) => {
        setToast(message);
        window.setTimeout(() => setToast(""), 3600);
    };
    const close = () => {
        setModal(null);
        setError("");
        setPreview(false);
    };
    useEffect(() => {
        const escape = (event) => {
            if (event.key === "Escape") {
                if (voucher) setVoucher(null);
                else if (modal) {
                    setModal(null);
                    setError("");
                } else setDetailNo(null);
            }
        };
        window.addEventListener("keydown", escape);
        return () => window.removeEventListener("keydown", escape);
    }, [modal, voucher]);
    const platform = (id) => platformStaff.find((x) => x.id === id);
    const internal = (id) => hr.find((x) => x.id === staffLinks[id]);
    const target = orders.find((x) => x.no === targetNo);
    const detail = orders.find((x) => x.no === detailNo);
    const associated = (no) =>
        businessOrders.filter((x) => links[no]?.nos.includes(x.no));
    const displayed = orders.filter((row) => {
        const f = applied;
        const staff = platform(row.staffId);
        const related = associated(row.no);
        return (
            (!f.record || row.recordNo.includes(f.record)) &&
            (!f.source || row.source === f.source) &&
            (!f.type || links[row.no]?.type === f.type) &&
            (!f.doc || row.docState === f.doc) &&
            (!f.carrier || carrierFor(row) === f.carrier) &&
            (!f.waybill || (row.waybill || "").includes(f.waybill)) &&
            (!f.no || (row.source === "货拉拉同步" && row.no.includes(f.no))) &&
            (!f.start || row.time.slice(0, 10) >= f.start) &&
            (!f.end || row.time.slice(0, 10) <= f.end) &&
            (!f.status || row.status === f.status) &&
            (!f.order ||
                (links[row.no]?.nos.length ? "已关联" : "未关联") ===
                    f.order) &&
            (!f.staff ||
                (row.staffId
                    ? staffLinks[row.staffId]
                        ? "已关联"
                        : "未关联"
                    : "暂无员工资料") === f.staff) &&
            (!f.name || staff?.name.includes(f.name)) &&
            (!f.biz || related.some((x) => x.no.includes(f.biz))) &&
            (!f.dept || related.some((x) => x.dept === f.dept)) &&
            (!f.sales || related.some((x) => x.sales.includes(f.sales))) &&
            (!f.destination ||
                row.route.some(
                    (x) =>
                        x.kind === "收" &&
                        (x.name + x.address).includes(f.destination),
                )) &&
            (!f.employee || internal(row.staffId)?.name === f.employee) &&
            (!f.phone ||
                (staff?.phone || "").includes(f.phone) ||
                row.route.some((x) => x.phone.includes(f.phone)))
        );
    });
    const filter = (key, value) =>
        setFilters((old) => ({ ...old, [key]: value }));
    const openLink = (no) => {
        if (orders.find((x) => x.no === no)?.docState !== "待确认") {
            notify("已确认记录请先撤销，再更改订单关联");
            return;
        }
        setTargetNo(no);
        setLinkType(links[no]?.type || "");
        setPicked(links[no]?.nos || []);
        setReason("");
        setBizFilter({ no: "", customer: "", sales: "", dept: "", date: "" });
        setError("");
        setModal("link");
    };
    const openEmployee = (id) => {
        if (!id) return;
        setStaffTarget(id);
        setEmployee(staffLinks[id] || "");
        setEmployeeSearch("");
        setReason("");
        setError("");
        setModal("employee");
    };
    const toggle = (value, values, setter) =>
        setter(
            values.includes(value)
                ? values.filter((x) => x !== value)
                : [...values, value],
        );
    const saveLink = () => {
        if (!linkType || !picked.length) {
            setError("请选择运输类型，并至少勾选一张业务订单");
            return;
        }
        const changed = !!links[targetNo];
        if (changed && !reason.trim()) {
            setError("请填写更改关联的原因");
            return;
        }
        setLinks((old) => ({
            ...old,
            [targetNo]: { type: linkType, nos: picked },
        }));
        setAudits((old) => [
            {
                no: targetNo,
                time: now,
                action: changed ? "更改订单关联" : "关联业务订单",
                content:
                    picked.join("、") + (reason ? `；原因：${reason}` : ""),
                by: "管理员",
            },
            ...old,
        ]);
        close();
        notify("业务订单关联已保存");
    };
    const unlinkOrders = () => {
        if (!reason.trim()) {
            setError("解除关联前，请填写原因");
            return;
        }
        setLinks((old) => {
            const next = { ...old };
            delete next[targetNo];
            return next;
        });
        setAudits((old) => [
            {
                no: targetNo,
                time: now,
                action: "解除订单关联",
                content: reason,
                by: "管理员",
            },
            ...old,
        ]);
        close();
        notify("订单关联已解除");
    };
    const saveEmployee = () => {
        if (!employee) {
            setError("请选择本系统员工");
            return;
        }
        if (staffLinks[staffTarget] && !reason.trim()) {
            setError("请填写更改员工关联的原因");
            return;
        }
        setStaffLinks((old) => ({ ...old, [staffTarget]: employee }));
        setAudits((old) => [
            {
                no: "*",
                staffId: staffTarget,
                time: now,
                action: staffLinks[staffTarget] ? "更改员工关联" : "关联员工",
                content: `${platform(staffTarget).name} → ${hr.find((x) => x.id === employee).name}${reason ? "；原因：" + reason : ""}`,
                by: "管理员",
            },
            ...old,
        ]);
        close();
        notify("员工关联已保存，该员工的相关订单已更新");
    };
    const unlinkEmployee = () => {
        if (!reason.trim()) {
            setError("解除员工关联前，请填写原因");
            return;
        }
        setStaffLinks((old) => {
            const next = { ...old };
            delete next[staffTarget];
            return next;
        });
        setAudits((old) => [
            {
                no: "*",
                staffId: staffTarget,
                time: now,
                action: "解除员工关联",
                content: reason,
                by: "管理员",
            },
            ...old,
        ]);
        close();
        notify("员工关联已解除");
    };
    const retryDetail = (no) => {
        if (!connected) {
            setModal("needConnection");
            return;
        }
        setOrders((old) =>
            old.map((row) =>
                row.no !== no
                    ? row
                    : savePlatformBill(row, {
                          detail: true,
                          route: [route[0], route[1]],
                          total: 45,
                          paid: 45,
                          unpaid: 0,
                          refund: 0,
                          bonus: 0,
                          fees: [
                              {
                                  id: "base",
                                  type: "206",
                                  name: "基础运费",
                                  amount: 45,
                                  vouchers: [],
                              },
                          ],
                          staffId: "HLL002",
                          completed: "2026-11-08 10:10:00",
                      }),
            ),
        );
        notify("示例：本单详情已补同步，原有关联保留");
    };
    const syncSingleBill = (row) => {
        if (!connected) {
            setModal("needConnection");
            return;
        }
        if (!row.detail) {
            retryDetail(row.no);
            return;
        }
        setOrders((old) =>
            old.map((item) =>
                item.no === row.no ? syncBillExample(item) : item,
            ),
        );
        notify("示例同步完成，平台账单已保存，已确认成本保留");
    };
    const sync = () => {
        if (!connected) {
            setModal("needConnection");
            return;
        }
        if (
            !syncRange.start ||
            !syncRange.end ||
            syncRange.start > syncRange.end
        ) {
            setError("请选择有效的用车日期范围");
            return;
        }
        setOrders((old) =>
            old.map((row) =>
                row.source === "货拉拉同步" &&
                row.time.slice(0, 10) >= syncRange.start &&
                row.time.slice(0, 10) <= syncRange.end
                    ? syncBillExample(row)
                    : row,
            ),
        );
        const found = orders.filter(
            (x) =>
                x.source === "货拉拉同步" &&
                x.time.slice(0, 10) >= syncRange.start &&
                x.time.slice(0, 10) <= syncRange.end,
        );
        setRuns((old) => [
            {
                time: now,
                state: found.some((x) => !x.detail) ? "部分成功" : "成功",
                list: found.length,
                detail: found.filter((x) => x.detail).length,
                pending: found.filter((x) => !x.detail).length,
                failed: 0,
            },
            ...old,
        ]);
        close();
        notify("示例同步完成：平台状态与费用已更新，订单及员工关联保留");
    };

    const openManual = (row = null) => {
        if (row && row.docState !== "待确认") {
            notify("只有待确认的运输记录可以编辑，请先撤销确认");
            return;
        }
        const origin = row?.route.find((point) => point.kind === "发");
        setEditNo(row?.no || null);
        setManual(
            row
                ? {
                      warehouseCode: row.originWarehouseCode || "",
                      shipper: origin?.name || "",
                      senderContact: origin?.contact || "",
                      senderPhone: origin?.phone || "",
                      senderAddress: origin?.address || "",
                      originSource:
                          row.originSource || (origin ? "manual" : ""),
                      type: links[row.no]?.type || "",
                      nos: links[row.no]?.nos || [],
                      date: row.time.slice(0, 10),
                      carrier: carrierFor(row),
                      waybill: row.waybill || "",
                      platformNo:
                          row.source === "货拉拉同步"
                              ? row.no
                              : row.manualPlatformNo || "",
                      payment: row.payment || "未知",
                      billItems: row.manualBillItems?.map((item) => ({...item})) || [],
                      amount: row.total == null ? "" : String(row.total),
                      owner: row.owner || "",
                      remark:
                          row.source === "货拉拉同步"
                              ? row.internalRemark || ""
                              : row.remark || "",
                      changeReason: "",
                  }
                : {
                      ...emptyShipping,
                      type: "",
                      nos: [],
                      date: "2026-11-09",
                      carrier: "",
                      waybill: "",
                      platformNo: "",
                      payment: "未知",
        billItems: [],
        amount: "",
                      owner: "",
                      remark: "",
                      changeReason: "",
                  },
        );
        setError("");
        setDetailNo(null);
        setModal("manual");
    };
    const updateBusinessSelection = (nos, type = manual.type) => {
        setManual((current) => {
            const next = { ...current, nos, type };
            if (
                editingPlatform ||
                ["warehouse", "manual"].includes(current.originSource)
            )
                return next;
            const selectedOrders = businessOrders.filter((row) =>
                nos.includes(row.no),
            );
            const originCodes = new Set(
                selectedOrders.map((row) => row.originWarehouseCode),
            );
            const code =
                originCodes.size === 1
                    ? selectedOrders[0]?.originWarehouseCode
                    : "";
            return {
                ...next,
                ...shippingFromWarehouse(
                    warehouses.find((row) => row.code === code),
                    "order",
                ),
            };
        });
    };
    const updateShipping = (key, value) =>
        setManual((current) => ({
            ...current,
            [key]: value,
            originSource: "manual",
        }));
    const saveManual = () => {
        if (editingRow && editingRow.docState !== "待确认") {
            setError("只有待确认的运输记录可以编辑");
            return;
        }
        const previous = links[editNo];
        const associationChanged =
            previous &&
            (previous.type !== manual.type ||
                [...previous.nos].sort().join() !==
                    [...manual.nos].sort().join());
        if (associationChanged && !manual.changeReason.trim()) {
            setError("更改或解除已有订单关联，请填写变更原因");
            return;
        }
        if (editingPlatform) {
            if (manual.nos.length && !manual.type) {
                setError("请选择运输类型");
                return;
            }
            setOrders((old) =>
                old.map((row) =>
                    row.no === editNo
                        ? {
                              ...row,
                              carrier: "货拉拉",
                              owner: manual.owner,
                              internalRemark: manual.remark,
                          }
                        : row,
                ),
            );
            setLinks((old) => {
                const next = { ...old };
                if (manual.type && manual.nos.length)
                    next[editNo] = { type: manual.type, nos: manual.nos };
                else delete next[editNo];
                return next;
            });
            setAudits((old) => [
                {
                    no: editNo,
                    time: now,
                    action: "编辑运输记录",
                    content: manual.changeReason || "更新费用负责人及备注",
                    by: "管理员",
                },
                ...old,
            ]);
            close();
            notify("运输记录已保存，货拉拉原始资料保留");
            return;
        }
        if (
            !manual.type ||
            !manual.nos.length ||
            !manual.date ||
            !manual.carrier
        ) {
            setError("请选择运输类型、业务订单、运输日期和物流公司");
            return;
        }
        if (
            manual.amount !== "" &&
            (!Number.isFinite(Number(manual.amount)) ||
                Number(manual.amount) < 0)
        ) {
            setError("运输费用不能小于0");
            return;
        }
        if (
            manual.platformNo &&
            orders.some(
                (x) =>
                    x.no !== editNo && carrierFor(x) === manual.carrier &&
                    (x.no === manual.platformNo ||
                        x.manualPlatformNo === manual.platformNo),
            )
        ) {
            setError(
                "该物流公司的第三方订单已存在，请在已有记录中查看或关联，不能重复登记",
            );
            return;
        }
        if (manual.billItems.some((item) => !item.name.trim() || item.amount === "" || !Number.isFinite(Number(item.amount)))) { setError("请填写每项费用名称和金额"); return; }
        const id = editNo || "MANUAL" + Date.now();
        const refs = businessOrders.filter((x) => manual.nos.includes(x.no));
        const row = {
            no: id,
            recordNo: editNo
                ? orders.find((x) => x.no === id).recordNo
                : `LC20261109${String(orders.length + 1).padStart(4, "0")}`,
            source: "手动录入",
            docState: "待确认",
            creator: editNo
                ? orders.find((x) => x.no === id).creator
                : "管理员",
            carrier: manual.carrier,
            waybill: manual.waybill,
            manualPlatformNo: manual.platformNo,
            time: manual.date + " 00:00:00",
            created: editingRow?.created || now,
            status: "-",
            staffId: null,
            total: manual.amount === "" ? null : Number(manual.amount),
            payment: manual.payment,
            manualBillItems: manual.billItems.map((item) => ({...item})),
            owner: manual.owner,
            remark: manual.remark,
            originWarehouseCode: manual.warehouseCode,
            originSource: manual.originSource,
            vehicle: "-",
            route: [
                ...([
                    manual.shipper,
                    manual.senderContact,
                    manual.senderPhone,
                    manual.senderAddress,
                ].some((value) => value.trim())
                    ? [
                          {
                              kind: "发",
                              name: manual.shipper.trim(),
                              contact: manual.senderContact.trim(),
                              phone: manual.senderPhone.trim(),
                              address: manual.senderAddress.trim(),
                          },
                      ]
                    : []),
                ...refs.map((x) => ({
                    kind: "收",
                    name: x.customer,
                    contact: x.contact,
                    phone: x.phone,
                    address: x.address,
                })),
            ],
            fees:
                manual.amount === ""
                    ? []
                    : [
                          {
                              id: "freight",
                              name: "运费",
                              amount: Number(manual.amount),
                              vouchers: [],
                          },
                      ],
            detail: true,
            synced: "-",
        };
        setOrders((old) =>
            editNo ? old.map((x) => (x.no === id ? row : x)) : [row, ...old],
        );
        setLinks((old) => ({
            ...old,
            [id]: { type: manual.type, nos: manual.nos },
        }));
        setAudits((old) => [
            {
                no: id,
                time: now,
                action: editNo ? "编辑运输记录" : "新增运输记录",
                content: manual.changeReason || manual.remark || "保存运输记录",
                by: "管理员",
            },
            ...old,
        ]);
        close();
        notify("运输记录已保存为待确认");
    };
    const stateAction = (row, action) => {
        const allowedState = {
            确认: "待确认",
            撤销: "已确认",
            作废: "待确认",
            删除: "已作废",
        }[action];
        if (row.docState !== allowedState) {
            notify("当前单据状态不支持此操作");
            return;
        }
        if (
            action === "确认" &&
            (!links[row.no]?.nos.length || row.total == null)
        ) {
            notify("请先关联业务订单，并补齐实际费用后再确认");
            return;
        }
        if (action === "删除") {
            setOrders((old) => old.filter((x) => x.no !== row.no));
        } else {
            setOrders((old) =>
                old.map((x) =>
                    x.no === row.no
                        ? {
                              ...x,
                              confirmedCost: action === "确认" ? x.total : null,
                              confirmedBillKey:
                                  action === "确认" && x.source === "货拉拉同步"
                                      ? billKey(x)
                                      : null,
                              confirmedBillSnapshot:
                                  action === "确认" && x.source === "货拉拉同步"
                                      ? billSnapshot(x)
                                      : null,
                              docState:
                                  action === "确认"
                                      ? "已确认"
                                      : action === "撤销"
                                        ? "待确认"
                                        : "已作废",
                          }
                        : x,
                ),
            );
        }
        setAudits((old) => [
            {
                no: row.no,
                time: billSyncTime,
                action,
                by: "管理员",
                content:
                    action === "确认"
                        ? "确认运输费用 ¥" + money(row.total)
                        : action === "撤销"
                          ? "撤销费用确认，回到待确认"
                          : "运输记录" + action,
            },
            ...old,
        ]);
        setRowMenu(null);
        notify("示例：" + action + "操作已完成");
    };
    const recommend = (order) => {
        const pts = target?.route || [];
        const result = [];
        if (pts.some((p) => p.phone === order.phone))
            result.push("收货电话一致");
        if (
            pts.some(
                (p) =>
                    p.name === order.customer ||
                    p.address.includes(order.address),
            )
        )
            result.push("地点一致");
        return (
            result.join("、") ||
            (target?.time.slice(0, 10) === order.date
                ? "同日订单"
                : "请人工核对")
        );
    };
    const cell = (row, key, plain = false) => {
        const staff = platform(row.staffId);
        const emp = internal(row.staffId);
        const related = associated(row.no);
        const texts = {
            recordNo: row.recordNo,
            source: row.source,
            payment: row.source === "货拉拉同步" ? "-" : row.payment || "未知",
            type: links[row.no]?.type || "-",
            date: row.time.slice(0, 10),
            doc: row.docState,
            creator: row.creator,
            carrier: carrierFor(row),
            waybill: row.waybill || "-",
            no:
                row.source === "货拉拉同步"
                    ? row.no
                    : row.manualPlatformNo || "-",
            time: row.time,
            status: row.status,
            staff: staff?.name || "-",
            destination:
                row.route
                    .filter((x) => x.kind === "收")
                    .map((x) => x.name || x.address)
                    .join("\n") || "-",
            total: money(row.total),
            business: links[row.no]?.nos.join("\n") || "-",
            orderState: links[row.no]?.nos.length ? "已关联" : "未关联",
            staffState: row.staffId
                ? emp
                    ? "已关联"
                    : "未关联"
                : "暂无员工资料",
            created: row.created,
            completed: row.completed || "-",
            origin:
                row.route
                    .filter((x) => x.kind === "发")
                    .map((x) => x.name || x.address)
                    .join("\n") || "-",
            vehicle: row.vehicle,
            paid: money(row.paid),
            unpaid: money(row.unpaid),
            refund: money(row.refund),
            employee: emp?.name || "-",
            phone: staff?.phone || "-",
            sales: [...new Set(related.map((x) => x.sales))].join("\n") || "-",
            dept: [...new Set(related.map((x) => x.dept))].join("\n") || "-",
            synced: row.synced,
        };
        if (plain) return texts[key];
        if (key === "recordNo")
            return (
                <button
                    className="link order-no"
                    onClick={() => setDetailNo(row.no)}
                >
                    {row.recordNo}
                </button>
            );
        if (key === "doc")
            return (
                <Tag
                    tone={
                        row.docState === "已确认"
                            ? "green"
                            : row.docState === "待确认"
                              ? "amber"
                              : ""
                    }
                >
                    {row.docState}
                </Tag>
            );
        if (key === "source")
            return (
                <span
                    className={
                        row.source === "货拉拉同步" ? "blue-text" : "muted"
                    }
                >
                    {row.source}
                </span>
            );
        if (key === "status")
            return (
                <Tag
                    tone={
                        row.status === "已完成"
                            ? "green"
                            : row.status === "进行中"
                              ? "blue"
                              : ""
                    }
                >
                    {row.status}
                </Tag>
            );
        if (key === "orderState" || key === "staffState")
            return (
                <span className={texts[key] === "已关联" ? "success" : "muted"}>
                    {texts[key]}
                </span>
            );
        if (key === "time")
            return (
                <span className="datetime">
                    {row.time.slice(0, 10)}
                    <small>{row.time.slice(11)}</small>
                </span>
            );
        if (key === "total")
            return <strong className="amount">{texts[key]}</strong>;
        return <span className="multiline">{texts[key]}</span>;
    };
    const exportRows = selected.length
        ? orders.filter((x) => selected.includes(x.no))
        : displayed;
    const candidates = businessOrders.filter(
        (x) =>
            x.kind === kindFor[linkType] &&
            x.no.includes(bizFilter.no) &&
            x.customer.includes(bizFilter.customer) &&
            x.sales.includes(bizFilter.sales) &&
            (!bizFilter.dept || x.dept === bizFilter.dept) &&
            (!bizFilter.date || x.date === bizFilter.date),
    );
    const heading =
        page === "orders"
            ? "物流运输记录"
            : page === "staff"
              ? "员工关联"
              : "货拉拉连接";
    return (
        <>
            <div className="review-strip">
                <strong>V2.6 方案预览</strong>
                <span>示例数据 · 操作仅在本页演示，刷新后恢复</span>
                <a href="/prd.html" target="_blank" rel="noreferrer">
                    查看产品需求文档 ↗
                </a>
            </div>
            <div className="shell" inert={!!(modal || detailNo)}>
                <aside>
                    <div className="brand">
                        <img src="/scdp-logo.png" alt="瑞盖供应链" />
                        <strong>供应链数字化平台</strong>
                    </div>
                    <div className="nav-label">成本管理</div>
                    <button
                        className={`nav ${page === "orders" ? "active" : ""}`}
                        onClick={() => setPage("orders")}
                    >
                        <span>▤</span>物流运输记录
                    </button>
                    <button
                        className={`nav ${page === "staff" ? "active" : ""}`}
                        onClick={() => setPage("staff")}
                    >
                        <span>♙</span>员工关联
                    </button>
                    <div className="nav-label">业务设置</div>
                    <button
                        className={`nav ${page === "settings" ? "active" : ""}`}
                        onClick={() => setPage("settings")}
                    >
                        <span>⚙</span>货拉拉连接
                    </button>
                    <div className="side-bottom">瑞盖 · 成本管理</div>
                </aside>
                <div className="workspace">
                    <header>
                        <div className="page-tab">
                            {heading}
                            <span>×</span>
                        </div>
                        <div className="account">
                            瑞盖文化传媒 <span className="avatar">管</span>{" "}
                            管理员
                        </div>
                    </header>
                    <main>
                        {page === "orders" && (
                            <>
                                <section className="filter-card">
                                    <form
                                        onSubmit={(e) => {
                                            e.preventDefault();
                                            setApplied(filters);
                                            setSelected([]);
                                        }}
                                    >
                                        <div className="filter-grid">
                                            <Field label="费用单号">
                                                <input
                                                    placeholder="输入费用单号"
                                                    value={filters.record}
                                                    onChange={(e) =>
                                                        filter(
                                                            "record",
                                                            e.target.value,
                                                        )
                                                    }
                                                />
                                            </Field>
                                            <Field label="数据来源">
                                                <Select
                                                    value={filters.source}
                                                    onChange={(v) =>
                                                        filter("source", v)
                                                    }
                                                    options={[
                                                        "货拉拉同步",
                                                        "手动录入",
                                                    ]}
                                                />
                                            </Field>
                                            <Field label="运输类型">
                                                <Select
                                                    value={filters.type}
                                                    onChange={(v) =>
                                                        filter("type", v)
                                                    }
                                                    options={Object.keys(
                                                        kindFor,
                                                    )}
                                                />
                                            </Field>
                                            <Field label="运输日期">
                                                <span className="date-range">
                                                    <input
                                                        aria-label="运输开始日期"
                                                        type="date"
                                                        value={filters.start}
                                                        onChange={(e) =>
                                                            filter(
                                                                "start",
                                                                e.target.value,
                                                            )
                                                        }
                                                    />
                                                    <span>至</span>
                                                    <input
                                                        aria-label="运输结束日期"
                                                        type="date"
                                                        value={filters.end}
                                                        onChange={(e) =>
                                                            filter(
                                                                "end",
                                                                e.target.value,
                                                            )
                                                        }
                                                    />
                                                </span>
                                            </Field>
                                            <Field label="单据状态">
                                                <Select
                                                    value={filters.doc}
                                                    onChange={(v) =>
                                                        filter("doc", v)
                                                    }
                                                    options={[
                                                        "待确认",
                                                        "已确认",
                                                        "已作废",
                                                    ]}
                                                />
                                            </Field>
                                            <Field label="订单关联状态">
                                                <Select
                                                    value={filters.order}
                                                    onChange={(v) =>
                                                        filter("order", v)
                                                    }
                                                    options={[
                                                        "未关联",
                                                        "已关联",
                                                    ]}
                                                />
                                            </Field>
                                            {more && (
                                                <>
                                                    <Field label="第三方订单号">
                                                        <input
                                                            value={filters.no}
                                                            onChange={(e) =>
                                                                filter(
                                                                    "no",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入货拉拉订单号"
                                                        />
                                                    </Field>
                                                    <Field label="运单号">
                                                        <input
                                                            value={
                                                                filters.waybill
                                                            }
                                                            onChange={(e) =>
                                                                filter(
                                                                    "waybill",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入运单号"
                                                        />
                                                    </Field>
                                                    <Field label="物流公司">
                                                        <Select
                                                            value={
                                                                filters.carrier
                                                            }
                                                            onChange={(v) =>
                                                                filter(
                                                                    "carrier",
                                                                    v,
                                                                )
                                                            }
                                                            options={[
                                                                "货拉拉",
                                                                "顺丰",
                                            "极兔",
                                                                "自营",
                                                                "中通",
                                                            ]}
                                                        />
                                                    </Field>
                                                    <Field label="货拉拉员工姓名">
                                                        <input
                                                            value={filters.name}
                                                            onChange={(e) =>
                                                                filter(
                                                                    "name",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入姓名"
                                                        />
                                                    </Field>
                                                    <Field label="员工关联状态">
                                                        <Select
                                                            value={
                                                                filters.staff
                                                            }
                                                            onChange={(v) =>
                                                                filter(
                                                                    "staff",
                                                                    v,
                                                                )
                                                            }
                                                            options={[
                                                                "未关联",
                                                                "已关联",
                                                                "暂无员工资料",
                                                            ]}
                                                        />
                                                    </Field>
                                                    <Field label="业务单号">
                                                        <input
                                                            value={filters.biz}
                                                            onChange={(e) =>
                                                                filter(
                                                                    "biz",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入业务单号"
                                                        />
                                                    </Field>
                                                    <Field label="业务员">
                                                        <input
                                                            value={
                                                                filters.sales
                                                            }
                                                            onChange={(e) =>
                                                                filter(
                                                                    "sales",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入业务员"
                                                        />
                                                    </Field>
                                                    <Field label="部门">
                                                        <Select
                                                            value={filters.dept}
                                                            onChange={(v) =>
                                                                filter(
                                                                    "dept",
                                                                    v,
                                                                )
                                                            }
                                                            options={[
                                                                "武汉销售部",
                                                                "杭州销售部",
                                                                "杭州运营部",
                                                            ]}
                                                        />
                                                    </Field>
                                                    <Field label="收货地点">
                                                        <input
                                                            value={
                                                                filters.destination
                                                            }
                                                            onChange={(e) =>
                                                                filter(
                                                                    "destination",
                                                                    e.target
                                                                        .value,
                                                                )
                                                            }
                                                            placeholder="输入名称或地址"
                                                        />
                                                    </Field>
                                                </>
                                            )}
                                        </div>
                                        <div className="filter-actions">
                                            <button
                                                type="button"
                                                className="link"
                                                onClick={() => setMore(!more)}
                                            >
                                                {more
                                                    ? "收起条件 ⌃"
                                                    : "更多条件 ⌄"}
                                            </button>
                                            <div>
                                                <button
                                                    type="button"
                                                    onClick={() => {
                                                        setFilters(
                                                            emptyFilters,
                                                        );
                                                        setApplied(
                                                            emptyFilters,
                                                        );
                                                        setSelected([]);
                                                    }}
                                                >
                                                    重置
                                                </button>
                                                <button
                                                    className="primary"
                                                    type="submit"
                                                >
                                                    查询
                                                </button>
                                            </div>
                                        </div>
                                    </form>
                                </section>
                                <section className="list-card">
                                    <div className="toolbar">
                                        <h2>
                                            物流运输记录{" "}
                                            <span className="count">
                                                {displayed.length} 条
                                            </span>
                                        </h2>
                                        <div className="button-group">
                                            <button
                                                onClick={() => setPage("staff")}
                                            >
                                                员工关联
                                            </button>
                                            <button
                                                onClick={() => setModal("runs")}
                                            >
                                                同步记录
                                            </button>
                                            <button
                                                aria-label="显示列"
                                                onClick={() =>
                                                    setModal("columns")
                                                }
                                            >
                                                显示列
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setPreview(false);
                                                    setModal("export");
                                                }}
                                            >
                                                自定义导出
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setError("");
                                                    setModal("sync");
                                                }}
                                            >
                                                同步货拉拉订单
                                            </button>
                                            <button
                                                className="primary"
                                                onClick={() => openManual()}
                                            >
                                                ＋ 新增运输记录
                                            </button>
                                        </div>
                                    </div>
                                    {selected.length > 0 && (
                                        <div className="selection">
                                            已选择 {selected.length} 条
                                            <button
                                                className="link"
                                                onClick={() => setSelected([])}
                                            >
                                                取消选择
                                            </button>
                                            <button
                                                className="link"
                                                onClick={() => {
                                                    setPreview(false);
                                                    setModal("export");
                                                }}
                                            >
                                                导出所选
                                            </button>
                                        </div>
                                    )}
                                    <div className="table-scroll">
                                        <table className="order-table">
                                            <thead>
                                                <tr>
                                                    <th className="check-col">
                                                        <input
                                                            type="checkbox"
                                                            aria-label="选择本页全部订单"
                                                            checked={
                                                                displayed.length >
                                                                    0 &&
                                                                displayed.every(
                                                                    (x) =>
                                                                        selected.includes(
                                                                            x.no,
                                                                        ),
                                                                )
                                                            }
                                                            onChange={(e) =>
                                                                setSelected(
                                                                    e.target
                                                                        .checked
                                                                        ? displayed.map(
                                                                              (
                                                                                  x,
                                                                              ) =>
                                                                                  x.no,
                                                                          )
                                                                        : [],
                                                                )
                                                            }
                                                        />
                                                    </th>
                                                    {allColumns
                                                        .filter(([key]) =>
                                                            columns.includes(
                                                                key,
                                                            ),
                                                        )
                                                        .map(([key, label]) => (
                                                            <th
                                                                key={key}
                                                                className={`col-${key}`}
                                                            >
                                                                {label}
                                                            </th>
                                                        ))}
                                                    <th className="sticky-actions">
                                                        操作
                                                    </th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {displayed.map((row) => (
                                                    <tr key={row.no}>
                                                        <td>
                                                            <input
                                                                type="checkbox"
                                                                aria-label={`选择订单${row.no}`}
                                                                checked={selected.includes(
                                                                    row.no,
                                                                )}
                                                                onChange={() =>
                                                                    toggle(
                                                                        row.no,
                                                                        selected,
                                                                        setSelected,
                                                                    )
                                                                }
                                                            />
                                                        </td>
                                                        {allColumns
                                                            .filter(([key]) =>
                                                                columns.includes(
                                                                    key,
                                                                ),
                                                            )
                                                            .map(([key]) => (
                                                                <td
                                                                    key={key}
                                                                    className={`col-${key}`}
                                                                >
                                                                    {cell(
                                                                        row,
                                                                        key,
                                                                    )}
                                                                </td>
                                                            ))}
                                                        <td className="sticky-actions">
                                                            <div className="row-actions">
                                                                <button
                                                                    className="link"
                                                                    onClick={() =>
                                                                        setDetailNo(
                                                                            row.no,
                                                                        )
                                                                    }
                                                                >
                                                                    查看
                                                                </button>
                                                                {row.docState ===
                                                                    "待确认" && (
                                                                    <button
                                                                        className="link"
                                                                        onClick={() =>
                                                                            openManual(
                                                                                row,
                                                                            )
                                                                        }
                                                                    >
                                                                        编辑
                                                                    </button>
                                                                )}
                                                                <button
                                                                    className="link"
                                                                    onClick={() =>
                                                                        setRowMenu(
                                                                            rowMenu ===
                                                                                row.no
                                                                                ? null
                                                                                : row.no,
                                                                        )
                                                                    }
                                                                >
                                                                    更多⌄
                                                                </button>
                                                                {rowMenu ===
                                                                    row.no && (
                                                                    <div className="action-menu">
                                                                        {row.docState ===
                                                                            "待确认" && (
                                                                            <>
                                                                                <button
                                                                                    onClick={() =>
                                                                                        stateAction(
                                                                                            row,
                                                                                            "确认",
                                                                                        )
                                                                                    }
                                                                                >
                                                                                    确认
                                                                                </button>
                                                                                <button
                                                                                    onClick={() =>
                                                                                        stateAction(
                                                                                            row,
                                                                                            "作废",
                                                                                        )
                                                                                    }
                                                                                >
                                                                                    作废
                                                                                </button>
                                                                            </>
                                                                        )}
                                                                        {row.docState ===
                                                                            "已确认" && (
                                                                            <button
                                                                                onClick={() =>
                                                                                    stateAction(
                                                                                        row,
                                                                                        "撤销",
                                                                                    )
                                                                                }
                                                                            >
                                                                                撤销
                                                                            </button>
                                                                        )}
                                                                        {row.docState ===
                                                                            "已作废" && (
                                                                            <button
                                                                                className="danger"
                                                                                onClick={() =>
                                                                                    stateAction(
                                                                                        row,
                                                                                        "删除",
                                                                                    )
                                                                                }
                                                                            >
                                                                                删除
                                                                            </button>
                                                                        )}
                                                                    </div>
                                                                )}
                                                            </div>
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                        {!displayed.length && (
                                            <div className="empty">
                                                暂无符合条件的物流运输记录
                                            </div>
                                        )}
                                    </div>
                                    <div className="pagination">
                                        <span>共 {displayed.length} 条</span>
                                        <select aria-label="每页条数">
                                            <option>20条/页</option>
                                        </select>
                                        <button aria-label="上一页" disabled>
                                            ‹
                                        </button>
                                        <button className="page-number">
                                            1
                                        </button>
                                        <button aria-label="下一页" disabled>
                                            ›
                                        </button>
                                    </div>
                                </section>
                            </>
                        )}
                        {page === "staff" && (
                            <>
                                <section className="filter-card">
                                    <div className="filter-grid staff-filters">
                                        <Field label="货拉拉员工姓名">
                                            <input
                                                value={staffFilter.name}
                                                onChange={(e) =>
                                                    setStaffFilter({
                                                        ...staffFilter,
                                                        name: e.target.value,
                                                    })
                                                }
                                                placeholder="输入姓名"
                                            />
                                        </Field>
                                        <Field label="手机号">
                                            <input
                                                value={staffFilter.phone}
                                                onChange={(e) =>
                                                    setStaffFilter({
                                                        ...staffFilter,
                                                        phone: e.target.value,
                                                    })
                                                }
                                                placeholder="输入手机号"
                                            />
                                        </Field>
                                        <Field label="关联状态">
                                            <Select
                                                value={staffFilter.state}
                                                onChange={(v) =>
                                                    setStaffFilter({
                                                        ...staffFilter,
                                                        state: v,
                                                    })
                                                }
                                                options={["未关联", "已关联"]}
                                            />
                                        </Field>
                                    </div>
                                </section>
                                <section className="list-card">
                                    <div className="toolbar">
                                        <h2>
                                            员工关联{" "}
                                            <span className="count">
                                                {
                                                    platformStaff.filter(
                                                        (s) =>
                                                            s.name.includes(
                                                                staffFilter.name,
                                                            ) &&
                                                            s.phone.includes(
                                                                staffFilter.phone,
                                                            ) &&
                                                            (!staffFilter.state ||
                                                                (staffLinks[
                                                                    s.id
                                                                ]
                                                                    ? "已关联"
                                                                    : "未关联") ===
                                                                    staffFilter.state),
                                                    ).length
                                                }{" "}
                                                人
                                            </span>
                                        </h2>
                                        <div className="button-group">
                                            <button
                                                onClick={() =>
                                                    setPage("orders")
                                                }
                                            >
                                                返回运输记录
                                            </button>
                                            <button
                                                onClick={() =>
                                                    !connected
                                                        ? setModal(
                                                              "needConnection",
                                                          )
                                                        : notify(
                                                              "示例：平台员工资料已更新，已有员工关联保留",
                                                          )
                                                }
                                            >
                                                同步员工资料
                                            </button>
                                        </div>
                                    </div>
                                    <div className="table-scroll">
                                        <table>
                                            <thead>
                                                <tr>
                                                    {[
                                                        "货拉拉员工姓名",
                                                        "手机号",
                                                        "货拉拉员工编号",
                                                        "本系统员工",
                                                        "本系统所属部门",
                                                        "关联状态",
                                                        "操作",
                                                    ].map((x) => (
                                                        <th key={x}>{x}</th>
                                                    ))}
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {platformStaff
                                                    .filter(
                                                        (s) =>
                                                            s.name.includes(
                                                                staffFilter.name,
                                                            ) &&
                                                            s.phone.includes(
                                                                staffFilter.phone,
                                                            ) &&
                                                            (!staffFilter.state ||
                                                                (staffLinks[
                                                                    s.id
                                                                ]
                                                                    ? "已关联"
                                                                    : "未关联") ===
                                                                    staffFilter.state),
                                                    )
                                                    .map((s) => (
                                                        <tr key={s.id}>
                                                            <td>{s.name}</td>
                                                            <td>{s.phone}</td>
                                                            <td>{s.id}</td>
                                                            <td>
                                                                {internal(s.id)
                                                                    ?.name ||
                                                                    "-"}
                                                            </td>
                                                            <td>
                                                                {internal(s.id)
                                                                    ?.dept ||
                                                                    "-"}
                                                            </td>
                                                            <td>
                                                                <Tag
                                                                    tone={
                                                                        staffLinks[
                                                                            s.id
                                                                        ]
                                                                            ? "green"
                                                                            : "amber"
                                                                    }
                                                                >
                                                                    {staffLinks[
                                                                        s.id
                                                                    ]
                                                                        ? "已关联"
                                                                        : "未关联"}
                                                                </Tag>
                                                            </td>
                                                            <td>
                                                                <button
                                                                    className="link"
                                                                    onClick={() =>
                                                                        openEmployee(
                                                                            s.id,
                                                                        )
                                                                    }
                                                                >
                                                                    {staffLinks[
                                                                        s.id
                                                                    ]
                                                                        ? "更改关联"
                                                                        : "关联员工"}
                                                                </button>
                                                            </td>
                                                        </tr>
                                                    ))}
                                            </tbody>
                                        </table>
                                    </div>
                                </section>
                            </>
                        )}
                        {page === "settings" && (
                            <section className="settings">
                                <Section title="账户连接">
                                    <div className="authorization">
                                        <div>
                                            <strong>货拉拉企业账户</strong>
                                            <p>
                                                <Tag
                                                    tone={
                                                        connected
                                                            ? "green"
                                                            : "amber"
                                                    }
                                                >
                                                    {connected
                                                        ? "已连接"
                                                        : "未连接"}
                                                </Tag>
                                                <span className="muted">
                                                    方案示例状态
                                                </span>
                                            </p>
                                        </div>
                                        <button
                                            className="primary"
                                            onClick={() => {
                                                setConnected(true);
                                                notify(
                                                    "示例：货拉拉账户已连接，可以演示同步流程",
                                                );
                                            }}
                                        >
                                            {connected
                                                ? "重新连接"
                                                : "连接货拉拉"}
                                        </button>
                                    </div>
                                </Section>
                                <Section title="同步设置">
                                    <div className="filter-grid">
                                        <Field label="自动同步">
                                            <span className="switch-field">
                                                <input
                                                    type="checkbox"
                                                    checked={autoSync}
                                                    disabled={!connected}
                                                    onChange={(e) =>
                                                        setAutoSync(
                                                            e.target.checked,
                                                        )
                                                    }
                                                />
                                                启用
                                            </span>
                                        </Field>
                                        <Field label="同步频率">
                                            <Select
                                                value={frequency}
                                                onChange={setFrequency}
                                                disabled={!connected}
                                                placeholder="请选择"
                                                options={[
                                                    "15分钟",
                                                    "30分钟",
                                                    "1小时",
                                                ]}
                                            />
                                        </Field>
                                    </div>
                                    <div className="settings-save">
                                        <button
                                            disabled={!connected}
                                            className="primary"
                                            onClick={() =>
                                                notify("示例同步设置已保存")
                                            }
                                        >
                                            保存设置
                                        </button>
                                    </div>
                                </Section>
                            </section>
                        )}
                    </main>
                </div>
            </div>
            {detail && (
                <Modal
                    title="物流运输记录详情"
                    inactive={!!(modal || voucher)}
                    close={() => setDetailNo(null)}
                    wide
                    record
                    footer={
                        <button onClick={() => setDetailNo(null)}>关闭</button>
                    }
                >
                    <div className="detail-title">
                        <div>
                            <small>费用单号</small>
                            <h1>{detail.recordNo}</h1>
                            <Tag
                                tone={
                                    detail.docState === "已确认"
                                        ? "green"
                                        : detail.docState === "待确认"
                                          ? "amber"
                                          : ""
                                }
                            >
                                {detail.docState}
                            </Tag>
                        </div>
                        <div className="button-group">
                            {detail.docState === "待确认" && (
                                <button onClick={() => openManual(detail)}>
                                    编辑
                                </button>
                            )}
                            {detail.source === "货拉拉同步" && (
                                <button onClick={() => syncSingleBill(detail)}>
                                    同步此单
                                </button>
                            )}
                        </div>
                    </div>
                    {!detail.detail && (
                        <div className="notice warning">
                            详细信息暂未同步，请重新同步此单。
                        </div>
                    )}
                    <Section title="基本信息">
                        <dl className="info-grid">
                            <Info label="数据来源">{detail.source}</Info>
                            <Info label="运输类型">
                                {links[detail.no]?.type || "-"}
                            </Info>
                            <Info label="订单类型">
                                {kindFor[links[detail.no]?.type] || "-"}
                            </Info>
                            <Info label="运输日期">
                                {detail.time?.slice(0, 10) || "-"}
                            </Info>
                            <Info label="物流公司">{carrierFor(detail)}</Info>
                            <Info label="运单号">{detail.waybill || "-"}</Info>
                            {(detail.source === "货拉拉同步" ||
                                detail.manualPlatformNo) && (
                                <Info label="第三方订单号">
                                    {detail.source === "货拉拉同步"
                                        ? detail.no
                                        : detail.manualPlatformNo}
                                </Info>
                            )}
                        </dl>
                    </Section>
                    <Section
                        title="关联业务订单"
                        action={
                            detail.docState === "待确认" && (
                                <button
                                    className="link"
                                    onClick={() =>
                                        detail.source === "货拉拉同步"
                                            ? openLink(detail.no)
                                            : openManual(detail)
                                    }
                                >
                                    {links[detail.no] ? "更改关联" : "关联订单"}
                                </button>
                            )
                        }
                    >
                        <BusinessOrderTable rows={associated(detail.no)} />
                    </Section>
                    <Section title="发货信息">
                        <ShippingStops
                            warehouseName={
                                detail.source === "手动录入"
                                    ? warehouses.find(
                                          (warehouse) =>
                                              warehouse.code ===
                                              detail.originWarehouseCode,
                                      )?.name
                                    : null
                            }
                            points={detail.route.filter(
                                (point) => point.kind === "发",
                            )}
                        />
                    </Section>
                    <Section title="收货信息">
                        <DeliveryStops
                            points={detail.route.filter(
                                (point) => point.kind === "收",
                            )}
                        />
                    </Section>
                    <Section title="费用信息">
                        <dl className={`info-grid ${detail.source !== "货拉拉同步" ? "manual-bill-summary" : ""}`}>
                            <Info label="运输费用（元）">
                                {money(detail.total)}
                            </Info>
                            {detail.source !== "货拉拉同步" && <Info label="付款状态">{detail.payment || "未知"}</Info>}
                            <Info label="费用负责人">
                                {detail.owner || "-"}
                            </Info>
                            <Info label="已确认运输成本（元）">
                                {detail.docState === "已确认"
                                    ? money(detail.confirmedCost)
                                    : "-"}
                            </Info>
                        </dl>
                        {detail.source !== "货拉拉同步" && detail.manualBillItems?.length > 0 && <><h4>账单明细</h4><table><thead><tr><th>费用项目</th><th>金额（元）</th></tr></thead><tbody>{detail.manualBillItems.map((item,index)=><tr key={index}><td>{item.name}</td><td className="numeric">{money(item.amount)}</td></tr>)}</tbody></table></>}
                        {detail.source === "货拉拉同步" && (
                            <PlatformBill
                                row={detail}
                                onVoucher={setVoucher}
                                onRevoke={() => stateAction(detail, "撤销")}
                            />
                        )}
                    </Section>
                    <Section title="备注">
                        <p className="record-remark">
                            {(detail.source === "货拉拉同步"
                                ? detail.internalRemark
                                : detail.remark) || "-"}
                        </p>
                    </Section>
                    {detail.source === "货拉拉同步" && (
                        <>
                            <Section title="货拉拉订单信息">
                                <dl className="info-grid">
                                    <Info label="货拉拉订单状态">
                                        {detail.status}
                                    </Info>
                                    <Info label="用车时间">{detail.time}</Info>
                                    <Info label="下单时间">
                                        {detail.created}
                                    </Info>
                                    <Info label="完成时间">
                                        {detail.completed || "-"}
                                    </Info>
                                    <Info label="车型">
                                        {detail.vehicle || "-"}
                                    </Info>
                                    <Info label="用车原因">
                                        {detail.reason || "-"}
                                    </Info>
                                    <Info label="货拉拉订单备注">
                                        {detail.remark || "-"}
                                    </Info>
                                </dl>
                            </Section>
                            <Section title="司机与车辆">
                                <dl className="info-grid">
                                    <Info label="司机">
                                        {detail.driver || "-"}
                                    </Info>
                                    <Info label="司机联系电话">
                                        {detail.driverPhone || "-"}
                                    </Info>
                                    <Info label="车牌号">
                                        {detail.plate || "-"}
                                    </Info>
                                </dl>
                            </Section>
                            <Section
                                title="人员关联"
                                action={
                                    detail.staffId && (
                                        <button
                                            className="link"
                                            onClick={() =>
                                                openEmployee(detail.staffId)
                                            }
                                        >
                                            {staffLinks[detail.staffId]
                                                ? "更改关联"
                                                : "关联员工"}
                                        </button>
                                    )
                                }
                            >
                                <dl className="info-grid">
                                    <Info label="货拉拉下单员工">
                                        {platform(detail.staffId)?.name || "-"}
                                    </Info>
                                    <Info label="平台员工手机号">
                                        {platform(detail.staffId)?.phone || "-"}
                                    </Info>
                                    <Info label="本系统员工">
                                        {internal(detail.staffId)?.name || "-"}
                                    </Info>
                                    <Info label="本系统员工部门">
                                        {internal(detail.staffId)?.dept || "-"}
                                    </Info>
                                    <Info label="员工关联状态">
                                        {detail.staffId
                                            ? staffLinks[detail.staffId]
                                                ? "已关联"
                                                : "未关联"
                                            : "暂无员工资料"}
                                    </Info>
                                </dl>
                            </Section>
                            <Section title="运输回单">
                                <div className="empty compact">
                                    暂无平台回单
                                </div>
                            </Section>
                        </>
                    )}
                    <Section title="操作记录">
                        <div className="table-scroll">
                            <table>
                                <thead>
                                    <tr>
                                        {["时间", "操作", "操作人", "内容"].map(
                                            (label) => (
                                                <th key={label}>{label}</th>
                                            ),
                                        )}
                                    </tr>
                                </thead>
                                <tbody>
                                    {audits
                                        .filter(
                                            (item) =>
                                                item.no === detail.no ||
                                                (item.no === "*" &&
                                                    item.staffId ===
                                                        detail.staffId),
                                        )
                                        .map((item, index) => (
                                            <tr key={index}>
                                                <td className="nowrap">
                                                    {item.time}
                                                </td>
                                                <td>{item.action}</td>
                                                <td>{item.by}</td>
                                                <td>{item.content}</td>
                                            </tr>
                                        ))}
                                </tbody>
                            </table>
                        </div>
                        {!audits.some(
                            (item) =>
                                item.no === detail.no ||
                                (item.no === "*" &&
                                    item.staffId === detail.staffId),
                        ) && <div className="empty compact">暂无操作记录</div>}
                    </Section>
                    <p className="sync-time">
                        创建人：{detail.creator}　　
                        {detail.source === "货拉拉同步"
                            ? "最近同步：" + detail.synced
                            : "创建时间：" + detail.created}
                    </p>
                </Modal>
            )}
            {voucher && (
                <Modal
                    title="费用凭证"
                    close={() => setVoucher(null)}
                    footer={
                        <>
                            <button onClick={() => setVoucher(null)}>
                                关闭预览
                            </button>
                            <a
                                className="button primary"
                                href={voucher.url}
                                download={voucher.name}
                            >
                                下载凭证
                            </a>
                        </>
                    }
                >
                    <dl className="info-grid">
                        <Info label="所属费用">{voucher.feeName}</Info>
                        <Info label="费用金额（元）">
                            {money(voucher.amount)}
                        </Info>
                        <Info label="文件">{voucher.name}</Info>
                    </dl>
                    <iframe
                        title="凭证文件预览"
                        className="voucher-preview"
                        src={voucher.url}
                    />
                </Modal>
            )}
            {modal === "manual" && (
                <Modal
                    title={editNo ? "编辑运输记录" : "新增运输记录"}
                    close={close}
                    wide
                    record
                    footer={
                        <>
                            <button onClick={close}>取消</button>
                            <button className="primary" onClick={saveManual}>
                                保存运输记录
                            </button>
                        </>
                    }
                >
                    <Section title="基本信息">
                        <div className="filter-grid">
                            <ReadonlyField
                                label="数据来源"
                                value={
                                    editingPlatform ? "货拉拉同步" : "手动录入"
                                }
                            />
                            <Field label="运输类型" required={!editingPlatform}>
                                <Select
                                    value={manual.type}
                                    onChange={(value) =>
                                        updateBusinessSelection([], value)
                                    }
                                    placeholder="请选择运输类型"
                                    options={Object.keys(kindFor)}
                                />
                            </Field>
                            <ReadonlyField
                                label="订单类型"
                                value={kindFor[manual.type] || "-"}
                            />
                            {editingPlatform ? (
                                <ReadonlyField
                                    label="运输日期"
                                    value={manual.date}
                                />
                            ) : (
                                <Field label="运输日期" required>
                                    <input
                                        type="date"
                                        value={manual.date}
                                        onChange={(event) =>
                                            setManual({
                                                ...manual,
                                                date: event.target.value,
                                            })
                                        }
                                    />
                                </Field>
                            )}
                            {editingPlatform ? (
                                <ReadonlyField
                                    label="物流公司"
                                    value="货拉拉"
                                />
                            ) : (
                                <Field label="物流公司" required>
                                    <Select
                                        value={manual.carrier}
                                        onChange={(value) =>
                                            setManual({
                                                ...manual,
                                                carrier: value,
                                            })
                                        }
                                        options={[
                                            "货拉拉",
                                            "顺丰",
                                            "极兔",
                                            "自营",
                                            "中通",
                                        ]}
                                        placeholder="请选择物流公司"
                                    />
                                </Field>
                            )}
                            {editingPlatform ? (
                                <ReadonlyField
                                    label="运单号"
                                    value={manual.waybill}
                                />
                            ) : (
                                <Field label="运单号">
                                    <input
                                        value={manual.waybill}
                                        onChange={(event) =>
                                            setManual({
                                                ...manual,
                                                waybill: event.target.value,
                                            })
                                        }
                                        placeholder="填写物流运单号"
                                    />
                                </Field>
                            )}
                            {editingPlatform ? (
                                    <ReadonlyField
                                        label="第三方订单号"
                                        value={manual.platformNo}
                                    />
                                ) : (
                                    <Field label="第三方订单号">
                                        <input
                                            value={manual.platformNo}
                                            onChange={(event) =>
                                                setManual({
                                                    ...manual,
                                                    platformNo:
                                                        event.target.value,
                                                })
                                            }
                                            placeholder="填写物流公司提供的订单号"
                                        />
                                    </Field>
                                )}
                        </div>
                    </Section>
                    <Section title="关联业务订单">
                        {manual.type ? (
                            <div className="table-scroll">
                                <table>
                                    <thead>
                                        <tr>
                                            {[
                                                "勾选",
                                                "业务单号",
                                                "客户／收货仓库",
                                                "业务员",
                                                "部门",
                                                "订单日期",
                                                "业务订单金额（元）",
                                            ].map((label) => (
                                                <th key={label}>{label}</th>
                                            ))}
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {businessOrders
                                            .filter(
                                                (row) =>
                                                    row.kind ===
                                                    kindFor[manual.type],
                                            )
                                            .map((row) => (
                                                <tr key={row.no}>
                                                    <td>
                                                        <input
                                                            type="checkbox"
                                                            aria-label={
                                                                "关联业务单" +
                                                                row.no
                                                            }
                                                            checked={manual.nos.includes(
                                                                row.no,
                                                            )}
                                                            onChange={() =>
                                                                updateBusinessSelection(
                                                                    manual.nos.includes(
                                                                        row.no,
                                                                    )
                                                                        ? manual.nos.filter(
                                                                              (
                                                                                  no,
                                                                              ) =>
                                                                                  no !==
                                                                                  row.no,
                                                                          )
                                                                        : [
                                                                              ...manual.nos,
                                                                              row.no,
                                                                          ],
                                                                )
                                                            }
                                                        />
                                                    </td>
                                                    <td>{row.no}</td>
                                                    <td>{row.customer}</td>
                                                    <td>{row.sales}</td>
                                                    <td>{row.dept}</td>
                                                    <td>{row.date}</td>
                                                    <td className="numeric">
                                                        {money(row.amount)}
                                                    </td>
                                                </tr>
                                            ))}
                                    </tbody>
                                </table>
                            </div>
                        ) : (
                            <div className="empty compact">
                                请先选择运输类型，再选择业务订单
                            </div>
                        )}
                    </Section>
                    <Section title="发货信息">
                        {editingPlatform ? (
                            <ShippingStops
                                points={editingRow.route.filter(
                                    (point) => point.kind === "发",
                                )}
                            />
                        ) : (
                            <>
                                <div className="filter-grid shipping-form">
                                    <Field label="发货仓库">
                                        <select
                                            value={manual.warehouseCode}
                                            onChange={(event) =>
                                                setManual((current) => ({
                                                    ...current,
                                                    ...shippingFromWarehouse(
                                                        warehouses.find(
                                                            (row) =>
                                                                row.code ===
                                                                event.target
                                                                    .value,
                                                        ),
                                                    ),
                                                }))
                                            }
                                        >
                                            <option value="">
                                                请选择发货仓库
                                            </option>
                                            {warehouses.map((row) => (
                                                <option
                                                    key={row.code}
                                                    value={row.code}
                                                >
                                                    {row.name}
                                                </option>
                                            ))}
                                        </select>
                                    </Field>
                                    <Field label="发货方">
                                        <input
                                            value={manual.shipper}
                                            onChange={(event) =>
                                                updateShipping(
                                                    "shipper",
                                                    event.target.value,
                                                )
                                            }
                                            placeholder="填写发货方名称"
                                        />
                                    </Field>
                                    <Field label="发货联系人">
                                        <input
                                            value={manual.senderContact}
                                            onChange={(event) =>
                                                updateShipping(
                                                    "senderContact",
                                                    event.target.value,
                                                )
                                            }
                                            placeholder="填写发货联系人"
                                        />
                                    </Field>
                                    <Field label="发货联系电话">
                                        <input
                                            value={manual.senderPhone}
                                            onChange={(event) =>
                                                updateShipping(
                                                    "senderPhone",
                                                    event.target.value,
                                                )
                                            }
                                            placeholder="填写发货联系电话"
                                        />
                                    </Field>
                                    <Field label="发货地址">
                                        <input
                                            value={manual.senderAddress}
                                            onChange={(event) =>
                                                updateShipping(
                                                    "senderAddress",
                                                    event.target.value,
                                                )
                                            }
                                            placeholder="填写完整发货地址"
                                        />
                                    </Field>
                                </div>
                            </>
                        )}
                    </Section>
                    <Section title="收货信息">
                        <DeliveryStops
                            points={
                                editingPlatform
                                    ? editingRow.route.filter(
                                          (point) => point.kind === "收",
                                      )
                                    : businessOrders
                                          .filter((row) =>
                                              manual.nos.includes(row.no),
                                          )
                                          .map((row) => ({
                                              name: row.customer,
                                              contact: row.contact,
                                              phone: row.phone,
                                              address: row.address,
                                          }))
                            }
                        />
                    </Section>
                    <Section title="费用信息">
                        <div className={`filter-grid ${!editingPlatform ? "manual-bill-summary" : ""}`}>
                            {editingPlatform ? (
                                <ReadonlyField
                                    label="运输费用（元）"
                                    value={money(editingRow.total)}
                                />
                            ) : (
                                <Field label="运输费用（元）">
                                    <input
                                        type="number"
                                        min="0"
                                        step="0.01"
                                        placeholder="填写运输费用"
                                        readOnly={manual.billItems.length > 0}
                                        value={manual.amount}
                                        onChange={(event) =>
                                            setManual({
                                                ...manual,
                                                amount: event.target.value,
                                            })
                                        }
                                    />
                                </Field>
                            )}
                            <Field label="费用负责人">
                                <Select
                                    value={manual.owner}
                                    onChange={(value) =>
                                        setManual({ ...manual, owner: value })
                                    }
                                    options={hr.map((row) => row.name)}
                                    placeholder="请选择费用负责人"
                                />
                            </Field>
                            {!editingPlatform && <Field label="付款状态"><Select value={manual.payment} onChange={(value) => setManual({...manual,payment:value})} options={["未知","未付款","已付款"]} /></Field>}
                        </div>
                        {!editingPlatform && <div className="manual-bill">
                          <div className="section-heading"><h4>账单明细（选填）</h4><button className="link" onClick={() => updateBillItems([...manual.billItems,{name:"",amount:""}])}>添加费用项目</button></div>
                          {manual.billItems.length > 0 && <table><thead><tr><th>费用项目</th><th>金额（元，优惠填负数）</th><th>操作</th></tr></thead><tbody>{manual.billItems.map((item,index)=><tr key={index}><td><input value={item.name} placeholder="填写费用项目" onChange={(event)=>updateBillItems(manual.billItems.map((row,i)=>i===index?{...row,name:event.target.value}:row))}/></td><td><input type="number" step="0.01" value={item.amount} onChange={(event)=>updateBillItems(manual.billItems.map((row,i)=>i===index?{...row,amount:event.target.value}:row))}/></td><td><button className="link danger" onClick={()=>updateBillItems(manual.billItems.filter((_,i)=>i!==index))}>移除</button></td></tr>)}</tbody></table>}
                        </div>}
                    </Section>
                    <Section title="备注">
                        <Field label="备注">
                            <textarea
                                value={manual.remark}
                                onChange={(event) =>
                                    setManual({
                                        ...manual,
                                        remark: event.target.value,
                                    })
                                }
                                placeholder="填写本次运输的业务备注"
                            />
                        </Field>
                        {editNo && links[editNo] && (
                            <Field label="变更原因">
                                <textarea
                                    value={manual.changeReason}
                                    onChange={(event) =>
                                        setManual({
                                            ...manual,
                                            changeReason: event.target.value,
                                        })
                                    }
                                    placeholder="更改或解除已有订单关联时填写"
                                />
                            </Field>
                        )}
                    </Section>
                    {editingPlatform && (
                        <Section title="货拉拉订单信息">
                            <dl className="info-grid">
                                <Info label="货拉拉订单状态">
                                    {editingRow.status}
                                </Info>
                                <Info label="用车时间">{editingRow.time}</Info>
                                <Info label="下单时间">
                                    {editingRow.created}
                                </Info>
                                <Info label="完成时间">
                                    {editingRow.completed || "-"}
                                </Info>
                                <Info label="车型">
                                    {editingRow.vehicle || "-"}
                                </Info>
                                <Info label="用车原因">
                                    {editingRow.reason || "-"}
                                </Info>
                                <Info label="货拉拉订单备注">
                                    {editingRow.remark || "-"}
                                </Info>
                            </dl>
                        </Section>
                    )}
                    {error && (
                        <p className="error" role="alert">
                            {error}
                        </p>
                    )}
                </Modal>
            )}
            {modal === "link" && target && (
                <Modal
                    title={
                        links[targetNo] ? "更改业务订单关联" : "关联业务订单"
                    }
                    close={close}
                    wide
                    footer={
                        <>
                            <span className="footer-left">
                                已勾选 {picked.length} 张业务订单
                            </span>
                            {links[targetNo] && (
                                <button
                                    className="danger"
                                    onClick={unlinkOrders}
                                >
                                    解除全部关联
                                </button>
                            )}
                            <button onClick={close}>取消</button>
                            <button className="primary" onClick={saveLink}>
                                保存关联
                            </button>
                        </>
                    }
                >
                    <div className="source-summary">
                        <Info label="第三方订单号">{target.no}</Info>
                        <Info label="用车时间">{target.time}</Info>
                        <Info label="订单费用（元）">
                            {money(target.total)}
                        </Info>
                        <Info label="送达地点">
                            {target.route
                                .filter((x) => x.kind === "收")
                                .map((x) => x.name || x.address)
                                .join("；") || "-"}
                        </Info>
                    </div>
                    <div className="type-grid">
                        <Field label="运输类型" required>
                            <Select
                                value={linkType}
                                onChange={(v) => {
                                    setLinkType(v);
                                    setPicked([]);
                                }}
                                options={Object.keys(kindFor)}
                                placeholder="请选择运输类型"
                            />
                        </Field>
                        <Field label="订单类型">
                            <input
                                value={
                                    kindFor[linkType] ||
                                    "选择运输类型后自动带出"
                                }
                                readOnly
                                className="readonly"
                            />
                        </Field>
                    </div>
                    {linkType ? (
                        <>
                            <div className="filter-grid biz-filters">
                                <Field label="业务单号">
                                    <input
                                        value={bizFilter.no}
                                        onChange={(e) =>
                                            setBizFilter({
                                                ...bizFilter,
                                                no: e.target.value,
                                            })
                                        }
                                        placeholder="输入单号"
                                    />
                                </Field>
                                <Field label="客户／收货仓库">
                                    <input
                                        value={bizFilter.customer}
                                        onChange={(e) =>
                                            setBizFilter({
                                                ...bizFilter,
                                                customer: e.target.value,
                                            })
                                        }
                                        placeholder="输入名称"
                                    />
                                </Field>
                                <Field label="业务员">
                                    <input
                                        value={bizFilter.sales}
                                        onChange={(e) =>
                                            setBizFilter({
                                                ...bizFilter,
                                                sales: e.target.value,
                                            })
                                        }
                                        placeholder="输入姓名"
                                    />
                                </Field>
                                <Field label="部门">
                                    <Select
                                        value={bizFilter.dept}
                                        onChange={(v) =>
                                            setBizFilter({
                                                ...bizFilter,
                                                dept: v,
                                            })
                                        }
                                        options={[
                                            "武汉销售部",
                                            "杭州销售部",
                                            "杭州运营部",
                                        ]}
                                    />
                                </Field>
                                <Field label="订单日期">
                                    <input
                                        type="date"
                                        value={bizFilter.date}
                                        onChange={(e) =>
                                            setBizFilter({
                                                ...bizFilter,
                                                date: e.target.value,
                                            })
                                        }
                                    />
                                </Field>
                            </div>
                            <div className="table-scroll">
                                <table>
                                    <thead>
                                        <tr>
                                            <th></th>
                                            {[
                                                "业务单号",
                                                "客户／收货仓库",
                                                "业务员",
                                                "部门",
                                                "订单日期",
                                                "业务订单金额（元）",
                                                "已关联运输",
                                                "推荐依据",
                                            ].map((x) => (
                                                <th key={x}>{x}</th>
                                            ))}
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {candidates.map((x) => (
                                            <tr key={x.no}>
                                                <td>
                                                    <input
                                                        type="checkbox"
                                                        aria-label={`关联${x.no}`}
                                                        checked={picked.includes(
                                                            x.no,
                                                        )}
                                                        onChange={() =>
                                                            toggle(
                                                                x.no,
                                                                picked,
                                                                setPicked,
                                                            )
                                                        }
                                                    />
                                                </td>
                                                <td className="nowrap">
                                                    {x.no}
                                                </td>
                                                <td>{x.customer}</td>
                                                <td>{x.sales}</td>
                                                <td>{x.dept}</td>
                                                <td className="nowrap">
                                                    {x.date}
                                                </td>
                                                <td className="numeric">
                                                    {money(x.amount)}
                                                </td>
                                                <td>
                                                    {
                                                        Object.values(
                                                            links,
                                                        ).filter((entry) =>
                                                            entry.nos.includes(
                                                                x.no,
                                                            ),
                                                        ).length
                                                    }{" "}
                                                    笔
                                                </td>
                                                <td className="muted">
                                                    {recommend(x)}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                                {!candidates.length && (
                                    <div className="empty compact">
                                        暂无符合条件的业务订单
                                    </div>
                                )}
                            </div>
                        </>
                    ) : (
                        <div className="empty">
                            请先选择运输类型，再勾选对应业务订单
                        </div>
                    )}
                    {links[targetNo] && (
                        <Field label="更改／解除原因" required>
                            <textarea
                                value={reason}
                                onChange={(e) => setReason(e.target.value)}
                                placeholder="说明本次关联调整的原因"
                            />
                        </Field>
                    )}
                    {error && (
                        <p role="alert" className="error">
                            {error}
                        </p>
                    )}
                </Modal>
            )}
            {modal === "employee" && (
                <Modal
                    title={
                        staffLinks[staffTarget] ? "更改员工关联" : "关联员工"
                    }
                    close={close}
                    footer={
                        <>
                            {staffLinks[staffTarget] && (
                                <button
                                    className="danger footer-left"
                                    onClick={unlinkEmployee}
                                >
                                    解除关联
                                </button>
                            )}
                            <button onClick={close}>取消</button>
                            <button className="primary" onClick={saveEmployee}>
                                保存关联
                            </button>
                        </>
                    }
                >
                    <div className="source-summary three">
                        <Info label="货拉拉员工">
                            {platform(staffTarget)?.name}
                        </Info>
                        <Info label="手机号">
                            {platform(staffTarget)?.phone}
                        </Info>
                        <Info label="本系统所属部门">
                            {hr.find((x) => x.id === employee)?.dept || "-"}
                        </Info>
                    </div>
                    <Field label="本系统员工" required>
                        <input
                            placeholder="搜索姓名、员工编号或手机号"
                            value={employeeSearch}
                            onChange={(e) => setEmployeeSearch(e.target.value)}
                        />
                    </Field>
                    <div className="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    {[
                                        "选择",
                                        "姓名",
                                        "员工编号",
                                        "手机号",
                                        "部门",
                                        "状态",
                                    ].map((x) => (
                                        <th key={x}>{x}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {hr
                                    .filter((x) =>
                                        (x.name + x.id + x.phone).includes(
                                            employeeSearch,
                                        ),
                                    )
                                    .map((x) => (
                                        <tr key={x.id}>
                                            <td>
                                                <input
                                                    type="radio"
                                                    name="employee"
                                                    aria-label={`选择员工${x.name}`}
                                                    checked={employee === x.id}
                                                    onChange={() =>
                                                        setEmployee(x.id)
                                                    }
                                                />
                                            </td>
                                            <td>{x.name}</td>
                                            <td>{x.id}</td>
                                            <td>{x.phone}</td>
                                            <td>{x.dept}</td>
                                            <td>{x.state}</td>
                                        </tr>
                                    ))}
                            </tbody>
                        </table>
                    </div>
                    <p className="business-hint">
                        保存一次后，该货拉拉员工的相关订单都会显示本系统对应员工。
                    </p>
                    {staffLinks[staffTarget] && (
                        <Field label="更改／解除原因" required>
                            <textarea
                                value={reason}
                                onChange={(e) => setReason(e.target.value)}
                                placeholder="说明本次员工关联调整的原因"
                            />
                        </Field>
                    )}
                    {error && (
                        <p className="error" role="alert">
                            {error}
                        </p>
                    )}
                </Modal>
            )}
            {(modal === "needConnection" ||
                (modal === "sync" && !connected)) && (
                <Modal
                    title="连接货拉拉"
                    close={close}
                    footer={
                        <>
                            <button onClick={close}>取消</button>
                            <button
                                className="primary"
                                onClick={() => {
                                    close();
                                    setDetailNo(null);
                                    setPage("settings");
                                }}
                            >
                                前往连接
                            </button>
                        </>
                    }
                >
                    <p className="connection-message">
                        尚未连接货拉拉账户，请先连接后再同步。已保存的运输记录仍可查看，也可以继续手动录入。
                    </p>
                </Modal>
            )}
            {modal === "sync" && connected && (
                <Modal
                    title="同步货拉拉订单"
                    close={close}
                    footer={
                        <>
                            <button onClick={close}>取消</button>
                            <button className="primary" onClick={sync}>
                                开始同步
                            </button>
                        </>
                    }
                >
                    <div className="filter-grid two">
                        <Field label="用车开始日期" required>
                            <input
                                type="date"
                                value={syncRange.start}
                                onChange={(e) =>
                                    setSyncRange({
                                        ...syncRange,
                                        start: e.target.value,
                                    })
                                }
                            />
                        </Field>
                        <Field label="用车结束日期" required>
                            <input
                                type="date"
                                value={syncRange.end}
                                onChange={(e) =>
                                    setSyncRange({
                                        ...syncRange,
                                        end: e.target.value,
                                    })
                                }
                            />
                        </Field>
                    </div>
                    <p className="business-hint">
                        同步后可直接查看订单列表和详情，已保存的订单及员工关联会保留。
                    </p>
                    {error && <p className="error">{error}</p>}
                </Modal>
            )}
            {modal === "runs" && (
                <Modal
                    title="同步记录"
                    close={close}
                    wide
                    footer={<button onClick={close}>关闭</button>}
                >
                    <div className="table-scroll">
                        <table>
                            <thead>
                                <tr>
                                    {[
                                        "同步时间",
                                        "结果",
                                        "列表已保存",
                                        "详情已保存",
                                        "详情待补",
                                        "失败",
                                    ].map((x) => (
                                        <th key={x}>{x}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {runs.map((x, i) => (
                                    <tr key={i}>
                                        <td>{x.time}</td>
                                        <td>
                                            <Tag
                                                tone={
                                                    x.pending
                                                        ? "amber"
                                                        : "green"
                                                }
                                            >
                                                {x.state}
                                            </Tag>
                                        </td>
                                        <td>{x.list}</td>
                                        <td>{x.detail}</td>
                                        <td>{x.pending}</td>
                                        <td>{x.failed}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    {orders.some((x) => !x.detail) && (
                        <Section title="详情待补订单">
                            {orders
                                .filter((x) => !x.detail)
                                .map((x) => (
                                    <div className="pending-detail" key={x.no}>
                                        <span>{x.no}</span>
                                        <span className="muted">
                                            列表已保存，详情未取得
                                        </span>
                                        <button
                                            className="link"
                                            onClick={() => retryDetail(x.no)}
                                        >
                                            重新同步此单
                                        </button>
                                    </div>
                                ))}
                        </Section>
                    )}
                </Modal>
            )}
            {modal === "columns" && (
                <Modal
                    title="显示列"
                    close={close}
                    footer={
                        <>
                            <button onClick={() => setColumns(defaultColumns)}>
                                恢复默认
                            </button>
                            <button className="primary" onClick={close}>
                                完成
                            </button>
                        </>
                    }
                >
                    <div className="checkbox-grid">
                        {allColumns.filter(([key]) => defaultColumns.includes(key)).map(([key, label]) => (
                            <label key={key}>
                                <input
                                    type="checkbox"
                                    checked={columns.includes(key)}
                                    disabled={key === "no"}
                                    onChange={() =>
                                        toggle(key, columns, setColumns)
                                    }
                                />
                                {label}
                            </label>
                        ))}
                    </div>
                </Modal>
            )}
            {modal === "export" && (
                <Modal
                    title="自定义导出"
                    close={close}
                    wide
                    footer={
                        <>
                            <span className="footer-left muted">
                                方案预览，仅展示导出范围与内容
                            </span>
                            <button onClick={close}>关闭</button>
                            <button
                                className="primary"
                                disabled={
                                    !exportFields.length || !exportRows.length
                                }
                                onClick={() => setPreview(true)}
                            >
                                预览导出内容
                            </button>
                        </>
                    }
                >
                    <div className="export-scope">
                        <strong>
                            {selected.length
                                ? `已勾选 ${exportRows.length} 条，仅导出所选运输记录`
                                : `未勾选，导出当前筛选全部 ${exportRows.length} 条`}
                        </strong>
                        <span>不受当前分页限制</span>
                    </div>
                    <div className="export-title">
                        <h3>导出字段</h3>
                        <button
                            className="link"
                            onClick={() =>
                                setExportFields(allColumns.map(([key]) => key))
                            }
                        >
                            全选
                        </button>
                        <button
                            className="link"
                            onClick={() => setExportFields(["no"])}
                        >
                            仅保留订单号
                        </button>
                    </div>
                    <div className="checkbox-grid">
                        {allColumns.map(([key, label]) => (
                            <label key={key}>
                                <input
                                    type="checkbox"
                                    checked={exportFields.includes(key)}
                                    disabled={key === "no"}
                                    onChange={() => {
                                        toggle(
                                            key,
                                            exportFields,
                                            setExportFields,
                                        );
                                        setPreview(false);
                                    }}
                                />
                                {label}
                            </label>
                        ))}
                    </div>
                    {preview && (
                        <div className="export-preview">
                            <h3>
                                导出内容 · {exportRows.length} 行，
                                {exportFields.length} 列
                            </h3>
                            <div className="table-scroll">
                                <table>
                                    <thead>
                                        <tr>
                                            {allColumns
                                                .filter(([key]) =>
                                                    exportFields.includes(key),
                                                )
                                                .map(([key, label]) => (
                                                    <th key={key}>{label}</th>
                                                ))}
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {exportRows.map((x) => (
                                            <tr key={x.no}>
                                                {allColumns
                                                    .filter(([key]) =>
                                                        exportFields.includes(
                                                            key,
                                                        ),
                                                    )
                                                    .map(([key]) => (
                                                        <td
                                                            key={key}
                                                            className="multiline nowrap"
                                                        >
                                                            {cell(x, key, true)}
                                                        </td>
                                                    ))}
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    )}
                </Modal>
            )}
            {toast && (
                <div className="toast" role="status">
                    ✓ {toast}
                </div>
            )}
        </>
    );
}
