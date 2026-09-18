package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesOrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InternalSalesOrderMapper extends BaseMapper<InternalSalesOrderEntity> {
    /** 历史关联订单的收款只能通过接续账本变更。 */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM order_history_member WHERE tenant_id=#{tenant} AND order_id=#{id}")
    long historyMemberCount(@org.apache.ibatis.annotations.Param("tenant") String tenant,@org.apache.ibatis.annotations.Param("id") Long id);
}
