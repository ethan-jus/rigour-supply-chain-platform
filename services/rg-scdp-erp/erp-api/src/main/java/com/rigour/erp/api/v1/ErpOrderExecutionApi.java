package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.SalesExecutionReceipt;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 执行只接受 Order 执行标识；回执与仓库状态接口仅供专用只读服务身份。 */
public interface ErpOrderExecutionApi {
    @PostMapping("/api/v1/erp/order-executions/{id}")
    ApiResponse<SalesExecutionReceipt> execute(@PathVariable String id);

    @GetMapping("/internal/v1/erp/order-executions/{id}/receipt")
    ApiResponse<SalesExecutionReceipt> receipt(@PathVariable String id);

    @GetMapping("/internal/v1/erp/warehouses/{id}/usable")
    ApiResponse<Boolean> warehouseUsable(@PathVariable long id);
}
