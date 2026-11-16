package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;

import java.util.List;

public interface CostAttachmentStore {
    record Submission(String entryId, String fingerprint, int revision) {}

    Submission submission(String tenant, String submissionId);

    void completed(
            String tenant, String entry, String submissionId, String fingerprint, int revision);

    void lock(String tenant);

    void retire(String tenant, String entry, String id);

    void touch(String tenant, String entry, int revision, String actor);

    List<LogisticsCostAttachmentView> list(String tenant, String entry);

    String fileKey(String tenant, String entry, String id);

    void add(
            String tenant,
            String entry,
            int revision,
            LogisticsCostAttachmentView file,
            String fileKey);
}
