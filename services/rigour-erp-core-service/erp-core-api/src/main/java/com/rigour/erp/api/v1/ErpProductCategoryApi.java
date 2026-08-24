package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 商品分类维护接口；分类是我方商品业务基础资料，订货宝分类后续只做映射。 */
public interface ErpProductCategoryApi {
    String BASE_PATH = "/api/v1/erp/product-categories";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalProductCategoryView>> categories(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) Long parentId);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductCategoryView> category(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalProductCategoryView> createCategory(@RequestBody InternalProductCategoryCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalProductCategoryView> updateCategory(
            @PathVariable("id") Long id,
            @RequestBody InternalProductCategoryCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteCategory(@PathVariable("id") Long id, @RequestParam int revision);
}
