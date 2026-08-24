package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 供应商档案接口。
 *
 * <p>本接口只服务我方采购业务使用的供应商档案。订货宝供应商后续只作为外部来源映射，
 * 不直接决定供应商启停、编辑和删除流程。</p>
 */
public interface ErpSupplierProfileApi {
    String BASE_PATH = "/api/v1/erp/supplier-profiles";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalSupplierProfileView>> suppliers(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String supplierCode,
            @RequestParam(required = false) String supplierName,
            @RequestParam(required = false) String contactPhone,
            @RequestParam(required = false) String statusCode);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalSupplierProfileView> supplier(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalSupplierProfileView> createSupplier(@RequestBody InternalSupplierProfileCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalSupplierProfileView> updateSupplier(
            @PathVariable("id") Long id,
            @RequestBody InternalSupplierProfileCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteSupplier(@PathVariable("id") Long id, @RequestParam int revision);
}
