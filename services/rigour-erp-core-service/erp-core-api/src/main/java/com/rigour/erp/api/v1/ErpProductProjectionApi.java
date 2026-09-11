package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ExternalProductResolveCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductSyncCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** ERP 商品外部来源投影契约；仅供 Integration 服务间调用。 */
public interface ErpProductProjectionApi {
    String BASE_PATH = "/internal/v1/erp/products";

    @PostMapping(BASE_PATH + "/external-sync")
    ApiResponse<ExternalProductSyncResult> syncExternalProducts(
            @RequestBody ExternalProductSyncCommand command);

    @PostMapping(BASE_PATH + "/source-resolve")
    ApiResponse<List<ExternalProductResolvedView>> resolveExternalProducts(
            @RequestBody ExternalProductResolveCommand command);
}
