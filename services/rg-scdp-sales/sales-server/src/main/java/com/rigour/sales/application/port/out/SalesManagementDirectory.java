package com.rigour.sales.application.port.out;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.hr.api.v1.model.HrEmployeeIdentityView;
import java.util.List;
import java.util.Set;
/** IAM decides scope; HR resolves departments and display identity. No duplicate employee master. */
public interface SalesManagementDirectory {
    record Access(boolean all, Set<String> employeeCodes) {
        public Access { employeeCodes = Set.copyOf(employeeCodes); }
        public boolean allows(String code) { return all || code != null && employeeCodes.contains(code); }
    }

    SupplyAuthorizationView authorization(CallerIdentity caller, String action);
    List<String> departmentMembers(CallerIdentity caller, List<String> departments, boolean descendants);
    List<HrEmployeeIdentityView> identities(CallerIdentity caller, List<String> codes);
}
