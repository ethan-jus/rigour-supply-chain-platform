package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand;
import com.rigour.merchant.api.v1.model.CustomerShippingAddressView;
import java.util.List;
import java.util.UUID;
public interface CustomerShippingAddressStore {
    List<CustomerShippingAddressView> addresses(String tenant, long customer);
    CustomerShippingAddressView save(String tenant, long customer, UUID id, CustomerShippingAddressCommand command, String actor);
    void createInitial(String tenant, long customer, CustomerShippingAddressCommand command, String actor);
    void delete(String tenant, long customer, UUID id, long revision, String actor);
}
