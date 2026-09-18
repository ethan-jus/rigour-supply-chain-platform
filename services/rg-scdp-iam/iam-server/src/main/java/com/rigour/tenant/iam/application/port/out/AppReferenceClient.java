package com.rigour.tenant.iam.application.port.out;

import java.util.List;
import java.util.UUID;

public interface AppReferenceClient {
    record Reference(String key, String name, String parentKey, String status, long revision) {}

    List<Reference> references(UUID tenant, String dimension);
}
