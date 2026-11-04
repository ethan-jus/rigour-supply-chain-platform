package com.rigour.sales.api;

import com.rigour.merchant.api.v1.model.StoreModels.Poi;
import com.rigour.sales.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.*;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;

/** Provider lookup for CRM maintenance, accessible only through a signed service context. */
@RestController
@RequestMapping("/internal/v1/sales/store-pois")
public class StorePoiController {
    private final AmapPoiClient client;
    public StorePoiController(AmapPoiClient client){this.client=client;}
    @GetMapping
    public ApiResponse<List<Poi>> search(@RequestParam String keyword,@RequestParam String areaCode) {
        authorize();
        if(keyword==null||keyword.isBlank()||keyword.length()>200||!areaCode.matches("[0-9]{6}"))
            throw new RequestValidationException("门店名称或地区编码无效");
        return call(()->client.searchText(keyword,areaCode,1,20).items().stream().map(StorePoiController::poi).toList());
    }
    @GetMapping("/{id}")
    public ApiResponse<Poi> detail(@PathVariable String id) {
        authorize();
        if(!id.matches("[A-Za-z0-9]{1,64}"))throw new RequestValidationException("POI 编码无效");
        return call(()->poi(client.detail(id)));
    }
    private static void authorize() {
        var caller=AuthorizationContext.requireCurrent();
        if(!"SERVICE".equals(caller.principalScope())||caller.tenantId()==null)
            throw new AuthorizationDeniedException("sales:store-poi:read");
        AuthorizationContext.requirePermission("sales:store-poi:read");
    }
    private static <T>ApiResponse<T> call(Supplier<T> operation) {
        try{return ApiResponse.success(operation.get());}
        catch(AmapPoiException e){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,e.getMessage(),
                List.of(new ApiErrorDetail("amap",e.reason(),e.getMessage())));}
    }
    private static Poi poi(AmapPoiClient.NearbyPoi p) {
        return new Poi(p.poiId(),p.name(),p.address(),p.publicPhone(),p.typeCode(),p.adcode(),p.longitude(),p.latitude());
    }
}
