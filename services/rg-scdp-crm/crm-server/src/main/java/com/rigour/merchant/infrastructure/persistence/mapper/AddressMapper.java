package com.rigour.merchant.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.merchant.infrastructure.persistence.entity.AddressEntity;

public interface AddressMapper extends BaseMapper<AddressEntity> {
    // Both source addresses are retained after a customer merge; only the chosen main
    // customer's source may supply the default. Local address choices stay authoritative.
    @org.apache.ibatis.annotations.Update("""
        UPDATE crm_address a
        JOIN crm_source_binding b ON b.tenant_id=a.tenant_id AND b.target_id=a.id
            AND b.source_object_type='ADDRESS' AND b.deleted=0
        JOIN crm_source_identity_alias x ON x.tenant_id=b.tenant_id AND x.connector_id=b.connector_id
            AND x.source_system=b.source_system AND x.source_object_type='CUSTOMER'
            AND ((x.alias_type='ID' AND x.alias_value=JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientId')))
              OR (x.alias_type='GUID' AND x.alias_value=JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientGuid')))
              OR (x.alias_type='NUM' AND x.alias_value=JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientNum'))))
        JOIN crm_source_binding customer ON customer.tenant_id=x.tenant_id AND customer.id=x.binding_id
            AND customer.target_id=a.party_id AND customer.binding_status='RESOLVED'
            AND customer.primary_customer_source_id IS NOT NULL AND customer.deleted=0
        SET a.is_default=0,a.revision=a.revision+1,a.updated_by='SYSTEM',a.updated_time=UTC_TIMESTAMP(6)
        WHERE a.tenant_id=#{tenant} AND a.id=#{address} AND a.deleted=0 AND a.is_default=1
            AND a.address_type='SHIPPING' AND a.ownership_state='EXTERNAL_PRIMARY'
        """)
    int suppressMergedSecondaryDefault(@org.apache.ibatis.annotations.Param("tenant") byte[] tenant,
            @org.apache.ibatis.annotations.Param("address") byte[] address);
}
