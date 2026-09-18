package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationFieldMappingEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IntegrationFieldMappingMapper extends BaseMapper<IntegrationFieldMappingEntity> {
}
