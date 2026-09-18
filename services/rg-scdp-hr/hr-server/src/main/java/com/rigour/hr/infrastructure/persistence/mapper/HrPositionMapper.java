package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrPositionEntity;
import org.apache.ibatis.annotations.Mapper;

/** HR 岗位 Mapper。 */
@Mapper
public interface HrPositionMapper extends BaseMapper<HrPositionEntity> {
}
