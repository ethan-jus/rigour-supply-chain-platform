package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesPaymentRecordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InternalSalesPaymentRecordMapper extends BaseMapper<InternalSalesPaymentRecordEntity> {
}
