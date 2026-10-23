package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollComponentReviewEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HrPayrollComponentReviewMapper
        extends BaseMapper<HrPayrollComponentReviewEntity> {}
