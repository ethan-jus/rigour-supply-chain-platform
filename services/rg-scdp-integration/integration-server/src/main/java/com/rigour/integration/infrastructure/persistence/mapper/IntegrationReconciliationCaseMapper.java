package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationReconciliationCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

@Mapper
public interface IntegrationReconciliationCaseMapper extends BaseMapper<IntegrationReconciliationCaseEntity> {
    @Update("""
            <script>
            UPDATE integration_external_object_mapping m
            STRAIGHT_JOIN integration_reconciliation_case d
              ON d.tenant_id=m.tenant_id AND d.source_system=m.source_system
             AND d.source_object_type=m.source_object_type
             AND d.business_key=<choose><when test="sourceNumber">m.source_object_no</when><otherwise>m.source_object_id</otherwise></choose>
            SET d.status='RESOLVED', d.resolved_at=#{now}, d.resolved_by=#{actor},
                d.updated_at=#{now}, d.updated_by=#{actor}, d.version=d.version+1
            WHERE m.tenant_id=#{tenant} AND m.source_system=#{sourceSystem}
              AND m.mapping_status='ACTIVE' AND m.deleted_at IS NULL
              AND m.internal_object_id IS NOT NULL AND m.last_seen_at IS NOT NULL
              AND m.last_seen_at &gt; d.updated_at AND d.status IN ('OPEN','ACKNOWLEDGED')
            </script>
            """)
    int resolveRecovered(@Param("tenant") byte[] tenant, @Param("actor") byte[] actor,
            @Param("now") LocalDateTime now, @Param("sourceSystem") String sourceSystem,
            @Param("sourceNumber") boolean sourceNumber);
}
