package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.PayrollBatchProfileCommand;

public interface HrEmployeePayrollProfileStore {
    void update(String tenant, PayrollBatchProfileCommand command, String actor);
}
