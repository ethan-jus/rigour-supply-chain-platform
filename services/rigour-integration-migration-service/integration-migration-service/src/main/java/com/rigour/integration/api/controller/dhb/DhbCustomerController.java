package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbCustomerApi;
import com.rigour.integration.api.v1.model.CustomerAreaListView;
import com.rigour.integration.api.v1.model.CustomerPageView;
import com.rigour.integration.api.v1.model.CustomerQueryCommand;
import com.rigour.integration.api.v1.model.CustomerTypeListView;
import com.rigour.integration.api.v1.model.ShippingAddressPageView;
import com.rigour.integration.api.v1.model.ShippingAddressQueryCommand;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝客户域 HTTP 边界；完整来源字段同时写入 Integration Raw Landing。 */
@RestController
@RequestMapping(DhbCustomerApi.BASE_PATH)
public final class DhbCustomerController implements DhbCustomerApi {
    private final DhbIntegrationService service;

    public DhbCustomerController(DhbIntegrationService service) {
        this.service = service;
    }

    @Override
    @PostMapping("/{connectorId}/types/query")
    public CustomerTypeListView queryCustomerTypes(@PathVariable("connectorId") UUID connectorId) {
        return service.customerTypes(connectorId);
    }

    @Override
    @PostMapping("/{connectorId}/areas/query")
    public CustomerAreaListView queryCustomerAreas(@PathVariable("connectorId") UUID connectorId) {
        return service.customerAreas(connectorId);
    }

    @Override
    @PostMapping("/{connectorId}/query")
    public CustomerPageView queryCustomers(@PathVariable("connectorId") UUID connectorId,
                                           @RequestBody(required = false)
                                           CustomerQueryCommand command) {
        return service.crmCustomers(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/shipping-addresses/query")
    public ShippingAddressPageView queryShippingAddresses(
            @PathVariable("connectorId") UUID connectorId,
            @RequestBody(required = false) ShippingAddressQueryCommand command) {
        return service.shippingAddresses(connectorId, command);
    }
}
