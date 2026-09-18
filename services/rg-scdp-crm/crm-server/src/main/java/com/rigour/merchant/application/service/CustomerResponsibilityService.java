package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.CustomerResponsibilityApi.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class CustomerResponsibilityService {
    private final CustomerResponsibilityStore store;
    private final CrmEmployeeClient employees;

    public CustomerResponsibilityService(
            CustomerResponsibilityStore store, CrmEmployeeClient employees) {
        this.store = store;
        this.employees = employees;
    }

    private CallerIdentity actor() {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope()))
            throw new AuthorizationDeniedException("crm:customer:assign-owner");
        AuthorizationContext.requirePermission("crm:customer:assign-owner");
        return actor;
    }

    public List<Employee> employees(String query) {
        var actor = actor();
        return employees.search(actor.tenantId().toString(), query).stream()
                .filter(CrmEmployeeClient.Owner::usable)
                .map(e -> new Employee(e.code(), e.name(), e.departmentName()))
                .toList();
    }

    public Overview overview(long id) {
        var actor = actor();
        return store.overview(actor.tenantId().toString(), id);
    }

    public Overview transfer(long id, Change c) {
        var actor = actor();
        return store.transfer(actor.tenantId().toString(), id, c, actor.userId().toString());
    }

    public Overview resolve(long id, long conflict, Resolution c) {
        var actor = actor();
        return store.resolve(
                actor.tenantId().toString(), id, conflict, c, actor.userId().toString());
    }
}
