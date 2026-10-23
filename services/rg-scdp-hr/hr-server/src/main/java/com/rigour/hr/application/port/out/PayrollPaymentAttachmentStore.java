package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.PayrollPaymentAttachmentView;
import java.util.List;
import java.util.Map;

public interface PayrollPaymentAttachmentStore {
    record Stored(PayrollPaymentAttachmentView metadata, String fileKey) {}
    record LockedPayroll(int revision, String statusCode) {}
    LockedPayroll lock(String tenant, String payrollId);
    List<PayrollPaymentAttachmentView> listForUpdate(String tenant, String payrollId, String paymentId);
    List<PayrollPaymentAttachmentView> list(String tenant, String payrollId, String paymentId);
    Map<String, List<PayrollPaymentAttachmentView>> listByPayroll(String tenant, String payrollId);
    Stored file(String tenant, String payrollId, String paymentId, String attachmentId);
    void add(String tenant, String payrollId, String paymentId, PayrollPaymentAttachmentView metadata, String fileKey);
}
