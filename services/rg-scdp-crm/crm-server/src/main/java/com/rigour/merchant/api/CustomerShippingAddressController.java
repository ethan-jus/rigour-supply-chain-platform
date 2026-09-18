package com.rigour.merchant.api;
import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CustomerShippingAddressStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/crm/internal-customers/{customerId}/shipping-addresses")
public class CustomerShippingAddressController {
    private final CustomerShippingAddressStore store;
    public CustomerShippingAddressController(CustomerShippingAddressStore store) { this.store=store; }
    private CallerIdentity actor(String permission) {
        var caller=AuthorizationContext.requireCurrent();
        if (caller.tenantId()==null || !"TENANT".equals(caller.principalScope())) throw new AuthorizationDeniedException("tenant-caller");
        boolean active=com.rigour.tenant.iam.client.SupplyAuthorizationContext.current().map(com.rigour.tenant.iam.client.SupplyAuthorizationContext::active).orElse(false);
        AuthorizationContext.requirePermission("crm:customer:read".equals(permission) || active ? permission : "crm:customer:write");
        return caller;
    }
    @GetMapping public ApiResponse<List<CustomerShippingAddressView>> list(@PathVariable long customerId) {
        var c=actor("crm:customer:read"); return ApiResponse.success(store.addresses(c.tenantId().toString(),customerId));
    }
    @PostMapping public ApiResponse<CustomerShippingAddressView> create(@PathVariable long customerId,@RequestBody CustomerShippingAddressCommand command) {
        var c=actor("crm:customer:update"); return ApiResponse.success(store.save(c.tenantId().toString(),customerId,null,command,c.principalId().toString()));
    }
    @PutMapping("/{id}") public ApiResponse<CustomerShippingAddressView> update(@PathVariable long customerId,@PathVariable UUID id,@RequestBody CustomerShippingAddressCommand command) {
        var c=actor("crm:customer:update"); return ApiResponse.success(store.save(c.tenantId().toString(),customerId,id,command,c.principalId().toString()));
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long customerId,@PathVariable UUID id,@RequestParam long revision) {
        var c=actor("crm:customer:update"); store.delete(c.tenantId().toString(),customerId,id,revision,c.principalId().toString());return ApiResponse.success(null);
    }
}
