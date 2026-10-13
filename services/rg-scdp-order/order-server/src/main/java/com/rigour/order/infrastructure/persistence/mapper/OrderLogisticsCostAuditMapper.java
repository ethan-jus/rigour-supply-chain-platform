package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderLogisticsCostAuditEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderLogisticsCostAuditMapper extends BaseMapper<OrderLogisticsCostAuditEntity> {}
