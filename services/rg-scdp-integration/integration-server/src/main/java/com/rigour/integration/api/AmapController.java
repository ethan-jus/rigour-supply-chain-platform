package com.rigour.integration.api;

import com.rigour.integration.application.port.out.AmapPoiClient;
import com.rigour.integration.application.port.out.AmapPoiException;
import com.rigour.integration.application.port.out.ReverseGeocoder;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;

/** Signed internal provider API. Customer permissions and admission stay in CRM. */
@RestController
@RequestMapping("/internal/v1/integration/amap")
public class AmapController {
    private final AmapPoiClient pois;
    private final ReverseGeocoder geocoder;
    public AmapController(AmapPoiClient pois, ReverseGeocoder geocoder) {
        this.pois = pois; this.geocoder = geocoder;
    }
    @GetMapping("/pois")
    public ApiResponse<AmapPoiClient.NearbyPoiPage> search(@RequestParam String keyword,
            @RequestParam String city, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        authorize(); page(page,size);
        if(keyword.isBlank() || keyword.length()>200 || !city.matches("[0-9]{6}"))
            throw new RequestValidationException("门店关键词或城市编码无效");
        return call(()->pois.searchText(keyword,city,page,size));
    }
    @GetMapping("/pois/around")
    public ApiResponse<AmapPoiClient.NearbyPoiPage> around(@RequestParam(defaultValue="") String keyword,
            @RequestParam BigDecimal longitude,@RequestParam BigDecimal latitude,
            @RequestParam int radius,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        authorize(); coordinates(longitude,latitude); page(page,size);
        if(keyword.length()>200 || radius<1 || radius>50000) throw new RequestValidationException("门店搜索范围无效");
        return call(()->pois.searchAround(keyword,longitude,latitude,radius,page,size));
    }
    @GetMapping("/pois/{id}")
    public ApiResponse<AmapPoiClient.NearbyPoi> detail(@PathVariable String id) {
        authorize();
        if(!id.matches("[A-Za-z0-9]{1,64}")) throw new RequestValidationException("POI 编码无效");
        return call(()->pois.detail(id));
    }
    @GetMapping("/reverse-geocode")
    public ApiResponse<ReverseGeocoder.GeocodeResult> reverse(@RequestParam BigDecimal longitude,@RequestParam BigDecimal latitude) {
        authorize(); coordinates(longitude,latitude);
        return ApiResponse.success(geocoder.resolve(longitude,latitude));
    }
    private static void authorize() {
        var caller=AuthorizationContext.requireCurrent();
        if(!"SERVICE".equals(caller.principalScope()) || caller.tenantId()==null)
            throw new AuthorizationDeniedException("integration:amap:read");
        AuthorizationContext.requirePermission("integration:amap:read");
    }
    private static void coordinates(BigDecimal longitude,BigDecimal latitude) {
        if(longitude.abs().compareTo(BigDecimal.valueOf(180))>0 || latitude.abs().compareTo(BigDecimal.valueOf(90))>0)
            throw new RequestValidationException("经纬度无效");
    }
    private static void page(int page,int size) {
        if(page<1 || page>200 || size<1 || size>25) throw new RequestValidationException("分页参数无效");
    }
    private static <T> ApiResponse<T> call(Supplier<T> action) {
        try { return ApiResponse.success(action.get()); }
        catch(AmapPoiException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,e.getMessage(),
                List.of(new ApiErrorDetail("amap",e.reason(),e.getMessage()))); }
    }
}
