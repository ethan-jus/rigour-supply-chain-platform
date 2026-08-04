package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderOutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderOutboxEventMapper extends BaseMapper<OrderOutboxEventEntity> {}
