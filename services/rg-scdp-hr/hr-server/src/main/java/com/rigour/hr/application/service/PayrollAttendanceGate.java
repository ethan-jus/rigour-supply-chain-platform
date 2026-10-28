package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollDetailView;
import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore;

import org.springframework.stereotype.Service;

import java.time.YearMonth;

/** A confirmed salary does not authorize payment after attendance has been reopened. */
@Service
public class PayrollAttendanceGate {
    private final HrAttendanceStore attendance;
    private final HrPayrollComponentLifecycleStore reviews;

    public PayrollAttendanceGate(
            HrAttendanceStore attendance, HrPayrollComponentLifecycleStore reviews) {
        this.attendance = attendance;
        this.reviews = reviews;
    }

    public String blockReason(String tenant, PayrollDetailView payroll, String action) {
        if ("PAID".equals(payroll.statusCode())) return null;
        var employee =
                attendance.employee(
                        tenant, YearMonth.parse(payroll.month()), payroll.employeeCode(), action);
        if (employee == null
                || employee.confirmation() == null
                || !"CONFIRMED".equals(employee.confirmation().attendanceStatus()))
            return "该员工当月考勤尚未确认，请先确认考勤后再确认工资、登记发放";
        if (reviews.reviews(tenant, "ATTENDANCE", payroll.month()).stream()
                .anyMatch(review -> payroll.employeeCode().equals(review.employeeCode())))
            return "该员工当月考勤存在规则变更待复核，请先处理后再发薪";
        if (attendance.reopenedSince(
                tenant,
                YearMonth.parse(payroll.month()),
                payroll.employeeCode(),
                payroll.updatedTime())) return "该员工考勤已撤销重核，原工资结果需重新核算；已确认工资请先撤回确认，再核算确认后发放";
        return null;
    }

    public void require(String tenant, PayrollDetailView payroll, String action) {
        var reason = blockReason(tenant, payroll, action);
        if (reason != null) throw HrPayrollService.bad(reason);
    }
}
