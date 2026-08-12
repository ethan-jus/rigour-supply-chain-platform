package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductBrandListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductCategoryListView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMasterDataQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductSpecificationPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductTagPageView;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝商品域 V1 HTTP 契约；调用方不接触订货宝原始 f/v 报文。 */
public interface DhbProductApi {

    String BASE_PATH = "/api/v1/integration/dhb/products";
    String QUERY_PATH = BASE_PATH + "/{connectorId}/query";
    String MEDIA_SYNC_PATH = BASE_PATH + "/{connectorId}/media-sync";
    String MEDIA_SYNC_STATUS_PATH = MEDIA_SYNC_PATH + "/{jobId}";
    String CATEGORY_QUERY_PATH = BASE_PATH + "/{connectorId}/categories/query";
    String BRAND_QUERY_PATH = BASE_PATH + "/{connectorId}/brands/query";
    String SPECIFICATION_QUERY_PATH = BASE_PATH + "/{connectorId}/specifications/query";
    String TAG_QUERY_PATH = BASE_PATH + "/{connectorId}/tags/query";

    @PostMapping(QUERY_PATH)
    ProductPageView queryProducts(@PathVariable("connectorId") UUID connectorId,
                                  @RequestBody(required = false) ProductQueryCommand command);

    @PostMapping(MEDIA_SYNC_PATH)
    ProductMediaSyncView startProductMediaSync(@PathVariable("connectorId") UUID connectorId,
                                               @RequestBody(required = false) ProductQueryCommand command);

    @GetMapping(MEDIA_SYNC_STATUS_PATH)
    ProductMediaSyncView productMediaSyncStatus(@PathVariable("connectorId") UUID connectorId,
                                                @PathVariable("jobId") UUID jobId);

    @PostMapping(CATEGORY_QUERY_PATH)
    ProductCategoryListView queryCategories(@PathVariable("connectorId") UUID connectorId,
                                            @RequestBody(required = false)
                                            ProductMasterDataQueryCommand command);

    @PostMapping(BRAND_QUERY_PATH)
    ProductBrandListView queryBrands(@PathVariable("connectorId") UUID connectorId,
                                     @RequestBody(required = false)
                                     ProductMasterDataQueryCommand command);

    @PostMapping(SPECIFICATION_QUERY_PATH)
    ProductSpecificationPageView querySpecifications(@PathVariable("connectorId") UUID connectorId,
                                                      @RequestBody(required = false)
                                                      ProductMasterDataQueryCommand command);

    @PostMapping(TAG_QUERY_PATH)
    ProductTagPageView queryTags(@PathVariable("connectorId") UUID connectorId,
                                 @RequestBody(required = false)
                                 ProductMasterDataQueryCommand command);
}
