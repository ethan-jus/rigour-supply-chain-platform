package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductProjectionApi;
import com.rigour.erp.api.v1.model.ExternalProductResolveCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductSyncCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.erp.application.service.product.ErpProductManagementService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** ERP 外部商品投影 HTTP 边界。 */
@RestController
public final class ErpProductProjectionController implements ErpProductProjectionApi {
    private final ErpProductManagementService productService;

    public ErpProductProjectionController(ErpProductManagementService productService) {
        this.productService = productService;
    }

    @Override
    public ApiResponse<ExternalProductSyncResult> syncExternalProducts(ExternalProductSyncCommand command) {
        return ApiResponse.success(productService.syncExternalProducts(command));
    }

    @Override
    public ApiResponse<List<ExternalProductResolvedView>> resolveExternalProducts(
            ExternalProductResolveCommand command) {
        return ApiResponse.success(productService.resolveExternalProducts(command));
    }
}
