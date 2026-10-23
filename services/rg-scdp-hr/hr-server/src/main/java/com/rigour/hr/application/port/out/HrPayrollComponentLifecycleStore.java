package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.PayrollComponentVersionView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HrPayrollComponentLifecycleStore {
    void lock(String tenant);

    Optional<PayrollComponentActionResult> byRequest(String tenant, String requestId);

    boolean inUse(String tenant, String versionId);

    PayrollComponentActionResult act(
            String tenant,
            PayrollComponentVersionView rule,
            PayrollComponentActionCommand command,
            String actor,
            LocalDate today);

    List<PayrollComponentOperationView> operations(String tenant, String ruleId);

    List<PayrollRuleReviewView> reviews(String tenant, String subject, String month);

    Optional<PayrollRuleReviewView> review(String tenant, String id);

    void resolve(String tenant, String id, String reason, String actor);

    void resolveSubject(
            String tenant, String subject, String subjectId, String reason, String actor);
}
