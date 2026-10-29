package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.util.List;

public interface StorePoolStore {
    void initializePolicy(StoreScope scope);
    void update(StoreScope scope, String id, Update command);
    List<Event> events(StoreScope scope, String id);
    Page list(StoreScope scope, Query query);
    Row detail(StoreScope scope, String id);
    void claim(StoreScope scope, String id, Claim command);
    void release(StoreScope scope, String id, Release command);
    List<Ownership> ownerships(StoreScope scope, String id);
    List<Lead> leads(StoreScope scope, boolean review);
    Lead submit(StoreScope scope, LeadCommand command);
    Lead review(StoreScope scope, String id, Review command);
    Coverage coverage(StoreScope scope, Query query);
}
