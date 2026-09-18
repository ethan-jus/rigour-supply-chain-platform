package com.rigour.tenant.iam.application.service.identity;
import com.rigour.tenant.iam.application.port.out.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public final class AuditActorService {
    private final AuditActorReader reader;
    private final AppEmployeeClient employees;
    public AuditActorService(AuditActorReader reader,AppEmployeeClient employees){this.reader=reader;this.employees=employees;}
    public Map<String,String> resolve(CurrentUser user,List<String> refs){
        if(!"TENANT".equals(user.principalScope())||user.tenantId()==null) throw new org.springframework.security.access.AccessDeniedException("仅企业用户可查询操作人姓名");
        if(refs==null||refs.size()>100||refs.stream().anyMatch(r->r==null||r.length()>128||r.isBlank())) throw new IllegalArgumentException("单次最多查询100个操作人");
        var rows=reader.actors(user.tenantId(),refs);
        var codes=new LinkedHashSet<String>();
        rows.stream().map(AuditActorReader.ActorName::employeeCode).filter(Objects::nonNull).forEach(codes::add);
        refs.stream().filter(r->r.matches("EMP[A-Za-z0-9]+" )).forEach(codes::add);
        Map<String,AppEmployeeClient.Employee> people=Map.of();
        try { if(!codes.isEmpty()) people=employees.employees(user.tenantId(),List.copyOf(codes)); }
        catch(RuntimeException ex) { /* HR不可用时保留IAM实名，绝不返回跨租户数据或敏感资料。 */ }
        Map<String,String> result=new LinkedHashMap<>();
        for(var row:rows){
            var employee=row.employeeCode()==null?null:people.get(row.employeeCode());
            String name=employee==null?row.name():employee.employeeName();
            if(name!=null&&!name.isBlank()) {if(refs.contains(row.id()))result.put(row.id(),name);if(row.employeeCode()!=null && refs.contains(row.employeeCode()))result.put(row.employeeCode(),name);}
        }
        for(String ref:refs){var person=people.get(ref);if(person!=null)result.put(ref,person.employeeName());}
        return result;
    }
}
