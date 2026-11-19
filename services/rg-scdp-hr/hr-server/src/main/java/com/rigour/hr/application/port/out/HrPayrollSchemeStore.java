package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;

import java.util.List;

public interface HrPayrollSchemeStore {
    List<PayrollSchemeVersionView> versions(String tenant);

    PayrollSchemeFactsView facts(String tenant, String employee, String month);

    PayrollSchemeFactsView saveFacts(
            String tenant,
            String employee,
            String month,
            PayrollSchemeFactsCommand command,
            String actor);
}
