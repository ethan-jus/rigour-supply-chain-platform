package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitVerificationModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;

public interface StoreVisitVerificationStore {
    boolean belongsTo(StoreScope scope,String store,String visit);
    Verification read(StoreScope scope,String store,String visit);
    Verification appeal(StoreScope scope,String store,String visit,AppealCommand command,Instant now);
    Queue queue(StoreScope scope,String status,int page,int size);
}
