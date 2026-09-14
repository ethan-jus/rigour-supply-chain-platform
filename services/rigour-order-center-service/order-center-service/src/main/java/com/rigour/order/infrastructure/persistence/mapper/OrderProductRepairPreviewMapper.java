package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.OrderProductRepairPreviewEntity;
import org.apache.ibatis.annotations.Mapper;

/** 修复预览及幂等应用记录。 */
@Mapper
public interface OrderProductRepairPreviewMapper extends BaseMapper<OrderProductRepairPreviewEntity> { }
