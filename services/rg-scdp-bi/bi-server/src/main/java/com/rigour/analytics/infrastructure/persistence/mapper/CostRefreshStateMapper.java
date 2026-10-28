package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.CostRefreshStateEntity;

/** 仅访问BI本库，所有费用读写显式限定租户与批次。 */
@org.apache.ibatis.annotations.Mapper
public interface CostRefreshStateMapper extends BaseMapper<CostRefreshStateEntity> {

    // 重复租户直接取得写锁；INSERT IGNORE 的共享锁再升级 FOR UPDATE 会形成并发死锁。
    // 已登记的状态不变，初始化和后续发布始终采用同一行锁顺序。
    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO bi_cost_refresh_state(tenant_id,status)"
                    + " VALUES(#{tenant},'PENDING') ON DUPLICATE KEY UPDATE tenant_id=tenant_id")
    void initialize(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "/* supply-scoped */ SELECT * FROM bi_cost_refresh_state WHERE tenant_id=#{tenant} FOR"
                    + " UPDATE")
    CostRefreshStateEntity lock(@org.apache.ibatis.annotations.Param("tenant") String tenant);
}
