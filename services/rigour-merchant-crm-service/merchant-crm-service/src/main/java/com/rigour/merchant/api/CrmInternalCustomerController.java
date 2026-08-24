package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CrmInternalCustomerApi;
import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.application.service.CrmInternalCustomerService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** CRM 自研客户管理 HTTP 边界；旧订货宝同步查询接口不从这里进入。 */
@RestController
public final class CrmInternalCustomerController implements CrmInternalCustomerApi {
    private final CrmInternalCustomerService service;

    public CrmInternalCustomerController(CrmInternalCustomerService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<PageView<InternalCustomerSummaryView>> customers(
            int begin, int step, String customerCode, String customerName,
            String contactPhone, String customerTypeCode, String regionCode, String ownerSalesUserId,
            String ownerStaffCode, String statusCode) {
        return ApiResponse.success(service.customers(begin, step, customerCode, customerName,
                contactPhone, customerTypeCode, regionCode, ownerSalesUserId, ownerStaffCode, statusCode));
    }

    @Override
    public ApiResponse<InternalCustomerDetailView> customer(Long id) {
        return ApiResponse.success(service.customer(id));
    }

    @Override
    public ApiResponse<InternalCustomerDetailView> create(InternalCustomerCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<InternalCustomerDetailView> update(Long id, InternalCustomerCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> delete(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}
