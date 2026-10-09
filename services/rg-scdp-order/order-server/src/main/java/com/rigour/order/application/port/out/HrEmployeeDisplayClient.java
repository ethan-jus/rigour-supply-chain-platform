package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Set;

/** Order读取HR员工展示信息的端口；业务主表只保存员工编码。 */
public interface HrEmployeeDisplayClient {
    /** 无 HR 通道时的空实现；只保留筛选部门本身，不静默放大成全量。 */
    HrEmployeeDisplayClient NONE = new HrEmployeeDisplayClient() {
        @Override
        public List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes) {
            return List.of();
        }

        @Override
        public Set<Long> departmentIdsInScope(
                CallerIdentity caller, Long departmentId, Boolean includeSubDepartments) {
            return departmentId == null ? Set.of() : Set.of(departmentId);
        }
    };

    List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes);

    /**
     * 部门范围内的部门ID集合（含自身；includeSubDepartments 为空或 true 时含子部门）。
     * 订单归属按快照部门匹配，这里只解析部门树，避免用当前员工归属改写历史口径。
     */
    Set<Long> departmentIdsInScope(
            CallerIdentity caller, Long departmentId, Boolean includeSubDepartments);

    /** 当前部门员工仅用于缺少历史部门快照的订单。 */
    default Set<String> departmentEmployeeCodes(CallerIdentity caller, Long departmentId, Boolean includeSubDepartments) {
        throw new UnsupportedOperationException("HR部门人员查询未配置");
    }

    record EmployeeDisplay(
            String employeeCode, String employeeName, String employmentStatus, String departmentName) {
    }
}
