package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
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
}
