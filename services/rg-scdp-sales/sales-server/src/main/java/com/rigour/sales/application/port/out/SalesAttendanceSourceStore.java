package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;

import java.util.List;

public interface SalesAttendanceSourceStore {
    List<Evidence> evidence(String tenant, Query query);
}
