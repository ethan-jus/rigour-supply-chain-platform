package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpProductCategoryApi;
import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.service.product.ErpProductCategoryService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** ERP 商品分类 HTTP 边界；只承载分类维护。 */
@RestController
public final class ErpProductCategoryController implements ErpProductCategoryApi {
    private final ErpProductCategoryService categoryService;

    public ErpProductCategoryController(ErpProductCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Override
    public ApiResponse<MasterDataPageView<InternalProductCategoryView>> categories(
            int begin, int step, String categoryCode, String categoryName, Long parentId) {
        return ApiResponse.success(categoryService.categories(begin, step, categoryCode, categoryName, parentId));
    }

    @Override
    public ApiResponse<InternalProductCategoryView> category(Long id) {
        return ApiResponse.success(categoryService.category(id));
    }

    @Override
    public ApiResponse<InternalProductCategoryView> createCategory(InternalProductCategoryCommand command) {
        return ApiResponse.success(categoryService.create(command));
    }

    @Override
    public ApiResponse<InternalProductCategoryView> updateCategory(Long id, InternalProductCategoryCommand command) {
        return ApiResponse.success(categoryService.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteCategory(Long id, int revision) {
        categoryService.delete(id, revision);
        return ApiResponse.success(null);
    }
}
