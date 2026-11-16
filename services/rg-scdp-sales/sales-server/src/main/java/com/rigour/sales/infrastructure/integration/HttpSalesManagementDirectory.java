package com.rigour.sales.infrastructure.integration;
import com.rigour.sales.application.port.out.SalesManagementDirectory;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.hr.api.v1.model.HrEmployeeIdentityView;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public final class HttpSalesManagementDirectory implements SalesManagementDirectory {
    private final SalesIdentityGateway gateway;
    public HttpSalesManagementDirectory(SalesIdentityGateway gateway) { this.gateway=gateway; }
    public SupplyAuthorizationView authorization(CallerIdentity caller,String action) { return gateway.authorization(caller,action); }
    public List<String> departmentMembers(CallerIdentity caller,List<String> departments,boolean descendants) { return gateway.departmentMembers(caller,departments,descendants); }
    public List<HrEmployeeIdentityView> identities(CallerIdentity caller,List<String> codes) { return gateway.identities(caller,codes); }
}
