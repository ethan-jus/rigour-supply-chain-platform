package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import java.util.List;

public interface LogisticsImportStore {
    List<Row> stage(String tenant, String actor, Upload upload);

    List<Row> rows(String tenant, String actor, int begin);

    Row lock(String tenant, String actor, String id);

    boolean trackingExists(String tenant, String trackingNo);

    void generated(String tenant, String id, int revision, String costId);

    default List<Row> query(String tenant, String actor, boolean administrator, SourceQuery query) {
        return rows(tenant, actor, query.begin());
    }

    default Row lockManaged(String tenant, String actor, boolean administrator, String id) {
        return lock(tenant, actor, id);
    }

    default Row receive(String tenant, String actor, ApiSource source) {
        throw new UnsupportedOperationException("API来源尚未接通");
    }

    default void accept(String tenant, String id, int revision, String actor, String reason) {
        throw new UnsupportedOperationException("来源确认尚未接通");
    }

    default void verifyBill(String tenant, String id, int revision, BillVerification verification) {
        throw new UnsupportedOperationException("账单核对尚未接通");
    }

    default java.util.Optional<Row> byCost(String tenant, String costId) {
        return java.util.Optional.empty();
    }
}
