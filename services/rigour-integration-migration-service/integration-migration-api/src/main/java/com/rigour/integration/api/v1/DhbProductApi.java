package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝商品域 V1 HTTP 契约；调用方不接触订货宝原始 f/v 报文。 */
public interface DhbProductApi {

    String BASE_PATH = "/api/v1/integration/dhb/products";
    String QUERY_PATH = BASE_PATH + "/{connectorId}/query";

    @PostMapping(QUERY_PATH)
    ProductPageView queryProducts(@PathVariable("connectorId") UUID connectorId,
                                  @RequestBody(required = false) ProductQueryCommand command);
}
