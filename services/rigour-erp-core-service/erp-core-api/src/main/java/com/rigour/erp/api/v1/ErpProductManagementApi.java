package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductManagementCommand;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 商品管理接口。
 *
 * <p>本接口服务我方自研商品业务表。订货宝商品后续只能通过 Integration 映射到这些表，
 * 不能直接驱动商品新增、编辑、上下架和删除流程。</p>
 */
public interface ErpProductManagementApi {
    String BASE_PATH = "/api/v1/erp/product-management/products";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<ProductManagementSummaryView>> products(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String unitCode,
            @RequestParam(required = false) String saleTypeCode,
            @RequestParam(required = false) String shelfStatusCode,
            @RequestParam(required = false) String submitStatusCode,
            @RequestParam(required = false) Long defaultWarehouseId);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<ProductManagementDetailView> product(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<ProductManagementDetailView> createProduct(@RequestBody ProductManagementCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<ProductManagementDetailView> updateProduct(
            @PathVariable("id") Long id,
            @RequestBody ProductManagementCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteProduct(@PathVariable("id") Long id, @RequestParam int revision);
}
