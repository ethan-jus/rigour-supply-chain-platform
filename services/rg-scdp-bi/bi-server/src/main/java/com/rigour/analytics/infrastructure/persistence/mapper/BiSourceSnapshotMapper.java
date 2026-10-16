package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisXMLLanguageDriver;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.BiPaymentStatusEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 后台同步按租户发布完整源镜像，查询看板仍由 BiScopedLanguageDriver 实施人员范围过滤。 */
public interface BiSourceSnapshotMapper extends BaseMapper<BiPaymentStatusEntity> {
    @Insert(
            """
INSERT IGNORE INTO bi_source_snapshot_checkpoint(tenant_id,dataset,source_version,projected_at)
VALUES(#{tenant},#{dataset},'UNINITIALIZED',UTC_TIMESTAMP(6))
""")
    int initialize(@Param("tenant") String tenant, @Param("dataset") String dataset);

    @Lang(MybatisXMLLanguageDriver.class)
    @Select(
            """
            SELECT source_version FROM bi_source_snapshot_checkpoint
            WHERE tenant_id=#{tenant} AND dataset=#{dataset} FOR UPDATE
            """)
    String lockVersion(@Param("tenant") String tenant, @Param("dataset") String dataset);

    @DeleteProvider(type = BiSourceSnapshotSql.class, method = "clear")
    int clearDataset(@Param("tenant") String tenant, @Param("dataset") String dataset);

    @InsertProvider(type = BiSourceSnapshotSql.class, method = "insert")
    int insertRows(@Param("dataset") String dataset, @Param("rows") List<Map<String, Object>> rows);

    @Update(
            """
UPDATE bi_source_snapshot_checkpoint SET source_version=#{version},projected_at=UTC_TIMESTAMP(6)
WHERE tenant_id=#{tenant} AND dataset=#{dataset}
""")
    int publish(
            @Param("tenant") String tenant,
            @Param("dataset") String dataset,
            @Param("version") String version);
}
