package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.CityProductSupplyView.Stock;
import com.rigour.analytics.api.v1.model.CityProductSupplyView.Operation;
import com.rigour.analytics.application.port.out.CityProductSupplyStore;
import com.rigour.analytics.infrastructure.persistence.mapper.CityProductSupplyMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 供货事实只读适配；UTC参数与现有BI存储保持一致。 */
@Repository
public class MybatisCityProductSupplyRepository implements CityProductSupplyStore {
    private final CityProductSupplyMapper mapper;
    public MybatisCityProductSupplyRepository(CityProductSupplyMapper mapper) { this.mapper = mapper; }
    @Override public List<Stock> stocks(String tenant, Long product, Long warehouse, Long sku, int limit) {
        return mapper.stocks(tenant, product, warehouse, sku, limit);
    }
    @Override public List<Operation> operations(String tenant, Instant from, Instant to, Long product, Long sku, int limit) {
        return mapper.operations(tenant, LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                LocalDateTime.ofInstant(to, ZoneOffset.UTC), product, sku, limit);
    }
    @Override public Checkpoint checkpoint(String tenant, String source) { return mapper.checkpoint(tenant, source); }
}
