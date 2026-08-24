package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** CRM 本地客户查询契约；查询链路不实时访问订货宝。 */
public interface CrmCustomerApi {
    String BASE_PATH = "/api/v1/crm";

    @GetMapping(BASE_PATH + "/customers")
    ApiResponse<PageView<CustomerSummaryView>> customers(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String status);

    @GetMapping(BASE_PATH + "/customers/{id}")
    ApiResponse<CustomerDetailView> customer(@PathVariable("id") UUID id);

    @GetMapping(BASE_PATH + "/shipping-addresses")
    ApiResponse<PageView<ShippingAddressSummaryView>> shippingAddresses(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(name = "q", required = false) String query);

    @GetMapping(BASE_PATH + "/customer-types")
    ApiResponse<PageView<DictionaryView>> customerTypes(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "100") int step,
            @RequestParam(name = "q", required = false) String query);

    @GetMapping(BASE_PATH + "/customer-areas")
    ApiResponse<PageView<DictionaryView>> customerAreas(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "100") int step,
            @RequestParam(name = "q", required = false) String query);
}
