package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;
import java.util.List;
public interface StoreVisitFieldworkStore {
    com.rigour.merchant.api.v1.model.StoreModels.Row current(StoreScope scope,String store);
    String businessType(String tenant,String store);
    List<Progress> businesses(String tenant,String store);
    String syncStatus(String tenant,String visit);
    Draft submitted(String tenant,String visit);
    void validateCurrent(StoreScope scope,String store,String visit,Draft draft);
    void apply(Evidence evidence,Instant now);
}
