package com.rigour.integration.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.integration.infrastructure.persistence.entity.IntegrationReconciliationCaseEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IntegrationReconciliationCaseMapper extends BaseMapper<IntegrationReconciliationCaseEntity> {
}
