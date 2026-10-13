package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.erp.infrastructure.persistence.entity.ErpCostEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ErpCostMapper extends BaseMapper<ErpCostEntity> {
    @Insert("INSERT IGNORE INTO erp_cost_guard(tenant_id,version) VALUES(#{tenant},0)")
    void ensureGuard(@Param("tenant") String tenant);

    @Select("SELECT version FROM erp_cost_guard WHERE tenant_id=#{tenant} FOR UPDATE")
    long lock(@Param("tenant") String tenant);
}
