package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;

import java.util.*;

public interface PlatformTransportStore {
    void lockTenant(String tenant);

    Row materialize(String tenant, String actor, Row row);

    Map<String, PlatformRecord> byCosts(String tenant, List<String> ids);

    default PlatformRecord detail(String tenant, String id) {
        return byCosts(tenant, List.of(id)).get(id);
    }

    void saveDraft(
            String tenant,
            String costId,
            Draft draft,
            List<LogisticsCostSourceView> sources,
            String actor);

    void assertReadOnly(String tenant, String id, LogisticsCostCommand command);

    void voucher(String tenant, VoucherUpdate update);
}
