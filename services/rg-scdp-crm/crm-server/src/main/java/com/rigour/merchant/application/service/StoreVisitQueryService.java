package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page;
import com.rigour.merchant.application.port.out.StoreVisitQueryStore;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class StoreVisitQueryService {
    private final StorePoolService pools;
    private final StoreVisitQueryStore store;
    public StoreVisitQueryService(StorePoolService pools,StoreVisitQueryStore store){this.pools=pools;this.store=store;}
    public Page list(LocalDate from,LocalDate to,String kind,String status,String keyword,int page,int size) {
        if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>366||page<1||page>100000||size<1||size>100
            ||keyword!=null&&keyword.length()>100||!Set.of("all","effective","first").contains(kind)
            ||!Set.of("ALL","IN_PROGRESS","PENDING_SUBMIT","SUBMITTED","PROCESSING","INSUFFICIENT","ERROR","APPEALED","EFFECTIVE","INVALID","VOID","CANCELLED").contains(status))
            throw new RequestValidationException("拜访查询条件无效");
        var scope=pools.scope("crm:store:history-read");
        if(scope.access().employeeId()==null)throw new RequestValidationException("当前账号未关联员工");
        var zone=ZoneId.of("Asia/Shanghai");
        return store.list(scope,from.atStartOfDay(zone).toInstant(),to.plusDays(1).atStartOfDay(zone).toInstant(),
            kind,status,keyword,page,size);
    }
}
