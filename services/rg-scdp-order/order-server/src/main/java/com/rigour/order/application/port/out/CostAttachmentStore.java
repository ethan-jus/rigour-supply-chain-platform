package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;

import java.util.List;

public interface CostAttachmentStore {
    void lock(String tenant);

    List<LogisticsCostAttachmentView> list(String tenant, String entry);

    String fileKey(String tenant, String entry, String id);

    void add(
            String tenant,
            String entry,
            int revision,
            LogisticsCostAttachmentView file,
            String fileKey);
}
