package com.rigour.sales.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.application.port.out.SalesManagementDirectory;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SalesManagementScopeTest {
    final SalesManagementDirectory directory=mock(SalesManagementDirectory.class);
    final SalesManagementScope service=new SalesManagementScope(directory);
    final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    final CallerIdentity caller=new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("sales:dashboard:read"));
    final Limit all=new Limit("ALL",List.of());
    Clause clause(String type,String mode,Limit departments){return new Clause(UUID.randomUUID(),type,mode,departments,all,all,false);}
    void authorize(List<Clause> clauses){when(directory.authorization(caller,"sales:dashboard:read")).thenReturn(new SupplyAuthorizationView("ACTIVE",tenant,user,"self",1,1,1,1,caller.permissions(),"sales:dashboard:read",true,clauses,all,all));}
    @Test void departmentalScopeDoesNotBecomeTenantWide(){
        var departments=new Limit("SPECIFIED",List.of("10"));authorize(List.of(clause("EMPLOYEE","DEPARTMENT",departments)));
        when(directory.departmentMembers(caller,List.of("10"),false)).thenReturn(List.of("beijing-a","beijing-b"));
        var access=service.resolve(caller,"sales:dashboard:read");assertThat(access.all()).isFalse();assertThat(access.allows("beijing-a")).isTrue();assertThat(access.allows("chengdu-a")).isFalse();assertThat(access.allows(null)).isFalse();
    }
    @Test void selfDepartmentClausesIntersectAndUnrecognizedOrEmptyPoliciesDeny(){
        authorize(List.of(clause("EMPLOYEE","SELF",all)));assertThat(service.resolve(caller,"sales:dashboard:read").employeeCodes()).containsExactly("self");
        authorize(List.of(clause("CUSTOMER","ALL",all)));assertThat(service.resolve(caller,"sales:dashboard:read").employeeCodes()).isEmpty();
        authorize(List.of());assertThat(service.resolve(caller,"sales:dashboard:read").all()).isFalse();
    }
    @Test void unscopedAllNeedsExplicitEmployeeGrant(){authorize(List.of(clause("EMPLOYEE","ALL",all)));assertThat(service.resolve(caller,"sales:dashboard:read").all()).isTrue();verify(directory,never()).departmentMembers(any(),any(),anyBoolean());}
    @Test void serviceFailureNeverFallsBackToAll(){when(directory.authorization(caller,"sales:dashboard:read")).thenThrow(new IllegalStateException("unavailable"));assertThatThrownBy(()->service.resolve(caller,"sales:dashboard:read")).hasMessageContaining("unavailable");}
}
