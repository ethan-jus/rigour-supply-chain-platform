package com.rigour.analytics.infrastructure.persistence.scope;

/** 到账业绩只使用已确认的回款时点归属；缺失归属保留待确认。 */
public final class PaymentPerformanceSql {
    private PaymentPerformanceSql() {}

    public static final String OWNER = "CASE WHEN p.payment_owner_evidence IS NOT NULL THEN NULLIF(TRIM(p.payment_owner_code),'') END";
    public static final String NAME = "CASE WHEN p.payment_owner_evidence IS NOT NULL THEN p.payment_owner_name END";

    // 原始 collector_* 字段不回写；回款归属不更改历史交易归属；城市由回款快照随事实同步更新。
    public static final String ALIGN =
            """
UPDATE bi_sales_payment_fact f
JOIN bi_source_order_order_payment_record p ON p.tenant_id=f.tenant_id AND p.id=f.payment_id
SET f.owner_staff_code=\
"""
                    + OWNER
                    + ",f.owner_staff_name="
                    + NAME
                    + """
,f.updated_time=#{syncedAt}
WHERE f.tenant_id=#{tenantId} AND (NOT(f.owner_staff_code COLLATE utf8mb4_general_ci <=>\
"""
                    + OWNER
                    + ") OR NOT(f.owner_staff_name COLLATE utf8mb4_general_ci <=> "
                    + NAME
                    + "))";
}
