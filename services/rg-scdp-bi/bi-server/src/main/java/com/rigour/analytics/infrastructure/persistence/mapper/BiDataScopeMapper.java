package com.rigour.analytics.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 仅访问 BI 本地授权投影和已授权事实，不在请求期间跨库查 HR/IAM/CRM。 */
@Mapper
public interface BiDataScopeMapper {
    @Select(
            """
<script>
WITH visible AS (
    SELECT region_code, region_name, owner_staff_code, owner_staff_name,
           customer_type_code, customer_type_name, source_system_code
      FROM bi_sales_order_fact
     WHERE tenant_id = #{tenantId} AND deleted = 0 AND order_status_code != 'CANCELLED'
    <if test="regions != null">
       AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
    <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
    UNION ALL
    SELECT region_code, region_name, owner_staff_code, owner_staff_name,
           customer_type_code, customer_type_name, NULL AS source_system_code
      FROM bi_customer_dim
     WHERE tenant_id = #{tenantId} AND deleted = 0 AND status_code = 'ACTIVE'
    <if test="regions != null">
       AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
    <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
    UNION ALL
    SELECT region_code, city_name, employee_code, employee_name, NULL, NULL, NULL
      FROM bi_employee_dim WHERE tenant_id = #{tenantId}
    <if test="regions != null">
       AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
    <if test="ownerStaffCode != null">AND employee_code = #{ownerStaffCode}</if>
    UNION ALL
    SELECT region_code, city_name, owner_staff_code, NULL, NULL, NULL, NULL
      FROM bi_sales_contact_fact WHERE tenant_id = #{tenantId}
    <if test="regions != null">
       AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
    <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
)
SELECT 'REGION' AS optionType, region_code AS optionValue, city_name AS optionLabel
  FROM bi_sales_contact_city_dim WHERE tenant_id=#{tenantId}
    <if test="regions != null">
       AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
UNION ALL
SELECT 'SALES_OWNER', owner_staff_code, COALESCE(MAX(owner_staff_name), owner_staff_code)
  FROM visible WHERE owner_staff_code IS NOT NULL GROUP BY owner_staff_code
UNION ALL
SELECT 'CUSTOMER_TYPE', customer_type_code, COALESCE(MAX(customer_type_name), customer_type_code)
  FROM visible WHERE customer_type_code IS NOT NULL GROUP BY customer_type_code
UNION ALL
SELECT 'SOURCE_SYSTEM', source_system_code, source_system_code
  FROM visible WHERE source_system_code IS NOT NULL GROUP BY source_system_code
UNION ALL
SELECT 'PRODUCT_CATEGORY', CAST(l.product_category_id AS CHAR),
       COALESCE(MAX(l.product_category_name), MAX(l.product_category_code))
  FROM bi_sales_order_line_fact l
 WHERE l.tenant_id = #{tenantId} AND l.deleted = 0 AND l.order_status_code != 'CANCELLED'
    <if test="regions != null">
   AND l.region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
    </if>
   AND l.product_category_id IS NOT NULL
<if test="ownerStaffCode != null">AND l.owner_staff_code = #{ownerStaffCode}</if>
 GROUP BY l.product_category_id
 ORDER BY optionType, optionValue
</script>
""")
    List<Map<String, Object>> filterOptions(
            @Param("tenantId") String tenantId,
            @Param("regions") List<String> regions,
            @Param("ownerStaffCode") String ownerStaffCode);
}
