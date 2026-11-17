package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationDeadLetterEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IntegrationDeadLetterMapper extends BaseMapper<IntegrationDeadLetterEntity> {
    @Select(
            """
            SELECT d.* FROM integration_dead_letter d
            JOIN integration_sync_run r ON r.tenant_id=d.tenant_id AND r.id=d.run_id
            JOIN integration_sync_task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id
            WHERE d.tenant_id=#{tenant} AND t.connector_id=#{connector}
              AND d.source_object_type=#{type} AND d.source_id=#{sourceId} AND d.status='OPEN'
            ORDER BY d.updated_at DESC, d.created_at DESC LIMIT 1
            """)
    IntegrationDeadLetterEntity findOpenForSource(
            @Param("tenant") byte[] tenant,
            @Param("connector") byte[] connector,
            @Param("type") String type,
            @Param("sourceId") String sourceId);

    @Select("""
            SELECT x.*
            FROM (SELECT d.*, ROW_NUMBER() OVER(PARTITION BY d.source_id ORDER BY d.updated_at DESC,d.created_at DESC,d.id DESC) AS rn
              FROM integration_dead_letter d
              JOIN integration_sync_run r ON r.tenant_id=d.tenant_id AND r.id=d.run_id
              JOIN integration_sync_task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id
              WHERE d.tenant_id=#{tenant} AND t.connector_id=#{connector} AND d.status='OPEN'
                AND d.source_object_type='SALES_SHIPMENT') x
            WHERE x.rn=1 ORDER BY x.updated_at DESC,x.source_id LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<IntegrationDeadLetterEntity> shipmentIssues(@Param("tenant") byte[] tenant,
            @Param("connector") byte[] connector, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT COUNT(DISTINCT d.source_id) FROM integration_dead_letter d
            JOIN integration_sync_run r ON r.tenant_id=d.tenant_id AND r.id=d.run_id
            JOIN integration_sync_task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id
            WHERE d.tenant_id=#{tenant} AND t.connector_id=#{connector} AND d.status='OPEN'
              AND d.source_object_type='SALES_SHIPMENT'
            """)
    long shipmentIssueCount(@Param("tenant") byte[] tenant, @Param("connector") byte[] connector);

    @Update(
            """
            <script>
            UPDATE integration_external_object_mapping m
            STRAIGHT_JOIN integration_dead_letter d
              ON d.tenant_id=m.tenant_id AND d.source_system=m.source_system
             AND d.source_object_type=m.source_object_type
             AND d.source_id=<choose><when test="sourceNumber">m.source_object_no</when><otherwise>m.source_object_id</otherwise></choose>
            SET d.status='RESOLVED', d.resolved_at=#{now}, d.resolved_by=#{actor},
                d.updated_at=#{now}, d.updated_by=#{actor}, d.version=d.version+1
            WHERE m.tenant_id=#{tenant} AND m.source_system=#{sourceSystem}
              AND m.mapping_status='ACTIVE' AND m.deleted_at IS NULL
              AND m.internal_object_id IS NOT NULL AND m.last_seen_at IS NOT NULL
              AND m.last_seen_at &gt; d.updated_at AND d.status IN ('OPEN','REPLAYING')
            </script>
            """)
    int resolveRecovered(
            @Param("tenant") byte[] tenant,
            @Param("actor") byte[] actor,
            @Param("now") LocalDateTime now,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceNumber") boolean sourceNumber);
}
