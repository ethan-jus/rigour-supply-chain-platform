package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationOutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IntegrationOutboxEventMapper extends BaseMapper<IntegrationOutboxEventEntity> {
}
