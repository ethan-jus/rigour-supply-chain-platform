package com.rigour.order.api.controller;

import com.rigour.order.api.v1.DhbOrderApi;
import com.rigour.order.api.v1.model.DhbOrderDetailView;
import com.rigour.order.api.v1.model.DhbOrderPageView;
import com.rigour.order.application.service.dhb.DhbOrderService;
import com.rigour.shared.context.TenantContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝一期本地投影查询边界；第三方同步由Integration负责。 */
@RestController
@RequestMapping(DhbOrderApi.BASE_PATH)
public class DhbOrderController {
    private final DhbOrderService service;

    public DhbOrderController(DhbOrderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DhbOrderPageView> list(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "100") int step,
            @RequestParam(name = "order_status_val", required = false) String orderStatusVal,
            @RequestParam(required = false) String starttime,
            @RequestParam(required = false) String endtime,
            @RequestParam(required = false) String updateGe,
            @RequestParam(required = false) String updateLe,
            @RequestParam(required = false) String exceptionStatus,
            @RequestParam(required = false) String apiStatus,
            @RequestParam(required = false) String payStatus,
            @RequestParam(required = false) Integer splitType) {
        return ApiResponse.success(service.list(tenantId(), query(begin, step, orderStatusVal, starttime, endtime,
                updateGe, updateLe, exceptionStatus, apiStatus, payStatus, splitType)));
    }

    @GetMapping("/{orderSn}")
    public ApiResponse<DhbOrderDetailView> detail(@PathVariable String orderSn) {
        return ApiResponse.success(service.detail(tenantId(), orderSn));
    }

    private static DhbOrderService.OrderQuery query(int begin, int step, String orderStatusVal, String starttime,
                                                    String endtime, String updateGe, String updateLe,
                                                    String exceptionStatus, String apiStatus, String payStatus,
                                                    Integer splitType) {
        return new DhbOrderService.OrderQuery(begin, step, orderStatusVal, starttime, endtime, updateGe, updateLe,
                exceptionStatus, apiStatus, payStatus, splitType);
    }

    private static String tenantId() {
        String value = TenantContext.getTenantId();
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
        return value;
    }
}
