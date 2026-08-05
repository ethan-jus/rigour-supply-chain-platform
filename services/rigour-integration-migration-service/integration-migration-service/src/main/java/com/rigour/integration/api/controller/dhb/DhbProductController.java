package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbProductApi;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.UUID;
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
}
