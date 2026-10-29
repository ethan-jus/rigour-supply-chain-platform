package com.rigour.merchant.infrastructure.persistence.repository;

import com.rigour.merchant.application.service.StoreScope;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import java.util.*;

/** Fixed SQL identifiers only; every external value is a bound parameter. */
public final class StoreScopeSql {
    private StoreScopeSql() {}
    public record Predicate(String sql, List<Object> args) { public Predicate { args=List.copyOf(args); } }
    public static Predicate compile(StoreScope scope) {
        var args=new ArrayList<Object>(); var clauses=new ArrayList<String>();
        for(var grant:scope.access().grants()) {
            String owners;
            switch(grant.ownerScope()) {
                case "ALL" -> owners="1=1";
                case "SELF" -> {
                    if(scope.access().employeeId()==null) continue;
                    owners="q.owner_employee_id=?"; args.add(scope.access().employeeId());
                }
                case "DEPARTMENT" -> {
                    var codes=grant.departmentIds().stream().flatMap(d->scope.departmentMembers().getOrDefault(d+":"+grant.descendants(),List.of()).stream()).distinct().toList();
                    owners=codes.isEmpty()?"1=0":"q.owner_employee_code IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")";
                    args.addAll(codes);
                }
                default -> { continue; }
            }
            String areas=grant.national()?"1=1":areas(scope,grant.areas(),args);
            clauses.add("((q.pool_type='PUBLIC' OR ("+owners+")) AND ("+areas+"))");
        }
        String sql=clauses.isEmpty()?"1=0":"("+String.join(" OR ",clauses)+")";
        if(scope.access().personalLimitEnabled()) sql+=" AND ("+areas(scope,scope.access().personalAreas(),args)+")";
        return new Predicate(sql,args);
    }
    private static String areas(StoreScope scope,List<StoreAccessView.Area> areas,List<Object> args) {
        var clauses=new ArrayList<String>(); var nodes=scope.nodes();
        for(var area:areas) {
            if(!scope.catalog().version().equals(area.version())) continue;
            var n=nodes.get(area.code()); if(n==null) continue;
            String field=switch(n.level()) {case "PROVINCE"->"q.province_code";case "PREFECTURE"->"q.city_code";case "COUNTY"->"q.admin_area_code";default->null;};
            if(field!=null) {clauses.add("("+field+"=? AND q.admin_area_version=?)");args.add(area.code());args.add(area.version());}
        }
        return clauses.isEmpty()?"1=0":String.join(" OR ",clauses);
    }
}
