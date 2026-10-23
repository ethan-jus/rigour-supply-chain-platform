package com.rigour.hr.application.port.out;

public interface PayrollPaymentAttachmentStorage {
    String upload(String tenant, String payrollId, String paymentId, String contentType, byte[] bytes);
    byte[] read(String fileKey);
    String signedDownload(String fileKey, String fileName, String contentType, java.time.Instant expiresAt);
    void discard(String fileKey);
}
