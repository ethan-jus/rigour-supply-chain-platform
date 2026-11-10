package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.shared.context.CallerIdentity;

import java.util.List;

public interface HuolalaDomainClient {
    List<EmployeeCandidate> employees(CallerIdentity caller, String keyword);

    EmployeeCandidate employee(CallerIdentity caller, Long id);

    Row receive(CallerIdentity caller, ApiSource source);

    default void employeeMapping(CallerIdentity caller, EmployeeMappingUpdate update) {
        throw new UnsupportedOperationException("员工关联推送尚未接通");
    }

    default void voucher(
            com.rigour.shared.context.CallerIdentity caller,
            com.rigour.order.api.v1.model.LogisticsImportModels.VoucherUpdate update) {
        throw new UnsupportedOperationException("凭证推送尚未接通");
    }
}
