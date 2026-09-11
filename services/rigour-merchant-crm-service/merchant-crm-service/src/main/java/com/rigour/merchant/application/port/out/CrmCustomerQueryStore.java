package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import java.util.List;
import java.util.UUID;

/** Portal 查询 CRM 本地投影的持久化端口。 */
public interface CrmCustomerQueryStore {
    PageView<CustomerSummaryView> customers(UUID tenantId, int begin, int step,
                                            String query, String status);
    CustomerDetailView customer(UUID tenantId, UUID id);
    PageView<ShippingAddressSummaryView> shippingAddresses(
            UUID tenantId, int begin, int step, String query);
    PageView<DictionaryView> customerTypes(UUID tenantId, int begin, int step, String query);
    PageView<DictionaryView> customerAreas(UUID tenantId, int begin, int step, String query);
    boolean existsByCustomerAreaCode(UUID tenantId, String areaCode);
    DictionaryView createCustomerArea(UUID tenantId, String areaCode, CrmCustomerAreaCommand command, UUID actorId);
    DictionaryView updateCustomerArea(UUID tenantId, UUID id, CrmCustomerAreaCommand command, UUID actorId);
    void deleteCustomerArea(UUID tenantId, UUID id, int revision, UUID actorId);
    ExternalCrmAreaSyncResult syncExternalCustomerAreas(UUID tenantId, String sourceSystem,
                                                        List<ExternalCrmAreaRowCommand> rows,
                                                        String actorId,
                                                        BusinessCodeGenerator codeGenerator);
}
