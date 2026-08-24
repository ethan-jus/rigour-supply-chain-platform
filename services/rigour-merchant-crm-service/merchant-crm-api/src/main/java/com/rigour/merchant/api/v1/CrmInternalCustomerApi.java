package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CRM 自研客户管理接口。
 *
 * <p>本接口只读写 CRM 自有 `crm_customer` 表，不访问订货宝，也不复用旧 Party 聚合模型。</p>
 */
public interface CrmInternalCustomerApi {
    String BASE_PATH = "/api/v1/crm/internal-customers";

    @GetMapping(BASE_PATH)
    ApiResponse<PageView<InternalCustomerSummaryView>> customers(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String contactPhone,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerSalesUserId,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String statusCode);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalCustomerDetailView> customer(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalCustomerDetailView> create(@RequestBody InternalCustomerCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalCustomerDetailView> update(
            @PathVariable("id") Long id,
            @RequestBody InternalCustomerCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> delete(
            @PathVariable("id") Long id,
            @RequestParam int revision);
}
