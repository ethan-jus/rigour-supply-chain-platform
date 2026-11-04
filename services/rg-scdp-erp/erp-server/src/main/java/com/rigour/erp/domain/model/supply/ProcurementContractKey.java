package com.rigour.erp.domain.model.supply;

/** 合同对象键不能跨租户，也不能将其他操作人的新文件挂到采购单。 */
public final class ProcurementContractKey {
    public static final String PREFIX = "erp-procurement-contracts";

    private ProcurementContractKey() {}

    public static boolean valid(String tenant, String key) {
        return key != null
                && key.startsWith(tenant + "/" + PREFIX + "/")
                && !key.contains("..")
                && key.substring((tenant + "/" + PREFIX + "/").length())
                        .matches("[A-Za-z0-9_-]+/[a-f0-9]{32}\\.(pdf|doc|docx|jpg|png)");
    }

    public static boolean writable(String tenant, String actor, String key, String current) {
        return key == null
                || valid(tenant, key)
                        && (key.equals(current)
                                || key.startsWith(tenant + "/" + PREFIX + "/" + actor + "/"));
    }
}
