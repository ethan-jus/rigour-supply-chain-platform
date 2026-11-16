// 评审原型的字典示例；正式页面读取现有 Foundation 字典，不在组件中维护公司白名单。
export const reviewDictionaries = {
    COST_CARRIER: [
        { code: "HLL", label: "货拉拉", enabled: true },
        { code: "SF", label: "顺丰", enabled: true },
        { code: "JT", label: "极兔", enabled: true },
        { code: "ZT", label: "中通", enabled: true },
        { code: "SS", label: "闪送", enabled: true },
    ],
    COST_DELIVERY_MODE: [
        { code: "THIRD_PARTY", label: "第三方配送", enabled: true },
        { code: "SELF", label: "自营配送", enabled: true },
    ],
    COST_TRANSPORT_PURPOSE: [
        { code: "DELIVERY", label: "销售订单配送", enabled: true },
        { code: "TRANSFER", label: "仓间调拨", enabled: true },
        { code: "OTHER", label: "其他费用", enabled: true },
    ],
    COST_LOGISTICS_FEE_TYPE: [
        { code: "FREIGHT", label: "基础运费", enabled: true },
        { code: "LOADING", label: "装卸费", enabled: true },
        { code: "INSURANCE", label: "运输保险费", enabled: true },
        { code: "DISCOUNT", label: "优惠", enabled: true },
        { code: "OTHER", label: "其他运输费用", enabled: true },
    ],
    COST_PAYMENT_STATUS: [
        { code: "UNKNOWN", label: "未知", enabled: true },
        { code: "UNPAID", label: "未付款", enabled: true },
        { code: "PARTIAL", label: "部分付款", enabled: true },
        { code: "PAID", label: "已付款", enabled: true },
    ],
};
export const dictionaryOptions = (code, dictionaries = reviewDictionaries) =>
    (dictionaries[code] || []).filter((item) => item.enabled);
export const carrierCode = (label, dictionaries = reviewDictionaries) =>
    (dictionaries.COST_CARRIER || []).find((item) => item.label === label)
        ?.code;
// 接口接入能力与展示名称分开：新增字典公司默认手工，不会凭空具备三方API能力。
export const carrierEntryMode = (code) =>
    code === "HLL" ? "SELECT_SYNCED" : "MANUAL";
