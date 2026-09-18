package com.rigour.merchant.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.merchant.infrastructure.persistence.entity.InternalCustomerEntity;

/** CRM 自研客户表 Mapper。 */
public interface InternalCustomerMapper extends BaseMapper<InternalCustomerEntity> {
    record CustomerSourceCodeRow(Long customerId, String sourceCode) {}

    @org.apache.ibatis.annotations.Select("""
        <script>
        SELECT c.id customerId, b.source_code sourceCode
        FROM crm_customer c JOIN crm_source_binding b ON b.tenant_id=UUID_TO_BIN(c.tenant_id)
          AND b.target_id=c.party_id AND b.source_object_type='CUSTOMER'
          AND b.source_system='DINGHUOBAO' AND b.binding_status='RESOLVED' AND b.deleted=0
        WHERE c.tenant_id=#{tenant} AND c.deleted=0 AND NULLIF(TRIM(b.source_code),'') IS NOT NULL
          AND c.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY b.primary_customer_source_id IS NOT NULL, b.source_code
        </script>
        """)
    java.util.List<CustomerSourceCodeRow> customerSourceCodes(
        @org.apache.ibatis.annotations.Param("tenant") String tenant,
        @org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);

    @org.apache.ibatis.annotations.Update("""
        UPDATE crm_customer c JOIN crm_address a ON a.id=(
            SELECT x.id FROM crm_address x WHERE x.tenant_id=UUID_TO_BIN(#{tenant}) AND x.party_id=#{party}
            AND x.address_type='SHIPPING' AND x.deleted=0 AND x.status='ACTIVE'
            ORDER BY x.is_default DESC, (x.ownership_state='INTERNAL_PRIMARY') DESC, x.created_time, x.id LIMIT 1)
        LEFT JOIN crm_contact k ON k.tenant_id=a.tenant_id AND k.id=a.contact_id AND k.deleted=0
        SET c.contact_name=k.contact_name,c.contact_phone=k.phone,c.address=a.full_address,c.updated_time=c.updated_time
        WHERE c.tenant_id=#{tenant} AND c.party_id=#{party} AND c.deleted=0
        """)
    int refreshDefaultShipping(@org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("party") byte[] party);
    @org.apache.ibatis.annotations.Update("""
        UPDATE crm_customer c SET login_account=#{account}, dhb_customer_code=#{code},
            settlement_type_code=COALESCE(#{settlement},settlement_type_code),
            synced_at=#{time}, synced_by=COALESCE((SELECT r.created_by FROM crm_sync_run r
                WHERE r.tenant_id=UUID_TO_BIN(#{tenant}) AND r.id=#{run}), 'SYSTEM'),
            updated_time=c.updated_time
        WHERE c.tenant_id=#{tenant} AND c.party_id=#{party} AND c.deleted=0
        """)
    int refreshDhbProfile(@org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("party") byte[] party,
            @org.apache.ibatis.annotations.Param("run") byte[] run,
            @org.apache.ibatis.annotations.Param("account") String account,
            @org.apache.ibatis.annotations.Param("code") String code,
            @org.apache.ibatis.annotations.Param("settlement") String settlement,
            @org.apache.ibatis.annotations.Param("time") java.time.LocalDateTime time);
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM crm_source_binding WHERE tenant_id=UUID_TO_BIN(#{tenantId}) AND source_system='DINGHUOBAO' AND source_object_type='CUSTOMER' AND target_id=#{partyId} AND binding_status='RESOLVED' AND deleted=0")
    long countDhbBindings(@org.apache.ibatis.annotations.Param("tenantId") String tenantId,
                         @org.apache.ibatis.annotations.Param("partyId") byte[] partyId);

}
