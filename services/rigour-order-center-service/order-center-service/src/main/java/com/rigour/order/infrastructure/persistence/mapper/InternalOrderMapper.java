package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.InternalOrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InternalOrderMapper extends BaseMapper<InternalOrderEntity> {}
