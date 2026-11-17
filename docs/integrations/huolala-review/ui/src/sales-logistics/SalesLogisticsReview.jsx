import React, { createContext, useContext, useEffect, useState } from "react";
import {
    orders,
    warehouses,
    money,
    cents,
    allocate,
    initialState,
    saveManual,
    saveManualForOrder,
    linkTransport,
    selectableSalesTransports,
    transition,
    syncCompleted,
    syncDhbExample,
    transportTime,
    transportStatus,
} from "./model.mjs";
import {
    dictionaryOptions,
    carrierCode,
    carrierEntryMode,
} from "./dictionary-examples.mjs";
import { validateVoucher } from "./validation.mjs";
import TransportAttachments from "./TransportAttachments.jsx";
import "./sales-logistics.css";
function Tag({ children }) {
    return (
        <span
            className={
                "sl-tag " +
                (["有效", "已完成", "已完善", "已确认", "已付款"].includes(children)
                    ? "green"
                    : ["已取消", "已作废"].includes(children)
                      ? "gray"
                      : "orange")
            }
        >
            {children}
        </span>
    );
}
const FieldErrors = createContext({});
function Field({ name, children, required, wide }) {
    const errors = useContext(FieldErrors);
    const error = errors[name];
    const limits = {
        快递单号: 80,
        发货方: 100,
        发货联系人: 100,
        发货联系电话: 50,
        发货地址: 500,
        收货单位: 100,
        收货联系人: 100,
        收货联系电话: 50,
        收货地址: 500,
    };
    const control =
        React.isValidElement(children) && typeof children.type === "string"
            ? React.cloneElement(children, {
                  "aria-required": !!required,
                  "aria-invalid": !!error,
                  "aria-describedby": error ? "sl-error-" + name : undefined,
                  ...(limits[name] ? { maxLength: limits[name] } : {}),
              })
            : children;
    return (
        <label
            className={
                "sl-field " + (wide ? "wide " : "") + (error ? "invalid" : "")
            }
        >
            <span>
                {required && <b>* </b>}
                {name}
                {name === "运输费用（元）" && (
                    <small className="sl-conditional">保存金额后直接计入成本</small>
                )}
            </span>
            {control}
            {error && (
                <small id={"sl-error-" + name} className="sl-field-error">
                    {error}
                </small>
            )}
        </label>
    );
}
function Info({ name, value }) {
    return (
        <div className="sl-info">
            <span>{name}</span>
            <strong>{value == null || value === "" ? "-" : value}</strong>
        </div>
    );
}
function Modal({ title, onClose, children, footer, large = false }) {
    return (
        <div className="sl-shade">
            <section
                role="dialog"
                aria-label={title}
                aria-modal="true"
                className={"sl-modal " + (large ? "large" : "")}
            >
                <header>
                    <h2>{title}</h2>
                    <button onClick={onClose}>关闭</button>
                </header>
                <div className="sl-modal-body">{children}</div>
                {footer && <footer>{footer}</footer>}
            </section>
        </div>
    );
}
function Route({ r }) {
    return (
        <div className="sl-route">
            <div>
                <span className="sl-dot start" />
                {r.from || "-"}
            </div>
            {r.stops > 1 && <small>{r.stops - 1}个中途收货地</small>}
            <div>
                <span className="sl-dot end" />
                {r.to || "-"}
            </div>
        </div>
    );
}
const orderNames = (r) =>
    r.links.map((id) => orders.find((o) => o.id === id)).filter(Boolean);
export function SalesLogisticsReview() {
    const [state, setState] = useState(initialState),
        [page, setPage] = useState("sales"),
        [sale, setSale] = useState(null),
        [linkSale, setLinkSale] = useState(null),
        [manual, setManual] = useState(null),
        [record, setRecord] = useState(null),
        [notice, setNotice] = useState(""),
        [q, setQ] = useState(""),
        [query, setQuery] = useState(""),
        [relation, setRelation] = useState(""),
        [companyFilter, setCompanyFilter] = useState(""),
        [attachmentResults, setAttachmentResults] = useState({}),
        [action, setAction] = useState(null),
        [reason, setReason] = useState(""),
        [error, setError] = useState("");
    useEffect(() => {
        document.title = "销售订单关联物流 · V3.6 交互原型";
    }, []);
    const linked = (o) =>
        state.records.filter(
            (r) => r.status !== "已作废" && r.links.includes(o.id),
        );
    const saleRows = orders.filter(
        (o) =>
            (o.no + o.customer + o.sales).includes(query) &&
            (!relation ||
                (relation === "yes" ? linked(o).length : !linked(o).length)),
    );
    const rows = state.records
        .filter(
            (r) =>
                (r.no + r.tracking + r.company).includes(query) &&
                (!companyFilter || r.company === companyFilter) &&
                (!relation ||
                    (relation === "yes" ? r.links.length : !r.links.length)),
        )
        .sort((a, b) => transportTime(b).localeCompare(transportTime(a)));
    const nav = (p) => {
        setPage(p);
        setQ("");
        setQuery("");
        setRelation("");
        setCompanyFilter("");
    };
    const runAction = () => {
        try {
            setState(transition(state, action.r.id, action.type, reason));
            setNotice("已" + action.type);
            setAction(null);
            setRecord(null);
            setError("");
        } catch (e) {
            setError(e.message);
        }
    };
    const operate = (r, type) => {
        setAction({ r, type });
        setReason("");
        setError("");
    };
    return (
        <div className="sl-app">
            <aside className="sl-nav">
                <img src="/scdp-logo.png" alt="供应链数字化平台" />
                <button
                    className={page === "sales" ? "active" : ""}
                    onClick={() => nav("sales")}
                >
                    订单
                </button>
                <button
                    className={page === "cost" ? "active" : ""}
                    onClick={() => nav("cost")}
                >
                    成本
                </button>
                <div className="sl-nav-bottom">瑞盖</div>
            </aside>
            <div className="sl-work">
                <div className="sl-review">
                    V3.6 交互评审原型 · 示例数据，不连接正式系统
                    <a href="/sales-logistics-prd.html" target="_blank">
                        阅读需求文档与场景清单
                    </a>
                    <button
                        onClick={() => {
                            setState(syncDhbExample(state));
                            setNotice(
                                "订货宝发货信息已同步：复用原运输及销售关系，未新增费用",
                            );
                        }}
                    >
                        演示订货宝重复同步
                    </button>
                    <button
                        onClick={() => {
                            setState(initialState());
                            setAttachmentResults({});
                            setNotice("示例已还原");
                        }}
                    >
                        还原示例
                    </button>
                </div>
                <header className="sl-top">
                    <button
                        className={page === "sales" ? "sl-tab" : "sl-quiet-tab"}
                        onClick={() => nav("sales")}
                    >
                        销售订单
                    </button>
                    <button
                        className={page === "cost" ? "sl-tab" : "sl-quiet-tab"}
                        onClick={() => nav("cost")}
                    >
                        物流运输记录
                    </button>
                    <span>瑞盖文化传媒　　管理员</span>
                </header>
                <main>
                    {notice && (
                        <div className="sl-notice" role="status">
                            {notice}
                            <button onClick={() => setNotice("")}>
                                关闭提示
                            </button>
                        </div>
                    )}
                    <section className="sl-filter">
                        <input
                            aria-label="列表查询"
                            placeholder={
                                page === "sales"
                                    ? "销售单号／客户名称／业务员"
                                    : "费用单号／快递单号／配送公司"
                            }
                            value={q}
                            onChange={(e) => setQ(e.target.value)}
                        />
                        {page === "cost" && (
                            <select
                                aria-label="物流公司筛选"
                                value={companyFilter}
                                onChange={(e) =>
                                    setCompanyFilter(e.target.value)
                                }
                            >
                                <option value="">全部物流公司</option>
                                {dictionaryOptions("COST_CARRIER").map(
                                    (item) => (
                                        <option
                                            key={item.code}
                                            value={item.label}
                                        >
                                            {item.label}
                                        </option>
                                    ),
                                )}
                            </select>
                        )}
                        <select
                            aria-label="物流关联筛选"
                            value={relation}
                            onChange={(e) => setRelation(e.target.value)}
                        >
                            <option value="">全部关联状态</option>
                            <option value="no">未关联</option>
                            <option value="yes">已关联</option>
                        </select>
                        <div className="sl-filter-actions">
                            <button
                                className="primary"
                                onClick={() => setQuery(q)}
                            >
                                查询
                            </button>
                            <button
                                onClick={() => {
                                    setQ("");
                                    setQuery("");
                                    setRelation("");
                                    setCompanyFilter("");
                                }}
                            >
                                重置
                            </button>
                        </div>
                    </section>
                    {page === "sales" ? (
                        <section className="sl-panel sl-list">
                            <div className="sl-heading">
                                <h2>
                                    销售订单列表{" "}
                                    <small>{saleRows.length} 条</small>
                                </h2>
                            </div>
                            <div className="sl-table-wrap">
                                <table>
                                    <thead>
                                        <tr>
                                            <th>销售订单号</th>
                                            <th>客户名称</th>
                                            <th>业务员</th>
                                            <th>部门</th>
                                            <th className="num">
                                                订单金额（元）
                                            </th>
                                            <th>订单状态</th>
                                            <th>物流关联</th>
                                            <th className="sticky-right">
                                                操作
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {saleRows.map((o) => (
                                            <tr key={o.id}>
                                                <td>
                                                    <button
                                                        className="link"
                                                        onClick={() =>
                                                            setSale(o)
                                                        }
                                                    >
                                                        {o.no}
                                                    </button>
                                                </td>
                                                <td>{o.customer}</td>
                                                <td>{o.sales}</td>
                                                <td>{o.department}</td>
                                                <td className="num">
                                                    {money(o.amount)}
                                                </td>
                                                <td>
                                                    <Tag>{o.status}</Tag>
                                                </td>
                                                <td>
                                                    {linked(o).length ? (
                                                        <button
                                                            className="link"
                                                            onClick={() =>
                                                                setSale(o)
                                                            }
                                                        >
                                                            已关联{" "}
                                                            {linked(o).length}{" "}
                                                            笔
                                                        </button>
                                                    ) : (
                                                        "未关联"
                                                    )}
                                                </td>
                                                <td className="sticky-right">
                                                    <div className="sl-row-actions">
                                                        <button
                                                            className="link"
                                                            onClick={() =>
                                                                setSale(o)
                                                            }
                                                        >
                                                            明细
                                                        </button>
                                                        <button
                                                            className="link"
                                                            disabled={
                                                                o.status !==
                                                                "有效"
                                                            }
                                                            onClick={() =>
                                                                setLinkSale(o)
                                                            }
                                                        >
                                                            选择物流
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                            <div className="sl-footer">
                                <span>共 {saleRows.length} 条</span>
                                <span>1　　20条/页</span>
                            </div>
                        </section>
                    ) : (
                        <section className="sl-panel sl-list">
                            <div className="sl-heading">
                                <h2>
                                    物流运输记录 <small>{rows.length} 条</small>
                                </h2>
                                <div className="sl-row-actions">
                                    <button
                                        onClick={() => {
                                            setState(syncCompleted(state));
                                            setNotice(
                                                "示例同步完成：已完成订单直接进入运输记录；重复同步不重复生成",
                                            );
                                        }}
                                    >
                                        同步货拉拉订单
                                    </button>
                                    <button
                                        className="primary"
                                        onClick={() => setManual({})}
                                    >
                                        新增运输记录
                                    </button>
                                </div>
                            </div>
                            <div className="sl-table-wrap">
                                <table className="sl-cost-table">
                                    <thead>
                                        <tr>
                                            <th>费用单号</th>
                                            <th>快递单号</th>
                                            <th>物流公司</th>
                                            <th>车型</th>
                                            <th>下单／发货时间</th>
                                            <th>行驶路段</th>
                                            <th>收货点数</th>
                                            <th>运输类型</th>
                                            <th className="num">
                                                运输费用（元）
                                            </th>
                                            <th>订单关联</th>
                                            <th>单据状态</th>
                                            <th>创建人</th>
                                            <th className="sticky-right">
                                                操作
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {rows.map((r) => (
                                            <tr key={r.id}>
                                                <td>
                                                    <button
                                                        className="link"
                                                        onClick={() =>
                                                            setRecord(r)
                                                        }
                                                    >
                                                        {r.no}
                                                    </button>
                                                </td>
                                                <td>{r.tracking || "-"}</td>
                                                <td>{r.company}</td>
                                                <td>{r.vehicle || "-"}</td>
                                                <td>{transportTime(r) || "-"}</td>
                                                <td>
                                                    <Route r={r} />
                                                </td>
                                                <td>{r.stops || "-"}</td>
                                                <td>
                                                    {r.transportType ||
                                                        "待分类"}
                                                </td>
                                                <td className="num">
                                                    {money(r.amount)}
                                                </td>
                                                <td>
                                                    {r.links.length ? (
                                                        <div>
                                                            {orderNames(r).map(
                                                                (o) => (
                                                                    <div
                                                                        key={
                                                                            o.id
                                                                        }
                                                                    >
                                                                        {o.no}
                                                                        <small className="sl-block">
                                                                            {
                                                                                o.sales
                                                                            }{" "}
                                                                            ·{" "}
                                                                            {
                                                                                o.department
                                                                            }
                                                                        </small>
                                                                    </div>
                                                                ),
                                                            )}
                                                        </div>
                                                    ) : (
                                                        <span className="sl-muted">
                                                            未关联销售订单
                                                        </span>
                                                    )}
                                                </td>
                                                <td>
                                                    <Tag>{transportStatus(r)}</Tag>
                                                </td>
                                                <td>
                                                    {r.source === "手工录入"
                                                        ? "管理员"
                                                        : "系统同步"}
                                                </td>
                                                <td className="sticky-right">
                                                    <div className="sl-row-actions">
                                                        <button
                                                            className="link"
                                                            onClick={() =>
                                                                setRecord(r)
                                                            }
                                                        >
                                                            查看
                                                        </button>
                                                        {r.status === "有效" ? <>
                                                            <button className="link" onClick={() => setManual({record:r})}>编辑</button>
                                                            <button className="link danger" onClick={() => operate(r, "作废")}>作废</button>
                                                        </> : <button className="link" onClick={() => operate(r, "删除")}>删除</button>}
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                            <div className="sl-footer">
                                <span>
                                    计入运输费用 ¥
                                    {money(
                                        state.records
                                            .filter(
                                                (r) => r.status === "有效",
                                            )
                                            .reduce((s, r) => s + r.amount, 0),
                                    )}
                                    　待归属运输费用 ¥
                                    {money(
                                        state.records
                                            .filter(
                                                (r) =>
                                                    r.status === "有效" &&
                                                    !r.links.length,
                                            )
                                            .reduce((s, r) => s + r.amount, 0),
                                    )}
                                </span>
                                <span>共 {rows.length} 条</span>
                            </div>
                        </section>
                    )}
                </main>
                <div className="sl-evidence-note">
                    评审原型：同步入运输记录 → 销售单关联 →
                    按运输记录保存金额计算成本；手工记录必须附凭证。
                </div>
            </div>
            {sale && (
                <Modal
                    title={"销售订单详情 · " + sale.no}
                    onClose={() => setSale(null)}
                    large
                    footer={
                        <>
                            <button onClick={() => setSale(null)}>关闭</button>
                            <button
                                className="primary"
                                disabled={sale.status !== "有效"}
                                onClick={() => {
                                    setLinkSale(sale);
                                    setSale(null);
                                }}
                            >
                                选择物流
                            </button>
                        </>
                    }
                >
                    <div className="sl-summary">
                        <Info name="客户" value={sale.customer} />
                        <Info name="业务员" value={sale.sales} />
                        <Info name="部门" value={sale.department} />
                    </div>
                    <div className="sl-tabs">
                        <strong>物流发货</strong>
                        <span>订单概览</span>
                        <span>商品明细</span>
                        <span>回款信息</span>
                    </div>
                    <div className="sl-kpis">
                        <Info
                            name="关联运输"
                            value={linked(sale).length + " 笔"}
                        />
                        <Info
                            name="本单分摊费用（元）"
                            value={
                                linked(sale).some((r) => r.amount == null)
                                    ? "-"
                                    : money(
                                          linked(sale).reduce(
                                              (s, r) =>
                                                  s +
                                                  (r.allocations[sale.id] || 0),
                                              0,
                                          ),
                                      )
                            }
                        />
                        <Info
                            name="计入分摊成本（元）"
                            value={money(
                                !linked(sale).some((r) => r.status === "有效")
                                    ? null
                                    : linked(sale)
                                          .filter((r) => r.status === "有效")
                                          .reduce(
                                              (s, r) =>
                                                  s +
                                                  (r.allocations[sale.id] || 0),
                                              0,
                                          ),
                            )}
                        />
                    </div>
                    <div className="sl-table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>费用单号</th>
                                    <th>配送公司</th>
                                    <th>快递单号</th>
                                    <th>发货日期</th>
                                    <th>本单分摊费用（元）</th>
                                    <th>状态</th>
                                    <th>操作</th>
                                </tr>
                            </thead>
                            <tbody>
                                {linked(sale).map((r) => (
                                    <tr key={r.id}>
                                        <td>{r.no}</td>
                                        <td>
                                            {r.company}
                                            <small className="sl-block">
                                                {r.source}
                                            </small>
                                        </td>
                                        <td>{r.tracking}</td>
                                        <td>{r.date}</td>
                                        <td className="num">
                                            {money(r.allocations[sale.id])}
                                        </td>
                                        <td>
                                            <Tag>{transportStatus(r)}</Tag>
                                        </td>
                                        <td>
                                            <button
                                                className="link"
                                                onClick={() => {
                                                    setRecord(r);
                                                    setSale(null);
                                                }}
                                            >
                                                查看物流
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        {!linked(sale).length && (
                            <div className="sl-empty">
                                暂无关联物流。货拉拉可选择已同步记录，其他公司可直接手工录入。
                            </div>
                        )}
                    </div>
                </Modal>
            )}
            {linkSale && (
                <LinkPicker
                    state={state}
                    order={linkSale}
                    onView={(row) => {
                        setRecord(row);
                        setLinkSale(null);
                    }}
                    onCreate={(defaults) => {
                        setManual({ order: linkSale, defaults });
                        setLinkSale(null);
                    }}
                    onClose={() => setLinkSale(null)}
                    onSave={(id) => {
                        const result = linkTransport(state, id, linkSale.id);
                        setState(result.state);
                        setSale(linkSale);
                        setLinkSale(null);
                        setNotice(
                            result.repeated
                                ? "该运输已关联当前销售单，未重复保存"
                                : "关联成功：已有运输记录已带入销售业务员和部门，未新增费用",
                        );
                    }}
                />
            )}
            {manual && (
                <ManualForm
                    existing={manual.record}
                    order={manual.order}
                    defaults={manual.defaults}
                    onClose={() => {
                        if (manual.order)
                            setLinkSale({
                                ...manual.order,
                                logisticsDefaults: manual.defaults,
                            });
                        setManual(null);
                    }}
                    onSave={(draft) => {
                        const result = manual.order
                            ? saveManualForOrder(state, draft, manual.order.id)
                            : saveManual(state, draft);
                        setState(result.state);
                        setManual(null);
                        if (manual.order) {
                            setSale(manual.order);
                            setNotice(
                                "手工物流和凭证已保存，已关联当前销售订单，保存金额直接计入成本",
                            );
                        } else {
                            setRecord(result.record);
                            setNotice(
                                "运输记录及凭证已保存，已进入运输记录列表",
                            );
                        }
                    }}
                />
            )}
            {record && (
                <RecordDetail
                    r={state.records.find((r) => r.id === record.id) || record}
                    platform={state.platform}
                    attachmentResults={attachmentResults}
                    onRetry={(file) => {
                        if (!file.retryUrl) {
                            setNotice(
                                "本条示例暂无可补取文件；正式功能将重试来源获取",
                            );
                            return;
                        }
                        setAttachmentResults((results) => ({
                            ...results,
                            [record.id + ":" + file.id]: {
                                state: "READY",
                                url: file.retryUrl,
                                reason: "",
                                fetchedAt: "本次获取",
                            },
                        }));
                        setNotice(
                            "示例文件重新获取完成，运输费用及人工维护成本保持不变",
                        );
                    }}
                    onClose={() => setRecord(null)}
                    onEdit={(r) => {
                        setManual({ record: r });
                        setRecord(null);
                    }}
                    onAction={operate}
                />
            )}
            {action && (
                <Modal
                    title={action.type + "运输记录"}
                    onClose={() => setAction(null)}
                    footer={
                        <>
                            <button onClick={() => setAction(null)}>
                                取消
                            </button>
                            <button className="primary" onClick={runAction}>
                                确定{action.type}
                            </button>
                        </>
                    }
                >
                    <p>
                        {action.r.no}　运输费用：{money(action.r.amount)} 元
                    </p>
                    <p>
                        请核对实际业务后继续，操作原因会留存在记录中。

                    </p>
                    {error && (
                        <p role="alert" className="sl-error">
                            {error}
                        </p>
                    )}
                    <Field name="操作说明" required>
                        <textarea
                            aria-label="操作说明"
                            maxLength={500}
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                        />
                    </Field>
                </Modal>
            )}
        </div>
    );
}
function LinkPicker({ state, order, onClose, onSave, onCreate, onView }) {
    const [mode, setMode] = useState(
            order.logisticsDefaults?.mode || "第三方配送",
        ),
        [company, setCompany] = useState(
            order.logisticsDefaults?.company || "货拉拉",
        ),
        [q, setQ] = useState(""),
        [employee, setEmployee] = useState(""),
        [destination, setDestination] = useState(""),
        [transportDate, setTransportDate] = useState(""),
        [linkState, setLinkState] = useState(""),
        [search, setSearch] = useState({ q: "", employee: "" }),
        [selected, setSelected] = useState(""),
        [error, setError] = useState("");
    const manualAllowed =
        mode === "自营配送" ||
        carrierEntryMode(carrierCode(company)) === "MANUAL";
    const rows = selectableSalesTransports(state, { mode, company, ...search });
    const r = state.records.find((r) => r.id === selected);
    const autoLinked = state.records.filter(
        (row) =>
            row.source === "订货宝同步" &&
            row.status !== "已作废" &&
            row.links.includes(order.id),
    );
    const createManual = () =>
        onCreate({ mode, company: mode === "自营配送" ? "" : company });
    return (
        <Modal
            title={"选择物流 · " + order.no}
            onClose={onClose}
            large
            footer={
                <>
                    <span className="sl-footer-context">
                        {manualAllowed
                            ? "手工录入后自动关联当前销售订单"
                            : "关联已有货拉拉运输，不重复计费"}
                    </span>
                    <button onClick={onClose}>取消</button>
                    {!manualAllowed && (
                        <button
                            className="primary"
                            disabled={!selected}
                            onClick={() => {
                                try {
                                    if (!selected)
                                        throw Error("请选择一笔运输记录");
                                    onSave(selected);
                                } catch (e) {
                                    setError(e.message);
                                }
                            }}
                        >
                            保存关联
                        </button>
                    )}
                </>
            }
        >
            <div className="sl-summary">
                <Info name="客户" value={order.customer} />
                <Info
                    name="业务员／部门"
                    value={order.sales + " · " + order.department}
                />
                <Info name="收货地址" value={order.address} />
            </div>
            <h3>{manualAllowed ? "配送方式与公司" : "筛选运输记录"}</h3>
            <div className="sl-filter">
                <select
                    aria-label="配送方式"
                    value={mode}
                    onChange={(e) => {
                        setMode(e.target.value);
                        setSelected("");
                    }}
                >
                    {dictionaryOptions("COST_DELIVERY_MODE").map((item) => (
                        <option key={item.code} value={item.label}>
                            {item.label}
                        </option>
                    ))}
                </select>
                {mode === "第三方配送" && (
                    <select
                        aria-label="配送公司"
                        value={company}
                        onChange={(e) => {
                            setCompany(e.target.value);
                            setSelected("");
                        }}
                    >
                        {dictionaryOptions("COST_CARRIER").map((item) => (
                            <option key={item.code} value={item.label}>
                                {item.label}
                            </option>
                        ))}
                    </select>
                )}
                {!manualAllowed && (
                    <input
                        aria-label="快递单号查询"
                        placeholder="快递单号"
                        value={q}
                        onChange={(e) => setQ(e.target.value)}
                    />
                )}
                {!manualAllowed && (
                    <input
                        aria-label="下单员工查询"
                        placeholder="货拉拉下单员工"
                        value={employee}
                        onChange={(e) => setEmployee(e.target.value)}
                    />
                )}
                {!manualAllowed && (
                    <>
                        <input
                            aria-label="收货地查询"
                            placeholder="收货地"
                            value={destination}
                            onChange={(e) => setDestination(e.target.value)}
                        />
                        <input
                            aria-label="运输日期查询"
                            type="date"
                            value={transportDate}
                            onChange={(e) => setTransportDate(e.target.value)}
                        />
                        <select
                            aria-label="销售关联状态查询"
                            value={linkState}
                            onChange={(e) => setLinkState(e.target.value)}
                        >
                            <option value="">全部销售关联</option>
                            <option value="yes">已关联</option>
                            <option value="no">未关联</option>
                        </select>
                    </>
                )}
                {!manualAllowed && (
                    <button
                        onClick={() =>
                            setSearch({
                                q,
                                employee,
                                destination,
                                transportDate,
                                linkState,
                            })
                        }
                    >
                        查询
                    </button>
                )}
            </div>
            {autoLinked.length > 0 && (
                <section className="sl-section">
                    <h3>已自动带入的订货宝物流</h3>
                    <p>以下物流已关联当前销售订单，无需重复录入或再次关联。</p>
                    {autoLinked.map((row) => (
                        <div className="sl-manual-entry" key={row.id}>
                            <span>
                                {row.company} · {row.tracking} ·{" "}
                                <Tag>{transportStatus(row)}</Tag>
                            </span>
                            <button onClick={() => onView(row)}>
                                查看已关联物流
                            </button>
                        </div>
                    ))}
                </section>
            )}
            {manualAllowed && (
                <div className="sl-manual-entry">
                    <div>
                        <h3>
                            手工录入{mode === "自营配送" ? "自营配送" : company}
                            物流
                        </h3>
                        <p>
                            带入当前销售订单与收货信息，补充物流资料、费用和凭证后保存。
                        </p>
                    </div>
                    <button className="primary" onClick={createManual}>
                        手工录入物流
                    </button>
                </div>
            )}
            {error && (
                <div role="alert" className="sl-error">
                    {error}
                </div>
            )}
            {!manualAllowed && (
                <div className="sl-table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>选择</th>
                                <th>快递单号／费用单号</th>
                                <th>下单时间</th>
                                <th>车型</th>
                                <th>收货点数</th>
                                <th>行驶路段</th>
                                <th>运输费用（元）</th>
                                <th>已有销售关联</th>
                                <th>单据状态</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map((x) => (
                                <tr key={x.id}>
                                    <td>
                                        <input
                                            type="radio"
                                            aria-label={"关联运输" + x.tracking}
                                            checked={selected === x.id}
                                            onChange={() => {
                                                setSelected(x.id);
                                                setError("");
                                            }}
                                        />
                                    </td>
                                    <td>
                                        {x.tracking}
                                        <small className="sl-block">
                                            {x.no}
                                        </small>
                                    </td>
                                    <td>{x.time}</td>
                                    <td>{x.vehicle || "-"}</td>
                                    <td>{x.stops}</td>
                                    <td>
                                        <Route r={x} />
                                    </td>
                                    <td className="num">{money(x.amount)}</td>
                                    <td>
                                        {orderNames(x).map((o) => (
                                            <div key={o.id}>
                                                {o.no}
                                                <small className="sl-block">
                                                    {o.sales} · {o.department}
                                                </small>
                                            </div>
                                        ))}
                                        {!x.links.length && "未关联"}
                                    </td>
                                    <td>
                                        <Tag>{x.status}</Tag>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    {!rows.length && (
                        <div className="sl-empty">
                            {manualAllowed
                                ? "暂无已有运输记录，可点击上方“手工录入物流”，填写后直接关联当前销售订单。"
                                : "暂无货拉拉运输，请先同步已完成订单。"}
                        </div>
                    )}
                </div>
            )}
            {r && (
                <section className="sl-section">
                    <h3>已选物流信息</h3>
                    <div className="sl-summary">
                        <Info name="快递单号" value={r.tracking} />
                        <Info name="物流公司" value={r.company} />
                        <Info name="下单时间" value={r.time} />
                        <Info name="运输费用（元）" value={money(r.amount)} />
                        <Info
                            name="当前销售业务员／部门"
                            value={order.sales + " · " + order.department}
                        />
                        <Info
                            name="关联后本单建议分摊（元）"
                            value={money(
                                allocate(
                                    r.amount,
                                    orders.filter((o) =>
                                        [...r.links, order.id].includes(o.id),
                                    ),
                                )[order.id],
                            )}
                        />
                    </div>
                    <p className="sl-muted">
                        保留原运输单号、费用、平台账单和凭证；同车多单共用一笔费用。
                    </p>
                </section>
            )}
        </Modal>
    );
}
function ManualForm({ existing: r, order, defaults, onClose, onSave }) {
    const original = order || orders.find((o) => r?.links?.includes(o.id));
    const platformRecord = r?.source === "货拉拉同步";
    const [d, setD] = useState({
            editId: r?.id,
            transportType: r?.transportType || (order ? "销售订单配送" : ""),
            mode: r?.mode || defaults?.mode || "第三方配送",
            company: r?.company || defaults?.company || "",
            tracking: r?.tracking || "",
            warehouse: r?.warehouse || "",
            shipper: r?.shipper || r?.from || "",
            shippingContact: r?.shippingContact || "",
            shippingPhone: r?.shippingPhone || "",
            shippingAddress: r?.shippingAddress || "",
            date: r?.date || "2026-11-12",
            shippingTime: r?.shippingTime || `${r?.date || "2026-11-12"}T00:00:00`,
            to: r?.to || original?.customer || "",
            contact: r?.contact || original?.contact || "",
            phone: r?.phone || original?.phone || "",
            address: r?.address || original?.address || "",
            payment: r?.payment || "未知",
            owner: r?.owner || order?.sales || "",
            remark: r?.remark || "",
            amount: r?.amount == null ? "" : String(r.amount / 100),
        }),
        [fees, setFees] = useState(
            (platformRecord ? [] : r?.fees || []).map((f) => ({
                name: f.name,
                value: String(f.amount / 100),
            })),
        ),
        [vouchers, setVouchers] = useState(r?.vouchers || []),
        [voucherCategory, setVoucherCategory] = useState("EXPENSE_VOUCHER"),
        [error, setError] = useState(""),
        [fieldErrors, setFieldErrors] = useState({});
    const put = (k, v) => setD({ ...d, [k]: v }),
        sourceReadOnly = ["订货宝同步", "货拉拉同步"].includes(r?.source);
    const total = () => {
        try {
            return fees.length
                ? fees.reduce((s, f) => s + (cents(f.value) || 0), 0)
                : cents(d.amount);
        } catch {
            return null;
        }
    };
    const save = () => {
        try {
            const values = fees.map((f) => {
                if (!f.name.trim() || f.value === "")
                    throw Object.assign(Error("请填写完整费用分项"), {
                        fields: {
                            账单明细:
                                "新增的费用项目和金额均为必填，请补齐或移除空行",
                        },
                    });
                try {
                    return { name: f.name, amount: cents(f.value) };
                } catch (e) {
                    throw Object.assign(e, { fields: { 账单明细: e.message } });
                }
            });
            onSave({
                ...d,
                fees: values,
                amount: fees.length ? total() : cents(d.amount),
                vouchers,
            });
        } catch (e) {
            setError(e.message);
            setFieldErrors(e.fields || { "运输费用（元）": e.message });
        }
    };
    const filesChanged = (e) => {
        const files = [...e.target.files];
        const invalid = vouchers.length + files.length > 20 ? "最多20份凭证" :
            ["LOADING", "UNLOADING"].includes(voucherCategory) && files.some(f => !/\.(png|jpe?g)$/i.test(f.name)) ?
                "装卸货照片请选择PNG或JPG图片" : files.map(validateVoucher).find(Boolean);
        if (invalid) {
            setError(invalid);
            setFieldErrors({ ...fieldErrors, 业务凭证: invalid });
            return;
        }
        setVouchers([
            ...vouchers,
            ...files.map((f) => ({
                name: f.name,
                url: URL.createObjectURL(f),
                size: f.size,
                categoryCode: voucherCategory,
            })),
        ]);
        setError("");
        e.target.value = "";
    };
    return (
        <FieldErrors.Provider value={fieldErrors}>
            <Modal
                title={
                    r
                        ? "编辑运输记录"
                        : order
                          ? "手工录入物流 · " + order.no
                          : "新增运输记录"
                }
                onClose={onClose}
                large
                footer={
                    <>
                        <span className="sl-footer-context">
                            <b className="sl-required">* </b>
                            为保存必填；金额未知暂不计成本。
                            <br />
                            {order
                                ? "保存运输记录并关联当前销售订单"
                                : original
                                  ? "保存运输记录，保留原销售订单关联"
                                  : "保存到运输记录；销售配送需补齐原销售单关系"}
                        </span>
                        <button onClick={onClose}>取消</button>
                        <button className="primary" onClick={save}>
                            {order ? "保存并关联销售订单" : "保存运输记录"}
                        </button>
                    </>
                }
            >
                {error && (
                    <div className="sl-error" role="alert">
                        {error}
                    </div>
                )}
                <section className="sl-section">
                    <h3>基本信息</h3>
                    <div className="sl-grid">
                        <Field name="运输类型" required>
                            <select
                                aria-label="运输类型"
                                disabled={!!original || sourceReadOnly}
                                value={d.transportType}
                                onChange={(e) =>
                                    put("transportType", e.target.value)
                                }
                            >
                                <option value="">请选择</option>
                                {dictionaryOptions(
                                    "COST_TRANSPORT_PURPOSE",
                                ).map((item) => (
                                    <option key={item.code} value={item.label}>
                                        {item.label}
                                    </option>
                                ))}
                            </select>
                        </Field>
                        <Field name="配送方式" required>
                            <select
                                aria-label="配送方式"
                                disabled={sourceReadOnly}
                                value={d.mode}
                                onChange={(e) => put("mode", e.target.value)}
                            >
                                {dictionaryOptions("COST_DELIVERY_MODE").map(
                                    (item) => (
                                        <option
                                            key={item.code}
                                            value={item.label}
                                        >
                                            {item.label}
                                        </option>
                                    ),
                                )}
                            </select>
                        </Field>
                        {d.mode === "第三方配送" && (
                            <>
                                <Field name="配送公司" required>
                                    <select
                                        aria-label="配送公司"
                                        disabled={sourceReadOnly}
                                        value={d.company}
                                        onChange={(e) =>
                                            put("company", e.target.value)
                                        }
                                    >
                                        <option value="">请选择</option>
                                        {dictionaryOptions("COST_CARRIER").map(
                                            (item) => (
                                                <option
                                                    key={item.code}
                                                    value={item.label}
                                                    disabled={
                                                        !!order &&
                                                        carrierEntryMode(
                                                            item.code,
                                                        ) === "SELECT_SYNCED"
                                                    }
                                                >
                                                    {item.label}
                                                </option>
                                            ),
                                        )}
                                    </select>
                                </Field>
                                <Field name="快递单号" required>
                                    <input
                                        aria-label="快递单号"
                                        readOnly={sourceReadOnly}
                                        value={d.tracking}
                                        onChange={(e) =>
                                            put("tracking", e.target.value)
                                        }
                                    />
                                </Field>
                            </>
                        )}
                    </div>
                </section>
                {original && (
                    <section className="sl-section">
                        <h3>关联销售订单</h3>
                        {(order ? [order] : orderNames(r)).map((related) => (
                            <div className="sl-summary" key={related.id}>
                                <Info name="销售订单" value={related.no} />
                                <Info name="客户" value={related.customer} />
                                <Info
                                    name="业务员／部门"
                                    value={
                                        related.sales +
                                        " · " +
                                        related.department
                                    }
                                />
                            </div>
                        ))}
                    </section>
                )}
                {r?.sourceShipment && <DhbShipmentInfo r={r} />}
                <section className="sl-section">
                    <h3>费用信息</h3>
                    <div className="sl-grid expense-grid">
                        <Field name="运输费用（元）">
                            <input
                                aria-label="运输费用"
                                placeholder="填写本次运输费用；未知可留空"
                                readOnly={fees.length > 0}
                                value={fees.length ? money(total()) : d.amount}
                                onChange={(e) => put("amount", e.target.value)}
                            />
                        </Field>
                        <Field name="费用负责人">
                            <select
                                aria-label="费用负责人"
                                value={d.owner}
                                onChange={(e) => put("owner", e.target.value)}
                            >
                                <option value="">请选择</option>
                                <option>林明</option>
                                <option>陈静</option>
                                <option>赵宁</option>
                            </select>
                        </Field>
                        <Field name="付款状态">
                            <select
                                aria-label="付款状态"
                                disabled={platformRecord}
                                value={d.payment}
                                onChange={(e) => put("payment", e.target.value)}
                            >
                                {dictionaryOptions("COST_PAYMENT_STATUS").map(
                                    (item) => (
                                        <option
                                            key={item.code}
                                            value={item.label}
                                        >
                                            {item.label}
                                        </option>
                                    ),
                                )}
                            </select>
                        </Field>
                        <Info name="计入运输成本（元）" value="-" />
                    </div>
                    {!platformRecord && <>
                    <div className="sl-fee-heading">
                        <h4>
                            账单明细 <small>（选填）</small>
                        </h4>
                        <button
                            className="link"
                            onClick={() =>
                                setFees([...fees, { name: "", value: "" }])
                            }
                        >
                            添加费用项目
                        </button>
                    </div>
                    {fieldErrors["账单明细"] && (
                        <p className="sl-field-error" role="alert">
                            {fieldErrors["账单明细"]}
                        </p>
                    )}
                    {fees.map((f, i) => (
                        <div className="sl-fee-row" key={i}>
                            <Field name="费用项目" required>
                                <select
                                    aria-label={"费用项目" + (i + 1)}
                                    value={f.name}
                                    onChange={(e) =>
                                        setFees(
                                            fees.map((x, k) =>
                                                k === i
                                                    ? {
                                                          ...x,
                                                          name: e.target.value,
                                                      }
                                                    : x,
                                            ),
                                        )
                                    }
                                >
                                    <option value="">请选择费用项目</option>
                                    {f.name &&
                                        !dictionaryOptions(
                                            "COST_LOGISTICS_FEE_TYPE",
                                        ).some(
                                            (item) => item.label === f.name,
                                        ) && (
                                            <option value={f.name}>
                                                {f.name}（历史值）
                                            </option>
                                        )}
                                    {dictionaryOptions(
                                        "COST_LOGISTICS_FEE_TYPE",
                                    ).map((item) => (
                                        <option
                                            key={item.code}
                                            value={item.label}
                                        >
                                            {item.label}
                                        </option>
                                    ))}
                                </select>
                            </Field>
                            <Field name="费用金额（元）" required>
                                <input
                                    aria-label={"费用金额" + (i + 1)}
                                    placeholder="优惠填负数"
                                    value={f.value}
                                    onChange={(e) =>
                                        setFees(
                                            fees.map((x, k) =>
                                                k === i
                                                    ? {
                                                          ...x,
                                                          value: e.target.value,
                                                      }
                                                    : x,
                                            ),
                                        )
                                    }
                                />
                            </Field>
                            <button
                                onClick={() =>
                                    setFees(fees.filter((_, k) => k !== i))
                                }
                            >
                                移除
                            </button>
                        </div>
                    ))}
                    </>}
                </section>
                <section className="sl-section">
                    <h3>发货信息</h3>
                    <div className="sl-grid shipping-grid">
                        <Field name="发货仓库">
                            <select
                                aria-label="发货仓库"
                                value={d.warehouse}
                                disabled={sourceReadOnly}
                                onChange={(e) => {
                                    const w = warehouses.find(
                                        (w) => w.id === e.target.value,
                                    );
                                    setD({
                                        ...d,
                                        warehouse: e.target.value,
                                        ...(w
                                            ? {
                                                  shipper: w.name,
                                                  shippingContact: w.contact,
                                                  shippingPhone: w.phone,
                                                  shippingAddress: w.address,
                                              }
                                            : {}),
                                    });
                                }}
                            >
                                <option value="">搜索并选择发货仓库</option>
                                {warehouses.map((w) => (
                                    <option key={w.id} value={w.id}>
                                        {w.name}
                                    </option>
                                ))}
                            </select>
                        </Field>
                        <Field name="发货方" required>
                            <input
                                aria-label="发货方"
                                placeholder="填写实际发货方"
                                value={d.shipper}
                                readOnly={sourceReadOnly}
                                onChange={(e) => put("shipper", e.target.value)}
                            />
                        </Field>
                        <Field name="发货联系人">
                            <input
                                aria-label="发货联系人"
                                value={d.shippingContact}
                                readOnly={sourceReadOnly}
                                onChange={(e) =>
                                    put("shippingContact", e.target.value)
                                }
                            />
                        </Field>
                        <Field name="发货联系电话">
                            <input
                                aria-label="发货联系电话"
                                value={d.shippingPhone}
                                readOnly={sourceReadOnly}
                                onChange={(e) =>
                                    put("shippingPhone", e.target.value)
                                }
                            />
                        </Field>
                        <Field name="发货时间" required>
                            <input
                                aria-label="发货时间"
                                type="datetime-local"
                                step="1"
                                value={d.shippingTime}
                                readOnly={sourceReadOnly}
                                onChange={(e) => { put("shippingTime", e.target.value); put("date", e.target.value.slice(0,10)); }}
                            />
                        </Field>
                        <Field name="发货地址" required wide>
                            <input
                                aria-label="发货地址"
                                placeholder="填写本次运输的发货地址"
                                value={d.shippingAddress}
                                readOnly={sourceReadOnly}
                                onChange={(e) =>
                                    put("shippingAddress", e.target.value)
                                }
                            />
                        </Field>
                    </div>
                </section>
                <section className="sl-section">
                    <h3>收货信息与行驶路段</h3>
                    {!sourceReadOnly && (
                        <Field name="从客户档案带入">
                            <select
                                aria-label="从客户档案带入"
                                defaultValue=""
                                onChange={(e) => {
                                    const o = orders.find(
                                        (o) => o.id === e.target.value,
                                    );
                                    if (o)
                                        setD({
                                            ...d,
                                            to: o.customer,
                                            contact: o.contact,
                                            phone: o.phone,
                                            address: o.address,
                                        });
                                }}
                            >
                                <option value="">
                                    选择客户带入本次收货信息
                                </option>
                                {orders
                                    .filter((o) => o.status === "有效")
                                    .map((o) => (
                                        <option key={o.id} value={o.id}>
                                            {o.customer}
                                        </option>
                                    ))}
                            </select>
                        </Field>
                    )}
                    <div className="sl-grid">
                        {[
                            ["to", "收货单位"],
                            ["contact", "收货联系人"],
                            ["phone", "收货联系电话"],
                            ["address", "收货地址"],
                        ].map(([k, n]) => (
                            <Field
                                key={k}
                                name={n}
                                required
                                wide={k === "address"}
                            >
                                <input
                                    aria-label={n}
                                    value={d[k]}
                                    readOnly={sourceReadOnly}
                                    onChange={(e) => put(k, e.target.value)}
                                />
                            </Field>
                        ))}
                    </div>
                </section>
                <section className="sl-section">
                    <h3>
                        <span className="sl-required">* </span>业务凭证
                    </h3>
                    <label>凭证分类 <select aria-label="凭证分类" value={voucherCategory} onChange={e => setVoucherCategory(e.target.value)}>
                        <option value="EXPENSE_VOUCHER">费用凭证</option><option value="RECEIPT">运输回单</option>
                        <option value="LOADING">装货照片</option><option value="UNLOADING">卸货照片</option>
                    </select></label>
                    <Field name="业务凭证">
                        <input
                            aria-label="上传凭证"
                            type="file"
                            multiple
                            accept=".jpg,.jpeg,.png,.pdf,.docx,.xlsx"
                            aria-required="true"
                            onChange={filesChanged}
                        />
                    </Field>
                    <p className="sl-muted">
                        至少一份 JPG、PNG、PDF、Word 或 Excel
                        文件（docx／xlsx），单个不超过10MB；至少一份，不要求每类都有。此评审原型不执行真实COS上传。
                    </p>
                    {vouchers.map((v, i) => (
                        <div className="sl-recipient" key={i}>
                            <span>{({EXPENSE_VOUCHER:"费用凭证",RECEIPT:"运输回单",LOADING:"装货照片",UNLOADING:"卸货照片"})[v.categoryCode || "EXPENSE_VOUCHER"]} · {v.name}</span>
                            <div className="sl-row-actions">
                                {v.url && (
                                    <a href={v.url} download={v.name}>
                                        下载
                                    </a>
                                )}
                                <button
                                    className="link danger"
                                    onClick={() =>
                                        setVouchers(
                                            vouchers.filter((_, k) => k !== i),
                                        )
                                    }
                                >
                                    移除凭证
                                </button>
                            </div>
                        </div>
                    ))}
                </section>
                <section className="sl-section">
                    <h3>
                        {total() === 0 && <b className="sl-required">* </b>}备注{" "}
                        {total() === 0 && <small>（零费用必填）</small>}
                    </h3>
                    <textarea
                        aria-label="备注"
                        aria-required={total() === 0}
                        aria-invalid={!!fieldErrors["备注"]}
                        maxLength={1000}
                        placeholder="填写本次运输备注；零费用请说明原因"
                        value={d.remark}
                        onChange={(e) => put("remark", e.target.value)}
                    />
                    {fieldErrors["备注"] && (
                        <p className="sl-field-error">{fieldErrors["备注"]}</p>
                    )}
                </section>
            </Modal>
        </FieldErrors.Provider>
    );
}
function RecordDetail({
    r,
    platform,
    attachmentResults,
    onRetry,
    onClose,
    onEdit,
    onAction,
}) {
    const p = platform.find((p) => p.id === r.sourceId);
    const payment = p
        ? p.unpaid == null || p.paid == null
            ? "待核实"
            : p.unpaid > 0
              ? p.paid > 0
                  ? "部分付款"
                  : "未付款"
              : "已付款"
        : r.payment;
    const recipients = p?.addresses?.slice(1) || [
        { name: r.to, contact: r.contact, phone: r.phone, address: r.address },
    ];
    const sender = p?.addresses?.[0] || {
        name: r.shipper || r.from,
        contact: r.shippingContact,
        phone: r.shippingPhone,
        address: r.shippingAddress,
    };
    return (
        <Modal
            title={"运输记录详情 · " + r.no}
            onClose={onClose}
            large
            footer={
                <>
                    <button onClick={onClose}>关闭</button>
                    {r.status === "有效" && <>
                        <button onClick={() => onEdit(r)}>编辑</button>
                        <button onClick={() => onAction(r,"作废")}>作废</button>
                    </>}
                    {r.status === "已作废" && <button onClick={() => onAction(r,"删除")}>删除</button>}

                </>
            }
        >
            <section className="sl-section">
                <h3>
                    基本信息 <Tag>{transportStatus(r)}</Tag>
                </h3>
                <div className="sl-grid">
                    <Info name="运输类型" value={r.transportType || "待分类"} />
                    <Info name="配送方式" value={r.mode} />
                    <Info name="物流公司" value={r.company} />
                    <Info name="快递单号" value={r.tracking} />
                    <Info name="车型" value={p?.vehicle || r.vehicle} />
                    <Info name={p ? "下单时间" : "发货时间"} value={transportTime(r)} />
                    {p && (
                        <>
                            <Info name="运输服务" value={p.service} />
                            <Info name="用车时间" value={p.useTime} />
                            <Info name="完成时间" value={p.completeTime} />
                            <Info
                                name="行程里程（公里）"
                                value={p.distanceKm}
                            />
                            <Info name="平台订单状态" value={p.status} />
                            <Info name="用车原因" value={p.reason} />
                        </>
                    )}
                </div>
            </section>
            <section className="sl-section">
                <h3>关联销售订单</h3>
                {!r.links.length ? (
                    <p className="sl-muted">
                        尚未关联销售订单，销售业务员和成本部门待归属。
                    </p>
                ) : (
                    <div className="sl-table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>销售订单</th>
                                    <th>客户</th>
                                    <th>业务员</th>
                                    <th>部门</th>
                                    <th className="num">分摊费用（元）</th>
                                </tr>
                            </thead>
                            <tbody>
                                {orderNames(r).map((o) => (
                                    <tr key={o.id}>
                                        <td>{o.no}</td>
                                        <td>{o.customer}</td>
                                        <td>{o.sales}</td>
                                        <td>{o.department}</td>
                                        <td className="num">
                                            {money(r.allocations[o.id])}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
            {r.sourceShipment && <DhbShipmentInfo r={r} />}
            <section className="sl-section">
                <h3>费用信息</h3>
                <div className="sl-grid expense-grid">
                    <Info name="运输费用（元）" value={money(r.amount)} />
                    <Info name="费用负责人" value={r.owner} />
                    <Info name="付款状态" value={payment} />
                    <Info
                        name="计入运输成本（元）"
                        value={r.status === "有效" ? money(r.amount) : "-"}
                    />
                </div>
                {r.billChanged && (
                    <div className="sl-error">
                        平台账单已更新为 ¥{money(r.platformAmount)}
                        ，本系统人工金额保持不变，可直接编辑核对。
                    </div>
                )}
                {p && (
                    <>
                        <h4>
                            平台账单 <span className="sl-tag gray">只读</span>
                        </h4>
                        <div className="sl-summary">
                            <Info
                                name="账单总计（元）"
                                value={money(p.amount)}
                            />
                            <Info name="已支付（元）" value={money(p.paid)} />
                            <Info name="未支付（元）" value={money(p.unpaid)} />
                            <Info name="退款（元）" value={money(p.refund)} />
                            <Info
                                name="赠金支付（元）"
                                value={money(p.bonus)}
                            />
                            <Info name="支付方式" value={p.payType} />
                            <Info name="账单更新时间" value={p.billUpdatedAt} />
                        </div>
                    </>
                )}
                <h4>账单明细</h4>
                <div className="sl-table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>费用项目</th>
                                <th className="num">金额（元）</th>
                            </tr>
                        </thead>
                        <tbody>
                            {(p?.fees || r.fees || []).map((f, i) => (
                                <tr key={i}>
                                    <td>{f.name}</td>
                                    <td className="num">{money(f.amount)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </section>
            <section className="sl-section">
                <h3>发货信息</h3>
                <div className="sl-grid shipping-grid">
                    <Info
                        name="发货仓库"
                        value={
                            warehouses.find((w) => w.id === r.warehouse)?.name
                        }
                    />
                    <Info name="发货方" value={sender.name} />
                    <Info name="发货联系人" value={sender.contact} />
                    <Info name="发货联系电话" value={sender.phone} />
                    <Info name="发货日期" value={r.date} />
                    <Info name="发货地址" value={sender.address} />
                </div>
            </section>
            <section className="sl-section">
                <h3>收货信息与行驶路段</h3>
                <Route r={r} />
                {recipients.map((point, i) => (
                    <div className="sl-recipient-block" key={i}>
                        <h4>
                            第{i + 1}个收货点 · {point.name || "-"}
                        </h4>
                        <div className="sl-summary">
                            <Info name="收货单位" value={point.name} />
                            <Info name="收货联系人" value={point.contact} />
                            <Info name="收货电话" value={point.phone} />
                            <Info name="收货地址" value={point.address} />
                        </div>
                    </div>
                ))}
            </section>
            <TransportAttachments
                record={r}
                platform={p}
                results={attachmentResults}
                onRetry={onRetry}
            />
            <section className="sl-section">
                <h3>备注</h3>
                <p>{r.remark || "-"}</p>
                {p && <Info name="平台订单备注" value={p.remark} />}
            </section>
            {p && (
                <section className="sl-section">
                    <h3>其他平台业务资料</h3>
                    <div className="sl-summary">
                        <Info name="下单员工" value={p.employee} />
                        <Info name="下单联系人" value={p.orderContact} />
                        <Info name="下单联系电话" value={p.orderPhone} />
                        <Info name="司机" value={p.driver?.name} />
                        <Info name="司机联系电话" value={p.driver?.phone} />
                        <Info name="车牌号" value={p.driver?.plate} />
                        <Info
                            name="可开票金额（元）"
                            value={money(p.invoiceAmount)}
                        />
                        <Info name="开票状态" value={p.invoiceStatus} />
                        <Info name="保价状态" value={p.insuranceStatus} />
                    </div>
                    <h4>运输进度</h4>
                    {p.progress?.length ? (
                        p.progress.map((item, i) => (
                            <p key={i}>
                                {item.time}　{item.label}
                            </p>
                        ))
                    ) : (
                        <p className="sl-muted">平台未提供运输进度</p>
                    )}
                </section>
            )}
            <section className="sl-section">
                <h3>来源与操作记录</h3>
                <div className="sl-summary">
                    <Info name="数据来源" value={r.source} />
                    {r.sourceShipment && (
                        <>
                            <Info
                                name="订货宝发货单"
                                value={r.sourceShipment}
                            />
                            <Info name="订货宝销售单" value={r.sourceOrder} />
                        </>
                    )}
                </div>
                {r.logs.map((log, i) => (
                    <p key={i}>{log}</p>
                ))}
            </section>
        </Modal>
    );
}

function DhbShipmentInfo({ r }) {
    return (
        <section className="sl-section">
            <h3>
                订货宝发货信息{" "}
                <span className="sl-tag gray">自动带入 · 只读</span>
            </h3>
            <div className="sl-summary">
                <Info name="订货宝销售单号" value={r.sourceOrder} />
                <Info name="订货宝发货单号" value={r.sourceShipment} />
                <Info name="发货状态" value={r.sourceShipmentStatus} />
                <Info name="来源发货时间" value={r.sourceShippedAt} />
                <Info name="物流公司" value={r.company} />
                <Info name="快递单号" value={r.tracking} />
                <Info name="最近来源更新时间" value={r.sourceUpdatedAt} />
            </div>
            <h4>发货商品</h4>
            {(r.sourceItems || []).map((item, i) => (
                <p key={i}>
                    {item.name}　{item.quantity} {item.unit}
                </p>
            ))}
        </section>
    );
}
