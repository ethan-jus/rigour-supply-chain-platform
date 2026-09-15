package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.application.port.out.BiDataScopeStore.Grant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅访问 BI 本地授权投影和已授权事实，不在请求期间跨库查 HR/IAM/CRM。 */
@Mapper
public interface BiDataScopeMapper {
    @Update("""
            UPDATE bi_data_access_identity SET verified_at=#{verifiedAt}, expires_at=#{expiresAt}
             WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND verified_at=#{previousVerifiedAt}
               AND expires_at=#{previousExpiresAt} AND employee_code=#{employeeCode} AND owner_staff_code=#{ownerStaffCode}
               AND user_security_version=#{userSecurityVersion} AND tenant_policy_version=#{tenantPolicyVersion}
            """)
    int renewIfUnchanged(Map<String, Object> row);

    @Delete("DELETE FROM bi_data_access_scope WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    void deleteGrants(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Delete("DELETE FROM bi_data_access_identity WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    void deleteIdentity(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Insert("""
            INSERT INTO bi_data_access_identity (tenant_id,user_id,employee_code,owner_staff_code,iam_binding_ref,
                hr_employee_ref,crm_employee_ref,user_security_version,tenant_policy_version,verified_at,expires_at)
            VALUES (#{tenantId},#{userId},#{employeeCode},#{ownerStaffCode},#{iamBindingRef},#{hrEmployeeRef},
                #{crmEmployeeRef},#{userSecurityVersion},#{tenantPolicyVersion},#{verifiedAt},#{expiresAt})
            """)
    void insertIdentity(Map<String, Object> row);

    @Insert("""
            INSERT INTO bi_data_access_scope (tenant_id,user_id,role_code,scope_type,region_code,iam_policy_ref)
            VALUES (#{tenantId},#{userId},#{grant.roleCode},#{grant.scopeType},#{grant.regionCode},#{grant.iamPolicyRef})
            """)
    void insertGrant(@Param("tenantId") String tenantId, @Param("userId") String userId, @Param("grant") Grant grant);

    @Insert("""
            INSERT INTO bi_data_access_audit (tenant_id,user_id,actor_id,operation_code,reason_code)
            VALUES (#{tenantId},#{userId},#{actorId},#{operation},#{reason})
            """)
    void audit(@Param("tenantId") String tenantId, @Param("userId") String userId,
               @Param("actorId") String actorId, @Param("operation") String operation, @Param("reason") String reason);

    @Select("""
            SELECT employee_code AS employeeCode, owner_staff_code AS ownerStaffCode,
                   iam_binding_ref AS iamBindingRef, hr_employee_ref AS hrEmployeeRef, crm_employee_ref AS crmEmployeeRef,
                   user_security_version AS userSecurityVersion, tenant_policy_version AS tenantPolicyVersion,
                   verified_at AS verifiedAt, expires_at AS expiresAt
              FROM bi_data_access_identity WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    Map<String, Object> identity(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT role_code AS roleCode, scope_type AS scopeType, region_code AS regionCode, iam_policy_ref AS iamPolicyRef
              FROM bi_data_access_scope WHERE tenant_id = #{tenantId} AND user_id = #{userId}
             ORDER BY role_code, scope_type, region_code
            """)
    List<Grant> grants(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            <script>
            WITH visible AS (
                SELECT region_code, region_name, owner_staff_code, owner_staff_name,
                       customer_type_code, customer_type_name, source_system_code
                  FROM bi_sales_order_fact
                 WHERE tenant_id = #{tenantId} AND deleted = 0 AND order_status_code != 'CANCELLED'
                   AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
                <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
                UNION ALL
                SELECT region_code, region_name, owner_staff_code, owner_staff_name,
                       customer_type_code, customer_type_name, NULL AS source_system_code
                  FROM bi_customer_dim
                 WHERE tenant_id = #{tenantId} AND deleted = 0 AND status_code = 'ACTIVE'
                   AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
                <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
                UNION ALL
                SELECT region_code, city_name, employee_code, employee_name, NULL, NULL, NULL
                  FROM bi_employee_dim WHERE tenant_id = #{tenantId}
                   AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
                <if test="ownerStaffCode != null">AND employee_code = #{ownerStaffCode}</if>
                UNION ALL
                SELECT region_code, city_name, owner_staff_code, NULL, NULL, NULL, NULL
                  FROM bi_sales_contact_fact WHERE tenant_id = #{tenantId}
                   AND region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
                <if test="ownerStaffCode != null">AND owner_staff_code = #{ownerStaffCode}</if>
            )
            SELECT 'REGION' AS optionType, region_code AS optionValue, COALESCE(MAX(region_name), region_code) AS optionLabel
              FROM visible WHERE region_code IS NOT NULL GROUP BY region_code
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
               AND l.region_code IN <foreach collection="regions" item="region" open="(" close=")" separator=",">#{region}</foreach>
               AND l.product_category_id IS NOT NULL
            <if test="ownerStaffCode != null">AND l.owner_staff_code = #{ownerStaffCode}</if>
             GROUP BY l.product_category_id
             ORDER BY optionType, optionValue
            </script>
            """)
    List<Map<String, Object>> filterOptions(@Param("tenantId") String tenantId,
            @Param("regions") List<String> regions, @Param("ownerStaffCode") String ownerStaffCode);
}
