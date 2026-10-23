package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.CostRefreshStateEntity;

/** 仅访问BI本库，所有费用读写显式限定租户与批次。 */
@org.apache.ibatis.annotations.Mapper
public interface CostRefreshStateMapper extends BaseMapper<CostRefreshStateEntity> {

    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO bi_cost_refresh_state(tenant_id,status)"
                    + " VALUES(#{tenant},'PENDING')")
    void initialize(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "/* supply-scoped */ SELECT * FROM bi_cost_refresh_state WHERE tenant_id=#{tenant} FOR"
                    + " UPDATE")
    CostRefreshStateEntity lock(@org.apache.ibatis.annotations.Param("tenant") String tenant);
}
