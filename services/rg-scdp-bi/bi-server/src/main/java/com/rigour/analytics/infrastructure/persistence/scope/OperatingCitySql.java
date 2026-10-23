package com.rigour.analytics.infrastructure.persistence.scope;

/** 城市名单只取启用的销售部所有下级部门；地区关联用于映射原始订单的历史城市。 */
public final class OperatingCitySql {
    private OperatingCitySql() {}
    /** Selected sales department includes its descendants; authorization remains a separate intersection. */
    public static final String REGION_SCOPE = """
 IN (SELECT #{regionCode} UNION
     SELECT child.region_code FROM bi_sales_contact_city_dim child
     JOIN bi_sales_contact_city_dim parent ON parent.tenant_id=child.tenant_id
     WHERE child.tenant_id=#{tenantId} AND parent.region_code=#{regionCode}
       AND (child.department_id=parent.department_id OR
         REPLACE(REPLACE(REPLACE(child.department_path,'[',','),']',','),' ','')
           LIKE CONCAT('%,',parent.department_id,',%')))
""";
    public static final String JDBC_REGION_SCOPE = REGION_SCOPE
            .replace("#{regionCode}", ":region").replace("#{tenantId}", ":tenant");

    public static final String INSERT = """
INSERT INTO bi_sales_contact_city_dim
 (tenant_id,region_code,city_name,department_id,department_path,source_region_code)
SELECT d.tenant_id,d.department_code,d.department_name,d.id,d.department_path,
 (SELECT CASE WHEN COUNT(*)=1 THEN MIN(a.area_code) END
    FROM bi_source_crm_crm_customer_area a
   WHERE a.tenant_id=UUID_TO_BIN(d.tenant_id) AND a.deleted=0 AND a.status='ACTIVE'
     AND TRIM(TRAILING '市' FROM TRIM(a.area_name))=TRIM(TRAILING '市' FROM TRIM(d.department_name)))
 FROM bi_source_hr_hr_department d
 JOIN bi_source_hr_hr_department p ON p.tenant_id=d.tenant_id AND p.id<>d.id AND REPLACE(REPLACE(REPLACE(d.department_path,'[',','),']',','),' ','') LIKE CONCAT('%,',p.id,',%')
 WHERE d.tenant_id=#{tenantId} AND d.deleted=0 AND d.status_code='ACTIVE'
   AND p.deleted=0 AND p.status_code='ACTIVE' AND p.department_name='销售部'
""";
}
