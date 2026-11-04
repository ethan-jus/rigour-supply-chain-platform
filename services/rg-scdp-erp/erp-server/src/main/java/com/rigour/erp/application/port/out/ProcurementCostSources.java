package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ProcurementCostSource;
import java.util.List;
import java.util.Optional;

public interface ProcurementCostSources {
    record Candidate(long id, String tenantId, String procurementNo) {}
    Optional<ProcurementCostSource> source(String tenant, String code);
    List<Candidate> candidates(long afterId, int limit);
}
