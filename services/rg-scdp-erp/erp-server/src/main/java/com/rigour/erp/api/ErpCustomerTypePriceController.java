package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpCustomerTypePriceApi;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceSyncCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import com.rigour.erp.application.service.product.ErpCustomerTypePriceService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** ERP 客户类型等级价 HTTP 边界；只承载本地等级价维护。 */
@RestController
public final class ErpCustomerTypePriceController implements ErpCustomerTypePriceApi {
    private final ErpCustomerTypePriceService customerTypePriceService;

    public ErpCustomerTypePriceController(ErpCustomerTypePriceService customerTypePriceService) {
        this.customerTypePriceService = customerTypePriceService;
    }

    @Override
    public ApiResponse<List<CustomerTypePriceView>> prices(List<Long> productIds) {
        return ApiResponse.success(customerTypePriceService.prices(productIds));
    }

    @Override
    public ApiResponse<List<CustomerTypePriceView>> syncVariantPrices(
            Long productVariantId, CustomerTypePriceSyncCommand command) {
        return ApiResponse.success(customerTypePriceService.syncVariant(productVariantId, command));
    }

    @Override
    public ApiResponse<CustomerTypePriceImportResult> importPrices(CustomerTypePriceImportCommand command) {
        return ApiResponse.success(customerTypePriceService.importPrices(command));
    }
}
