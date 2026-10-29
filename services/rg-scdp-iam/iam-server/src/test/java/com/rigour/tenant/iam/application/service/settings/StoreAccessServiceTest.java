package com.rigour.tenant.iam.application.service.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.application.model.settings.StoreAccessSnapshot.Area;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot;
import com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot.*;
import com.rigour.tenant.iam.application.port.out.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoreAccessServiceTest {
    @Test void includesChildDepartmentTerritoriesOnlyWhenExplicitlyAuthorized() {
        var authorization = mock(AppAuthorizationService.class);
        var employees = mock(AppEmployeeClient.class);
        var territories = mock(StoreTerritoryStore.class);
        var references = mock(AppReferenceClient.class);
        var caller = mock(CallerIdentity.class);
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String action = "crm:pool:team-read";
        var clauses = List.of(
                new Clause(UUID.randomUUID(), "CUSTOMER", "DEPARTMENT", new Limit("SPECIFIED", List.of("1")), new Limit("ALL", List.of()), new Limit("ALL", List.of()), true),
                new Clause(UUID.randomUUID(), "CUSTOMER", "DEPARTMENT", new Limit("SPECIFIED", List.of("2")), new Limit("ALL", List.of()), new Limit("ALL", List.of()), false));
        when(authorization.authorization(caller, action)).thenReturn(new AppAuthorizationSnapshot("ACTIVE", tenant, user, null, 2, 1, 1, 1, Set.of(action), action, true, clauses, new Limit("ALL", List.of()), new Limit("ALL", List.of())));
        when(references.references(tenant, "DEPARTMENT")).thenReturn(List.of(
                new AppReferenceClient.Reference("1", "销售部", null, "ACTIVE", 1),
                new AppReferenceClient.Reference("2", "杭州", "1", "ACTIVE", 1),
                new AppReferenceClient.Reference("3", "南京", "1", "ACTIVE", 1),
                new AppReferenceClient.Reference("4", "兰州", null, "ACTIVE", 1),
                new AppReferenceClient.Reference("5", "停用", "1", "INACTIVE", 1)));
        when(territories.departments(tenant.toString(), List.of("1", "2", "3"))).thenReturn(List.of(new Area("V1", "330100"), new Area("V1", "320100")));
        when(territories.departments(tenant.toString(), List.of("2"))).thenReturn(List.of(new Area("V1", "330100")));
        when(territories.personal(tenant.toString(), user.toString())).thenReturn(new StoreTerritoryStore.Ceiling(false, List.of()));
        var result = new StoreAccessService(authorization, employees, territories, references).access(caller, action);
        assertThat(result.grants().getFirst().departmentIds()).containsExactly("1");
        assertThat(result.grants().getFirst().areas()).extracting(Area::code).containsExactly("330100", "320100");
        assertThat(result.grants().get(1).areas()).extracting(Area::code).containsExactly("330100");
        verify(territories, times(2)).departments(eq(tenant.toString()), anyList());
        verify(references).references(tenant, "DEPARTMENT");
    }
}
