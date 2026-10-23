package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.CostFactEntity;

/** 仅访问BI本库，所有费用读写显式限定租户与批次。 */
@org.apache.ibatis.annotations.Mapper
public interface CostFactMapper extends BaseMapper<CostFactEntity> {

    @org.apache.ibatis.annotations.Insert(
            "<script>INSERT INTO"
                + " bi_source_cost_record(id,tenant_id,snapshot_id,source_service,source_id,cost_date,category,record_json)"
                + " VALUES <foreach collection='rows' item='r'"
                + " separator=','>(#{r.id},#{r.tenantId},#{r.snapshotId},#{r.sourceService},#{r.sourceId},#{r.costDate},#{r.category},#{r.recordJson})</foreach></script>")
    void insertBatch(
            @org.apache.ibatis.annotations.Param("rows") java.util.List<CostFactEntity> rows);
}
