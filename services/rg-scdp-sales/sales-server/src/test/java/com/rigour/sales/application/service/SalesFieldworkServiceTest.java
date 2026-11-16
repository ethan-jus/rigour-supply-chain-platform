package com.rigour.sales.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.api.v1.model.SalesFieldworkModels.Totals;
import com.rigour.hr.api.v1.model.HrEmployeeIdentityView;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class SalesFieldworkServiceTest {
    final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    final SalesFieldworkRepository repository=mock(SalesFieldworkRepository.class);
    final SalesManagementDirectory directory=mock(SalesManagementDirectory.class);
    final SalesManagementScope scopes=mock(SalesManagementScope.class);
    final SalesFieldworkService service=new SalesFieldworkService(repository,directory,scopes);
    @BeforeEach void caller() throws Exception {
        var method=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);method.setAccessible(true);
        method.invoke(null,new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("sales:dashboard:read")));
        when(repository.totals(eq(tenant),any(),any(),any(SalesFieldworkRepository.Search.class))).thenReturn(new Totals(0,0,0,0,0,0));
        when(repository.page(eq(tenant),any(),any(),any(SalesFieldworkRepository.Search.class),anyInt(),anyInt())).thenReturn(List.of());
    }
    @AfterEach void clear() throws Exception {var m=AuthorizationContext.class.getDeclaredMethod("clear");m.setAccessible(true);m.invoke(null);}
    HrEmployeeIdentityView person(String code,String name,long department){return new HrEmployeeIdentityView(1L,code,name,"ACTIVE",department,"部门"+department,null,null,List.of(),1,1,1,true,null);}
    @Test void selectedDepartmentIntersectsPermissionAndTotalsUseSameFilter(){
        when(scopes.resolve(any(),eq("sales:dashboard:read"))).thenReturn(new SalesManagementDirectory.Access(false,Set.of("visible","outside-department")));
        when(directory.departmentMembers(any(),eq(List.of("8")),eq(true))).thenReturn(List.of("visible","unauthorized"));
        service.page(LocalDate.of(2026,11,13),"ACTIVE","","8",true,true,"DEVICE",2,20);
        var scope=ArgumentCaptor.forClass(SalesManagementDirectory.Access.class);var search=ArgumentCaptor.forClass(SalesFieldworkRepository.Search.class);
        verify(repository).page(eq(tenant),any(),scope.capture(),search.capture(),eq(20),eq(20));
        assertThat(scope.getValue().employeeCodes()).containsExactly("visible");assertThat(scope.getValue().all()).isFalse();
        assertThat(search.getValue().pendingOnly()).isTrue();assertThat(search.getValue().riskKind()).isEqualTo("DEVICE");
        verify(repository).totals(eq(tenant),any(),eq(scope.getValue()),eq(search.getValue()));
    }
    @Test void nameSearchResolvesAllBatchesRatherThanOnlyDisplayedPage(){
        var scope=new SalesManagementDirectory.Access(true,Set.of());when(scopes.resolve(any(),anyString())).thenReturn(scope);
        var codes=IntStream.range(0,101).mapToObj(i->"E"+i).toList();when(repository.employeeCodes(tenant,scope)).thenReturn(codes);
        when(directory.identities(any(),anyList())).thenAnswer(invocation->{List<String> batch=invocation.getArgument(1);return batch.stream().map(code->person(code,code.equals("E100")?"张松":"其他",8)).toList();});
        service.page(LocalDate.now(),null,"张松",1,20);
        var search=ArgumentCaptor.forClass(SalesFieldworkRepository.Search.class);verify(repository).page(eq(tenant),any(),eq(scope),search.capture(),eq(20),eq(0));
        assertThat(search.getValue().nameMatches()).containsExactly("E100");
        verify(directory).identities(any(),eq(codes.subList(0,100)));verify(directory).identities(any(),eq(List.of("E100")));
    }
    @Test void optionsContainOnlyDepartmentsOfAuthorizedSales(){
        var scope=new SalesManagementDirectory.Access(false,Set.of("visible"));when(scopes.resolve(any(),anyString())).thenReturn(scope);
        when(repository.employeeCodes(tenant,scope)).thenReturn(List.of("visible"));when(directory.identities(any(),eq(List.of("visible")))).thenReturn(List.of(person("visible","可见",8)));
        assertThat(service.departments()).extracting(d->d.id()).containsExactly("8");
    }
}
