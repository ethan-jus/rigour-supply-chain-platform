package com.rigour.order.application.port.out;

import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderPermit;
import com.rigour.order.api.v1.model.StoreOrderFacts;
import java.time.Instant;

public interface StoreOrderSubmissionStore {
    record Submission(String storeId,String ownershipId,String requestId,String status,int revision) {}
    Submission reserve(String tenant,String user,long id,int revision,String store,String ownership,String request,Instant now);
    void authorize(String tenant,String user,long id,String request,OrderPermit permit,Instant now);
    StoreOrderFacts facts(String tenant,String store,String ownership,Instant now);
}
