package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbProductApi;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductBrandListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductCategoryListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMasterDataQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSpecificationPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductTagPageView;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝商品域 HTTP 边界；商品主数据由调用方领域服务落库。 */
@RestController
@RequestMapping(DhbProductApi.BASE_PATH)
public final class DhbProductController implements DhbProductApi {

    private final DhbIntegrationService service;

    public DhbProductController(DhbIntegrationService service) {
        this.service = service;
    }

    @Override
    @PostMapping("/{connectorId}/query")
    public ProductPageView queryProducts(@PathVariable("connectorId") UUID connectorId,
                                         @RequestBody(required = false) ProductQueryCommand command) {
        return service.products(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/media-sync")
    public ProductMediaSyncView startProductMediaSync(
            @PathVariable("connectorId") UUID connectorId,
            @RequestBody(required = false) ProductQueryCommand command) {
        return service.startProductMediaSync(connectorId, command);
    }

    @Override
    @GetMapping("/{connectorId}/media-sync/{jobId}")
    public ProductMediaSyncView productMediaSyncStatus(
            @PathVariable("connectorId") UUID connectorId,
            @PathVariable("jobId") UUID jobId) {
        return service.productMediaSyncStatus(connectorId, jobId);
    }

    @Override
    @PostMapping("/{connectorId}/categories/query")
    public ProductCategoryListView queryCategories(@PathVariable("connectorId") UUID connectorId,
                                                   @RequestBody(required = false)
                                                   ProductMasterDataQueryCommand command) {
        return service.productCategories(connectorId);
    }

    @Override
    @PostMapping("/{connectorId}/brands/query")
    public ProductBrandListView queryBrands(@PathVariable("connectorId") UUID connectorId,
                                            @RequestBody(required = false)
                                            ProductMasterDataQueryCommand command) {
        return service.productBrands(connectorId);
    }

    @Override
    @PostMapping("/{connectorId}/specifications/query")
    public ProductSpecificationPageView querySpecifications(
            @PathVariable("connectorId") UUID connectorId,
            @RequestBody(required = false) ProductMasterDataQueryCommand command) {
        return service.productSpecifications(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/tags/query")
    public ProductTagPageView queryTags(@PathVariable("connectorId") UUID connectorId,
                                        @RequestBody(required = false)
                                        ProductMasterDataQueryCommand command) {
        return service.productTags(connectorId, command);
    }
}
