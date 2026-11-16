// 文件取得结果与运输金额分离；重复同步不能用文件数或分项再累计费用。
export function attachmentGroups(record, platform, results = {}) {
    const unique = (files) => [
        ...new Map(
            files.map((file) => [file.id || file.url || file.name, file]),
        ).values(),
    ];
    const decorate = (files) =>
        unique(files).map((file) => ({
            ...file,
            ...results[record.id + ":" + file.id],
        }));
    if (!platform) return [
        ["EXPENSE_VOUCHER", "费用凭证"], ["RECEIPT", "运输回单"],
        ["LOADING", "装货照片"], ["UNLOADING", "卸货照片"],
    ].map(([code, title]) => ({ title, empty: "暂无" + title,
        files: decorate((record.vouchers || []).filter(file => (file.categoryCode || "EXPENSE_VOUCHER") === code)
            .map((file, index) => ({ ...file, id: file.id || code + "-" + index,
                group: title, state: "READY", fetchedAt: file.fetchedAt || "本次选择（原型演示）" }))),
    }));
    const fees = (platform.fees || []).flatMap((fee) =>
        (fee.vouchers || []).map((file) => ({ ...file, group: fee.name })),
    );
    return [
        {
            title: "账单凭证",
            empty: "货拉拉未提供账单凭证",
            files: decorate([
                ...fees,
                ...(platform.billVouchers || []).map((file) => ({
                    ...file,
                    group: file.group || "账单",
                })),
            ]),
        },
        {
            title: "运输回单",
            empty: "货拉拉未提供拍照回单",
            files: decorate(
                (platform.receipts || []).map((file) => ({
                    ...file,
                    group: "运输回单",
                })),
            ),
        },
        {
            title: "装卸货照片",
            empty: "货拉拉未提供装卸货照片",
            files: decorate(
                (platform.stopPhotos || []).flatMap((point) =>
                    point.files.map((file) => ({
                        ...file,
                        group: `第${point.index + 1}站 ${point.name} · ${point.phase}`,
                    })),
                ),
            ),
        },
    ];
}
