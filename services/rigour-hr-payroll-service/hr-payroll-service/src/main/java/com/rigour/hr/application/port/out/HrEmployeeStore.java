package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import java.util.List;
import java.util.Optional;

/** HR 员工主档持久化端口。 */
public interface HrEmployeeStore {
    HrPageView<HrEmployeeView> employees(String tenantId, int begin, int step,
                                         EmployeeSearchCriteria criteria);

    Optional<HrEmployeeView> employee(String tenantId, Long id);

    boolean existsByEmployeeCode(String tenantId, String employeeCode);

    ExternalEmployeeSyncResult syncExternalEmployees(String tenantId, String sourceSystem,
                                                     List<ExternalEmployeeRowCommand> rows,
                                                     String actorId,
                                                     BusinessCodeGenerator codeGenerator);

    List<ExternalEmployeeResolvedView> resolveExternalEmployees(String tenantId,
                                                                String sourceSystem,
                                                                String sourceTenantKey,
                                                                List<String> sourceEmployeeIds,
                                                                List<String> employeeNames);

    record EmployeeSearchCriteria(String keyword,
                                  String employeeCode,
                                  String employeeName,
                                  String mobile,
                                  String employmentStatus,
                                  String jobCategory,
                                  String positionName,
                                  String regionName,
                                  String cityName,
                                  String sourceSystem) {
    }
}
