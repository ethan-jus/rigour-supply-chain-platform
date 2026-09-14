package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.CityProductReportStore;
import com.rigour.analytics.api.v1.model.CityProductReportView.CustomerArchive;
import com.rigour.analytics.infrastructure.persistence.mapper.CityProductReportMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 城市商品报表 BI 本地持久化适配，时间与现有看板统一使用 UTC。 */
@Repository
public class MybatisCityProductReportRepository implements CityProductReportStore {
    private final CityProductReportMapper mapper;

    public MybatisCityProductReportRepository(CityProductReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Instant> latestOrderDate(String tenantId) {
        return Optional.ofNullable(mapper.latestOrderDate(tenantId)).map(value -> value.toInstant(ZoneOffset.UTC));
    }

    @Override
    public List<CustomerArchive> customerArchives(String tenantId, SupplyDashboardFilter filter) {
        return mapper.customerArchives(tenantId, filter.regionCode(), filter.ownerStaffCode(), filter.customerTypeCode());
    }

    @Override
    public List<Long> categoryIds(String tenantId, Long parentId) { return mapper.categoryIds(tenantId, parentId); }

    @Override
    public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, int orderLimit, int lineLimit) {
        return load(tenantId, filter, new ProductSelection(null, null, null), orderLimit, lineLimit);
    }

    @Override
    public List<FactRow> load(String tenantId, SupplyDashboardFilter filter, ProductSelection selection,
                              int orderLimit, int lineLimit) {
        return mapper.load(tenantId, LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(filter.to(), ZoneOffset.UTC), filter.regionCode(), filter.ownerStaffCode(),
                filter.customerTypeCode(), filter.productCategoryId(), filter.sourceSystemCode(),
                selection.brandId(), selection.productId(), selection.skuId(), orderLimit, lineLimit);
    }
}
