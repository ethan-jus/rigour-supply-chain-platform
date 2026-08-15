package com.rigour.order.api.controller;

import com.rigour.order.api.v1.DhbOrderApi;
import com.rigour.order.api.v1.model.DhbOrderDetailView;
import com.rigour.order.api.v1.model.DhbOrderPageView;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncResult;
import com.rigour.order.application.service.dhb.DhbOrderManualSyncService;
import com.rigour.order.application.service.dhb.DhbOrderService;
import com.rigour.shared.context.TenantContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 订货宝订单中心查询和本地导入边界；第三方协议仍由Integration负责。 */
@RestController
@RequestMapping(DhbOrderApi.BASE_PATH)
public class DhbOrderController {
    private final DhbOrderService service;
    private final DhbOrderManualSyncService manualSyncService;

    public DhbOrderController(DhbOrderService service, DhbOrderManualSyncService manualSyncService) {
        this.service = service;
        this.manualSyncService = manualSyncService;
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
            @RequestParam(required = false) Integer splitType,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.list(tenantId(), query(begin, step, orderStatusVal, starttime, endtime,
                updateGe, updateLe, exceptionStatus, apiStatus, payStatus, splitType, keyword)));
    }

    @GetMapping("/{orderSn}")
    public ApiResponse<DhbOrderDetailView> detail(@PathVariable String orderSn) {
        return ApiResponse.success(service.detail(tenantId(), orderSn));
    }

    /** 前端立即同步入口；按当前登录租户自动选择唯一启用的订货宝连接器。 */
    @PostMapping("/sync")
    public ApiResponse<DhbOrderSyncResult> sync(@RequestBody(required = false) DhbOrderSyncCommand command) {
        return ApiResponse.success(manualSyncService.run(command));
    }

    /** 兼容旧入口；connectorId必须先经Integration sync-targets校验。 */
    @PostMapping("/sync/{connectorId}")
    public ApiResponse<DhbOrderSyncResult> sync(
            @PathVariable UUID connectorId,
            @RequestBody(required = false) DhbOrderSyncCommand command) {
        return ApiResponse.success(manualSyncService.run(connectorId, command));
    }

    private static DhbOrderService.OrderQuery query(int begin, int step, String orderStatusVal, String starttime,
                                                    String endtime, String updateGe, String updateLe,
                                                    String exceptionStatus, String apiStatus, String payStatus,
                                                    Integer splitType, String keyword) {
        return new DhbOrderService.OrderQuery(begin, step, orderStatusVal, starttime, endtime, updateGe, updateLe,
                exceptionStatus, apiStatus, payStatus, splitType, keyword);
    }

    private static String tenantId() {
        String value = TenantContext.getTenantId();
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
        return value;
    }
}
