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

    /** Targets may be set at either level, so aggregate each visible ancestor independently. */
    public static final String TARGET_CITY_JOIN = """
 LEFT JOIN bi_sales_contact_city_dim target_leaf ON target_leaf.tenant_id=f.tenant_id
  AND target_leaf.region_code=f.region_code
 LEFT JOIN bi_sales_contact_city_dim target_city ON target_city.tenant_id=f.tenant_id
  AND (target_city.department_id=target_leaf.department_id OR
    REPLACE(REPLACE(REPLACE(target_leaf.department_path,'[',','),']',','),' ','')
      LIKE CONCAT('%,',target_city.department_id,',%'))
""";

    /** One display department per source city: national roots, or children of the selected department. */
    public static final String DISPLAY_CITY_JOIN = """
 LEFT JOIN bi_sales_contact_city_dim display_city ON display_city.tenant_id=#{tenantId}
 AND display_city.region_code=(
   SELECT ancestor.region_code FROM bi_sales_contact_city_dim ancestor
   JOIN bi_sales_contact_city_dim leaf ON leaf.tenant_id=ancestor.tenant_id
   WHERE ancestor.tenant_id=#{tenantId} AND leaf.region_code=""";
    public static final String DISPLAY_CITY_END = """
     AND (ancestor.department_id=leaf.department_id OR
       REPLACE(REPLACE(REPLACE(leaf.department_path,'[',','),']',','),' ','')
         LIKE CONCAT('%,',ancestor.department_id,',%'))
     AND (#{regionCode} IS NULL OR ancestor.region_code=leaf.region_code OR
       (ancestor.region_code&lt;&gt;#{regionCode} AND ancestor.region_code""" + REGION_SCOPE + """
       ))
   ORDER BY JSON_LENGTH(ancestor.department_path),ancestor.region_code LIMIT 1)
""";

    public static final String SELECT = """
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

    public static final String INSERT = """
INSERT INTO bi_sales_contact_city_dim
 (tenant_id,region_code,city_name,department_id,department_path,source_region_code)
""" + SELECT;
}
