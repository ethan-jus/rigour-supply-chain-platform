package com.rigour.integration.application.port.out;

import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferOrderProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockInProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.shared.context.CallerIdentity;

/** Integration 向 ERP 投影外部来源出库单的端口；实现只能调用 ERP 公开 API。 */
public interface ErpStockOutProjectionClient {

    InternalStockOutOrderDetailView confirmExternalStockOut(
            CallerIdentity caller, ExternalStockOutProjectionCommand command);

    InternalTransferOrderDetailView confirmExternalTransferStockOut(
            CallerIdentity caller, ExternalTransferStockOutProjectionCommand command);

    InternalTransferOrderDetailView confirmExternalTransferStockIn(
            CallerIdentity caller, ExternalTransferStockInProjectionCommand command);

    InternalTransferOrderDetailView upsertExternalTransferOrder(
            CallerIdentity caller, ExternalTransferOrderProjectionCommand command);

    InternalStockOutOrderDetailView confirmExternalGenericStockOut(
            CallerIdentity caller, ExternalGenericStockOutProjectionCommand command);
}
