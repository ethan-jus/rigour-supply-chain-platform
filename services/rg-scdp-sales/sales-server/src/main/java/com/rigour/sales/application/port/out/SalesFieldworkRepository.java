package com.rigour.sales.application.port.out;
import com.rigour.sales.api.v1.model.SalesFieldworkModels.*;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import java.time.*;
import java.util.*;
public interface SalesFieldworkRepository {
    record Search(String status,String keyword,Set<String> nameMatches,boolean pendingOnly,String riskKind) {}
    List<String> employeeCodes(UUID tenant,Access scope);
    List<PersonDay> page(UUID tenant,LocalDate date,Access scope,Search search,int limit,int offset);
    Totals totals(UUID tenant,LocalDate date,Access scope,Search search);
    record Pending(UUID tenantId, UUID workDayId, long version, LocalDate date, UUID fieldPolicyId, UUID salesProfileId) {}
    List<Pending> pendingSummaries(int limit);
    void saveSummary(Pending day, Integer distanceMeters, int trackedMinutes, int gaps, int rejected, Instant calculatedAt);
    default List<PersonDay> page(UUID tenant, LocalDate date, Access scope, String status, String keyword, int limit, int offset) {return page(tenant,date,scope,new Search(status,keyword,Set.of(),false,null),limit,offset);}
    default Totals totals(UUID tenant, LocalDate date, Access scope, String status, String keyword) {return totals(tenant,date,scope,new Search(status,keyword,Set.of(),false,null));}
    Optional<String> profileEmployeeCode(UUID tenant, UUID profile);
    Optional<String> visitEmployeeCode(UUID tenant, UUID visit);
}
