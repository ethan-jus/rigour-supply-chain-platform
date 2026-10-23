package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.actor;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;

@Service
public class HrPayrollRuleReviewService {
    private final HrPayrollComponentLifecycleStore lifecycle;
    private final HrPayrollStore payroll;
    private final HrAttendanceStore attendance;

    public HrPayrollRuleReviewService(
            HrPayrollComponentLifecycleStore lifecycle,
            HrPayrollStore payroll,
            HrAttendanceStore attendance) {
        this.lifecycle = lifecycle;
        this.payroll = payroll;
        this.attendance = attendance;
    }

    public List<PayrollRuleReviewView> list(String subject, String month) {
        String permission = permission(subject, false);
        var a = actor(permission);
        try {
            YearMonth.parse(month);
        } catch (RuntimeException invalid) {
            throw bad("复核月份无效");
        }
        String tenant = a.tenantId().toString();
        return lifecycle.reviews(tenant, subject, month).stream()
                .map(
                        review -> {
                            try {
                                String name = scope(tenant, review, permission);
                                return new PayrollRuleReviewView(
                                        review.id(),
                                        review.subject(),
                                        review.subjectId(),
                                        review.employeeCode(),
                                        name,
                                        review.month(),
                                        review.ruleName(),
                                        review.action(),
                                        review.reason(),
                                        review.time());
                            } catch (AuthorizationDeniedException denied) {
                                return (PayrollRuleReviewView) null;
                            } catch (BusinessException unavailable) {
                                if (unavailable.getErrorCode() == ErrorCode.NOT_FOUND)
                                    return (PayrollRuleReviewView) null;
                                throw unavailable;
                            }
                        })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @org.springframework.transaction.annotation.Transactional
    public void resolve(String id, PayrollRuleReviewCommand command) {
        if (command == null
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 1000) throw bad("请填写1至1000字复核处理说明");
        var identity = com.rigour.shared.context.AuthorizationContext.requireCurrent();
        if (identity.tenantId() == null)
            throw new AuthorizationDeniedException("hr:payroll:confirm");
        String tenant = identity.tenantId().toString();
        lifecycle.lock(tenant);
        var review = lifecycle.review(tenant, id).orElseThrow(() -> bad("待复核记录不存在或已处理"));
        String permission = permission(review.subject(), true);
        var a = actor(permission);
        scope(tenant, review, permission);
        lifecycle.resolve(tenant, id, command.reason(), a.principalId().toString());
        LoggerFactory.getLogger(getClass())
                .info(
                        "Rule review resolved tenant={} actor={} reviewId={} subject={}"
                                + " subjectId={}",
                        tenant,
                        a.principalId(),
                        id,
                        review.subject(),
                        review.subjectId());
    }

    private String scope(String tenant, PayrollRuleReviewView review, String permission) {
        if ("PAYROLL".equals(review.subject()))
            return payroll.detail(tenant, review.subjectId(), permission).employeeName();
        return attendance
                .employee(
                        tenant, YearMonth.parse(review.month()), review.employeeCode(), permission)
                .name();
    }

    private static String permission(String subject, boolean write) {
        if ("PAYROLL".equals(subject)) return write ? "hr:payroll:confirm" : "hr:payroll:read";
        if ("ATTENDANCE".equals(subject))
            return write ? "hr:attendance:write" : "hr:attendance:read";
        throw bad("复核对象类型无效");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
