package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrEmployeeEntity;
import org.apache.ibatis.annotations.Mapper;

/** HR 员工主档 Mapper。 */
@Mapper
public interface HrEmployeeMapper extends BaseMapper<HrEmployeeEntity> {
}
