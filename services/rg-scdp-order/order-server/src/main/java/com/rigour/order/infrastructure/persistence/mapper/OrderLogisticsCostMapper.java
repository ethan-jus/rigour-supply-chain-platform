package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderLogisticsCostMapper extends BaseMapper<OrderLogisticsCostEntity> {
    @Insert("INSERT IGNORE INTO order_logistics_cost_guard(tenant_id,version) VALUES(#{tenant},0)")
    void ensureGuard(@Param("tenant") String tenant);

    @Select("SELECT version FROM order_logistics_cost_guard WHERE tenant_id=#{tenant} FOR UPDATE")
    long lock(@Param("tenant") String tenant);
}
