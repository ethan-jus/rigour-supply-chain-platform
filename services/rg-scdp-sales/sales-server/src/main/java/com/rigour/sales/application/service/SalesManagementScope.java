package com.rigour.sales.application.service;
import com.rigour.sales.application.port.out.SalesManagementDirectory;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public final class SalesManagementScope {
    static final String FIELDWORK_READ = "sales:fieldwork:read";
    static final String VISIT_RECORD_READ = "sales:visit-record:read";

    private final SalesManagementDirectory directory;
    public SalesManagementScope(SalesManagementDirectory directory) { this.directory=directory; }
    public Access resolve(CallerIdentity caller, String action) {
        var policy=directory.authorization(caller,action);
        if (policy == null || !"ACTIVE".equals(policy.mode()) || !policy.functionAllowed()
                || !Objects.equals(caller.tenantId(), policy.tenantId()) || !Objects.equals(caller.userId(), policy.userId())
                || !action.equals(policy.action())) throw new AuthorizationDeniedException(action);
        Set<String> employees=new HashSet<>();
        Map<String,List<String>> departments=new HashMap<>();
        for(var clause:policy.clauses()) {
            if (!"EMPLOYEE".equals(clause.objectType()) || !Set.of("NONE","ALL").contains(clause.regions().mode())
                    || !Set.of("NONE","ALL").contains(clause.warehouses().mode())) continue;
            if (!Set.of("ALL","SELF","DEPARTMENT").contains(clause.scopeMode())) continue;
            if ("ALL".equals(clause.departments().mode()) && "ALL".equals(clause.scopeMode())) return new Access(true,Set.of());
            Set<String> members=new HashSet<>();
            if ("SPECIFIED".equals(clause.departments().mode()) && !clause.departments().references().isEmpty()) {
                String key=clause.departments().references().toString()+clause.includeDescendants();
                members.addAll(departments.computeIfAbsent(key,k -> directory.departmentMembers(caller,clause.departments().references(),clause.includeDescendants())));
            } else if ("SELF".equals(clause.scopeMode()) && "ALL".equals(clause.departments().mode()) && policy.employeeCode()!=null) members.add(policy.employeeCode());
            if ("SELF".equals(clause.scopeMode())) members.removeIf(code -> !code.equals(policy.employeeCode()));
            employees.addAll(members);
        }
        return new Access(false,employees);
    }
}
