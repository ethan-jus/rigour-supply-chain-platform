package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.CustomerTypePriceImportCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceSyncCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 客户类型等级价维护接口；按商品批量查询，按商品规格整体保存。 */
public interface ErpCustomerTypePriceApi {
    String BASE_PATH = "/api/v1/erp/customer-type-prices";

    @GetMapping(BASE_PATH)
    ApiResponse<List<CustomerTypePriceView>> prices(@RequestParam(required = false) List<Long> productIds);

    @PutMapping(BASE_PATH + "/variants/{productVariantId}")
    ApiResponse<List<CustomerTypePriceView>> syncVariantPrices(
            @PathVariable("productVariantId") Long productVariantId,
            @RequestBody CustomerTypePriceSyncCommand command);

    @PostMapping(BASE_PATH + "/import")
    ApiResponse<CustomerTypePriceImportResult> importPrices(
            @RequestBody CustomerTypePriceImportCommand command);
}
