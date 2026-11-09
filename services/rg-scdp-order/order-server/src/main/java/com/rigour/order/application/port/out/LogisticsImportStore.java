package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsImportModels.*;

import java.util.List;

public interface LogisticsImportStore {
    List<Row> stage(String tenant, String actor, Upload upload);

    List<Row> rows(String tenant, String actor, int begin);

    Row lock(String tenant, String actor, String id);

    boolean trackingExists(String tenant, String trackingNo);

    void generated(String tenant, String id, int revision, String costId);
}
