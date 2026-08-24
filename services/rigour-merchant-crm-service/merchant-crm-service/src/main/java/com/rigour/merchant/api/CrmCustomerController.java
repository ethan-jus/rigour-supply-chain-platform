package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CrmCustomerApi;
import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.merchant.application.service.CrmCustomerQueryService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/** CRM 本地客户和客户字典查询边界。 */
@RestController
public final class CrmCustomerController implements CrmCustomerApi {
    private final CrmCustomerQueryService service;

    public CrmCustomerController(CrmCustomerQueryService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<PageView<CustomerSummaryView>> customers(
            int begin, int step, String query, String status) {
        return ApiResponse.success(service.customers(begin, step, query, status));
    }

    @Override
    public ApiResponse<CustomerDetailView> customer(UUID id) {
        return ApiResponse.success(service.customer(id));
    }

    @Override
    public ApiResponse<PageView<ShippingAddressSummaryView>> shippingAddresses(
            int begin, int step, String query) {
        return ApiResponse.success(service.shippingAddresses(begin, step, query));
    }

    @Override
    public ApiResponse<PageView<DictionaryView>> customerTypes(
            int begin, int step, String query) {
        return ApiResponse.success(service.customerTypes(begin, step, query));
    }

    @Override
    public ApiResponse<PageView<DictionaryView>> customerAreas(
            int begin, int step, String query) {
        return ApiResponse.success(service.customerAreas(begin, step, query));
    }
}
