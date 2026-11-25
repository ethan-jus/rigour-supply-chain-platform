package com.rigour.sales.application.service;
import com.rigour.sales.api.v1.model.SalesFieldworkModels.*;
import com.rigour.sales.application.port.out.SalesFieldworkRepository;
import com.rigour.sales.application.port.out.SalesManagementDirectory;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SalesFieldworkService {
    private final SalesFieldworkRepository repository;
    private final SalesManagementDirectory directory;
    private final SalesManagementScope scopes;
    public SalesFieldworkService(SalesFieldworkRepository repository,SalesManagementDirectory directory,SalesManagementScope scopes) {
        this.repository=repository;this.directory=directory;this.scopes=scopes;
    }
    public Page page(LocalDate date,String status,String keyword,int page,int size) {
        return page(date,status,keyword,null,false,false,null,page,size);
    }
    public Page page(LocalDate date,String status,String keyword,String department,boolean descendants,boolean pendingOnly,String riskKind,int page,int size) {
        var caller=SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(SalesManagementScope.FIELDWORK_READ);
        if(date==null||page<1||page>100000||size<1||size>100||keyword!=null&&keyword.length()>80
                ||status!=null&&!status.isBlank()&&!Set.of("ACTIVE","FINISHED","NOT_STARTED").contains(status)
                ||department!=null&&!department.isBlank()&&!department.matches("[1-9][0-9]{0,17}")
                ||riskKind!=null&&!riskKind.isBlank()&&!Set.of("DEVICE","LOCATION","TRACK_GAP","RECORDING").contains(riskKind))
            throw new RequestValidationException("外勤查询条件无效");
        var scope=scopes.resolve(caller,SalesManagementScope.FIELDWORK_READ);
        if(department!=null&&!department.isBlank()) {
            var members=new HashSet<>(directory.departmentMembers(caller,List.of(department),descendants));
            if(!scope.all()) members.retainAll(scope.employeeCodes());
            scope=new SalesManagementDirectory.Access(false,members);
        }
        Set<String> matches=matchingEmployeeCodes(caller,scope,keyword);
        var search=new SalesFieldworkRepository.Search(status,keyword,matches,pendingOnly,riskKind);
        var totals=repository.totals(caller.tenantId(),date,scope,search);
        var rows=repository.page(caller.tenantId(),date,scope,search,size,(page-1)*size);
        var codes=rows.stream().map(PersonDay::employeeCode).filter(Objects::nonNull).distinct().toList();
        var identities=directory.identities(caller,codes).stream().collect(Collectors.toMap(e->e.employeeCode(),Function.identity(),(a,b)->a));
        var result=rows.stream().map(row->{
            var identity=identities.get(row.employeeCode());
            return new PersonDay(row.salesProfileId(),row.employeeCode(),identity==null?row.employeeName():identity.employeeName(),
                    identity==null?null:identity.departmentName(),row.workDayId(),row.status(),row.checkedInAt(),row.checkedOutAt(),row.workMinutes(),
                    row.summaryPending()?null:row.distanceMeters(),row.summaryPending()?null:row.gapCount(),row.visitCount(),row.pendingReviewCount(),row.summaryPending());
        }).toList();
        return new Page(date,result,totals.total(),page,size,totals);
    }
    public List<Department> departments() {
        var caller=SalesWorkContextService.requireTenantCaller();AuthorizationContext.requirePermission(SalesManagementScope.FIELDWORK_READ);
        var result=new TreeMap<Long,Department>();
        for(var person:identities(caller,scopes.resolve(caller,SalesManagementScope.FIELDWORK_READ)))
            if(person.departmentId()!=null&&person.departmentName()!=null) result.put(person.departmentId(),new Department(person.departmentId().toString(),person.departmentName()));
        return List.copyOf(result.values());
    }
    public Set<String> matchingEmployeeCodes(com.rigour.shared.context.CallerIdentity caller,SalesManagementDirectory.Access scope,String keyword) {
        if(keyword==null||keyword.isBlank()) return Set.of();
        String term=keyword.strip().toLowerCase(Locale.ROOT);
        return identities(caller,scope).stream()
                .filter(person->person.employeeName()!=null && person.employeeName().toLowerCase(Locale.ROOT).contains(term))
                .map(person->person.employeeCode()).collect(Collectors.toSet());
    }
    private List<com.rigour.hr.api.v1.model.HrEmployeeIdentityView> identities(com.rigour.shared.context.CallerIdentity caller,SalesManagementDirectory.Access scope) {
        var codes=repository.employeeCodes(caller.tenantId(),scope);
        var result=new ArrayList<com.rigour.hr.api.v1.model.HrEmployeeIdentityView>();
        for(int start=0;start<codes.size();start+=100) result.addAll(directory.identities(caller,codes.subList(start,Math.min(start+100,codes.size()))));
        return result;
    }
    public SalesManagementDirectory.Access access(String action) {
        var caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(action);
        return scopes.resolve(caller, action);
    }
    public void requireProfile(UUID profile,String action) {
        var caller=SalesWorkContextService.requireTenantCaller();AuthorizationContext.requirePermission(action);
        var scope=scopes.resolve(caller,action);
        if(!scope.all()&&!scope.allows(repository.profileEmployeeCode(caller.tenantId(),profile).orElse(null))) throw new AuthorizationDeniedException(action);
    }
    public void requireVisit(UUID visit,String action) {
        var caller=SalesWorkContextService.requireTenantCaller();AuthorizationContext.requirePermission(action);
        var scope=scopes.resolve(caller,action);
        requireVisibleVisit(caller.tenantId(),visit,scope,action);
    }
    void requireVisibleVisit(UUID tenant,UUID visit,SalesManagementDirectory.Access scope,String action) {
        if(!scope.all()&&!scope.allows(repository.visitEmployeeCode(tenant,visit).orElse(null))) throw new AuthorizationDeniedException(action);
    }
}
