package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderLogisticsCostMapper extends BaseMapper<OrderLogisticsCostEntity> {
    @Insert("INSERT IGNORE INTO order_logistics_cost_guard(tenant_id,version) VALUES(#{tenant},0)")
    void ensureGuard(@Param("tenant") String tenant);

    @Select("SELECT version FROM order_logistics_cost_guard WHERE tenant_id=#{tenant} FOR UPDATE")
    long lock(@Param("tenant") String tenant);

    @Update("UPDATE order_logistics_cost_guard SET version=version+1 WHERE tenant_id=#{tenant}")
    void advanceAnalyticsVersion(@org.apache.ibatis.annotations.Param("tenant") String tenant);

    @org.apache.ibatis.annotations.Select(
            "SELECT COALESCE((SELECT version FROM order_logistics_cost_guard WHERE"
                    + " tenant_id=#{tenant}),0)")
    String analyticsVersion(@org.apache.ibatis.annotations.Param("tenant") String tenant);
}
