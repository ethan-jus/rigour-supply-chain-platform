package com.rigour.tenant.iam.application.port.out;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import java.util.UUID;

public interface StoreDivisionClient {
    AdminDivisionCatalog published(UUID tenant);
}
