package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollComponentOperationEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HrPayrollComponentOperationMapper
        extends BaseMapper<HrPayrollComponentOperationEntity> {}
