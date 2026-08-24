package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 商品规格维护接口；规格是我方商品中心基础资料，订货宝规格后续只做映射。 */
public interface ErpProductSpecificationApi {
    String BASE_PATH = "/api/v1/erp/product-specifications";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalProductSpecificationView>> specifications(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String specificationCode,
            @RequestParam(required = false) String specificationName,
            @RequestParam(required = false) String statusCode);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductSpecificationView> specification(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalProductSpecificationView> createSpecification(
            @RequestBody InternalProductSpecificationCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductSpecificationView> updateSpecification(
            @PathVariable("id") Long id,
            @RequestBody InternalProductSpecificationCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteSpecification(@PathVariable("id") Long id, @RequestParam int revision);
}
