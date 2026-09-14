package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.CityProductSupplyView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 商品订货前只读核查，仓库由业务明确选择，与城市销售筛选相互独立。 */
public interface AnalyticsCityProductSupplyApi {
    String PATH = "/api/v1/analytics/supply/dashboard/city-product-report/supply";
    @GetMapping(PATH)
    ApiResponse<CityProductSupplyView> supply(
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam Long productId, @RequestParam Long warehouseId,
            @RequestParam(required = false) Long skuId);
}
