package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.CustomerAreaListView;
import com.rigour.integration.api.v1.model.CustomerPageView;
import com.rigour.integration.api.v1.model.CustomerQueryCommand;
import com.rigour.integration.api.v1.model.CustomerTypeListView;
import com.rigour.integration.api.v1.model.ShippingAddressPageView;
import com.rigour.integration.api.v1.model.ShippingAddressQueryCommand;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝客户域 V1 HTTP 契约；CRM 不接触订货宝 f/v、Token 或 Secret。 */
public interface DhbCustomerApi {

    String BASE_PATH = "/api/v1/integration/dhb/customers";

    @PostMapping(BASE_PATH + "/{connectorId}/types/query")
    CustomerTypeListView queryCustomerTypes(@PathVariable("connectorId") UUID connectorId);

    @PostMapping(BASE_PATH + "/{connectorId}/areas/query")
    CustomerAreaListView queryCustomerAreas(@PathVariable("connectorId") UUID connectorId);

    @PostMapping(BASE_PATH + "/{connectorId}/query")
    CustomerPageView queryCustomers(@PathVariable("connectorId") UUID connectorId,
                                    @RequestBody(required = false) CustomerQueryCommand command);

    @PostMapping(BASE_PATH + "/{connectorId}/shipping-addresses/query")
    ShippingAddressPageView queryShippingAddresses(
            @PathVariable("connectorId") UUID connectorId,
            @RequestBody(required = false) ShippingAddressQueryCommand command);
}
