package com.rigour.sales.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.rigour.sales.api.v1.model.SalesFieldworkReviewModels.Case;

public interface SalesFieldworkReviewRepository {
    void detect(UUID tenant, UUID profile, UUID day, UUID visit, String key, String kind, String title, String evidenceJson, Instant now);
    List<Case> byDay(UUID tenant, UUID day);
    List<Case> byVisit(UUID tenant, UUID visit);
    Optional<Case> find(UUID tenant, UUID id, boolean lock);
    Optional<Case> findEvidence(UUID tenant,String evidenceKey);
    Optional<Command> command(UUID tenant, UUID id, UUID user, String requestId);
    void append(UUID tenant, UUID id, UUID user, String requestId, String type, String decision, String note, String status, Instant now);
    record Command(String type, String decision, String note) {}
}
