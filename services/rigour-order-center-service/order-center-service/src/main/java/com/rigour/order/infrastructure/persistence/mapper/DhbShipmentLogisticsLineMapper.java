package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.DhbShipmentLogisticsLineEntity;
import org.apache.ibatis.annotations.Mapper;

/** getWaitShips物流明细 MyBatis-Plus Mapper。 */
@Mapper
public interface DhbShipmentLogisticsLineMapper extends BaseMapper<DhbShipmentLogisticsLineEntity> {
}
