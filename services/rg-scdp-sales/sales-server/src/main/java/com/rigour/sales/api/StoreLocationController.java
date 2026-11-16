package com.rigour.sales.api;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.*;
import com.rigour.shared.core.exception.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class StoreLocationController {
    private final AmapPoiClient maps;
    public StoreLocationController(AmapPoiClient maps){this.maps=maps;}
    public record Location(BigDecimal longitude,BigDecimal latitude,String coordinateSystem) {}
    @PostMapping("/api/v1/sales/work/location/normalize")
    public ApiResponse<AmapPoiClient.Coordinates> normalize(@RequestBody Location c){
        var caller=AuthorizationContext.current().orElseThrow(()->new RequestValidationException("缺少登录上下文"));
        if(!caller.permissions().contains("sales:location:write")) AuthorizationContext.requirePermission("sales:visit:own:write");
        if(c==null||c.longitude()==null||c.latitude()==null||c.longitude().abs().doubleValue()>180||c.latitude().abs().doubleValue()>90)
            throw new RequestValidationException("设备坐标无效");
        if("GCJ02".equals(c.coordinateSystem()))return ApiResponse.success(new AmapPoiClient.Coordinates(c.longitude(),c.latitude(),"GCJ02"));
        if(!"WGS84".equals(c.coordinateSystem()))throw new RequestValidationException("设备坐标系无效");
        try{return ApiResponse.success(maps.convertGps(c.longitude(),c.latitude()));}
        catch(AmapPoiException e){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,e.getMessage(),List.of());}
    }
}
