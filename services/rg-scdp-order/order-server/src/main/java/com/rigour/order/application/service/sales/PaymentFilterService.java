package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentFilterService {
    private final PaymentFilterStore store;
    private final CrmCustomerAreaDisplayClient crm;
    private final HrEmployeeDisplayClient hr;
    private final PaymentFilterCatalogClient catalog;
    public PaymentFilterService(PaymentFilterStore store, CrmCustomerAreaDisplayClient crm,
                                HrEmployeeDisplayClient hr, PaymentFilterCatalogClient catalog) {
        this.store = store; this.crm = crm; this.hr = hr; this.catalog = catalog;
    }
    public Options options() {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(PaymentReadPermission.current());
        var parties = store.parties(actor.tenantId().toString());
        var areas = crm.all(serviceCaller(actor, "crm:customer:read")).stream()
                .map(a -> new Choice(a.areaCode(), a.areaName())).toList();
        var departments = hr.departments(serviceCaller(actor, "hr:employee:read")).stream()
                .filter(d -> "ACTIVE".equals(d.status()))
                .map(d -> new Department(d.id(), d.parentId(), d.name(), 0)).toList();
        var products = catalog.catalog(serviceCaller(actor, "erp:product:read"));
        return new Options(areas, departments, parties.employees(), parties.creators(),
                products.categories(), products.products());
    }
    private static CallerIdentity serviceCaller(CallerIdentity actor, String permission) {
        return new CallerIdentity("SERVICE", UUID.nameUUIDFromBytes(
                "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8)), actor.tenantId(),
                null, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(permission));
    }
}
