package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Set;

/** Order读取HR员工展示信息的端口；业务主表只保存员工编码。 */
public interface HrEmployeeDisplayClient {
    /** 无 HR 通道时的空实现；部门筛选按“无匹配”处理，不静默放大成全量。 */
    HrEmployeeDisplayClient NONE = new HrEmployeeDisplayClient() {
        @Override
        public List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes) {
            return List.of();
        }

        @Override
        public Set<String> employeeCodesInDepartment(
                CallerIdentity caller, Long departmentId, Boolean includeSubDepartments) {
            return Set.of();
        }
    };

    List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes);

    /** 按部门解析员工编码；includeSubDepartments 为空或 true 时包含子部门。 */
    Set<String> employeeCodesInDepartment(
            CallerIdentity caller, Long departmentId, Boolean includeSubDepartments);

    record EmployeeDisplay(
            String employeeCode, String employeeName, String employmentStatus, String departmentName) {
    }
}
