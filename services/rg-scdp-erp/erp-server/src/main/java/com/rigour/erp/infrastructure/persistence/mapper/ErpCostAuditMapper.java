package com.rigour.erp.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.erp.infrastructure.persistence.entity.ErpCostAuditEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ErpCostAuditMapper extends BaseMapper<ErpCostAuditEntity> {}
