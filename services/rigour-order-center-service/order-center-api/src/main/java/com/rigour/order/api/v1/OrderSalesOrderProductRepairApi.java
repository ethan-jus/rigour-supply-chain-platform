package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** Order 历史商品修复入口；禁止通过通用订单编辑重算成交金额。 */
public interface OrderSalesOrderProductRepairApi {
    String BASE_PATH = "/api/v1/orders/sales/{id}/product-repairs";

    @GetMapping("/api/v1/orders/sales/product-repair-evidence")
    ApiResponse<EvidencePage> evidence(@RequestParam(defaultValue = "0") long afterLineId,
                                      @RequestParam(defaultValue = "20000") int limit);

    @GetMapping(BASE_PATH)
    ApiResponse<Context> context(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH + "/previews")
    ApiResponse<Preview> preview(@PathVariable("id") Long id, @RequestBody PreviewCommand command);

    @PostMapping(BASE_PATH + "/{previewId}/apply")
    ApiResponse<Applied> apply(@PathVariable("id") Long id, @PathVariable("previewId") String previewId,
                               @RequestBody ApplyCommand command);
}
