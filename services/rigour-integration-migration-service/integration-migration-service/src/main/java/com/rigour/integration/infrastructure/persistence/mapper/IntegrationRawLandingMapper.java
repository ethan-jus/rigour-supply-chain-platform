package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationRawLandingEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IntegrationRawLandingMapper extends BaseMapper<IntegrationRawLandingEntity> {
}
