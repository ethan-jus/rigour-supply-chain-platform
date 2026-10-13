package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSalaryEntity;

import org.apache.ibatis.annotations.Mapper;

/** 仅访问员工工资字段。 */
@Mapper
public interface HrEmployeeSalaryMapper extends BaseMapper<HrEmployeeSalaryEntity> {}
