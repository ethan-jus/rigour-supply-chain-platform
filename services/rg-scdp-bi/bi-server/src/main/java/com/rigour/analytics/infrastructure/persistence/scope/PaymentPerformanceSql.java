package com.rigour.analytics.infrastructure.persistence.scope;

/** 到账业绩优先归属原始回款经办人；经办人缺失时归属客户当前业务员。 */
public final class PaymentPerformanceSql {
    private PaymentPerformanceSql() {}

    public static final String CUSTOMER =
            "CASE WHEN COALESCE(c.deleted,0)=0 AND UPPER(TRIM(c.owner_employee_code)) NOT IN"
                + " ('UNKNOWN','MULTI') THEN NULLIF(TRIM(c.owner_employee_code),'') END";
    public static final String COLLECTOR =
            "CASE WHEN UPPER(TRIM(p.collector_staff_code)) NOT IN ('UNKNOWN','MULTI') THEN"
                + " NULLIF(TRIM(p.collector_staff_code),'') END";
    public static final String OWNER = "COALESCE(" + COLLECTOR + "," + CUSTOMER + ")";
    public static final String NAME =
            "COALESCE((SELECT MAX(NULLIF(TRIM(e.employee_name),'')) FROM bi_source_hr_hr_employee e"
                + " WHERE e.tenant_id=p.tenant_id AND e.employee_code="
                    + OWNER
                    + "),CASE WHEN "
                    + COLLECTOR
                    + " IS NOT NULL THEN"
                    + " NULLIF(TRIM(p.collector_name_snapshot),'')"
                    + " WHEN "
                    + CUSTOMER
                    + " IS NOT NULL THEN"
                    + " COALESCE(NULLIF(TRIM(c.owner_employee_name_snapshot),''),NULLIF(TRIM(c.owner_sales_name),''))"
                    + " END,"
                    + OWNER
                    + ")";

    // 原始 collector_* 字段不回写；城市仍按已有订单城市口径，人员归属不更改历史交易归属。
    public static final String ALIGN =
            """
UPDATE bi_sales_payment_fact f
JOIN bi_source_order_order_payment_record p ON p.tenant_id=f.tenant_id AND p.id=f.payment_id
LEFT JOIN bi_source_order_order_sales_order o ON o.tenant_id=p.tenant_id AND o.id=p.order_id
LEFT JOIN bi_source_crm_crm_customer c ON c.tenant_id=p.tenant_id AND c.id=COALESCE(p.customer_id,o.customer_id)
SET f.owner_staff_code=\
"""
                    + OWNER
                    + ",f.owner_staff_name="
                    + NAME
                    + """
,f.updated_time=#{syncedAt}
WHERE f.tenant_id=#{tenantId} AND (NOT(f.owner_staff_code <=>\
"""
                    + OWNER
                    + ") OR NOT(f.owner_staff_name <=> "
                    + NAME
                    + "))";
}
