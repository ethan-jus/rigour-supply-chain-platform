package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;

import java.util.List;
import java.util.Optional;

/** HR 组织与人工任职维护端口；跨服务只暴露 API，不共享人员表。 */
public interface HrOrganizationStore {
    List<HrDepartmentView> departments(String tenantId);

    HrDepartmentView saveDepartment(
            String tenantId, Long id, HrDepartmentCommand command, String actorId);

    void deleteDepartment(String tenantId, long id, int revision, String actorId);

    long saveEmployee(String tenantId, Long id, HrEmployeeCommand command, String actorId);

    Optional<HrEmployeeIdentityView> identity(String tenantId, String employeeCode);

    HrPageView<HrEmployeeIdentityView> identities(
            String tenantId, String keyword, int begin, int step);

    List<HrAssignmentView> assignments(String tenantId, long employeeId);
}
