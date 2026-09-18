package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.InternalFundDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/** 资金收付款单 MyBatis-Plus Mapper。 */
@Mapper
public interface InternalFundDocumentMapper extends BaseMapper<InternalFundDocumentEntity> {
}
