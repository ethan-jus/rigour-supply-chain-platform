package com.rigour.hr.application.port.out;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;

import java.util.List;
import java.util.Set;

public interface HrAttendanceSource {
    Set<String> leaveTypes(String tenant);

    List<Evidence> evidence(String tenant, Query query);
}
