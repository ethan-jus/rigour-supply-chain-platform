package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.DhbOrderSyncCheckpointEntity;
import org.apache.ibatis.annotations.Mapper;

/** 订货宝订单域同步游标MyBatis-Plus Mapper。 */
@Mapper
public interface DhbOrderSyncCheckpointMapper extends BaseMapper<DhbOrderSyncCheckpointEntity> {
}
