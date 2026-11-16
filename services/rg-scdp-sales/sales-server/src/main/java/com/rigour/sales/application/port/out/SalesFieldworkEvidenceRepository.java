package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesFieldworkEvidenceRepository {
    List<DeviceEvent> devices(UUID tenant, UUID profile, UUID day);
    Optional<Capture> capture(UUID tenant, UUID visit);
    VisitHistory history(UUID tenant, Capture current, SalesManagementDirectory.Access scope, int page, int size);
}
