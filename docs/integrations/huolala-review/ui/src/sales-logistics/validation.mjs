import { dictionaryOptions } from "./dictionary-examples.mjs";

// 评审原型的保存规则；正式实施仍需在服务端使用同一业务约束。
export function validateVoucher(file) {
    if (!/\.(png|jpe?g|pdf|docx|xlsx)$/i.test(file.name || ""))
        return "仅支持 JPG、PNG、PDF、Word、Excel 文件";
    if (!Number.isFinite(file.size) || file.size <= 0)
        return "凭证文件不能为空";
    if (file.size > 10 * 1024 * 1024) return "单个凭证文件不能超过10MB";
    return "";
}
export function manualFieldErrors(draft) {
    const errors = {};
    const required = (key, label) => {
        if (!String(draft[key] ?? "").trim()) errors[label] = "请填写" + label;
    };
    const option = (key, label, dictionary) => {
        if (!dictionaryOptions(dictionary).some((x) => x.label === draft[key]))
            errors[label] = "请选择有效的" + label;
    };
    option("transportType", "运输类型", "COST_TRANSPORT_PURPOSE");
    option("mode", "配送方式", "COST_DELIVERY_MODE");
    if (draft.mode === "第三方配送") {
        option("company", "配送公司", "COST_CARRIER");
        required("tracking", "快递单号");
    }
    for (const [key, label] of [
        ["shipper", "发货方"],
        ["shippingAddress", "发货地址"],
        ["to", "收货单位"],
        ["contact", "收货联系人"],
        ["phone", "收货联系电话"],
        ["address", "收货地址"],
    ])
        required(key, label);
    const date = String(draft.date || "");
    if (
        !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
        !Number.isFinite(Date.parse(date)) ||
        new Date(date).toISOString().slice(0, 10) !== date
    )
        errors["发货日期"] = "请选择有效的发货日期";
    for (const [key, label, limit] of [
        ["tracking", "快递单号", 80],
        ["shipper", "发货方", 100],
        ["shippingContact", "发货联系人", 100],
        ["shippingPhone", "发货联系电话", 50],
        ["shippingAddress", "发货地址", 500],
        ["to", "收货单位", 100],
        ["contact", "收货联系人", 100],
        ["phone", "收货联系电话", 50],
        ["address", "收货地址", 500],
        ["remark", "备注", 1000],
    ]) {
        if (String(draft[key] || "").trim().length > limit)
            errors[label] = `${label}不能超过${limit}个字符`;
    }
    // 兼容手机、固话、国际号码和分机；不强制11位手机，排除纯文字和无效占位值。
    for (const [key, label] of [
        ["phone", "收货联系电话"],
        ["shippingPhone", "发货联系电话"],
    ]) {
        const value = String(draft[key] || "").trim();
        if (
            value &&
            (!/^[\d+()（）\s#-]+$/.test(value) ||
                (value.match(/\d/g) || []).length < 5)
        )
            errors[label] = "请填写有效联系电话，可包含区号、分机或国际区号";
    }
    option("payment", "付款状态", "COST_PAYMENT_STATUS");
    if (
        draft.fees?.some(
            (f) => !f.name?.trim() || !Number.isSafeInteger(f.amount),
        )
    )
        errors["账单明细"] = "每一行费用项目和金额均为必填，请补齐或移除空行";
    const amount = draft.fees?.length
        ? draft.fees.reduce((s, f) => s + f.amount, 0)
        : draft.amount;
    if (amount != null && (!Number.isSafeInteger(amount) || amount < 0))
        errors["运输费用（元）"] = "运输费用必须为非负金额，最多两位小数";
    if (amount === 0 && !draft.remark?.trim())
        errors["备注"] = "零费用请说明原因";
    if (!draft.vouchers?.length)
        errors["业务凭证"] = "请至少上传一份图片或文件凭证";
    else {
        const invalid = draft.vouchers.map(validateVoucher).find(Boolean);
        if (invalid) errors["业务凭证"] = invalid;
    }
    return errors;
}
export function assertManualFields(draft) {
    const fields = manualFieldErrors(draft);
    if (Object.keys(fields).length) {
        const error = new Error(
            "请检查标红字段：" + Object.keys(fields).join("、"),
        );
        error.fields = fields;
        throw error;
    }
}
