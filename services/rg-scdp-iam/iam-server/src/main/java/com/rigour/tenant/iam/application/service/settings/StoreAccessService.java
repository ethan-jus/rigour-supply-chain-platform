package com.rigour.tenant.iam.application.service.settings;

import com.rigour.shared.context.*;
import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot;
import com.rigour.tenant.iam.application.port.out.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class StoreAccessService {
    private final AppAuthorizationService authorization;
    private final AppEmployeeClient employees;
    private final StoreTerritoryStore territories;
    private final AppReferenceClient references;
    public StoreAccessService(AppAuthorizationService authorization, AppEmployeeClient employees, StoreTerritoryStore territories, AppReferenceClient references) {
        this.authorization = authorization; this.employees = employees; this.territories = territories; this.references = references;
    }

    public StoreAccessSnapshot access(CallerIdentity caller, String action) {
        if (action == null || !action.matches("crm:(store|pool|lead|coverage):[a-z-]+"))
            throw new AuthorizationDeniedException("store-action");
        var p = authorization.authorization(caller, action);
        if (!p.functionAllowed() || !"ACTIVE".equals(p.mode())) throw new AuthorizationDeniedException(action);
        var employee = p.employeeCode() == null ? null : employees.employee(p.tenantId(), p.employeeCode());
        String tenant = p.tenantId().toString();
        List<StoreAccessSnapshot.Grant> grants = new ArrayList<>();
        List<AppReferenceClient.Reference> departmentCatalog = null;
        for (var c : p.clauses()) {
            if (!"CUSTOMER".equals(c.objectType())) continue;
            boolean national = "ALL".equals(c.scopeMode()) && "ALL".equals(c.departments().mode());
            List<String> departmentIds = "SPECIFIED".equals(c.departments().mode()) ? c.departments().references() : List.of();
            if ("SELF".equals(c.scopeMode())) {
                if (employee == null || !employee.usable() || employee.departmentId() == null) continue;
                departmentIds = List.of(employee.departmentId().toString());
            }
            if (!Set.of("ALL", "SELF", "DEPARTMENT").contains(c.scopeMode())) continue;
            List<String> areaDepartments = departmentIds;
            if (!national && c.includeDescendants() && !"SELF".equals(c.scopeMode()) && !departmentIds.isEmpty()) {
                if (departmentCatalog == null) departmentCatalog = references.references(p.tenantId(), "DEPARTMENT");
                areaDepartments = descendants(departmentIds, departmentCatalog);
            }
            var areas = national ? List.<StoreAccessSnapshot.Area>of() : territories.departments(tenant, areaDepartments);
            grants.add(new StoreAccessSnapshot.Grant(c.scopeMode(), departmentIds, c.includeDescendants(), national, areas));
        }
        var ceiling = territories.personal(tenant, p.userId().toString());
        return new StoreAccessSnapshot(tenant, p.userId().toString(), action, p.applicationVersion(),
                employee != null && employee.usable() ? Long.toString(employee.id()) : null,
                p.employeeCode(), employee == null ? null : employee.employeeName(),
                employee == null || employee.departmentId() == null ? null : employee.departmentId().toString(),
                p.permissions(), grants, ceiling.enabled(), ceiling.areas());
    }

    private static List<String> descendants(List<String> roots, List<AppReferenceClient.Reference> catalog) {
        var result = new LinkedHashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var department : catalog) {
                if ("ACTIVE".equals(department.status()) && result.contains(department.parentKey()))
                    changed |= result.add(department.key());
            }
        } while (changed);
        return List.copyOf(result);
    }
}
