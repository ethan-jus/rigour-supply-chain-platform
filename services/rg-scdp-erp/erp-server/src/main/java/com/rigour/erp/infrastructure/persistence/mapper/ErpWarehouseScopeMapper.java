package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.erp.infrastructure.persistence.entity.InternalInventoryWarehouseEntity;

import org.apache.ibatis.annotations.*;

/** 表名仅接收数据范围组件中的固定白名单，不接受接口输入。 */
@Mapper
public interface ErpWarehouseScopeMapper extends BaseMapper<InternalInventoryWarehouseEntity> {
    @Select("SELECT COUNT(*) FROM ${table} ${ew.customSqlSegment}")
    int countVisible(@Param("table") String table, @Param(Constants.WRAPPER) Wrapper<?> query);
}
