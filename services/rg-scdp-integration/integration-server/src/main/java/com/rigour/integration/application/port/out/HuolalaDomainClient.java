package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.shared.context.CallerIdentity;

import java.util.List;

public interface HuolalaDomainClient {
    List<EmployeeCandidate> employees(CallerIdentity caller, String keyword);

    EmployeeCandidate employee(CallerIdentity caller, Long id);

    Row receive(CallerIdentity caller, ApiSource source);
}
