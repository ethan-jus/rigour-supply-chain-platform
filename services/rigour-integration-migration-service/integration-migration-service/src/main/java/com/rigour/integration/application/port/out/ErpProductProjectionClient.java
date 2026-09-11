package com.rigour.integration.application.port.out;

import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Integration 向 ERP 投影外部商品主数据的出站端口。 */
public interface ErpProductProjectionClient {
    ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                   List<ExternalProductRowCommand> rows);

    default List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                      List<ExternalProductResolveRowCommand> rows) {
        return List.of();
    }
}
