package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.util.List;
import java.util.Set;

public interface StorePoolStore {
    com.rigour.merchant.api.v1.model.StorePoolPolicyModels.Policy currentPolicy(String tenant);
    List<com.rigour.merchant.api.v1.model.StorePoolPolicyModels.Policy> policyHistory(String tenant);
    com.rigour.merchant.api.v1.model.StorePoolPolicyModels.Policy publishPolicy(StoreScope scope, com.rigour.merchant.api.v1.model.StorePoolPolicyModels.Publish command);
    List<StoreMatch> lookup(StoreScope scope,String keyword,String areaCode);
    List<IntakeCandidate> intakeCandidates(StoreScope scope, List<Poi> pois);
    Lead lead(StoreScope scope, String id);
    Admission admit(StoreScope scope,AmapAdmission command,Poi poi,String typeCode);
    Lead resubmit(StoreScope scope,String id,Resubmit command);
    void businessStatus(StoreScope scope,String id,BusinessStatusChange command);
    void update(StoreScope scope, String id, Update command);
    List<Event> events(StoreScope scope, String id);
    default Page list(StoreScope scope, Query query) {return list(scope, query, Set.of());}
    Page list(StoreScope scope, Query query, Set<String> typeCodes);
    FollowupEvidencePage followupEvidence(
            StoreScope scope, Query query, String ownerCode, java.time.LocalDate from, java.time.LocalDate through);
    NearbyModels.Page<Row> nearby(StoreScope scope, Query query, Set<String> typeCodes, NearbyModels.Origin origin, String cursor);
    long count(StoreScope scope, Query query, Set<String> typeCodes);
    MapResult map(StoreScope scope, Query query, Set<String> typeCodes, MapWindow window);
    Page mapStores(StoreScope scope, Query query, Set<String> typeCodes, MapWindow window, String coordinateSystem);
    Row detail(StoreScope scope, String id);
    void claim(StoreScope scope, String id, Claim command);
    void release(StoreScope scope, String id, Release command);
    List<Ownership> ownerships(StoreScope scope, String id);
    LeadPage leads(StoreScope scope, boolean review, int page, int size, String keyword, String status);
    Lead submit(StoreScope scope, LeadCommand command);
    Lead review(StoreScope scope, String id, Review command);
    default Coverage coverage(StoreScope scope, Query query) {return coverage(scope, query, Set.of());}
    Coverage coverage(StoreScope scope, Query query, Set<String> typeCodes);
}
