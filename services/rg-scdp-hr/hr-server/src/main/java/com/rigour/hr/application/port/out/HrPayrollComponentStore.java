package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;

import java.util.List;

public interface HrPayrollComponentStore {
    java.util.Optional<PayrollComponentVersionView> byRequest(String tenant, String requestId);

    List<PayrollComponentVersionView> versions(String tenant);

    PayrollComponentVersionView save(
            String tenant, PayrollComponentSaveCommand command, String actor);

    String unifiedFrom(String tenant);
}
