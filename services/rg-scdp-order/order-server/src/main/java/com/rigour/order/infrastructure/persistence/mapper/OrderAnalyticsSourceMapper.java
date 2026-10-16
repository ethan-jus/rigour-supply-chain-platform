package com.rigour.order.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.order.infrastructure.persistence.entity.InternalSalesPaymentRecordEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderAnalyticsSourceMapper extends BaseMapper<InternalSalesPaymentRecordEntity> {
    @SelectProvider(type = OrderAnalyticsSourceSql.class, method = "version")
    String version(@Param("tenant") String tenant, @Param("dataset") String dataset);

    @SelectProvider(type = OrderAnalyticsSourceSql.class, method = "page")
    List<Map<String, Object>> page(
            @Param("tenant") String tenant,
            @Param("dataset") String dataset,
            @Param("after") String after);
}
