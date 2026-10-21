package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;

import java.util.List;

public interface HrPayrollSchemeStore {
    List<PayrollSchemeVersionView> versions(String tenant);

    PayrollSchemeVersionView save(
            String tenant, String id, PayrollSchemeSaveCommand command, String actor);

    PayrollSchemeVersionView publish(
            String tenant, String id, PayrollSchemePublishCommand command, String actor);

    PayrollSchemeVersionView stop(
            String tenant, String id, PayrollSchemeStopCommand command, String actor);

    PayrollSchemeFactsView facts(String tenant, String employee, String month);

    PayrollSchemeFactsView saveFacts(
            String tenant,
            String employee,
            String month,
            PayrollSchemeFactsCommand command,
            String actor);
}
