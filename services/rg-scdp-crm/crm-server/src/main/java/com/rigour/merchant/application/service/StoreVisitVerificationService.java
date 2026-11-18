package com.rigour.merchant.application.service;
import com.rigour.merchant.api.v1.model.StoreVisitVerificationModels.*;
import com.rigour.merchant.application.port.out.StoreVisitVerificationStore;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class StoreVisitVerificationService {
    private final StorePoolService pools;private final StoreVisitVerificationStore store;private final Clock clock;
    public StoreVisitVerificationService(StorePoolService pools,StoreVisitVerificationStore store,Clock clock){this.pools=pools;this.store=store;this.clock=clock;}
    public Verification read(String id,String visit){
        var scope=pools.scope("crm:store:history-read");
        if(!store.belongsTo(scope,id,visit))scope=pools.scopeForStore("crm:store:history-read",id);
        return store.read(scope,id,visit);
    }
    public Verification appeal(String id,String visit,AppealCommand c){
        if(c==null||c.reason()==null||c.reason().isBlank()||c.reason().length()>2000||c.requestId()==null||!c.requestId().matches("[A-Za-z0-9_-]{16,64}"))
            throw new RequestValidationException("请填写申诉原因，最多 2000 字");
        return store.appeal(pools.scope("crm:store:visit-write"),id,visit,c,clock.instant());
    }
    public Queue queue(String status,int page,int size){
        if(!java.util.Set.of("PENDING","RESOLVED").contains(status)||page<1||size<1||size>100)throw new RequestValidationException("申诉查询参数无效");
        return store.queue(pools.scope("crm:store:visit-review"),status,page,size);
    }
}
