package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 商品品牌维护接口；品牌是我方商品业务基础资料，订货宝品牌后续只做映射。 */
public interface ErpProductBrandApi {
    String BASE_PATH = "/api/v1/erp/product-brands";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalProductBrandView>> brands(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String brandCode,
            @RequestParam(required = false) String brandName);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductBrandView> brand(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalProductBrandView> createBrand(@RequestBody InternalProductBrandCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductBrandView> updateBrand(
            @PathVariable("id") Long id,
            @RequestBody InternalProductBrandCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteBrand(@PathVariable("id") Long id, @RequestParam int revision);
}
