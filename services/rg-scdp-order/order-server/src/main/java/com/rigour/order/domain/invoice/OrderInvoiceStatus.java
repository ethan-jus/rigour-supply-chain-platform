package com.rigour.order.domain.invoice;

/** 开票登记状态；未申请与已撤回在展示上都回显"未申请"，但审计上保留撤回痕迹。 */
public enum OrderInvoiceStatus {
    PENDING("待开票"),
    INVOICED("已开票"),
    REVOKED("已撤回");

    public static final String NOT_APPLIED_CODE = "NOT_APPLIED";
    public static final String NOT_APPLIED_NAME = "未申请";

    private final String displayName;

    OrderInvoiceStatus(String displayName) {
        this.displayName = displayName;
    }

    public String code() {
        return name();
    }

    public String displayName() {
        return displayName;
    }

    /** 列表/详情展示名；无记录或已撤回都按未申请展示。 */
    public static String displayNameOf(String code) {
        OrderInvoiceStatus status = fromCode(code);
        if (status == null || status == REVOKED) return NOT_APPLIED_NAME;
        return status.displayName();
    }

    public static OrderInvoiceStatus fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        try {
            return valueOf(code.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
