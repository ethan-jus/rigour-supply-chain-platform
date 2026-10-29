package com.rigour.sales.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import java.util.UUID;
public interface StoreVisitCaptureStore {
    record Link(String storeId,String visitId,String ownershipId){}
    Link link(UUID tenant,UUID capture);
    void create(UUID tenant,UUID profile,UUID workDay,UUID capture,Context context,LocationEvidence location);
    Facts facts(UUID tenant,String store,String ownership);
}
