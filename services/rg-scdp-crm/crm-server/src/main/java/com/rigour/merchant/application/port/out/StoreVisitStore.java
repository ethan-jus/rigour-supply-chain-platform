package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;
import java.util.List;
public interface StoreVisitStore {
    String evidenceCapture(StoreScope scope,String store,String visit);
    com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context captureContext(StoreScope scope,String store,String visit);
    Visit checkIn(StoreScope scope,String store,CheckIn c,com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Policy policy,Instant now);
    Visit submit(StoreScope scope,String store,String visit,SubmitVisit c,Instant now);
    Visit cancel(StoreScope scope,String store,String visit,CancelVisit c,Instant now);
    Visit review(StoreScope scope,String store,String visit,ReviewVisit c,Instant now);
    List<Visit> visits(StoreScope scope,String store);
    OrderPermit authorize(StoreScope scope,String store,OrderAuthorization c,Instant now);
}
