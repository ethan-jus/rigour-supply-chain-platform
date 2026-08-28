package com.rigour.merchant.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.merchant.infrastructure.persistence.entity.CrmSyncRunEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CrmSyncRunMapper extends BaseMapper<CrmSyncRunEntity> {

    /** 只终结超过阈值且没有有效锁所有权的运行，避免普通新任务误改活跃批次。 */
    @Update("""
            UPDATE crm_sync_run sync_run
            LEFT JOIN crm_sync_lock lock_row
              ON lock_row.tenant_id = sync_run.tenant_id
             AND lock_row.connector_id = sync_run.connector_id
             AND lock_row.run_id = sync_run.id
             AND lock_row.expires_at > #{now}
               SET sync_run.status = 'FAILED',
                   sync_run.error_code = 'STALE_RUN_RECOVERED',
                   sync_run.error_message = '同步运行超过恢复阈值且没有有效锁，已在后续批次启动前终结',
                   sync_run.finished_at = #{now},
                   sync_run.updated_time = #{now}
             WHERE sync_run.tenant_id = #{tenantId}
               AND sync_run.connector_id = #{connectorId}
               AND sync_run.object_type = #{objectType}
               AND sync_run.status = 'RUNNING'
               AND sync_run.updated_time <= #{staleBefore}
               AND lock_row.id IS NULL
            """)
    int recoverStaleRuns(@Param("tenantId") byte[] tenantId,
                         @Param("connectorId") byte[] connectorId,
                         @Param("objectType") String objectType,
                         @Param("staleBefore") LocalDateTime staleBefore,
                         @Param("now") LocalDateTime now);
}
