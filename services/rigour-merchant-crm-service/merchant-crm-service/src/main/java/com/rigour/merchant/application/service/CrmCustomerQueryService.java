package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CustomerDetailView;
import com.rigour.merchant.api.v1.model.CustomerSummaryView;
import com.rigour.merchant.api.v1.model.DictionaryView;
import com.rigour.merchant.api.v1.model.ExternalStaffView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.merchant.api.v1.model.ShippingAddressSummaryView;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Portal CRM 查询用例；所有页面只读取 CRM 本地表。 */
@Service
public final class CrmCustomerQueryService {
    private final CrmCustomerQueryStore store;

    public CrmCustomerQueryService(CrmCustomerQueryStore store) {
        this.store = store;
    }

    public PageView<CustomerSummaryView> customers(int begin, int step,
                                                   String query, String status) {
        return store.customers(tenant(), pageBegin(begin), pageStep(step), query, status);
    }

    public CustomerDetailView customer(UUID id) {
        if (id == null) throw new IllegalArgumentException("客户ID不能为空");
        return store.customer(tenant(), id);
    }

    public PageView<ShippingAddressSummaryView> shippingAddresses(
            int begin, int step, String query) {
        return store.shippingAddresses(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public PageView<DictionaryView> customerTypes(int begin, int step, String query) {
        return store.customerTypes(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public PageView<DictionaryView> customerAreas(int begin, int step, String query) {
        return store.customerAreas(tenant(), pageBegin(begin), pageStep(step), query);
    }

    public PageView<ExternalStaffView> externalStaff(int begin, int step, String query) {
        return store.externalStaff(tenant(), pageBegin(begin), pageStep(step), query);
    }

    private static UUID tenant() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("crm:customer:read");
        return caller.tenantId();
    }

    private static int pageBegin(int value) {
        if (value < 0) throw new IllegalArgumentException("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw new IllegalArgumentException("step必须在1到200之间");
        return value;
    }
}
