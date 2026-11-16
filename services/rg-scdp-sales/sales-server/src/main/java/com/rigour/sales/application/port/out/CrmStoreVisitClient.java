package com.rigour.sales.application.port.out;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context;
public interface CrmStoreVisitClient {
    com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page ownVisits(java.time.LocalDate from,java.time.LocalDate to,String kind,String status,String keyword,int page,int size);
    Visit checkIn(String store,CheckIn command);
    String evidenceAccess(String store,String visit);
    Context context(String store,String visit);
    Visit cancel(String store,String visit,CancelVisit command);
}
