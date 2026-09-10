package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeSourceBindingEntity;
import org.apache.ibatis.annotations.Mapper;

/** HR 员工外部来源绑定 Mapper。 */
@Mapper
public interface HrEmployeeSourceBindingMapper extends BaseMapper<HrEmployeeSourceBindingEntity> {
}
