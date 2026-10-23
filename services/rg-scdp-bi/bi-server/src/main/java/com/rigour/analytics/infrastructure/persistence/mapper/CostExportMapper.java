package com.rigour.analytics.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.analytics.infrastructure.persistence.entity.CostExportEntity;

/** 仅访问BI本库，所有费用读写显式限定租户与批次。 */
@org.apache.ibatis.annotations.Mapper
public interface CostExportMapper extends BaseMapper<CostExportEntity> {}
