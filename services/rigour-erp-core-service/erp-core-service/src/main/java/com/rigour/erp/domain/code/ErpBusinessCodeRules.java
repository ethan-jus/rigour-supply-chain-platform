package com.rigour.erp.domain.code;

import com.rigour.shared.core.code.BusinessCodeRule;

/** ERP 领域业务编码规则定义。 */
public final class ErpBusinessCodeRules {

    public static final BusinessCodeRule PRODUCT = BusinessCodeRule.daily("PRD", 4);
    public static final BusinessCodeRule SKU = BusinessCodeRule.daily("SKU", 4);
    public static final BusinessCodeRule CATEGORY = BusinessCodeRule.daily("CAT", 4);
    public static final BusinessCodeRule BRAND = BusinessCodeRule.daily("BRD", 4);
    public static final BusinessCodeRule TAG = BusinessCodeRule.daily("TAG", 4);
    public static final BusinessCodeRule SPECIFICATION = BusinessCodeRule.daily("SPC", 4);
    public static final BusinessCodeRule SPECIFICATION_VALUE = BusinessCodeRule.daily("SPV", 4);
    public static final BusinessCodeRule SUPPLIER = BusinessCodeRule.daily("SUP", 4);
    public static final BusinessCodeRule WAREHOUSE = BusinessCodeRule.daily("WH", 4);
    public static final BusinessCodeRule PURCHASE_ORDER = BusinessCodeRule.daily("PO", 4);
    public static final BusinessCodeRule PURCHASE_RETURN_ORDER = BusinessCodeRule.daily("PR", 4);
    public static final BusinessCodeRule STOCK_IN_ORDER = BusinessCodeRule.daily("SI", 4);
    public static final BusinessCodeRule STOCK_OUT_ORDER = BusinessCodeRule.daily("SO", 4);
    public static final BusinessCodeRule TRANSFER_ORDER = BusinessCodeRule.daily("TR", 4);
    public static final BusinessCodeRule STOCK_FLOW = BusinessCodeRule.millisecond("SF", 6);

    private ErpBusinessCodeRules() {
    }
}
