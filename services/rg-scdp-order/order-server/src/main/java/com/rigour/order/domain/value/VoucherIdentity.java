package com.rigour.order.domain.value;

/** 仅信任已知内容寻址目录；普通路径仍按完整对象 key 比较。 */
public final class VoucherIdentity {
    private VoucherIdentity() {}
    public static String of(String key) {
        if (key.contains("/voucher-audit-20261009/") || key.contains("/fund-attachments/")) {
            String file = key.substring(key.lastIndexOf('/') + 1);
            if (file.matches("[a-f0-9]{64}\\.[a-zA-Z0-9]+"))
                return "sha256:" + file.substring(0, 64);
        }
        return "object:" + key;
    }
}
