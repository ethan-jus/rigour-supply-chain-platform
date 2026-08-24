package com.rigour.merchant.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Portal CRM 列表和详情的定向查询；分页和租户过滤均在数据库完成。 */
public interface CrmQueryMapper {

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM crm_party p
              JOIN crm_customer_profile cp ON cp.tenant_id=p.tenant_id AND cp.party_id=p.id
             WHERE p.tenant_id=#{tenantId} AND p.deleted_at IS NULL
            <if test="query != null and query != ''">
               AND (p.party_code LIKE #{query} OR p.display_name LIKE #{query}
                    OR cp.login_account LIKE #{query})
            </if>
            <if test="status != null and status != ''"> AND p.internal_status=#{status}</if>
            </script>
            """)
    long countCustomers(@Param("tenantId") byte[] tenantId,
                        @Param("query") String query, @Param("status") String status);

    @Select("""
            <script>
            SELECT p.id,p.party_code,p.display_name,p.internal_status,cp.login_account,
                   COALESCE(ct.type_name,cp.customer_type_name_snapshot) AS type_name,
                   COALESCE(ca.area_name,cp.customer_area_name_snapshot) AS area_name,
                   c.contact_name,c.phone,COALESCE(sa.iam_staff_name_snapshot,sa.source_name_snapshot)
                       AS source_name_snapshot,b.source_updated_at,
                   b.synced_at,b.source_presence,b.source_status,b.source_absent_at
              FROM crm_party p
              JOIN crm_customer_profile cp ON cp.tenant_id=p.tenant_id AND cp.party_id=p.id
              LEFT JOIN crm_customer_type ct ON ct.tenant_id=cp.tenant_id AND ct.id=cp.customer_type_id
              LEFT JOIN crm_customer_area ca ON ca.tenant_id=cp.tenant_id AND ca.id=cp.customer_area_id
              LEFT JOIN crm_contact c ON c.tenant_id=p.tenant_id AND c.party_id=p.id
                   AND c.contact_type='PRIMARY' AND c.record_origin='IMPORTED'
              LEFT JOIN crm_sales_assignment sa ON sa.tenant_id=p.tenant_id AND sa.party_id=p.id
                   AND sa.assignment_type='PRIMARY' AND sa.status='ACTIVE'
              LEFT JOIN crm_source_binding b ON b.tenant_id=p.tenant_id AND b.target_id=p.id
                   AND b.target_type='PARTY' AND b.source_object_type='CUSTOMER'
             WHERE p.tenant_id=#{tenantId} AND p.deleted_at IS NULL
            <if test="query != null and query != ''">
               AND (p.party_code LIKE #{query} OR p.display_name LIKE #{query}
                    OR cp.login_account LIKE #{query})
            </if>
            <if test="status != null and status != ''"> AND p.internal_status=#{status}</if>
             ORDER BY b.source_updated_at DESC,p.updated_at DESC
             LIMIT #{step} OFFSET #{begin}
            </script>
            """)
    List<Map<String, Object>> customers(@Param("tenantId") byte[] tenantId,
                                        @Param("begin") int begin, @Param("step") int step,
                                        @Param("query") String query,
                                        @Param("status") String status);

    @Select("""
            <script>
            SELECT sa.party_id,sa.assignment_type,sa.source_staff_id,sa.iam_staff_code,
                   COALESCE(sa.iam_staff_name_snapshot,sa.source_name_snapshot) AS staff_name
              FROM crm_sales_assignment sa
             WHERE sa.tenant_id=#{tenantId} AND sa.status='ACTIVE'
               AND sa.party_id IN
               <foreach collection="partyIds" item="partyId" open="(" separator="," close=")">
                 #{partyId}
               </foreach>
             ORDER BY sa.party_id,FIELD(sa.assignment_type,'PRIMARY','SECONDARY','SERVICE'),
                      sa.effective_from,sa.created_at
            </script>
            """)
    List<Map<String, Object>> salesAssignments(@Param("tenantId") byte[] tenantId,
                                               @Param("partyIds") List<byte[]> partyIds);

    @Select("""
            SELECT p.id,p.party_code,p.display_name,p.internal_status,cp.login_account,
                   COALESCE(ct.type_name,cp.customer_type_name_snapshot) AS type_name,
                   COALESCE(ca.area_name,cp.customer_area_name_snapshot) AS area_name,
                   cp.city_text,cp.inviter_name,cp.remark,c.contact_name,c.phone,c.email,
                   a.full_address,pol.settlement_mode,
                   COALESCE(sa.iam_staff_name_snapshot,sa.source_name_snapshot) AS source_name_snapshot,b.source_status,
                   b.source_object_id,b.source_created_at,b.source_updated_at,b.synced_at,
                   b.source_presence,b.source_absent_at,b.source_fields_json
              FROM crm_party p
              JOIN crm_customer_profile cp ON cp.tenant_id=p.tenant_id AND cp.party_id=p.id
              LEFT JOIN crm_customer_type ct ON ct.tenant_id=cp.tenant_id AND ct.id=cp.customer_type_id
              LEFT JOIN crm_customer_area ca ON ca.tenant_id=cp.tenant_id AND ca.id=cp.customer_area_id
              LEFT JOIN crm_contact c ON c.tenant_id=p.tenant_id AND c.party_id=p.id
                   AND c.contact_type='PRIMARY' AND c.record_origin='IMPORTED'
              LEFT JOIN crm_address a ON a.tenant_id=p.tenant_id AND a.party_id=p.id
                   AND a.address_type='CONTACT' AND a.record_origin='IMPORTED'
              LEFT JOIN crm_customer_policy pol ON pol.tenant_id=p.tenant_id AND pol.party_id=p.id
              LEFT JOIN crm_sales_assignment sa ON sa.tenant_id=p.tenant_id AND sa.party_id=p.id
                   AND sa.assignment_type='PRIMARY' AND sa.status='ACTIVE'
              LEFT JOIN crm_source_binding b ON b.tenant_id=p.tenant_id AND b.target_id=p.id
                   AND b.target_type='PARTY' AND b.source_object_type='CUSTOMER'
             WHERE p.tenant_id=#{tenantId} AND p.id=#{id} AND p.deleted_at IS NULL
             LIMIT 1
            """)
    Map<String, Object> customer(@Param("tenantId") byte[] tenantId, @Param("id") byte[] id);

    @Select("""
            SELECT a.id,a.consignee,c.contact_name,c.phone,a.region_text,a.area_name,
                   a.address_detail,a.full_address,a.is_default,b.source_updated_at,
                   b.source_fields_json,b.source_presence,b.source_absent_at
              FROM crm_address a
              LEFT JOIN crm_contact c ON c.tenant_id=a.tenant_id AND c.id=a.contact_id
              LEFT JOIN crm_source_binding b ON b.tenant_id=a.tenant_id AND b.target_id=a.id
                   AND b.target_type='ADDRESS' AND b.source_object_type='ADDRESS'
             WHERE a.tenant_id=#{tenantId} AND a.party_id=#{partyId}
               AND a.address_type='SHIPPING' AND a.deleted_at IS NULL
             ORDER BY a.is_default DESC,a.updated_at DESC
            """)
    List<Map<String, Object>> shippingAddresses(@Param("tenantId") byte[] tenantId,
                                                @Param("partyId") byte[] partyId);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM crm_address a
              JOIN crm_party p ON p.tenant_id=a.tenant_id AND p.id=a.party_id
              LEFT JOIN crm_contact c ON c.tenant_id=a.tenant_id AND c.id=a.contact_id
             WHERE a.tenant_id=#{tenantId} AND a.address_type='SHIPPING'
               AND a.deleted_at IS NULL AND p.deleted_at IS NULL
            <if test="query != null and query != ''">
               AND (p.party_code LIKE #{query} OR p.display_name LIKE #{query}
                    OR a.consignee LIKE #{query} OR c.contact_name LIKE #{query}
                    OR c.phone LIKE #{query} OR a.region_text LIKE #{query}
                    OR a.area_name LIKE #{query} OR a.address_detail LIKE #{query}
                    OR a.full_address LIKE #{query})
            </if>
            </script>
            """)
    long countShippingAddressBook(@Param("tenantId") byte[] tenantId,
                                  @Param("query") String query);

    @Select("""
            <script>
            SELECT a.id,p.id AS customer_id,p.party_code,p.display_name,
                   b.source_object_id,a.consignee,c.contact_name,c.phone,
                   a.region_text,a.area_name,a.address_detail,a.full_address,
                   a.is_default,a.status,b.source_updated_at,b.synced_at,b.source_presence,
                   b.source_absent_at
              FROM crm_address a
              JOIN crm_party p ON p.tenant_id=a.tenant_id AND p.id=a.party_id
              LEFT JOIN crm_contact c ON c.tenant_id=a.tenant_id AND c.id=a.contact_id
              LEFT JOIN crm_source_binding b ON b.tenant_id=a.tenant_id AND b.target_id=a.id
                   AND b.target_type='ADDRESS' AND b.source_object_type='ADDRESS'
             WHERE a.tenant_id=#{tenantId} AND a.address_type='SHIPPING'
               AND a.deleted_at IS NULL AND p.deleted_at IS NULL
            <if test="query != null and query != ''">
               AND (p.party_code LIKE #{query} OR p.display_name LIKE #{query}
                    OR a.consignee LIKE #{query} OR c.contact_name LIKE #{query}
                    OR c.phone LIKE #{query} OR a.region_text LIKE #{query}
                    OR a.area_name LIKE #{query} OR a.address_detail LIKE #{query}
                    OR a.full_address LIKE #{query})
            </if>
             ORDER BY a.is_default DESC,b.source_updated_at DESC,a.updated_at DESC
             LIMIT #{step} OFFSET #{begin}
            </script>
            """)
    List<Map<String, Object>> shippingAddressBook(
            @Param("tenantId") byte[] tenantId,
            @Param("begin") int begin, @Param("step") int step,
            @Param("query") String query);

    @Select("""
            <script>
            SELECT COUNT(*) FROM crm_customer_type d
             WHERE d.tenant_id=#{tenantId} AND d.deleted_at IS NULL
            <if test="query != null and query != ''">
              AND (d.type_code LIKE #{query} OR d.type_name LIKE #{query})
            </if>
            </script>
            """)
    long countCustomerTypes(@Param("tenantId") byte[] tenantId, @Param("query") String query);

    @Select("""
            <script>
            SELECT d.id,d.type_code AS code,d.type_name AS name,d.status,b.synced_at,
                   b.source_presence,b.source_absent_at
              FROM crm_customer_type d
              LEFT JOIN crm_source_binding b ON b.tenant_id=d.tenant_id AND b.target_id=d.id
                   AND b.source_object_type='CUSTOMER_TYPE'
             WHERE d.tenant_id=#{tenantId} AND d.deleted_at IS NULL
            <if test="query != null and query != ''">
              AND (d.type_code LIKE #{query} OR d.type_name LIKE #{query})
            </if>
             ORDER BY d.type_name LIMIT #{step} OFFSET #{begin}
            </script>
            """)
    List<Map<String, Object>> customerTypes(@Param("tenantId") byte[] tenantId,
                                            @Param("begin") int begin, @Param("step") int step,
                                            @Param("query") String query);

    @Select("""
            <script>
            SELECT COUNT(*) FROM crm_customer_area d
             WHERE d.tenant_id=#{tenantId} AND d.deleted_at IS NULL
            <if test="query != null and query != ''">
              AND (d.area_code LIKE #{query} OR d.area_name LIKE #{query})
            </if>
            </script>
            """)
    long countCustomerAreas(@Param("tenantId") byte[] tenantId, @Param("query") String query);

    @Select("""
            <script>
            SELECT d.id,d.area_code AS code,d.area_name AS name,d.status,b.synced_at,
                   b.source_presence,b.source_absent_at,
                   d.parent_area_code AS parent_code,p.id AS parent_id
              FROM crm_customer_area d
              LEFT JOIN crm_customer_area p ON p.tenant_id=d.tenant_id
                   AND p.area_code=d.parent_area_code AND p.deleted_at IS NULL
              LEFT JOIN crm_source_binding b ON b.tenant_id=d.tenant_id AND b.target_id=d.id
                   AND b.source_object_type='CUSTOMER_AREA'
             WHERE d.tenant_id=#{tenantId} AND d.deleted_at IS NULL
            <if test="query != null and query != ''">
              AND (d.area_code LIKE #{query} OR d.area_name LIKE #{query})
            </if>
             ORDER BY d.area_name LIMIT #{step} OFFSET #{begin}
            </script>
            """)
    List<Map<String, Object>> customerAreas(@Param("tenantId") byte[] tenantId,
                                            @Param("begin") int begin, @Param("step") int step,
                                            @Param("query") String query);

}
